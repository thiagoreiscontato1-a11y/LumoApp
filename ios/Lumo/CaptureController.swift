import Foundation
import ImageCaptureCore

/// Conexão com a câmera (USB pelo ImageCaptureCore ou Wi-Fi PTP/IP) e laço de captura
/// equivalente ao `CaptureService.java`: ativa o modo remoto Canon, lê o índice do cartão a
/// cada ~0,8 s, baixa fotos novas e recupera as antigas em segundo plano.
final class CaptureController: NSObject, ObservableObject {
    enum Phase: Equatable {
        case idle, searching, connecting, connected, busy
        case failed(String)

        var keepsScreenOn: Bool {
            switch self {
            case .connected, .busy, .connecting: return true
            default: return false
            }
        }

        var label: String {
            switch self {
            case .idle: return "DESCONECTADA"
            case .searching: return "PROCURANDO"
            case .connecting: return "CONECTANDO"
            case .connected: return "CONECTADA"
            case .busy: return "OCUPADA"
            case .failed: return "ERRO"
            }
        }
    }

    enum Mode: String { case usb = "USB", wifi = "Wi-Fi", catalog = "USB · catálogo" }

    @Published private(set) var phase: Phase = .idle
    @Published private(set) var status = "Conecte a câmera pelo cabo USB-C (modo PTP) ou pelo Wi-Fi."
    @Published private(set) var cameraNames: [String] = []
    @Published private(set) var activeCamera = ""
    @Published private(set) var mode: Mode?
    @Published private(set) var received = 0
    @Published private(set) var recovered = 0
    @Published private(set) var backlogCount = 0
    @Published private(set) var log: [String] = []

    /// Entrega uma foto ao pipeline. Devolve `true` quando ela foi adicionada ao evento.
    var deliver: ((IncomingPhoto) async -> Bool)?
    /// Consulta se a foto da origem já existe no evento atual.
    var alreadyHave: ((String) async -> Bool)?
    /// Recuperar fotos que já estavam no cartão ao conectar.
    var backfillEnabled = true

    private let browser = ICDeviceBrowser()
    private var cameras: [ICCameraDevice] = []
    private var camera: ICCameraDevice?
    private var loop: Task<Void, Never>?
    private var wifi: WiFiPTPTransport?
    private var catalogSeen = Set<String>()
    private var wantsUSB = false
    /// O iOS terminou de ler o índice do cartão (deviceDidBecomeReady).
    private var catalogReady = false
    /// Comandos PTP diretos já falharam uma vez nesta conexão.
    private var ptpFailures = 0
    private var progressTimer: Timer?
    private var sessionOpenedAt: Date?
    private var slowHintShown = false

    override init() {
        super.init()
        browser.delegate = self
        browser.browsedDeviceTypeMask = .camera
    }

    var isRunning: Bool { demo || loop != nil || camera?.hasOpenSession == true }

    /// Somente para o modo de demonstração (capturas de tela no simulador).
    private var demo = false

    func demoConnected(camera name: String) {
        demo = true
        activeCamera = name
        mode = .usb
        phase = .connected
        status = "Conexão confirmada · aguardando fotos"
    }

    func demoReceived() { received += 1 }

    // MARK: Registro

    private func ui(_ block: @escaping () -> Void) {
        if Thread.isMainThread { block() } else { DispatchQueue.main.async(execute: block) }
    }

    private func note(_ message: String, status update: Bool = true) {
        let stamp = DateFormatter.localizedString(from: Date(), dateStyle: .none, timeStyle: .medium)
        ui {
            if update { self.status = message }
            self.log.insert("\(stamp) · \(message)", at: 0)
            if self.log.count > 200 { self.log.removeLast(self.log.count - 200) }
        }
    }

    private func set(_ phase: Phase) { ui { self.phase = phase } }

    // MARK: USB

    /// Pede acesso às câmeras e começa a procurar dispositivos USB.
    func startBrowsing() {
        guard !browser.isBrowsing else { return }
        browser.requestContentsAuthorization { [weak self] contents in
            self?.browser.requestControlAuthorization { control in
                guard let self else { return }
                self.ui {
                    if contents == .authorized && control == .authorized {
                        self.browser.start()
                        if self.phase == .idle { self.phase = .searching }
                        self.note("Procurando câmera USB… Use o cabo USB-C e ligue a câmera.", status: self.camera == nil)
                    } else {
                        self.phase = .failed("Sem permissão")
                        self.note("O iOS não autorizou o acesso à câmera. Ajustes > Lumo > permita o acesso a câmeras e fotos.")
                    }
                }
            }
        }
    }

