import Foundation

/// Códigos PTP/Canon usados pelo Lumo (mesmos do `Ptp.java`).
public enum PTPCode {
    public static let openSession: UInt16 = 0x1002
    public static let closeSession: UInt16 = 0x1003
    public static let getDeviceInfo: UInt16 = 0x1001
    public static let getStorageIDs: UInt16 = 0x1004
    public static let getObjectHandles: UInt16 = 0x1007
    public static let getObjectInfo: UInt16 = 0x1008
    public static let getObject: UInt16 = 0x1009
    public static let canonSetRemoteMode: UInt16 = 0x9114
    public static let canonSetEventMode: UInt16 = 0x9115
    public static let canonGetEvent: UInt16 = 0x9116

    public static let ok: UInt16 = 0x2001
    public static let invalidObjectHandle: UInt16 = 0x2009
    public static let deviceBusy: UInt16 = 0x2019
    public static let sessionAlreadyOpen: UInt16 = 0x201E

    public static let formatJPEG: UInt16 = 0x3801
    public static let maxObjectSize = 150 * 1024 * 1024
}

public enum PTPError: LocalizedError, Equatable {
    /// A câmera respondeu com um código diferente de OK.
    case response(UInt16)
    case protocolViolation(String)
    case transport(String)

    public var errorDescription: String? {
        switch self {
        case .response(let code): return String(format: "Resposta PTP 0x%04X", code)
        case .protocolViolation(let m), .transport(let m): return m
        }
    }

    public var responseCode: UInt16? {
        if case .response(let c) = self { return c }
        return nil
    }
}

/// Leitura/escrita little-endian sobre `Data`.
public struct ByteReader {
    public let data: Data
    public var offset: Int

    public init(_ data: Data, offset: Int = 0) {
        self.data = Data(data)  // reindexa a partir de 0
        self.offset = offset
    }

    public var remaining: Int { data.count - offset }

    public mutating func u8() throws -> UInt8 {
        guard remaining >= 1 else { throw PTPError.protocolViolation("Dados PTP incompletos.") }
        defer { offset += 1 }
        return data[offset]
    }

    public mutating func u16() throws -> UInt16 {
        guard remaining >= 2 else { throw PTPError.protocolViolation("Dados PTP incompletos.") }
        defer { offset += 2 }
        return UInt16(data[offset]) | UInt16(data[offset + 1]) << 8
    }

    public mutating func u32() throws -> UInt32 {
        guard remaining >= 4 else { throw PTPError.protocolViolation("Dados PTP incompletos.") }
        defer { offset += 4 }
        var v: UInt32 = 0
        for i in 0..<4 { v |= UInt32(data[offset + i]) << (8 * UInt32(i)) }
        return v
    }

    public mutating func u64() throws -> UInt64 {
        let lo = try u32(), hi = try u32()
        return UInt64(lo) | UInt64(hi) << 32
    }

    public mutating func bytes(_ n: Int) throws -> Data {
        guard n >= 0, remaining >= n else { throw PTPError.protocolViolation("Dados PTP incompletos.") }
        defer { offset += n }
        return data.subdata(in: offset..<offset + n)
    }

    /// String PTP: 1 byte com o número de caracteres UTF-16 (incluindo o nulo).
    public mutating func ptpString() throws -> String {
        guard remaining > 0 else { return "" }
        let n = Int(try u8())
        guard remaining >= n * 2 else { throw PTPError.protocolViolation("Texto PTP incompleto.") }
        var units: [UInt16] = []
        for _ in 0..<n {
            let c = try u16()
            if c != 0 { units.append(c) }
        }
        return String(decoding: units, as: UTF16.self)
    }
}

extension Data {
    mutating func appendLE(_ v: UInt16) { append(UInt8(v & 255)); append(UInt8(v >> 8)) }
    mutating func appendLE(_ v: UInt32) { for i in 0..<4 { append(UInt8((v >> (8 * UInt32(i))) & 255)) } }
    mutating func appendLE(_ v: UInt64) { appendLE(UInt32(v & 0xFFFF_FFFF)); appendLE(UInt32(v >> 32)) }
}

