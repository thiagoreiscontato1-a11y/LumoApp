import Foundation
import ImageCaptureCore
import Network

/// Comandos PTP brutos pela sessão USB do ImageCaptureCore (Canon R8/SL2 via cabo).
/// Cada comando leva um número de transação próprio (o 0 só vale para OpenSession e algumas
/// Canon mais antigas não respondem a ele) e tem tempo limite, para nunca travar a captura.
final class ICCPTPTransport: PTPTransport {
    let camera: ICCameraDevice
    /// Registro de diagnóstico: primeiros comandos, comandos lentos e falhas.
    var trace: ((String) -> Void)?
    private var transaction: UInt32 = 0
    private var traced = 0
    private let lock = NSLock()

    init(camera: ICCameraDevice) { self.camera = camera }

    static func timeout(for code: UInt16) -> TimeInterval { code == PTPCode.getObject ? 180 : 20 }

    private func nextTransaction() -> UInt32 {
        lock.lock(); defer { lock.unlock() }
        transaction &+= 1
        if transaction == 0 { transaction = 1 }
        return transaction
    }

    private func shouldTrace() -> Bool {
        lock.lock(); defer { lock.unlock() }
        traced += 1
        return traced <= 20
    }

    func execute(_ code: UInt16, _ params: [UInt32]) async throws -> Data {
        let tx = nextTransaction()
        let command = PTPContainer.command(code, transaction: tx, params: params)
        let limit = Self.timeout(for: code)
        let started = Date()
        let once = ResumeOnce()
        let opName = String(format: "0x%04X", code)
        let result: Result<Data, Error> = await withCheckedContinuation { continuation in
            DispatchQueue.global().asyncAfter(deadline: .now() + limit) {
                guard once.claim() else { return }
                continuation.resume(returning: .failure(PTPError.transport("A câmera não respondeu ao comando \(opName) em \(Int(limit)) s.")))
            }
            camera.requestSendPTPCommand(command, outData: nil) { data, response, error in
                guard once.claim() else { return }
                if let error {
                    continuation.resume(returning: .failure(PTPError.transport("USB/PTP \(opName): \(error.localizedDescription)")))
                    return
                }
                do {
                    let code = try PTPContainer.responseCode(response)
                    guard code == PTPCode.ok else { throw PTPError.response(code) }
                    continuation.resume(returning: .success(data))
                } catch {
                    continuation.resume(returning: .failure(error))
                }
            }
        }
        let elapsed = Date().timeIntervalSince(started)
        let first = shouldTrace()
        switch result {
        case .success(let data):
            if first || elapsed > 3 {
                trace?(String(format: "PTP %@ tx %u · OK · %d bytes · %.2f s", opName, tx, data.count, elapsed))
            }
            return data
        case .failure(let error):
            trace?(String(format: "PTP %@ tx %u · falhou em %.2f s · ", opName, tx, elapsed) + error.localizedDescription)
            throw error
        }
    }
}

/// PTP/IP pela rede Wi-Fi da câmera (porta de `WifiPtp.java`).
final class WiFiPTPTransport: PTPTransport {
    let host: String
    let guid: UUID
    private var command: NWConnection?
    private var event: NWConnection?
    private var transaction: UInt32 = 0
    private var sessionOpen = false
    private let queue = DispatchQueue(label: "lumo.wifi.ptp")
    private(set) var broken = false

    init(host: String, guid: UUID) {
        self.host = host
        self.guid = guid
    }