    func connectUSB() {
        wantsUSB = true
        startBrowsing()
        guard let device = cameras.first else {
            note("Nenhuma câmera USB encontrada. Confira o cabo, o adaptador e se a câmera está ligada (modo PTP/PC).")
            return
        }
        open(device)
    }

    private func open(_ device: ICCameraDevice) {
        guard camera == nil else { return }
        camera = device
        device.delegate = self
        set(.connecting)
        ui {
            self.activeCamera = device.name ?? "Câmera"
            self.mode = .usb
        }
        catalogReady = false
        ptpFailures = 0
        slowHintShown = false
        note("Abrindo sessão PTP com \(device.name ?? "a câmera")…")
        let ids = String(device.usbVendorID, radix: 16) + ":" + String(device.usbProductID, radix: 16)
        note("USB \(ids) · recursos: " + device.capabilities.joined(separator: ", "), status: false)
        device.requestOpenSession()
    }

    // MARK: Wi-Fi

    func connectWiFi(host: String) {
        guard loop == nil, camera == nil else { note("Encerre a conexão atual antes de usar o Wi-Fi."); return }
        let trimmed = host.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { note("Informe o IP da câmera (ex.: 192.168.1.2)."); return }
        let transport = WiFiPTPTransport(host: trimmed, guid: Keychain.wifiGUID)
        wifi = transport
        set(.connecting)
        ui {
            self.mode = .wifi
            self.activeCamera = "Wi-Fi \(trimmed)"
        }
        note("Wi-Fi: autorize LUMO na câmera, se solicitado.")
        loop = Task { [weak self] in
            guard let self else { return }
            do {
                try await transport.open()
                await self.runLoop(session: CanonPTPSession(transport: transport), label: "wifi:\(trimmed)", source: .wifi)
            } catch {
                self.fail("Wi-Fi: \(error.localizedDescription)")
            }
            await transport.closeSession()
            transport.close()
            self.finishLoop()
        }
    }

    // MARK: Encerrar

    func stop() {
        wantsUSB = false
        if demo {
            demo = false
            phase = .idle
            mode = nil
            return
        }
        if let loop {
            // O laço devolve a câmera ao modo normal e então fecha a sessão USB.
            loop.cancel()
        } else {
            camera?.requestCloseSession()
        }
        catalogQueue.removeAll()
        note("Encerrando conexão…")
    }

    private func finishLoop() {
        ui {
            self.loop = nil
            self.wifi = nil
            if self.camera == nil { self.mode = nil }
            if case .failed = self.phase {} else { self.phase = self.camera == nil ? .idle : self.phase }
        }
    }

    private func fail(_ message: String) {
        set(.failed(message))
        note(message)
    }

    // MARK: Laço de captura (porta do CaptureService)