public enum PTPContainer {
    public static let command: UInt16 = 1
    public static let data: UInt16 = 2
    public static let response: UInt16 = 3
    public static let event: UInt16 = 4

    /// Contêiner de comando USB (12 bytes + 4 por parâmetro).
    public static func command(_ code: UInt16, transaction: UInt32, params: [UInt32]) -> Data {
        var d = Data()
        d.appendLE(UInt32(12 + 4 * params.count))
        d.appendLE(Self.command)
        d.appendLE(code)
        d.appendLE(transaction)
        for p in params { d.appendLE(p) }
        return d
    }

    public struct Header: Equatable {
        public var length: UInt32
        public var type: UInt16
        public var code: UInt16
        public var transaction: UInt32
    }

    public static func header(_ data: Data) throws -> Header {
        var r = ByteReader(data)
        let h = Header(length: try r.u32(), type: try r.u16(), code: try r.u16(), transaction: try r.u32())
        guard h.length >= 12, h.length <= UInt32(PTPCode.maxObjectSize) else {
            throw PTPError.protocolViolation("Tamanho PTP inválido.")
        }
        return h
    }

    /// Código de resposta de um contêiner de resposta (ou de um bloco só com os 12 bytes).
    public static func responseCode(_ data: Data) throws -> UInt16 {
        let h = try header(data)
        guard h.type == response else { throw PTPError.protocolViolation("Resposta PTP inesperada.") }
        return h.code
    }
}

/// Informações de um objeto do cartão (GetObjectInfo).
public struct PTPObjectInfo: Equatable {
    public var storage: UInt32
    public var format: UInt16
    public var size: Int
    public var name: String
    public var captureDate: String

    public var isJPEG: Bool { format == PTPCode.formatJPEG }

    public static func parse(_ data: Data) throws -> PTPObjectInfo {
        guard data.count >= 53 else { throw PTPError.protocolViolation("Dados da foto incompletos.") }
        var r = ByteReader(data)
        let storage = try r.u32()
        let format = try r.u16()
        _ = try r.u16()  // protection
        let size = Int(try r.u32())
        r.offset = 52
        let name = try r.ptpString()
        let date = try r.ptpString()
        return PTPObjectInfo(storage: storage, format: format, size: size, name: name, captureDate: date)
    }
}

public enum PTPArray {
    public static func u32(_ bytes: Data) throws -> [UInt32] {
        guard bytes.count >= 4 else { throw PTPError.protocolViolation("Lista PTP incompleta.") }
        var r = ByteReader(bytes)
        let n = Int(try r.u32())
        guard n >= 0, n <= (bytes.count - 4) / 4 else { throw PTPError.protocolViolation("Lista PTP inválida.") }
        return try (0..<n).map { _ in try r.u32() }
    }
}

public enum PTPDate {
    /// Datas PTP como "20261013T183000" (com ou sem fração/fuso).
    public static func parse(_ s: String) -> Date? {
        let t = s.trimmingCharacters(in: .whitespaces)
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = .current
        for pattern in ["yyyyMMdd'T'HHmmss", "yyyyMMddHHmmss", "yyyy:MM:dd HH:mm:ss"] {
            f.dateFormat = pattern
            let candidate = pattern.hasPrefix("yyyyMMdd'T'") ? String(t.prefix(15)) : t
            if let d = f.date(from: candidate) { return d }
        }
        return nil
    }
}

/// Transporte de comandos PTP (USB via ImageCaptureCore ou Wi-Fi PTP/IP).
public protocol PTPTransport: AnyObject {
    /// Executa um comando e devolve a fase de dados (vazia quando não houver).
    /// Deve lançar `PTPError.response` quando a resposta não for OK.
    func execute(_ code: UInt16, _ params: [UInt32]) async throws -> Data
}

