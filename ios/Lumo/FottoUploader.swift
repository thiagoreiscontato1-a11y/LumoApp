import Foundation

/// Fila de entrega ao Fotto (porta de `FottoSync.java`): cria a mídia, envia direto à URL
/// assinada e confirma em lote, com UMA leitura do evento para várias fotos.
/// Uma foto cujo PUT foi aceito nunca é reenviada automaticamente.
actor FottoUploader {
    let library: EventLibrary
    let client: FottoClient
    private var running = false
    private var stopRequested = false
    private var status: (@Sendable (String) -> Void)?
    private var changed: (@Sendable (PhotoRecord) -> Void)?

    init(library: EventLibrary, client: FottoClient) {
        self.library = library
        self.client = client
    }

    func setHandlers(status: @escaping @Sendable (String) -> Void, changed: @escaping @Sendable (PhotoRecord) -> Void) {
        self.status = status
        self.changed = changed
    }

    var isRunning: Bool { running }

    func setCredential(_ credential: FottoCredential?) { client.credential = credential }

    /// Pede para encerrar: termina somente a foto em envio e não inicia a próxima.
    func stop() {
        stopRequested = true
        if running { status?("Encerrando envio após a foto atual…") }
    }

    /// Envia as fotos aprovadas pendentes do evento. `retryFailed` reabre as que falharam.
    /// Devolve quantas fotos foram aceitas pelo armazenamento nesta rodada.
    @discardableResult
    func run(event: LumoEvent, retryFailed: Bool = false, only ids: Set<String>? = nil) async -> Int {
        guard !running else { return 0 }
        guard let gallery = event.fottoGallery else { status?("Escolha o evento Fotto de destino."); return 0 }
        guard client.credential != nil else { status?("Conecte sua conta Fotto."); return 0 }
        running = true
        stopRequested = false
        defer { running = false }

        if retryFailed {
            for var p in library.photos(in: event) where p.fottoGalleryID == gallery.id && p.upload == .failed {
                p.upload = .none
                p.uploadError = ""
                save(p, event)
            }
        }
        // Fotos interrompidas no meio do envio voltam para a fila.
        for var p in library.photos(in: event) where p.fottoGalleryID == gallery.id && p.upload == .uploading {
            p.upload = .none
            save(p, event)
        }
        await reconcile(event: event, gallery: gallery, quiet: true)

        var sent = 0
        while !stopRequested {
            var pending = library.pendingUploads(in: event, galleryID: gallery.id)
            if let ids { pending = pending.filter { ids.contains($0.id) } }
            guard var photo = pending.first else { break }
            photo.fottoGalleryID = gallery.id
            photo.upload = .uploading
            photo.uploadError = ""
            save(photo, event)
            status?("Enviando \(photo.deliveryName) → \(gallery.title)")
            do {
                let file = library.editedURL(photo, in: event)
                guard FileManager.default.fileExists(atPath: file.path) else {
                    throw FottoError.invalidResponse("Arquivo editado não está mais disponível.")
                }
                let mediaID = try await client.upload(file: file, name: photo.deliveryName, galleryID: gallery.id)
                photo.fottoMediaID = mediaID
                photo.upload = .uploaded
                photo.uploadError = "Aguardando processamento/confirmação no evento."
                save(photo, event)
                sent += 1
                if sent % 15 == 0 { await reconcile(event: event, gallery: gallery, quiet: true) }
            } catch {
                photo.upload = .failed
                photo.uploadError = error.localizedDescription
                save(photo, event)
                status?("Falha no envio · \(error.localizedDescription)")
                if (error as? FottoError) == .sessionExpired { break }
            }
        }
        let wasStopped = stopRequested
        await reconcile(event: event, gallery: gallery, quiet: false)
        let done = library.photos(in: event).filter { $0.fottoGalleryID == gallery.id && $0.upload == .confirmed }.count
        if wasStopped { status?("Envio encerrado · \(done) confirmada(s) no Fotto") }
        else if sent > 0 { status?("\(sent) foto(s) enviada(s) · \(done) confirmada(s) no Fotto") }
        return wasStopped ? 0 : sent
    }

    /// Confirma várias fotos com uma única leitura do evento.
    func reconcile(event: LumoEvent, gallery: FottoGallery, quiet: Bool) async {
        let waiting = library.photos(in: event).filter { $0.fottoGalleryID == gallery.id && $0.upload == .uploaded }
        guard !waiting.isEmpty else { return }
        await client.revalidate(galleryID: gallery.id)
        do {
            let visible = try await client.processedMedia(galleryID: gallery.id)
            guard !visible.isEmpty else { return }
            var byName: [String: String] = [:]
            for (id, name) in visible where !name.isEmpty { byName[name.lowercased()] = id }
            var confirmed = 0
            for var p in waiting {
                let found = (!p.fottoMediaID.isEmpty && visible[p.fottoMediaID] != nil) ? p.fottoMediaID : byName[p.deliveryName.lowercased()]
                guard let found, !found.isEmpty else { continue }
                p.fottoMediaID = found
                p.upload = .confirmed
                p.uploadError = ""
                save(p, event)
                confirmed += 1
            }
            if confirmed > 0 && !quiet { status?("\(confirmed) foto(s) confirmada(s) no Fotto") }
        } catch {
            if !quiet { status?("Confirmação adiada · \(error.localizedDescription)") }
        }
    }

    private func save(_ p: PhotoRecord, _ event: LumoEvent) {
        try? library.save(p, in: event)
        changed?(p)
    }
}