    /// Devolve `false` se a abertura (modo remoto + leitura do índice) falhou.
    @discardableResult
    private func runLoop(session: CanonPTPSession, label: String, source: PhotoSource) async -> Bool {
        var seen = Set<CanonPTPSession.ObjectRef>()
        var backlog: [CanonPTPSession.ObjectRef] = []
        do {
            note("PTP conectado · ativando captura Canon…")
            do {
                try await session.enableCaptureMode()
            } catch let e as PTPError where e.responseCode == 0x2005 {
                // Operação não suportada: câmera não Canon. Segue pelo índice padrão do cartão.
                note("Modo remoto Canon indisponível; usando somente o índice do cartão.", status: false)
            }
            try? await session.drainEvents()
            note("Conectada · lendo índice do cartão…")
            let initial = try await session.jpegObjects()
            seen = Set(initial)
            if backfillEnabled { backlog = initial.reversed() }
            set(.connected)
            ui { self.backlogCount = backlog.count }
            note(backlog.isEmpty ? "Conexão confirmada. Fotografe na câmera." : "Conexão confirmada · retroativo em segundo plano (\(backlog.count))")
        } catch {
            fail("Abertura falhou: \(error.localizedDescription)")
            await session.close()
            return false
        }

        var transientErrors = 0
        while !Task.isCancelled {
            let started = Date()
            do {
                try await session.drainEvents()
                var live = false
                for o in try await session.jpegObjects() where !seen.contains(o) {
                    if Task.isCancelled { break }
                    if try await download(o, session: session, label: label, source: source, retro: false) { live = true }
                    seen.insert(o)
                }
                if !Task.isCancelled, !live, let old = backlog.first {
                    do {
                        _ = try await download(old, session: session, label: label, source: source, retro: true)
                    } catch let e as PTPError where e.responseCode == PTPCode.invalidObjectHandle {
                        note("Retroativo ignorado · objeto já não existe no cartão.", status: false)
                    }
                    backlog.removeFirst()
                    let remaining = backlog.count
                    ui { self.backlogCount = remaining }
                }
                transientErrors = 0
                if phase != .connected { set(.connected) }
                let remaining = backlog.count
                ui { self.status = remaining == 0 ? "Conexão confirmada · aguardando fotos" : "Conectada · recuperando retroativo (\(remaining) restante(s))" }
            } catch let e as PTPError where (e.responseCode == PTPCode.deviceBusy || e.responseCode == PTPCode.invalidObjectHandle) && transientErrors < 8 {
                transientErrors += 1
                set(.busy)
                note("Câmera ocupada · aguardando resposta")
            } catch {
                if !Task.isCancelled { fail("Captura pausada: \(error.localizedDescription)") }
                break
            }
            let elapsed = Date().timeIntervalSince(started)
            let wait = max(0.001, 0.8 - elapsed)
            try? await Task.sleep(nanoseconds: UInt64(wait * 1_000_000_000))
        }
        await session.close()
        return true
    }

    private func download(_ o: CanonPTPSession.ObjectRef, session: CanonPTPSession, label: String, source: PhotoSource, retro: Bool) async throws -> Bool {
        let info: PTPObjectInfo
        do {
            info = try await session.info(o.handle)
        } catch let e as PTPError where e.responseCode == nil {
            note("Ignorado · \(e.localizedDescription)", status: false)
            return false
        }
        let key = "\(label):\(o.storage):\(info.name):\(info.captureDate):\(info.size)"
        if await alreadyHave?(key) == true { return false }
        note((retro ? "Recuperando " : "Baixando ") + "\(info.name) · \(info.size / 1024) KB", status: false)
        let started = Date()
        let data = try await session.download(o.handle, expectedSize: info.size)
        let incoming = IncomingPhoto(data: data, originalName: info.name, capturedAt: PTPDate.parse(info.captureDate),
                                     source: source, sourceKey: key)
        guard await deliver?(incoming) == true else { return false }
        let seconds = String(format: "%.2f", Date().timeIntervalSince(started))
        ui {
            self.received += 1
            if retro { self.recovered += 1 }
        }
        note("\(retro ? "Retroativo" : "Recebida") · \(info.name) · \(seconds) s", status: false)
        return true
    }

    // MARK: Catálogo do ImageCaptureCore (alternativa quando PTP bruto não é aceito)

    private func startCatalogMode(_ device: ICCameraDevice) {
        ui { self.mode = .catalog }
        set(.connected)
        note("Modo compatível: o Lumo recebe as fotos que a câmera publica no catálogo USB.")
        let files = (device.mediaFiles ?? []).compactMap { $0 as? ICCameraFile }
        for f in files { catalogSeen.insert(catalogKey(f)) }
        if backfillEnabled { files.reversed().forEach { enqueueCatalog($0, retro: true) } }
    }

    private func catalogKey(_ f: ICCameraFile) -> String {
        "usbcat:\(f.name ?? "?"):\(f.fileSize):\(f.exifCreationDate?.timeIntervalSince1970 ?? 0)"
    }

    private var catalogQueue: [(ICCameraFile, Bool)] = []
    private var catalogBusy = false

    private func enqueueCatalog(_ file: ICCameraFile, retro: Bool) {
        let name = (file.name ?? "").lowercased()
        guard name.hasSuffix(".jpg") || name.hasSuffix(".jpeg") || (file.uti ?? "").contains("jpeg") else { return }
        catalogQueue.append((file, retro))
        pumpCatalog()
    }