/// Sessão Canon com a mesma sequência validada no Android (R8, SL2…).
public final class CanonPTPSession {
    public let transport: PTPTransport
    public private(set) var remote = false
    public private(set) var events = false
    let sleep: (UInt64) async -> Void

    public init(transport: PTPTransport, sleep: ((UInt64) async -> Void)? = nil) {
        self.transport = transport
        self.sleep = sleep ?? { ns in try? await Task.sleep(nanoseconds: ns) }
    }

    @discardableResult
    public func execute(_ code: UInt16, _ params: UInt32...) async throws -> Data {
        try await transport.execute(code, params)
    }

    /// DeviceBusy (0x2019) é comum por alguns instantes ao entrar no modo USB.
    func busyRetry(_ code: UInt16, _ params: [UInt32]) async throws {
        var last: Error?
        for attempt in 1...8 {
            do {
                _ = try await transport.execute(code, params)
                return
            } catch let e as PTPError where e.responseCode == PTPCode.deviceBusy {
                last = e
                await sleep(UInt64(min(900, 120 * attempt)) * 1_000_000)
            }
        }
        if let last { throw last }
    }

    /// SetRemoteMode / SetEventMode / GetEvent.
    public func enableCaptureMode() async throws {
        if !remote { try await busyRetry(PTPCode.canonSetRemoteMode, [1]); remote = true }
        if !events { try await busyRetry(PTPCode.canonSetEventMode, [1]); events = true }
        do { try await busyRetry(PTPCode.canonGetEvent, []) } catch let e as PTPError where e.responseCode == PTPCode.deviceBusy || e.responseCode == PTPCode.invalidObjectHandle {}
    }

    /// Esvazia a fila de eventos Canon para a câmera atualizar o índice do cartão.
    public func drainEvents() async throws {
        guard events else { return }
        do { _ = try await transport.execute(PTPCode.canonGetEvent, []) } catch let e as PTPError where e.responseCode == PTPCode.deviceBusy || e.responseCode == PTPCode.invalidObjectHandle {}
    }

    public struct ObjectRef: Hashable {
        public var storage: UInt32
        public var handle: UInt32
        public init(storage: UInt32, handle: UInt32) { self.storage = storage; self.handle = handle }
    }

    /// JPEGs de todos os cartões.
    public func jpegObjects() async throws -> [ObjectRef] {
        var all: [ObjectRef] = []
        for storage in try PTPArray.u32(try await transport.execute(PTPCode.getStorageIDs, [])) {
            let handles = try PTPArray.u32(try await transport.execute(PTPCode.getObjectHandles, [storage, UInt32(PTPCode.formatJPEG), 0]))
            all += handles.map { ObjectRef(storage: storage, handle: $0) }
        }
        return all
    }

    public func info(_ handle: UInt32) async throws -> PTPObjectInfo {
        let info = try PTPObjectInfo.parse(try await transport.execute(PTPCode.getObjectInfo, [handle]))
        guard info.isJPEG else { throw PTPError.protocolViolation("Arquivo não JPEG.") }
        guard info.size > 0, info.size <= PTPCode.maxObjectSize - 12 else { throw PTPError.protocolViolation("Foto excede 150 MB.") }
        return info
    }

    public func download(_ handle: UInt32, expectedSize: Int) async throws -> Data {
        let data = try await transport.execute(PTPCode.getObject, [handle])
        guard data.count == expectedSize else { throw PTPError.protocolViolation("JPEG incompleto.") }
        return data
    }

    /// Devolve a câmera ao modo normal. Erros são ignorados.
    public func close() async {
        if events { _ = try? await transport.execute(PTPCode.canonSetEventMode, [0]) }
        if remote { _ = try? await transport.execute(PTPCode.canonSetRemoteMode, [0]) }
        events = false
        remote = false
    }
}
