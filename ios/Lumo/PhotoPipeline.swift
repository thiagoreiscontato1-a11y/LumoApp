import Foundation

/// Uma foto recebida da câmera, do cartão ou do app Fotos.
struct IncomingPhoto {
    var data: Data
    var originalName: String
    var capturedAt: Date?
    var source: PhotoSource
    /// Identifica a mesma foto na origem (cartão + nome + data + tamanho), para não baixar de novo.
    var sourceKey: String
}

enum IngestResult {
    case added(PhotoRecord)
    case duplicate(String)
}

/// Fila de processamento: grava o original, edita, faz a curadoria e separa
/// entre `Editadas` e `Sob Revisao`. Roda fora da thread principal.
actor PhotoPipeline {
    let library: EventLibrary
    private var onChange: (@Sendable (PhotoRecord) -> Void)?

    init(library: EventLibrary) {
        self.library = library
    }

    func setOnChange(_ handler: @escaping @Sendable (PhotoRecord) -> Void) { onChange = handler }

    func contains(sourceKey: String, in event: LumoEvent) -> Bool {
        library.contains(id: EventLibrary.photoID(eventID: event.id, sourceKey: sourceKey), in: event)
    }

    /// Salva o original e processa a foto. Duplicatas exatas dentro do evento são ignoradas.
    func ingest(_ incoming: IncomingPhoto, event: LumoEvent, options: EditOptions) throws -> IngestResult {
        let id = EventLibrary.photoID(eventID: event.id, sourceKey: incoming.sourceKey)
        if library.contains(id: id, in: event) { return .duplicate("Foto já recebida · \(incoming.originalName)") }
        // RAW (CR2/CR3/DNG…): o iOS revela pelo ImageIO; o arquivo RAW fica guardado em `RAW`.
        let isRaw = RawFormat.isRaw(incoming.originalName)
        let jpeg = try ImageBridge.jpegData(from: incoming.data)
        let sha = PhotoHash.sha256Hex(jpeg)
        if let dup = library.duplicate(sha256: sha, in: event) {
            return .duplicate("Duplicata exata ignorada · \(incoming.originalName) = \(dup.originalName)")
        }
        let captured = incoming.capturedAt ?? ImageBridge.captureDate(jpeg) ?? Date()
        var record = PhotoRecord(id: id, eventID: event.id, source: incoming.source, originalName: incoming.originalName,
                                 baseName: EventLibrary.baseName(originalName: incoming.originalName, id: id),
                                 capturedAt: captured, size: jpeg.count, sha256: sha)
        record.presetName = options.presetName
        if isRaw {
            let rawName = record.baseName + "." + (incoming.originalName as NSString).pathExtension.uppercased()
            let rawURL = library.folder(event, EventLibrary.raw).appendingPathComponent(rawName)
            try FileManager.default.createDirectory(at: rawURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            try incoming.data.write(to: rawURL, options: .atomic)
            record.rawFileName = rawName
        }
        let originalURL = library.originalURL(record, in: event)
        try FileManager.default.createDirectory(at: originalURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try jpeg.write(to: originalURL, options: .atomic)
        try library.save(record, in: event)
        notify(record)
        record = process(record, event: event, options: options, original: jpeg)
        return .added(record)
    }

    /// Reedita a partir do original (por exemplo, depois de ajustes manuais ou troca de preset).
    @discardableResult
    func reprocess(_ record: PhotoRecord, event: LumoEvent, options: EditOptions) -> PhotoRecord {
        guard let original = try? Data(contentsOf: library.originalURL(record, in: event)) else {
            var failed = record
            failed.stage = .failed
            failed.reason = "Original não encontrado em Originais."
            try? library.save(failed, in: event)
            notify(failed)
            return failed
        }
        return process(record, event: event, options: options, original: original)
    }

    private func process(_ input: PhotoRecord, event: LumoEvent, options: EditOptions, original: Data) -> PhotoRecord {
        var record = input
        let previousEdited = library.editedURL(record, in: event)
        do {
            var image = try ImageBridge.decode(original, maxEdge: options.edge)
            var opts = options
            opts.manual = record.manual
            PhotoEditor.edit(&image, options: opts)

            let small = image.downsampled(maxEdge: 720)
            let quality = Curator.measure(small, sensitivity: options.curationSensitivity)
            let hash = PhotoHash.perceptual(small)
            record.perceptualHash = hash
            let dup = library.visualDuplicate(of: record, hash: hash, in: event)
            record.duplicateOf = dup?.id ?? ""
            var score = quality.score - (dup == nil ? 0 : 3)
            score = max(1, min(10, score))
            // Pessoas: rosto cortado, encoberto, olhos fechados (Vision).
            var semantic = SemanticCurator.Result()
            if let cg = try? ImageBridge.cgImage(small) {
                semantic = SemanticCurator.analyze(cg, sensitivity: options.curationSensitivity)
            }
            score = max(1, min(10, score - semantic.penalty))
            var reasons: [String] = []
            if quality.review { reasons.append(quality.reason) }
            if !semantic.note.isEmpty { reasons.append(semantic.note) }
            if dup != nil { reasons.append("Possível duplicata de outra foto") }
            var review = options.curation && (quality.review || semantic.review || (dup != nil && options.curationSensitivity > 0))
            // Uma aprovação manual vale até o fotógrafo mudar de ideia.
            if input.stage == .approved && input.reason.hasPrefix("Aprovada manualmente") { review = false }
            record.score = score
            record.reason = !review && input.reason.hasPrefix("Aprovada manualmente") ? input.reason
                : reasons.isEmpty ? "Aprovada · nota \(score)/10" : reasons.joined(separator: " · ")
            if !options.curation { record.reason += " · Curadoria desligada: nota informativa" }
            record.stage = review ? .review : .approved
            record.presetName = options.presetName

            let target = library.editedURL(record, in: event)
            try FileManager.default.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
            try ImageBridge.writeJPEG(image, to: target, metadataFrom: original)
            if previousEdited != target { try? FileManager.default.removeItem(at: previousEdited) }
            if record.stage == .approved, record.upload == .failed { record.upload = .none; record.uploadError = "" }
        } catch {
            record.stage = .failed
            record.reason = "Falha na edição · \(error.localizedDescription)"
        }
        try? library.save(record, in: event)
        notify(record)
        return record
    }

    /// Aprovação manual de uma foto da Revisão: move o JPEG para `Editadas`.
    func approve(_ input: PhotoRecord, event: LumoEvent) throws -> PhotoRecord {
        var record = input
        let from = library.editedURL(record, in: event)
        record.stage = .approved
        record.reason = "Aprovada manualmente · nota \(record.score)/10"
        let to = library.editedURL(record, in: event)
        try FileManager.default.createDirectory(at: to.deletingLastPathComponent(), withIntermediateDirectories: true)
        if from != to {
            try? FileManager.default.removeItem(at: to)
            try FileManager.default.moveItem(at: from, to: to)
        }
        try library.save(record, in: event)
        notify(record)
        return record
    }

    /// Envia uma foto aprovada de volta para a Revisão (bloqueia a entrega).
    func sendToReview(_ input: PhotoRecord, event: LumoEvent) throws -> PhotoRecord {
        var record = input
        let from = library.editedURL(record, in: event)
        record.stage = .review
        record.reason = "Enviada para revisão manualmente"
        let to = library.editedURL(record, in: event)
        try FileManager.default.createDirectory(at: to.deletingLastPathComponent(), withIntermediateDirectories: true)
        if from != to {
            try? FileManager.default.removeItem(at: to)
            try FileManager.default.moveItem(at: from, to: to)
        }
        try library.save(record, in: event)
        notify(record)
        return record
    }

    private func notify(_ record: PhotoRecord) { onChange?(record) }
}

enum RawFormat {
    static let extensions: Set<String> = ["cr2", "cr3", "crw", "dng", "nef", "nrw", "arw", "raf", "rw2", "orf", "pef", "srw"]

    static func isRaw(_ name: String) -> Bool { extensions.contains((name as NSString).pathExtension.lowercased()) }
}