    private func pumpCatalog() {
        guard !catalogBusy, !catalogQueue.isEmpty else { return }
        catalogBusy = true
        let (file, retro) = catalogQueue.removeFirst()
        ui { self.backlogCount = self.catalogQueue.count }
        Task { [weak self] in
            guard let self else { return }
            await self.downloadCatalog(file, retro: retro)
            self.ui {
                self.catalogBusy = false
                self.pumpCatalog()
            }
        }
    }

    private func downloadCatalog(_ file: ICCameraFile, retro: Bool) async {
        let key = catalogKey(file)
        if await alreadyHave?(key) == true { return }
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("lumo-usb", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let temp = UUID().uuidString + ".jpg"
        let options: [ICDownloadOption: Any] = [.downloadsDirectoryURL: dir, .saveAsFilename: temp, .overwrite: true]
        let url: URL? = await withCheckedContinuation { continuation in
            _ = file.requestDownload(options: options) { filename, error in
                if error != nil { continuation.resume(returning: nil); return }
                continuation.resume(returning: dir.appendingPathComponent(filename ?? temp))
            }
        }
        guard let url, let data = try? Data(contentsOf: url) else {
            note("Falha ao baixar \(file.name ?? "foto") pelo catálogo USB.", status: false)
            return
        }
        try? FileManager.default.removeItem(at: url)
        let incoming = IncomingPhoto(data: data, originalName: file.name ?? "IMG.JPG", capturedAt: file.exifCreationDate,
                                     source: .usb, sourceKey: key)
        if await deliver?(incoming) == true {
            ui {
                self.received += 1
                if retro { self.recovered += 1 }
            }
            note("\(retro ? "Retroativo" : "Recebida") · \(file.name ?? "")", status: false)
        }
    }
}

// MARK: - ImageCaptureCore

extension CaptureController: ICDeviceBrowserDelegate {
    func deviceBrowser(_ browser: ICDeviceBrowser, didAdd device: ICDevice, moreComing: Bool) {
        guard let cam = device as? ICCameraDevice else { return }
        ui {
            if !self.cameras.contains(where: { $0 === cam }) { self.cameras.append(cam) }
            self.cameraNames = self.cameras.map { $0.name ?? "Câmera" }
            self.note("Câmera encontrada: \(cam.name ?? "USB")")
            if self.wantsUSB && self.camera == nil { self.open(cam) }
        }
    }

    func deviceBrowser(_ browser: ICDeviceBrowser, didRemove device: ICDevice, moreGoing: Bool) {
        ui {
            self.cameras.removeAll { $0 === device }
            self.cameraNames = self.cameras.map { $0.name ?? "Câmera" }
            if self.camera === device { self.cameraGone("Câmera desconectada do USB.") }
        }
    }

    private func cameraGone(_ message: String) {
        stopProgress()
        loop?.cancel()
        camera = nil
        catalogQueue.removeAll()
        catalogSeen.removeAll()
        phase = wantsUSB ? .failed(message) : .idle
        note(message)
    }
}

extension CaptureController: ICCameraDeviceDelegate {
    func device(_ device: ICDevice, didOpenSessionWithError error: Error?) {
        ui {
            if let error {
                self.camera = nil
                self.fail("Não foi possível abrir a câmera: \(error.localizedDescription)")
                return
            }
            guard let cam = device as? ICCameraDevice, self.camera === cam else { return }
            self.note("Sessão aberta · lendo o cartão…")
            self.sessionOpenedAt = Date()
            self.startProgress(cam)
            // Não espera o iOS terminar de catalogar o cartão inteiro (pode demorar muito
            // com milhares de fotos): tenta os comandos Canon diretos imediatamente.
            self.startPTP(cam)
        }
    }

    func deviceDidBecomeReady(withCompleteContentCatalog device: ICCameraDevice) {
        ui {
            guard self.camera === device else { return }
            self.catalogReady = true
            self.stopProgress()
            self.note("iOS terminou de ler o cartão · \(device.mediaFiles?.count ?? 0) arquivo(s).", status: false)
            guard self.loop == nil, self.mode != .catalog else { return }
            if self.ptpFailures >= 2 { self.startCatalogMode(device) } else { self.startPTP(device) }
        }
    }

