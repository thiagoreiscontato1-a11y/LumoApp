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
            let t0 = Date()
            let decoded = try ImageBridge.decode(original, maxEdge: options.edge)
            var opts = options
            opts.manual = record.manual
            opts.extra = record.extra
            opts.crop = record.crop
            let sensitivity = options.curationSensitivity
            // Nitidez medida na ORIGINAL (~1600 px, já recortada), antes de perfil, preset, nitidez e grão.
            let cropped = PhotoEditor.cropped(decoded, options: opts)
            let detail = PhotoCheck.shrink(cropped, maxEdge: PhotoCheck.detailEdge)
            let sharp = PhotoCheck.sharpness(detail)
            let source = Develop.Source(PhotoEditor.withProfile(cropped, options: opts))
            var settings = PhotoEditor.settings(source, options: opts)
            // Exposição conferida na foto já com o preset (prévia de 512 px): clara ou escura demais é corrigida aqui.
            var exposure = PhotoCheck.Exposure()
            if PhotoCheck.exposureFix(opts) {
                exposure = PhotoCheck.fixExposure(PhotoCheck.shrink(detail, maxEdge: PhotoCheck.previewEdge), settings: &settings,
                                                  faces: { SemanticCurator.faces($0) })
            }
            let t1 = Date()
            var engine = ""
            let image = source.render(settings, engine: &engine)
            let t2 = Date()

            let small = image.downsampled(maxEdge: 720)
            let quality = Curator.measure(small, sensitivity: sensitivity)
            let hash = PhotoHash.perceptual(small)
            record.perceptualHash = hash
            let dup = library.visualDuplicate(of: record, hash: hash, in: event)
            record.duplicateOf = dup?.id ?? ""
            // Pessoas: rosto principal cortado na lateral ou embaixo (Vision), na foto editada.
            var semantic = SemanticCurator.Result()
            if let cg = try? ImageBridge.cgImage(image.downsampled(maxEdge: 900)) {
                semantic = SemanticCurator.analyze(cg, sensitivity: sensitivity)
            }
            let decision = PhotoCheck.decide(tech: quality, faceReview: semantic.review, faceNote: semantic.note, sharp: sharp,
                                             exposure: exposure, duplicate: dup != nil, sensitivity: sensitivity)
            var review = options.curation && decision.review
            // Uma aprovação manual vale até o fotógrafo mudar de ideia.
            if input.stage == .approved && input.reason.hasPrefix("Aprovada manualmente") { review = false }
            record.score = decision.score
            record.reason = !review && input.reason.hasPrefix("Aprovada manualmente") ? input.reason : decision.reason
            if !options.curation { record.reason += " · Curadoria desligada: nota informativa" }
            record.stage = review ? .review : .approved
            record.presetName = options.presetName
            let posix = Locale(identifier: "en_US_POSIX")
            let prepare = String(format: "%.2f", locale: posix, t1.timeIntervalSince(t0))
            let develop = String(format: "%.2f", locale: posix, t2.timeIntervalSince(t1))
            var timing = "abrir+medir \(prepare) s · revelar \(develop) s (\(engine))"
            if exposure.ev != 0 { timing += String(format: " · exposição %+.2f EV", locale: posix, exposure.ev) }
            record.timing = timing + " · \(image.width)×\(image.height)"

            let target = library.editedURL(record, in: event)
            try FileManager.default.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
            try ImageBridge.writeJPEG(image, to: target, metadataFrom: original)
            if previousEdited != target { try? FileManager.default.removeItem(at: previousEdited) }
            if record.stage == .approved, record.upload == .failed { record.upload = .none; record.uploadError = "" }
            // Nova edição: a galeria online recebe a versão nova (nova revisão, pela data do arquivo).
            record.onlineSignature = ""
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
