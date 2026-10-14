import Foundation
import SwiftUI
import UIKit

/// Estado do app: evento atual, fotos, ajustes, câmera e entrega Fotto.
@MainActor
final class AppModel: ObservableObject {
    static let version = "0.3.0"

    let library: EventLibrary
    let pipeline: PhotoPipeline
    let client: FottoClient
    let uploader: FottoUploader
    let capture = CaptureController()

    @Published private(set) var events: [LumoEvent] = []
    @Published private(set) var currentEvent: LumoEvent?
    @Published private(set) var photos: [PhotoRecord] = []
    @Published var settings = LumoSettings.load() {
        didSet {
            settings.save()
            capture.backfillEnabled = settings.backfill
        capture.onDisconnect = { [weak self] message in
            Task { @MainActor in
                guard let self, self.settings.disconnectAlarm || self.settings.eventMode else { return }
                Alerts.disconnect(message)
            }
        }
        }
    }
    @Published private(set) var presets = PresetStore.load()
    @Published private(set) var processing = 0
    @Published var message: String?

    @Published private(set) var fottoConnected = false
    @Published private(set) var fottoStatus = ""
    @Published private(set) var fottoGalleries: [FottoGallery] = []
    @Published private(set) var fottoListingWarning = ""
    @Published private(set) var fottoLoading = false
    @Published private(set) var uploading = false
    @Published private(set) var importing = false
    /// Modo de demonstração: foto a abrir no visualizador.
    @Published var demoOpenPhoto: String?

    init() {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        library = EventLibrary(root: docs.appendingPathComponent("Lumo", isDirectory: true))
        pipeline = PhotoPipeline(library: library)
        let credential = Keychain.fottoCredential
        client = FottoClient(credential: credential)
        uploader = FottoUploader(library: library, client: client)
        fottoConnected = credential != nil

        events = library.events()
        currentEvent = events.first { $0.id == settings.activeEventID } ?? events.last
        reloadPhotos()

        capture.deliver = { [weak self] incoming in
            guard let self else { return false }
            return await self.ingest(incoming)
        }
        capture.alreadyHave = { [weak self] key in
            guard let self, let event = await self.currentEvent else { return false }
            return await self.pipeline.contains(sourceKey: key, in: event)
        }
        capture.backfillEnabled = settings.backfill
        Task {
            await pipeline.setOnChange { [weak self] record in
                Task { @MainActor in self?.apply(record) }
            }
            await uploader.setHandlers(
                status: { [weak self] text in Task { @MainActor in self?.fottoStatus = text } },
                changed: { [weak self] record in Task { @MainActor in self?.apply(record) } })
        }
    }

    // MARK: Contadores

    /// Fotos da mais recente para a mais antiga (ordem de chegada).
    var recent: [PhotoRecord] { photos.sorted { $0.receivedAt > $1.receivedAt } }
    var latest: PhotoRecord? { photos.max { $0.receivedAt < $1.receivedAt } }

    /// Arquivo a exibir: o original enquanto edita (ou se a edição falhou), senão o editado.
    func displayURL(_ photo: PhotoRecord) -> URL? {
        guard let event = currentEvent else { return nil }
        return photo.stage == .received || photo.stage == .failed
            ? library.originalURL(photo, in: event) : library.editedURL(photo, in: event)
    }

    func photoCount(_ event: LumoEvent) -> Int { library.photos(in: event).count }

    var approved: [PhotoRecord] { photos.filter { $0.stage == .approved }.sorted { $0.capturedAt > $1.capturedAt } }
    var underReview: [PhotoRecord] { photos.filter { $0.stage == .review || $0.stage == .failed }.sorted { $0.capturedAt > $1.capturedAt } }
    var pendingEdit: Int { photos.filter { $0.stage == .received }.count }
    var sentCount: Int {
        guard let g = currentEvent?.fottoGallery else { return 0 }
        return photos.filter { $0.fottoGalleryID == g.id && ($0.upload == .uploaded || $0.upload == .confirmed) }.count
    }
    var confirmedCount: Int {
        guard let g = currentEvent?.fottoGallery else { return 0 }
        return photos.filter { $0.fottoGalleryID == g.id && $0.upload == .confirmed }.count
    }
    var failedUploads: Int {
        guard let g = currentEvent?.fottoGallery else { return 0 }
        return photos.filter { $0.fottoGalleryID == g.id && $0.upload == .failed }.count
    }
    var pendingUploads: Int {
        guard let event = currentEvent, let g = event.fottoGallery else { return 0 }
        return library.pendingUploads(in: event, galleryID: g.id).count
    }

