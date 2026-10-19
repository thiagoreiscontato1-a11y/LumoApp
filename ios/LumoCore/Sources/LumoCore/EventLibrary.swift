import Foundation

/// Evento local: toda captura/importação pertence a um evento (como no Android 0.15.28).
public struct LumoEvent: Codable, Identifiable, Hashable {
    public var id: String
    public var name: String
    public var createdAt: Date
    /// Destino Fotto lembrado por evento local.
    public var fottoGallery: FottoGallery?

    public init(id: String = LumoEvent.newID(), name: String, createdAt: Date = Date(), fottoGallery: FottoGallery? = nil) {
        self.id = id; self.name = name; self.createdAt = createdAt; self.fottoGallery = fottoGallery
    }

    public static func newID() -> String {
        String(UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(10)).lowercased()
    }

    /// Nome de pasta seguro: `<nome>_<id>`.
    public var folderName: String { EventLibrary.safeName(name, fallback: "Evento") + "_" + id }
}

public enum EventLibraryError: LocalizedError {
    case emptyName

    public var errorDescription: String? { "Informe o nome do evento." }
}

public enum PhotoStage: String, Codable {
    /// Original salvo, aguardando edição.
    case received
    case approved
    /// Bloqueada pela curadoria; não entra na entrega até ser aprovada.
    case review
    case failed
}

public enum UploadStage: String, Codable {
    case none, pending, uploading, uploaded, confirmed, failed
}

public enum PhotoSource: String, Codable {
    case usb, wifi, photos, files
}

public struct PhotoRecord: Codable, Identifiable, Hashable {
    public var id: String
    public var eventID: String
    public var source: PhotoSource
    public var originalName: String
    /// Nome base usado nas pastas (sem extensão).
    public var baseName: String
    public var capturedAt: Date
    public var receivedAt: Date
    public var size: Int
    public var sha256: String
    public var perceptualHash: String = ""
    public var stage: PhotoStage = .received
    public var score: Int = 0
    public var reason: String = ""
    public var duplicateOf: String = ""
    public var presetName: String = ""
    /// Ajustes manuais aplicados por cima do automático/preset.
    public var manual: EditControls?
    /// Ajustes extras do editor (HSL, calibração, efeitos, detalhe), como em `EditOptions.extra`.
    public var extra: [String: Double]?
    public var upload: UploadStage = .none
    public var fottoGalleryID: String = ""
    public var fottoMediaID: String = ""
    public var uploadError: String = ""
    public var favorite = false
    /// Preset escolhido só para esta foto: `nil` usa o do evento, `""` desliga o preset.
    public var customPreset: String?
    /// Arquivo RAW original guardado em `RAW` (o JPEG de trabalho fica em Originais).
    public var rawFileName: String?

    public init(id: String, eventID: String, source: PhotoSource, originalName: String, baseName: String,
                capturedAt: Date, receivedAt: Date = Date(), size: Int, sha256: String) {
        self.id = id; self.eventID = eventID; self.source = source; self.originalName = originalName
        self.baseName = baseName; self.capturedAt = capturedAt; self.receivedAt = receivedAt
        self.size = size; self.sha256 = sha256
    }

    /// Leitura tolerante: campos adicionados em versões novas têm valor padrão quando ausentes.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        eventID = try c.decode(String.self, forKey: .eventID)
        source = try c.decode(PhotoSource.self, forKey: .source)
        originalName = try c.decode(String.self, forKey: .originalName)
        baseName = try c.decode(String.self, forKey: .baseName)
        capturedAt = try c.decode(Date.self, forKey: .capturedAt)
        receivedAt = try c.decodeIfPresent(Date.self, forKey: .receivedAt) ?? capturedAt
        size = try c.decodeIfPresent(Int.self, forKey: .size) ?? 0
        sha256 = try c.decodeIfPresent(String.self, forKey: .sha256) ?? ""
        perceptualHash = try c.decodeIfPresent(String.self, forKey: .perceptualHash) ?? ""
        stage = try c.decodeIfPresent(PhotoStage.self, forKey: .stage) ?? .received
        score = try c.decodeIfPresent(Int.self, forKey: .score) ?? 0
        reason = try c.decodeIfPresent(String.self, forKey: .reason) ?? ""
        duplicateOf = try c.decodeIfPresent(String.self, forKey: .duplicateOf) ?? ""
        presetName = try c.decodeIfPresent(String.self, forKey: .presetName) ?? ""
        manual = try c.decodeIfPresent(EditControls.self, forKey: .manual)
        extra = try c.decodeIfPresent([String: Double].self, forKey: .extra)
        upload = try c.decodeIfPresent(UploadStage.self, forKey: .upload) ?? .none
        fottoGalleryID = try c.decodeIfPresent(String.self, forKey: .fottoGalleryID) ?? ""
        fottoMediaID = try c.decodeIfPresent(String.self, forKey: .fottoMediaID) ?? ""
        uploadError = try c.decodeIfPresent(String.self, forKey: .uploadError) ?? ""
        favorite = try c.decodeIfPresent(Bool.self, forKey: .favorite) ?? false
        customPreset = try c.decodeIfPresent(String.self, forKey: .customPreset)
        rawFileName = try c.decodeIfPresent(String.self, forKey: .rawFileName)
    }

    public var isSent: Bool { upload == .uploaded || upload == .confirmed }

    public var originalFileName: String { baseName + ".jpg" }
    public var editedFileName: String { baseName + (stage == .review ? "_REVISAO.jpg" : "_Editada.jpg") }
    /// Nome enviado ao Fotto.
    public var deliveryName: String { baseName + "_Editada.jpg" }
}

