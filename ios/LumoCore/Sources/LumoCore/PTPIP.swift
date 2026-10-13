import Foundation

/// Enquadramento PTP/IP (Wi-Fi), porta de `PtpIpWire.java` / `WifiPtp.java`.
public enum PTPIP {
    public static let port: UInt16 = 15740

    public enum PacketType: UInt32 {
        case initCommandRequest = 1, initCommandAck = 2, initEventRequest = 3, initEventAck = 4, initFail = 5
        case operationRequest = 6, operationResponse = 7, event = 8, startData = 9, data = 10
        case cancel = 11, endData = 12, probeRequest = 13, probeResponse = 14
    }

    public static func packet(_ type: UInt32, _ body: Data = Data()) -> Data {
        var d = Data()
        d.appendLE(UInt32(body.count + 8))
        d.appendLE(type)
        d.append(body)
        return d
    }

    /// Cabeçalho de 8 bytes → (tamanho do corpo, tipo).
    public static func header(_ data: Data) throws -> (bodyLength: Int, type: UInt32) {
        var r = ByteReader(data)
        let size = try r.u32(), type = try r.u32()
        guard size >= 8, size <= UInt32(PTPCode.maxObjectSize) else { throw PTPError.protocolViolation("Pacote PTP/IP inválido.") }
        return (Int(size) - 8, type)
    }

    /// Init Command Request com GUID estável e nome "LUMO".
    public static func initCommandRequest(guid: UUID, name: String = "LUMO") -> Data {
        var body = Data()
        withUnsafeBytes(of: guid.uuid) { body.append(contentsOf: $0) }
        for unit in (name + "\0").utf16 { body.appendLE(unit) }
        body.appendLE(UInt32(0x10000))
        return packet(PacketType.initCommandRequest.rawValue, body)
    }

    public static func initEventRequest(connectionNumber: UInt32) -> Data {
        var body = Data()
        body.appendLE(connectionNumber)
        return packet(PacketType.initEventRequest.rawValue, body)
    }

    public static func operationRequest(_ op: UInt16, transaction: UInt32, params: [UInt32]) -> Data {
        var body = Data()
        body.appendLE(UInt32(1))  // data phase info: sem dados do host
        body.appendLE(op)
        body.appendLE(transaction)
        for p in params { body.appendLE(p) }
        return packet(PacketType.operationRequest.rawValue, body)
    }

    /// Monta a resposta de uma transação a partir dos pacotes recebidos.
    public struct ResponseAssembler {
        public let transaction: UInt32
        public let maxInMemory: Int
        public private(set) var expected: Int64 = -1
        public private(set) var received: Int64 = 0
        public private(set) var ended = false
        public private(set) var data = Data()

        public init(transaction: UInt32, maxInMemory: Int = PTPCode.maxObjectSize) {
            self.transaction = transaction
            self.maxInMemory = maxInMemory
        }

        public enum Step: Equatable {
            case needMore
            /// A câmera mandou um probe; responda com `probeResponse`.
            case replyProbe
            case done(code: UInt16, data: Data)
        }

        public mutating func consume(type: UInt32, body: Data) throws -> Step {
            var r = ByteReader(body)
            switch PacketType(rawValue: type) {
            case .probeRequest where body.isEmpty:
                return .replyProbe
            case .startData:
                guard body.count == 12, expected == -1 else { throw PTPError.protocolViolation("Início de dados Wi-Fi inválido.") }
                guard try r.u32() == transaction else { throw PTPError.protocolViolation("Transação Wi-Fi fora de sequência.") }
                expected = Int64(try r.u64())
                guard expected >= 0, expected <= Int64(maxInMemory) else { throw PTPError.protocolViolation("Resposta Wi-Fi excede o limite.") }
                data.reserveCapacity(Int(expected))
                return .needMore
            case .data, .endData:
                guard body.count >= 4, expected >= 0, !ended else { throw PTPError.protocolViolation("Dados Wi-Fi inesperados.") }
                guard try r.u32() == transaction else { throw PTPError.protocolViolation("Transação Wi-Fi fora de sequência.") }
                let payload = try r.bytes(body.count - 4)
                guard received + Int64(payload.count) <= expected else { throw PTPError.protocolViolation("Dados Wi-Fi excedem tamanho anunciado.") }
                data.append(payload)
                received += Int64(payload.count)
                if type == PacketType.endData.rawValue { ended = true }
                return .needMore
            case .operationResponse:
                guard body.count >= 6, body.count <= 26 else { throw PTPError.protocolViolation("Resposta Wi-Fi inválida.") }
                let code = try r.u16()
                guard try r.u32() == transaction else { throw PTPError.protocolViolation("Transação Wi-Fi fora de sequência.") }
                if code == PTPCode.ok && expected >= 0 && (!ended || received != expected) {
                    throw PTPError.protocolViolation("Foto Wi-Fi incompleta.")
                }
                return .done(code: code, data: data)
            default:
                throw PTPError.protocolViolation("Pacote Wi-Fi inesperado: \(type)")
            }
        }
    }
}