    private func connect() async throws -> NWConnection {
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        tcp.connectionTimeout = 6
        let params = NWParameters(tls: nil, tcp: tcp)
        params.requiredInterfaceType = .wifi
        let c = NWConnection(host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: PTPIP.port)!, using: params)
        return try await withCheckedThrowingContinuation { continuation in
            let once = ResumeOnce()
            c.stateUpdateHandler = { state in
                guard !once.done else { return }
                switch state {
                case .ready:
                    once.done = true
                    continuation.resume(returning: c)
                case .failed(let error), .waiting(let error):
                    once.done = true
                    c.cancel()
                    continuation.resume(throwing: PTPError.transport("Wi-Fi: câmera não encontrada em \(self.host) (\(error.localizedDescription)). Conecte o iPhone ao Wi-Fi da câmera."))
                case .cancelled:
                    once.done = true
                    continuation.resume(throwing: PTPError.transport("Conexão Wi-Fi cancelada."))
                default:
                    break
                }
            }
            c.start(queue: queue)
        }
    }

    private func send(_ c: NWConnection, _ data: Data) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            c.send(content: data, completion: .contentProcessed { error in
                if let error { continuation.resume(throwing: PTPError.transport("Wi-Fi: \(error.localizedDescription)")) }
                else { continuation.resume() }
            })
        }
    }

    private func read(_ c: NWConnection, _ count: Int) async throws -> Data {
        if count == 0 { return Data() }
        var out = Data()
        out.reserveCapacity(count)
        while out.count < count {
            let chunk: Data = try await withCheckedThrowingContinuation { continuation in
                c.receive(minimumIncompleteLength: 1, maximumLength: min(count - out.count, 1 << 20)) { data, _, complete, error in
                    if let error { continuation.resume(throwing: PTPError.transport("Wi-Fi: \(error.localizedDescription)")); return }
                    if let data, !data.isEmpty { continuation.resume(returning: data); return }
                    continuation.resume(throwing: PTPError.transport(complete ? "Conexão Wi-Fi encerrada pela câmera." : "Wi-Fi sem dados."))
                }
            }
            out.append(chunk)
        }
        return out
    }

    private func readPacket(_ c: NWConnection) async throws -> (type: UInt32, body: Data) {
        let h = try PTPIP.header(try await read(c, 8))
        return (h.type, try await read(c, h.bodyLength))
    }

    /// Handshake PTP/IP (comando + eventos) e OpenSession.
    func open() async throws {
        do {
            let c = try await connect()
            command = c
            try await send(c, PTPIP.initCommandRequest(guid: guid))
            let ack = try await readPacket(c)
            if ack.type == PTPIP.PacketType.initFail.rawValue { throw PTPError.transport("Pareamento recusado. Autorize LUMO na câmera.") }
            guard ack.type == PTPIP.PacketType.initCommandAck.rawValue, ack.body.count >= 24, ack.body.count <= 1024 else {
                throw PTPError.transport("A câmera não aceitou PTP/IP. Confira o modo Wi-Fi.")
            }
            var r = ByteReader(ack.body)
            let connectionNumber = try r.u32()
            let e = try await connect()
            event = e
            try await send(e, PTPIP.initEventRequest(connectionNumber: connectionNumber))
            let eack = try await readPacket(e)
            guard eack.type == PTPIP.PacketType.initEventAck.rawValue, eack.body.isEmpty else {
                throw PTPError.transport("Canal de eventos Wi-Fi não confirmado.")
            }
            startEventReader(e)
            do {
                _ = try await execute(PTPCode.openSession, [1])
            } catch let error as PTPError where error.responseCode == PTPCode.sessionAlreadyOpen {}
            sessionOpen = true
        } catch {
            close()
            throw error
        }
    }

    /// O canal de eventos só precisa responder aos probes da câmera.
    private func startEventReader(_ e: NWConnection) {
        Task { [weak self] in
            while let self, !self.broken {
                do {
                    let p = try await self.readPacket(e)
                    if p.type == PTPIP.PacketType.probeRequest.rawValue {
                        try await self.send(e, PTPIP.packet(PTPIP.PacketType.probeResponse.rawValue))
                    }
                } catch {
                    if self.event != nil { self.broken = true }
                    return
                }
            }
        }
    }

    func execute(_ code: UInt16, _ params: [UInt32]) async throws -> Data {
        guard let c = command, !broken else { throw PTPError.transport("Conexão Wi-Fi interrompida.") }
        let tx = sessionOpen ? nextTransaction() : 0
        do {
            try await send(c, PTPIP.operationRequest(code, transaction: tx, params: params))
            var assembler = PTPIP.ResponseAssembler(transaction: tx)
            for _ in 0..<200_000 {
                let p = try await readPacket(c)
                switch try assembler.consume(type: p.type, body: p.body) {
                case .needMore: continue
                case .replyProbe: try await send(c, PTPIP.packet(PTPIP.PacketType.probeResponse.rawValue))
                case .done(let result, let data):
                    guard result == PTPCode.ok else { throw PTPError.response(result) }
                    return data
                }
            }
            throw PTPError.protocolViolation("Excesso de pacotes Wi-Fi.")
        } catch let e as PTPError where e.responseCode != nil {
            throw e
        } catch {
            broken = true
            throw error
        }
    }

    private func nextTransaction() -> UInt32 {
        transaction &+= 1
        if transaction == 0 { transaction = 1 }
        return transaction
    }

    func closeSession() async {
        if sessionOpen, !broken { _ = try? await execute(PTPCode.closeSession, []) }
        sessionOpen = false
    }

    func close() {
        let c = command, e = event
        command = nil
        event = nil
        c?.cancel()
        e?.cancel()
    }
}

/// Garante que uma continuação seja retomada uma única vez.
private final class ResumeOnce: @unchecked Sendable {
    var done = false
    private let lock = NSLock()

    /// `true` somente para o primeiro chamador.
    func claim() -> Bool {
        lock.lock(); defer { lock.unlock() }
        if done { return false }
        done = true
        return true
    }
}