/// Armazenamento local em `Documents/Lumo/Eventos/<evento>/{Originais,Editadas,Sob Revisao}`.
/// O índice de cada evento fica em `photos.json`; operações são serializadas por um lock.
public final class EventLibrary: @unchecked Sendable {
    public static let originals = "Originais"
    public static let edited = "Editadas"
    public static let review = "Sob Revisao"
    public static let raw = "RAW"

    public let root: URL
    private let lock = NSLock()
    private var cache: [String: [PhotoRecord]] = [:]
    private let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.outputFormatting = [.prettyPrinted, .sortedKeys]
        e.dateEncodingStrategy = .iso8601
        return e
    }()
    private let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .iso8601
        return d
    }()

    public init(root: URL) {
        self.root = root
        try? FileManager.default.createDirectory(at: eventsRoot, withIntermediateDirectories: true)
    }

    public var eventsRoot: URL { root.appendingPathComponent("Eventos", isDirectory: true) }
    var eventsIndex: URL { root.appendingPathComponent("events.json") }

    public static func safeName(_ s: String, fallback: String) -> String {
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-_ "))
        let cleaned = String(s.unicodeScalars.map { allowed.contains($0) ? Character($0) : "_" })
            .trimmingCharacters(in: .whitespaces)
            .replacingOccurrences(of: " ", with: "_")
        return cleaned.isEmpty ? fallback : String(cleaned.prefix(60))
    }

    // MARK: Eventos

    public func events() -> [LumoEvent] {
        lock.lock(); defer { lock.unlock() }
        return loadEvents()
    }

    private func loadEvents() -> [LumoEvent] {
        guard let data = try? Data(contentsOf: eventsIndex), let list = try? decoder.decode([LumoEvent].self, from: data) else { return [] }
        return list
    }

    private func saveEvents(_ list: [LumoEvent]) throws {
        try encoder.encode(list).write(to: eventsIndex, options: .atomic)
    }

    @discardableResult
    public func createEvent(named name: String) throws -> LumoEvent {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { throw EventLibraryError.emptyName }
        lock.lock(); defer { lock.unlock() }
        var list = loadEvents()
        let event = LumoEvent(name: trimmed)
        for sub in [Self.originals, Self.edited, Self.review] {
            try FileManager.default.createDirectory(at: folder(event, sub), withIntermediateDirectories: true)
        }
        list.append(event)
        try saveEvents(list)
        return event
    }

    public func update(_ event: LumoEvent) throws {
        lock.lock(); defer { lock.unlock() }
        var list = loadEvents()
        guard let i = list.firstIndex(where: { $0.id == event.id }) else { return }
        list[i] = event
        try saveEvents(list)
    }

    public func eventFolder(_ event: LumoEvent) -> URL { eventsRoot.appendingPathComponent(event.folderName, isDirectory: true) }

    public func folder(_ event: LumoEvent, _ sub: String) -> URL { eventFolder(event).appendingPathComponent(sub, isDirectory: true) }

    // MARK: Fotos

    private func indexURL(_ event: LumoEvent) -> URL { eventFolder(event).appendingPathComponent("photos.json") }

    private func loadPhotos(_ event: LumoEvent) -> [PhotoRecord] {
        if let cached = cache[event.id] { return cached }
        let list = (try? Data(contentsOf: indexURL(event))).flatMap { try? decoder.decode([PhotoRecord].self, from: $0) } ?? []
        cache[event.id] = list
        return list
    }

    public func photos(in event: LumoEvent) -> [PhotoRecord] {
        lock.lock(); defer { lock.unlock() }
        return loadPhotos(event)
    }

    public func photo(id: String, in event: LumoEvent) -> PhotoRecord? {
        photos(in: event).first { $0.id == id }
    }

    /// Insere ou substitui pelo `id`.
    public func save(_ photo: PhotoRecord, in event: LumoEvent) throws {
        lock.lock(); defer { lock.unlock() }
        var list = loadPhotos(event)
        if let i = list.firstIndex(where: { $0.id == photo.id }) { list[i] = photo } else { list.append(photo) }
        try FileManager.default.createDirectory(at: eventFolder(event), withIntermediateDirectories: true)
        try encoder.encode(list).write(to: indexURL(event), options: .atomic)
        cache[event.id] = list
    }

    /// Remove fotos do índice e apaga seus arquivos (original, RAW e editado).
    public func remove(ids: Set<String>, in event: LumoEvent) throws {
        lock.lock(); defer { lock.unlock() }
        var list = loadPhotos(event)
        let fm = FileManager.default
        for p in list where ids.contains(p.id) {
            try? fm.removeItem(at: folder(event, Self.originals).appendingPathComponent(p.originalFileName))
            if let raw = p.rawFileName { try? fm.removeItem(at: folder(event, Self.raw).appendingPathComponent(raw)) }
            for sub in [Self.edited, Self.review] {
                try? fm.removeItem(at: folder(event, sub).appendingPathComponent(p.baseName + "_Editada.jpg"))
                try? fm.removeItem(at: folder(event, sub).appendingPathComponent(p.baseName + "_REVISAO.jpg"))
            }
        }
        list.removeAll { ids.contains($0.id) }
        try encoder.encode(list).write(to: indexURL(event), options: .atomic)
        cache[event.id] = list
    }

    public func contains(id: String, in event: LumoEvent) -> Bool { photo(id: id, in: event) != nil }

    /// Duplicata exata pelo conteúdo, dentro do evento.
    public func duplicate(sha256: String, in event: LumoEvent) -> PhotoRecord? {
        photos(in: event).first { $0.sha256 == sha256 }
    }

    /// Duplicata visual (dHash próximo e tamanho parecido), ignorando a própria foto.
    public func visualDuplicate(of photo: PhotoRecord, hash: String, in event: LumoEvent, threshold: Int = 4) -> PhotoRecord? {
        guard !hash.isEmpty else { return nil }
        return photos(in: event).first {
            $0.id != photo.id && !$0.perceptualHash.isEmpty && PhotoHash.hamming($0.perceptualHash, hash) <= threshold
                && abs($0.receivedAt.timeIntervalSince(photo.receivedAt)) < 120
        }
    }

    public func originalURL(_ photo: PhotoRecord, in event: LumoEvent) -> URL {
        folder(event, Self.originals).appendingPathComponent(photo.originalFileName)
    }

    public func editedURL(_ photo: PhotoRecord, in event: LumoEvent) -> URL {
        folder(event, photo.stage == .review ? Self.review : Self.edited).appendingPathComponent(photo.editedFileName)
    }

    /// Fotos aprovadas que ainda precisam ir para o destino Fotto informado.
    public func pendingUploads(in event: LumoEvent, galleryID: String) -> [PhotoRecord] {
        photos(in: event).filter {
            $0.stage == .approved && !($0.fottoGalleryID == galleryID && [.uploaded, .confirmed].contains($0.upload))
                && !($0.fottoGalleryID == galleryID && $0.upload == .failed)
        }.sorted { $0.capturedAt < $1.capturedAt }
    }

    /// Identificador estável de uma foto da câmera (evento + origem + metadados do cartão).
    public static func photoID(eventID: String, sourceKey: String) -> String {
        String(PhotoHash.sha256Hex(Data((eventID + ":" + sourceKey).utf8)).prefix(24))
    }

    public static func baseName(originalName: String, id: String) -> String {
        let stem = (originalName as NSString).deletingPathExtension
        return safeName(stem, fallback: "IMG") + "_" + String(id.prefix(8))
    }
}