    /// Tenta o caminho principal (comandos Canon diretos). Na segunda falha usa o catálogo.
    private func startPTP(_ device: ICCameraDevice) {
        guard loop == nil, camera === device else { return }
        let transport = ICCPTPTransport(camera: device)
        transport.trace = { [weak self] line in self?.note(line, status: false) }
        let label = "usb:\(device.usbVendorID):\(device.usbProductID):\(device.name ?? "")"
        loop = Task { [weak self] in
            guard let self else { return }
            let session = CanonPTPSession(transport: transport)
            var opened = false
            do {
                // Verifica se o iOS aceita comandos PTP diretos para esta câmera.
                _ = try await session.jpegObjects()
                opened = await self.runLoop(session: session, label: label, source: .usb)
            } catch {
                self.note("PTP direto indisponível (\(error.localizedDescription)).", status: false)
            }
            self.ui {
                self.loop = nil
                if opened {
                    if !self.wantsUSB { device.requestCloseSession() }
                    self.finishLoop()
                    return
                }
                guard self.camera === device, self.wantsUSB else { self.finishLoop(); return }
                self.ptpFailures += 1
                if self.catalogReady {
                    self.set(.connecting)
                    if self.ptpFailures >= 2 { self.startCatalogMode(device) } else { self.startPTP(device) }
                } else {
                    self.set(.connecting)
                    self.note("Aguardando o iOS terminar de ler o cartão para tentar de novo…")
                }
            }
        }
    }

    /// Mostra o progresso da leitura do cartão e dicas se demorar.
    private func startProgress(_ device: ICCameraDevice) {
        stopProgress()
        progressTimer = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { [weak self] _ in
            guard let self, self.camera === device, !self.catalogReady else { return }
            let count = device.mediaFiles?.count ?? 0
            if self.loop == nil || self.phase == .connecting {
                self.status = "Sessão aberta · iOS lendo o cartão \(device.contentCatalogPercentCompleted)% (\(count) arquivo(s))"
            }
            if !self.slowHintShown, let opened = self.sessionOpenedAt, Date().timeIntervalSince(opened) > 60, self.phase != .connected {
                self.slowHintShown = true
                self.note("Está demorando. Confira: Wi-Fi/Bluetooth da câmera DESLIGADOS (a Canon bloqueia o USB com Wi-Fi ativo), desligamento automático desativado, cabo de dados e cartão com poucas fotos. Se continuar, toque em Copiar registro e envie.", status: false)
            }
        }
    }

    private func stopProgress() {
        progressTimer?.invalidate()
        progressTimer = nil
    }

    func device(_ device: ICDevice, didCloseSessionWithError error: Error?) {
        ui {
            if self.camera === device {
                self.stopProgress()
                self.loop?.cancel()
                self.camera = nil
                if case .failed = self.phase {} else { self.phase = .idle }
                self.mode = nil
            }
        }
    }

    func didRemove(_ device: ICDevice) {
        ui { if self.camera === device { self.cameraGone("Câmera desconectada.") } }
    }

    func cameraDevice(_ camera: ICCameraDevice, didAdd items: [ICCameraItem]) {
        ui {
            guard self.mode == .catalog, self.camera === camera else { return }
            for case let f as ICCameraFile in items where self.catalogSeen.insert(self.catalogKey(f)).inserted {
                self.enqueueCatalog(f, retro: false)
            }
        }
    }

    func cameraDevice(_ camera: ICCameraDevice, didRemove items: [ICCameraItem]) {}
    func cameraDevice(_ camera: ICCameraDevice, didReceiveThumbnail thumbnail: CGImage?, for item: ICCameraItem, error: Error?) {}
    func cameraDevice(_ camera: ICCameraDevice, didReceiveMetadata metadata: [AnyHashable: Any]?, for item: ICCameraItem, error: Error?) {}
    func cameraDevice(_ camera: ICCameraDevice, didRenameItems items: [ICCameraItem]) {}
    func cameraDeviceDidChangeCapability(_ camera: ICCameraDevice) {}
    func cameraDevice(_ camera: ICCameraDevice, didReceivePTPEvent eventData: Data) {}
    func cameraDeviceDidRemoveAccessRestriction(_ device: ICDevice) {}
    func cameraDeviceDidEnableAccessRestriction(_ device: ICDevice) {
        note("A câmera bloqueou o acesso (cartão protegido ou modo incompatível).")
    }

    /// Texto do registro em ordem cronológica, para copiar ou compartilhar.
    var logText: String {
        (["Lumo iOS \(AppModel.version) · \(activeCamera) · \(mode?.rawValue ?? "-") · \(phase.label)"] + log.reversed())
            .joined(separator: "\n")
    }
}