    // MARK: Modo evento e perfis

    func setEventMode(_ on: Bool) {
        guard requireEventNoticeIfNeeded(on) else { return }
        settings.eventMode = on
        if on {
            settings.backfill = true
            settings.curation = true
            settings.disconnectAlarm = true
            if fottoConnected && currentEvent?.fottoGallery != nil { settings.fottoAuto = true }
            Alerts.requestNotificationPermission()
            message = "Modo evento ativo: tela sempre ligada, retroativo, curadoria e alarme de desconexão."
        }
        UIApplication.shared.isIdleTimerDisabled = on
    }

    private func requireEventNoticeIfNeeded(_ on: Bool) -> Bool {
        if on && currentEvent == nil { message = "Crie ou escolha um evento antes de ativar o modo evento."; return false }
        return true
    }

    func applyProfile(_ name: String) {
        settings.apply(profile: name)
        message = "Perfil “\(name)” aplicado: rigor e intensidade ajustados para as próximas fotos."
    }

    // MARK: Saúde do fluxo

    struct Health {
        var battery: String
        var storage: String
        var lastPhoto: String
        var failedEdits: Int
    }

    func health() -> Health {
        UIDevice.current.isBatteryMonitoringEnabled = true
        let level = UIDevice.current.batteryLevel
        let battery = level < 0 ? "—" : "\(Int(level * 100))%" + (UIDevice.current.batteryState == .charging ? " · carregando" : "")
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let free = (try? docs.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]).volumeAvailableCapacityForImportantUsage) ?? nil
        let storage = free.map { ByteCountFormatter.string(fromByteCount: $0, countStyle: .file) + " livres" } ?? "—"
        let last = latest.map { $0.receivedAt.formatted(.relative(presentation: .named)) } ?? "nenhuma"
        return Health(battery: battery, storage: storage, lastPhoto: last, failedEdits: photos.filter { $0.stage == .failed }.count)
    }

    /// Manifesto JSON para a futura galeria online (mesmo propósito do Android).
    func manifestURL() -> URL? {
        guard let event = currentEvent else { return nil }
        let list = library.photos(in: event).map { p -> [String: Any] in
            ["id": p.id, "album": event.name, "originalName": p.originalName,
             "capturedAt": Int64(p.capturedAt.timeIntervalSince1970 * 1000), "receivedAt": Int64(p.receivedAt.timeIntervalSince1970 * 1000),
             "state": p.stage.rawValue, "score": p.score, "favorite": p.favorite, "hasRaw": p.rawFileName != nil,
             "hasEdited": p.stage == .approved || p.stage == .review, "fotto": p.upload.rawValue]
        }
        let root: [String: Any] = ["version": 1, "app": "Lumo iOS \(Self.version)",
                                   "exportedAt": Int64(Date().timeIntervalSince1970 * 1000),
                                   "event": ["id": event.id, "name": event.name], "photos": list]
        guard let data = try? JSONSerialization.data(withJSONObject: root, options: [.prettyPrinted, .sortedKeys]) else { return nil }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("lumo-gallery-manifest.json")
        try? data.write(to: url, options: .atomic)
        return url
    }

    var busy: Bool { capture.isRunning || processing > 0 || uploading }

    // MARK: Eventos

    func createEvent(_ name: String) {
        guard !busy else { message = "Encerre a captura, a importação e o envio antes de trocar de evento."; return }
        do {
            let e = try library.createEvent(named: name)
            events = library.events()
            select(e)
        } catch {
            message = error.localizedDescription
        }
    }

    func select(_ event: LumoEvent) {
        guard event.id != currentEvent?.id else { return }
        guard !busy else { message = "Encerre a captura, a importação e o envio antes de trocar de evento."; return }
        currentEvent = event
        settings.activeEventID = event.id
        // O destino Fotto é lembrado por evento; ao trocar, o envio automático fica pausado.
        settings.fottoAuto = false
        reloadPhotos()
    }

    func reloadPhotos() {
        photos = currentEvent.map { library.photos(in: $0) } ?? []
    }

    private func apply(_ record: PhotoRecord) {
        guard record.eventID == currentEvent?.id else { return }
        if let i = photos.firstIndex(where: { $0.id == record.id }) { photos[i] = record } else { photos.append(record) }
    }

    func folderURL(_ sub: String) -> URL? { currentEvent.map { library.folder($0, sub) } }

    // MARK: Entrada de fotos

    @discardableResult
    func ingest(_ incoming: IncomingPhoto) async -> Bool {
        guard let event = currentEvent else {
            message = "Crie ou escolha um evento antes de conectar a câmera ou importar fotos."
            return false
        }
        processing += 1
        defer { processing -= 1 }
        let options = settings.editOptions(presets: presets)
        do {
            switch try await pipeline.ingest(incoming, event: event, options: options) {
            case .added(let record):
                if record.stage == .approved && settings.fottoAuto { startUpload() }
                return true
            case .duplicate(let note):
                fottoStatusIfIdle(note)
                return false
            }
        } catch {
            message = "Falha ao receber \(incoming.originalName): \(error.localizedDescription)"
            return false
        }
    }

    private func fottoStatusIfIdle(_ text: String) {
        if !uploading { fottoStatus = text }
    }

    /// Fotos do app Fotos.
    func importPhotoData(_ items: [(Data, String?)]) async {
        var added = 0
        for (data, name) in items {
            let fileName = name ?? "IMG_\(Int(Date().timeIntervalSince1970)).JPG"
            let key = "photos:" + PhotoHash.sha256Hex(data.prefix(1 << 20)) + ":\(data.count)"
            if await ingest(IncomingPhoto(data: data, originalName: fileName, capturedAt: nil, source: .photos, sourceKey: key)) { added += 1 }
        }
        message = "\(added) foto(s) importada(s) do app Fotos."
    }

    /// Arquivos ou pastas (leitor de cartão USB pelo app Arquivos). Percorre subpastas, só JPEG.
    /// Arquivos ou pastas (leitor de cartão pelo app Arquivos). Percorre subpastas.
    /// JPEG é importado direto; RAW só quando não há JPEG com o mesmo nome (RAW+JPEG),
    /// e então é revelado pelo iOS e guardado na pasta RAW do evento.
    func importFiles(_ urls: [URL]) async {
        guard currentEvent != nil else { message = "Crie ou escolha um evento antes de importar."; return }
        var jpegs: [URL] = []
        var raws: [URL] = []
        var scoped: [URL] = []
        for url in urls {
            if url.startAccessingSecurityScopedResource() { scoped.append(url) }
            collectPhotos(url, jpegs: &jpegs, raws: &raws)
        }
        defer { scoped.forEach { $0.stopAccessingSecurityScopedResource() } }
        let stems = Set(jpegs.map { $0.deletingPathExtension().path.lowercased() })
        let rawOnly = raws.filter { !stems.contains($0.deletingPathExtension().path.lowercased()) }
        let pairedRaw = raws.count - rawOnly.count
        var added = 0, duplicates = 0
        importing = true
        defer { importing = false }
        for file in (jpegs + rawOnly).sorted(by: { $0.path < $1.path }) {
            guard let data = try? Data(contentsOf: file) else { continue }
            let size = data.count
            let date = (try? file.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? Date()
            let key = "files:\(file.lastPathComponent):\(size):\(Int(date.timeIntervalSince1970))"
            if await ingest(IncomingPhoto(data: data, originalName: file.lastPathComponent, capturedAt: nil, source: .files, sourceKey: key)) {
                added += 1
            } else {
                duplicates += 1
            }
        }
        var text = "\(added) foto(s) importada(s)"
        if !rawOnly.isEmpty { text += " (\(rawOnly.count) RAW revelada(s) pelo iOS)" }
        if duplicates > 0 { text += " · \(duplicates) já existente(s)" }
        if pairedRaw > 0 { text += " · \(pairedRaw) RAW com JPEG par: usado o JPEG" }
        message = text
    }

    private func collectPhotos(_ url: URL, jpegs: inout [URL], raws: inout [URL]) {
        var isDir: ObjCBool = false
        guard FileManager.default.fileExists(atPath: url.path, isDirectory: &isDir) else { return }
        var found: [URL] = []
        if isDir.boolValue {
            guard let walker = FileManager.default.enumerator(at: url, includingPropertiesForKeys: [.isRegularFileKey],
                                                              options: [.skipsHiddenFiles]) else { return }
            for case let f as URL in walker { found.append(f) }
        } else {
            found = [url]
        }
        for f in found {
            let ext = f.pathExtension.lowercased()
            if ext == "jpg" || ext == "jpeg" { jpegs.append(f) } else if RawFormat.extensions.contains(ext) { raws.append(f) }
        }
    }

    // MARK: Edição e revisão

    /// Ajustes de edição para uma foto: os do evento, com o preset próprio da foto se houver.
    func options(for photo: PhotoRecord) -> EditOptions {
        var o = settings.editOptions(presets: presets)
        if let custom = photo.customPreset {
            o.presetName = custom
            o.preset = custom.isEmpty ? nil : presets.preset(named: custom)
        }
        return o
    }

    /// Salva ajustes manuais e/ou preset próprio e reedita a partir do original.
    func reprocess(_ photo: PhotoRecord, manual: EditControls?, customPreset: String?? = nil) async {
        guard let event = currentEvent else { return }
        var p = library.photo(id: photo.id, in: event) ?? photo
        p.manual = manual
        if let customPreset { p.customPreset = customPreset }
        processing += 1
        defer { processing -= 1 }
        _ = await pipeline.reprocess(p, event: event, options: options(for: p))
    }

    /// Reedita todas as fotos do evento com os ajustes atuais (ex.: depois de trocar o preset).
    func reprocessAll() async {
        guard let event = currentEvent else { return }
        processing += 1
        defer { processing -= 1 }
        for p in library.photos(in: event) where !p.isSent {
            _ = await pipeline.reprocess(p, event: event, options: options(for: p))
        }
        message = "Fotos reeditadas com os ajustes atuais."
    }

    func approve(_ photo: PhotoRecord) async {
        guard let event = currentEvent else { return }
        do {
            let p = try await pipeline.approve(photo, event: event)
            if settings.fottoAuto && p.stage == .approved { startUpload() }
        } catch { message = error.localizedDescription }
    }

    func approve(ids: Set<String>) async {
        for p in photos where ids.contains(p.id) && p.stage == .review { await approve(p) }
    }

    func sendToReview(_ photo: PhotoRecord) async {
        guard let event = currentEvent else { return }
        do { _ = try await pipeline.sendToReview(photo, event: event) } catch { message = error.localizedDescription }
    }

    func toggleFavorite(_ ids: Set<String>) {
        guard let event = currentEvent else { return }
        let targets = photos.filter { ids.contains($0.id) }
        // Se alguma não é favorita, favorita todas; senão, desfavorita.
        let value = targets.contains { !$0.favorite }
        for var p in targets {
            p.favorite = value
            try? library.save(p, in: event)
            apply(p)
        }
        Haptics.tap()
    }

    func delete(_ ids: Set<String>) {
        guard let event = currentEvent, !ids.isEmpty else { return }
        do {
            try library.remove(ids: ids, in: event)
            reloadPhotos()
            message = "\(ids.count) foto(s) excluída(s) do evento."
        } catch {
            message = "Falha ao excluir: \(error.localizedDescription)"
        }
    }

    func editedURLs(_ ids: Set<String>) -> [URL] {
        recent.filter { ids.contains($0.id) && $0.stage != .failed }.compactMap { displayURL($0) }
    }

    func originalURLs(_ ids: Set<String>) -> [URL] {
        guard let event = currentEvent else { return [] }
        return recent.filter { ids.contains($0.id) }.map { library.originalURL($0, in: event) }
    }

    // MARK: Copiar/colar ajustes

    struct Adjustments {
        var manual: EditControls?
        var customPreset: String?
    }

    @Published private(set) var clipboard: Adjustments?

    func copyAdjustments(from photo: PhotoRecord) {
        clipboard = Adjustments(manual: photo.manual, customPreset: photo.customPreset)
        message = "Ajustes copiados. Selecione fotos na galeria e toque em Colar."
    }

    func pasteAdjustments(to ids: Set<String>) async {
        guard let clip = clipboard else { return }
        for p in photos where ids.contains(p.id) && !p.isSent {
            await reprocess(p, manual: clip.manual, customPreset: .some(clip.customPreset))
        }
        message = "Ajustes colados em \(ids.count) foto(s)."
    }

    func baseControls(for photo: PhotoRecord) -> EditControls {
        guard let event = currentEvent,
              let image = try? ImageBridge.decode(url: library.originalURL(photo, in: event), maxEdge: 720) else { return .zero }
        return PhotoEditor.baseControls(image.pixels, options: options(for: photo))
    }

    // MARK: Presets

    func importPreset(from url: URL) {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        do {
            let values = try XMPPreset.parse(try Data(contentsOf: url))
            let name = url.deletingPathExtension().lastPathComponent
            presets.put(name, values)
            settings.presetName = name
            message = "Preset “\(name)” importado (\(values.count) ajustes)."
        } catch {
            message = "Preset inválido: \(error.localizedDescription)"
        }
    }

    func removePreset(_ name: String) {
        presets.remove(name)
        if settings.presetName == name { settings.presetName = "" }
    }

    // MARK: Fotto

    func connectFotto(_ credential: FottoCredential) {
        Keychain.fottoCredential = credential
        client.credential = credential
        fottoConnected = true
        fottoStatus = "Fotto conectado ao LUMO. Carregando eventos…"
        Task { await loadGalleries() }
    }

    func disconnectFotto() {
        Keychain.fottoCredential = nil
        client.credential = nil
        fottoConnected = false
        fottoGalleries = []
        settings.fottoAuto = false
        fottoStatus = "Conta Fotto desconectada."
    }

    func loadGalleries() async {
        fottoLoading = true
        defer { fottoLoading = false }
        do {
            let listing = try await client.galleries()
            fottoGalleries = listing.items
            fottoListingWarning = listing.complete ? "" : listing.warning
            fottoStatus = "\(listing.items.count) evento(s) no Fotto"
        } catch {
            fottoStatus = error.localizedDescription
            if (error as? FottoError) == .sessionExpired { fottoConnected = false }
        }
    }

    func findGallery(id input: String) async {
        do {
            let g = try await client.gallery(id: input)
            if !fottoGalleries.contains(g) { fottoGalleries.insert(g, at: 0) }
            chooseGallery(g)
        } catch {
            fottoStatus = error.localizedDescription
        }
    }

    func chooseGallery(_ gallery: FottoGallery?) {
        guard var event = currentEvent else { message = "Escolha um evento local primeiro."; return }
        guard !uploading else { message = "Encerre o envio antes de trocar o destino."; return }
        event.fottoGallery = gallery
        try? library.update(event)
        currentEvent = event
        events = library.events()
        settings.fottoAuto = false
        fottoStatus = gallery.map { "Destino: \($0.title). Ative o envio automático ou envie os pendentes." } ?? "Destino removido."
    }

    func startUpload(retryFailed: Bool = false, only ids: Set<String>? = nil) {
        guard let event = currentEvent, !uploading else { return }
        uploading = true
        // Pede alguns minutos extras ao iOS se o app for para segundo plano durante o envio.
        let background = BackgroundTask(name: "Envio Fotto")
        Task {
            let sent = await uploader.run(event: event, retryFailed: retryFailed, only: ids)
            uploading = false
            reloadPhotos()
            background.end()
            // Fotos que chegaram no fim da rodada entram na próxima.
            if settings.fottoAuto, ids == nil, sent > 0, pendingUploads > 0 { startUpload() }
        }
    }

    func stopUpload() {
        settings.fottoAuto = false
        Task { await uploader.stop() }
    }

    func reconcileNow() {
        guard let event = currentEvent, let g = event.fottoGallery else { return }
        Task {
            await uploader.reconcile(event: event, gallery: g, quiet: false)
            reloadPhotos()
        }
    }
}

/// Tempo extra de execução concedido pelo iOS em segundo plano.
@MainActor
final class BackgroundTask {
    private var id: UIBackgroundTaskIdentifier = .invalid

    init(name: String) {
        id = UIApplication.shared.beginBackgroundTask(withName: name) { [weak self] in
            MainActor.assumeIsolated { self?.end() }
        }
    }

    func end() {
        guard id != .invalid else { return }
        UIApplication.shared.endBackgroundTask(id)
        id = .invalid
    }
}
