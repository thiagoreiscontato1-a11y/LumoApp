import Foundation
#if canImport(Compression)
import Compression
#endif

/// Referência do perfil criativo (crs:Look) guardada junto do preset. As tabelas ficam em `CreativeProfile.directory`,
/// pelo MD5 que o próprio Lightroom usa (`Table_<MD5>`); a intensidade fica no preset em `LookAmount` (0–200).
public struct CreativeLook: Codable, Hashable {
    public var name: String
    public var rgbTable: String?
    public var rgbAmount: Double = 1
    public var lookTable: String?
    public var grayscale = false

    public init(name: String, rgbTable: String? = nil, rgbAmount: Double = 1, lookTable: String? = nil, grayscale: Bool = false) {
        self.name = name; self.rgbTable = rgbTable; self.rgbAmount = rgbAmount; self.lookTable = lookTable; self.grayscale = grayscale
    }
}

/// Perfis criativos do Lightroom embutidos nos .xmp: a tabela 3D de cor (RGBTable) e a tabela matiz/saturação/valor
/// (LookTable), no formato do DNG SDK da Adobe (dng_big_table): texto num base 85 próprio → 4 bytes com o tamanho + zlib
/// → tabela. Na revelação as tabelas são "assadas" numa tabela 3D no sRGB do JPEG e aplicadas antes do resto do motor,
/// com interpolação tetraédrica como no SDK. Mesma conta de `CreativeProfile.java`.
public enum CreativeProfileError: Error { case invalid(String) }

public enum CreativeProfile {
    static let lookTableType: UInt32 = 0, rgbTableType: UInt32 = 1
    static let sRGB = 0, adobe = 1, proPhoto = 2, p3 = 3, rec2020 = 4
    static let linear = 0, gammaSRGB = 1, gamma18 = 2, gamma22 = 3, gammaRec2020 = 4
    /// Lado da tabela assada (pontos por canal no sRGB do JPEG).
    static let bakeSize = 33
    /// Pasta das tabelas (definida pelo app; nos testes fica só a memória).
    public static var directory: URL?
    private static let lock = NSLock()
    private static var memory: [String: String] = [:]
    private static var baked: [String: [Float]] = [:]
    private static var bakedOrder: [String] = []

    // MARK: Texto → binário (dng_big_table::ASCIItoBinary)

    static let decodeTable: [UInt8] = {
        var t = [UInt8](repeating: 0xFF, count: 96)
        for i in 0..<10 { t[Int(Character("0").asciiValue!) - 32 + i] = UInt8(i) }
        for i in 0..<26 {
            t[Int(Character("a").asciiValue!) - 32 + i] = UInt8(10 + i)
            t[Int(Character("A").asciiValue!) - 32 + i] = UInt8(36 + i)
        }
        for (i, ch) in ".-:+=^!/*?`'|()[]{}@%$#".utf8.enumerated() { t[Int(ch) - 32] = UInt8(62 + i) }
        return t
    }()

    static func ascii(_ text: String) -> [UInt8] {
        var out: [UInt8] = []
        out.reserveCapacity(text.utf8.count * 4 / 5 + 4)
        var phase = 0
        var value: UInt64 = 0
        for e in text.utf8 {
            guard e >= 32 && e <= 127 else { continue }
            let d = UInt64(decodeTable[Int(e) - 32])
            guard d <= 85 else { continue }
            phase += 1
            switch phase {
            case 1: value = d
            case 2: value += d * 85
            case 3: value += d * 85 * 85
            case 4: value += d * 85 * 85 * 85
            default:
                value += d * 85 * 85 * 85 * 85
                out.append(UInt8(value & 255)); out.append(UInt8((value >> 8) & 255))
                out.append(UInt8((value >> 16) & 255)); out.append(UInt8((value >> 24) & 255))
                phase = 0
            }
        }
        let extra = phase > 3 ? 3 : phase > 2 ? 2 : phase > 1 ? 1 : 0
        for k in 0..<extra { out.append(UInt8((value >> (8 * UInt64(k))) & 255)) }
        return out
    }

    /// Bloco comprimido: tamanho descomprimido (uint32 LE) e zlib.
    static func inflate(_ block: [UInt8]) throws -> [UInt8] {
        guard block.count >= 7 else { throw CreativeProfileError.invalid("Tabela de perfil vazia.") }
        let size = Int(block[0]) | Int(block[1]) << 8 | Int(block[2]) << 16 | Int(block[3]) << 24
        guard size > 0 && size <= 16_000_000 else { throw CreativeProfileError.invalid("Tabela de perfil inválida.") }
        #if canImport(Compression)
        // O Compression lê DEFLATE puro: pula o cabeçalho zlib (2 bytes) e ignora o adler32 do fim.
        let body = Array(block[6...])
        var out = [UInt8](repeating: 0, count: size)
        let n = out.withUnsafeMutableBufferPointer { dst in
            body.withUnsafeBufferPointer { src in
                compression_decode_buffer(dst.baseAddress!, size, src.baseAddress!, src.count, nil, COMPRESSION_ZLIB)
            }
        }
        guard n == size else { throw CreativeProfileError.invalid("Tabela de perfil incompleta.") }
        return out
        #else
        throw CreativeProfileError.invalid("Descompressão indisponível nesta plataforma.")
        #endif
    }

    // MARK: Tabelas

    /// Tabela RGB (1D ou 3D) com as amostras já somadas à identidade, 0–65535, como dng_rgb_table.
    public struct RGBTable { var dims = 3, divisions = 2, primaries = 0, gamma = 1, gamut = 0; var samples: [Int32] = []; var minAmount = 0.0, maxAmount = 2.0 }
    /// Tabela matiz/saturação/valor: desvio de matiz em graus e escalas de saturação e valor por ponto.
    public struct LookTable { var hue = 1, sat = 1, val = 1, encoding = 0; var deltas: [Float] = []; var minAmount = 1.0, maxAmount = 1.0 }
    public enum Table { case rgb(RGBTable), look(LookTable) }

    struct Reader {
        let b: [UInt8]; var p = 0
        mutating func need(_ n: Int) throws { if p + n > b.count { throw CreativeProfileError.invalid("Tabela de perfil truncada.") } }
        mutating func u16() throws -> Int { try need(2); let v = Int(b[p]) | Int(b[p + 1]) << 8; p += 2; return v }
        mutating func u32() throws -> UInt32 { try need(4); let v = UInt32(b[p]) | UInt32(b[p + 1]) << 8 | UInt32(b[p + 2]) << 16 | UInt32(b[p + 3]) << 24; p += 4; return v }
        mutating func f32() throws -> Float { Float(bitPattern: try u32()) }
        mutating func f64() throws -> Double { let lo = UInt64(try u32()), hi = UInt64(try u32()); return Double(bitPattern: hi << 32 | lo) }
    }

    /// Lê uma tabela do texto de `crs:Table_<MD5>`.
    public static func decode(_ text: String) throws -> Table {
        var r = Reader(b: try inflate(ascii(text)))
        let type = try r.u32()
        if type == rgbTableType {
            var t = RGBTable()
            guard try r.u32() == 1 else { throw CreativeProfileError.invalid("Versão de tabela RGB desconhecida.") }
            t.dims = Int(try r.u32()); t.divisions = Int(try r.u32())
            let d = t.divisions
            if t.dims == 1 { guard d >= 2 && d <= 4096 else { throw CreativeProfileError.invalid("Tabela RGB inválida.") } }
            else if t.dims == 3 { guard d >= 2 && d <= 64 else { throw CreativeProfileError.invalid("Tabela RGB inválida.") } }
            else { throw CreativeProfileError.invalid("Tabela RGB inválida.") }
            let nop = (0..<d).map { Int32((Int64($0) * 0xFFFF + Int64(d >> 1)) / Int64(d - 1)) }
            if t.dims == 1 {
                t.samples = [Int32](repeating: 0, count: d * 3)
                for i in 0..<d { for c in 0..<3 { let v = try r.u16(); t.samples[i * 3 + c] = (Int32(v) + nop[i]) & 0xFFFF } }
            } else {
                t.samples = [Int32](repeating: 0, count: d * d * d * 3)
                var k = 0
                for ri in 0..<d { for gi in 0..<d { for bi in 0..<d {
                    let r0 = try r.u16(), g0 = try r.u16(), b0 = try r.u16()
                    t.samples[k] = (Int32(r0) + nop[ri]) & 0xFFFF
                    t.samples[k + 1] = (Int32(g0) + nop[gi]) & 0xFFFF
                    t.samples[k + 2] = (Int32(b0) + nop[bi]) & 0xFFFF
                    k += 3
                } } }
            }
            t.primaries = Int(try r.u32()); t.gamma = Int(try r.u32()); t.gamut = Int(try r.u32())
            guard t.primaries <= 4 && t.gamma <= 4 && t.gamut <= 1 else { throw CreativeProfileError.invalid("Espaço de cor da tabela desconhecido.") }
            t.minAmount = try r.f64(); t.maxAmount = try r.f64()
            if !(t.minAmount >= 0 && t.minAmount <= 1 && t.maxAmount >= 1 && t.maxAmount < 10) { t.minAmount = 0; t.maxAmount = 2 }
            return .rgb(t)
        }
        if type == lookTableType {
            var t = LookTable()
            let version = try r.u32()
            guard version == 1 || version == 2 else { throw CreativeProfileError.invalid("Versão de tabela de visual desconhecida.") }
            t.hue = Int(try r.u32()); t.sat = Int(try r.u32()); t.val = Int(try r.u32())
            guard t.hue >= 1 && t.hue <= 360 && t.sat >= 1 && t.sat <= 256 && t.val >= 1 && t.val <= 256 && t.hue * t.sat * t.val <= 100_000 else {
                throw CreativeProfileError.invalid("Tabela de visual inválida.")
            }
            let n = t.hue * t.sat * t.val * 3
            t.deltas = [Float](repeating: 0, count: n)
            for i in 0..<n { t.deltas[i] = try r.f32() }
            t.encoding = Int(try r.u32())
            guard t.encoding <= 1 else { throw CreativeProfileError.invalid("Codificação da tabela de visual desconhecida.") }
            if version != 1 {
                t.minAmount = try r.f64(); t.maxAmount = try r.f64()
                if !(t.minAmount >= 0 && t.minAmount <= 1 && t.maxAmount >= 1 && t.maxAmount < 10) { t.minAmount = 0; t.maxAmount = 2 }
            }
            return .look(t)
        }
        throw CreativeProfileError.invalid("Tipo de tabela de perfil desconhecido.")
    }

    // MARK: Armazenamento

    static func validKey(_ md5: String?) -> Bool {
        guard let md5, md5.count == 32 else { return false }
        return md5.allSatisfy { $0.isHexDigit }
    }

    /// Guarda o texto de uma tabela (uma vez por MD5).
    public static func store(_ md5: String, _ text: String) throws {
        guard validKey(md5) else { throw CreativeProfileError.invalid("Tabela de perfil sem identificador.") }
        _ = try decode(text)
        let k = md5.uppercased()
        lock.lock(); memory[k] = text; lock.unlock()
        guard let dir = directory else { return }
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let file = dir.appendingPathComponent(k + ".txt")
        if let attrs = try? FileManager.default.attributesOfItem(atPath: file.path), (attrs[.size] as? Int) == text.utf8.count { return }
        try Data(text.utf8).write(to: file, options: .atomic)
    }

    public static func load(_ md5: String?) -> String? {
        guard let md5, validKey(md5) else { return nil }
        let k = md5.uppercased()
        lock.lock(); let cached = memory[k]; lock.unlock()
        if let cached { return cached }
        guard let dir = directory, let data = try? Data(contentsOf: dir.appendingPathComponent(k + ".txt")), let text = String(data: data, encoding: .ascii) else { return nil }
        lock.lock(); memory[k] = text; lock.unlock()
        return text
    }

    // MARK: Espaços de cor

    static let primariesXY: [[Double]] = [[0.64, 0.33, 0.30, 0.60, 0.15, 0.06], [0.64, 0.33, 0.21, 0.71, 0.15, 0.06],
                                          [0.7347, 0.2653, 0.1596, 0.8404, 0.0366, 0.0001], [0.68, 0.32, 0.265, 0.69, 0.15, 0.06],
                                          [0.708, 0.292, 0.170, 0.797, 0.131, 0.046]]
    static let d65 = [0.95047, 1, 1.08883], d50 = [0.96422, 1, 0.82521]
    typealias M3 = [[Double]]

    static func mul(_ m: M3, _ v: [Double]) -> [Double] {
        [m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2], m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2], m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2]]
    }
    static func mul(_ a: M3, _ b: M3) -> M3 {
        var r: M3 = [[0, 0, 0], [0, 0, 0], [0, 0, 0]]
        for i in 0..<3 { for j in 0..<3 { for k in 0..<3 { r[i][j] += a[i][k] * b[k][j] } } }
        return r
    }
    static func inv(_ m: M3) -> M3 {
        let a = m[0][0], b = m[0][1], c = m[0][2], d = m[1][0], e = m[1][1], f = m[1][2], g = m[2][0], h = m[2][1], i = m[2][2]
        let det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        return [[(e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det],
                [(f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det],
                [(d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det]]
    }
    static func toXYZ(_ primaries: Int) -> M3 {
        let p = primariesXY[primaries], w = primaries == proPhoto ? d50 : d65
        var m: M3 = [[0, 0, 0], [0, 0, 0], [0, 0, 0]]
        for i in 0..<3 { let x = p[2 * i], y = p[2 * i + 1]; m[0][i] = x / y; m[1][i] = 1; m[2][i] = (1 - x - y) / y }
        let s = mul(inv(m), w)
        for r in 0..<3 { for c in 0..<3 { m[r][c] *= s[c] } }
        return m
    }
    /// Bradford de um branco para outro.
    static func adapt(_ from: [Double], _ to: [Double]) -> M3 {
        let b: M3 = [[0.8951, 0.2664, -0.1614], [-0.7502, 1.7135, 0.0367], [0.0389, -0.0685, 1.0296]]
        let a = mul(b, from), z = mul(b, to)
        let d: M3 = [[z[0] / a[0], 0, 0], [0, z[1] / a[1], 0], [0, 0, z[2] / a[2]]]
        return mul(inv(b), mul(d, b))
    }
    /// Matriz linear sRGB → primárias da tabela (com adaptação D65 → D50 no ProPhoto).
    static func fromSRGB(_ primaries: Int) -> M3 {
        if primaries == sRGB { return [[1, 0, 0], [0, 1, 0], [0, 0, 1]] }
        var xyz = toXYZ(sRGB)
        if primaries == proPhoto { xyz = mul(adapt(d65, d50), xyz) }
        return mul(inv(toXYZ(primaries)), xyz)
    }
    static func decodeSRGB(_ v: Double) -> Double { v <= 0.04045 ? v / 12.92 : pow((v + 0.055) / 1.055, 2.4) }
    static func encodeSRGB(_ v: Double) -> Double { v <= 0.0031308 ? v * 12.92 : 1.055 * pow(v, 1 / 2.4) - 0.055 }
    static func clamp(_ v: Double) -> Double { v < 0 ? 0 : v > 1 ? 1 : v }
    static func encode(_ gamma: Int, _ x: Double) -> Double {
        let v = clamp(x)
        switch gamma {
        case linear: return v
        case gammaSRGB: return encodeSRGB(v)
        case gamma18: return pow(v, 1 / 1.8)
        case gamma22: return pow(v, 1 / 2.2)
        default: return v < 0.0181 ? 4.5 * v : 1.0993 * pow(v, 0.45) - 0.0993
        }
    }
    static func decode(_ gamma: Int, _ x: Double) -> Double {
        let v = clamp(x)
        switch gamma {
        case linear: return v
        case gammaSRGB: return decodeSRGB(v)
        case gamma18: return pow(v, 1.8)
        case gamma22: return pow(v, 2.2)
        default: return v < 0.0814 ? v / 4.5 : pow((v + 0.0993) / 1.0993, 1 / 0.45)
        }
    }

    // MARK: Aplicação de cada tabela (sRGB linear de entrada e saída)

    static func rgb(_ t: RGBTable, _ amount: Double, _ enc: M3, _ dec: M3, _ c: inout [Double]) {
        let m = mul(enc, c)
        let pr = clamp(m[0]), pg = clamp(m[1]), pb = clamp(m[2])
        let extend = t.gamut == 1
        let dr = extend ? m[0] - pr : 0, dg = extend ? m[1] - pg : 0, db = extend ? m[2] - pb : 0
        let er = encode(t.gamma, pr), eg = encode(t.gamma, pg), eb = encode(t.gamma, pb)
        var o: [Double]
        if t.dims == 1 {
            let s = Double(t.divisions - 1)
            o = [lerp1(t, er * s, 0), lerp1(t, eg * s, 1), lerp1(t, eb * s, 2)]
        } else {
            o = tetra(t.samples, t.divisions, er, eg, eb)
        }
        let or = clamp(er + amount * (o[0] - er)), og = clamp(eg + amount * (o[1] - eg)), ob = clamp(eb + amount * (o[2] - eb))
        c = mul(dec, [decode(t.gamma, or) + dr, decode(t.gamma, og) + dg, decode(t.gamma, ob) + db])
    }
    static func lerp1(_ t: RGBTable, _ x: Double, _ ch: Int) -> Double {
        let i = max(0, min(t.divisions - 2, Int(x))), f = x - Double(i)
        let a: Double = Double(t.samples[i * 3 + ch]) * (1 - f)
        let b: Double = Double(t.samples[(i + 1) * 3 + ch]) * f
        return (a + b) / 65535
    }
    /// Interpolação tetraédrica numa grade n³ de trincas (mesma escolha de tetraedro do SDK), amostras 0–65535.
    static func tetra(_ s: [Int32], _ n: Int, _ r: Double, _ g: Double, _ b: Double) -> [Double] {
        let w = weights(n, r, g, b)
        var out = [0.0, 0.0, 0.0]
        for c in 0..<3 {
            let a0: Double = w.w0 * Double(s[w.base + c])
            let a1: Double = w.w1 * Double(s[w.base + w.o1 + c])
            let a2: Double = w.w2 * Double(s[w.base + w.o2 + c])
            let a3: Double = w.f3 * Double(s[w.base + w.o111 + c])
            out[c] = (a0 + a1 + a2 + a3) / 65535
        }
        return out
    }
    struct Weights { var base: Int, o1: Int, o2: Int, o111: Int, w0: Double, w1: Double, w2: Double, f3: Double }
    @inline(__always) static func weights(_ n: Int, _ r: Double, _ g: Double, _ b: Double) -> Weights {
        let sc = Double(n - 1), rs = r * sc, gs = g * sc, bs = b * sc
        let ri = max(0, min(n - 2, Int(rs))), gi = max(0, min(n - 2, Int(gs))), bi = max(0, min(n - 2, Int(bs)))
        let rf = rs - Double(ri), gf = gs - Double(gi), bf = bs - Double(bi)
        let o001 = 3, o010 = 3 * n, o100 = 3 * n * n, o011 = o010 + o001, o101 = o100 + o001, o110 = o100 + o010, o111 = o110 + o001
        var o1: Int, o2: Int, f1: Double, f2: Double, f3: Double
        if gf >= rf {
            if bf >= gf { o1 = o001; o2 = o011; f1 = bf; f2 = gf; f3 = rf }
            else if bf >= rf { o1 = o010; o2 = o011; f1 = gf; f2 = bf; f3 = rf }
            else { o1 = o010; o2 = o110; f1 = gf; f2 = rf; f3 = bf }
        } else {
            if bf >= rf { o1 = o001; o2 = o101; f1 = bf; f2 = rf; f3 = gf }
            else if bf >= gf { o1 = o100; o2 = o101; f1 = rf; f2 = bf; f3 = gf }
            else { o1 = o100; o2 = o110; f1 = rf; f2 = gf; f3 = bf }
        }
        return Weights(base: ri * o100 + gi * o010 + bi * o001, o1: o1, o2: o2, o111: o111, w0: 1 - f1, w1: f1 - f2, w2: f2 - f3, f3: f3)
    }

    /// RefBaselineHueSatMap no ProPhoto linear (espaço do perfil); a intensidade aproxima os desvios da identidade.
    static func look(_ t: LookTable, _ amount: Double, _ toPro: M3, _ fromPro: M3, _ c: inout [Double]) {
        let p = mul(toPro, c)
        let r = max(0, p[0]), g = max(0, p[1]), b = max(0, p[2])
        let mx = max(r, max(g, b)), mn = min(r, min(g, b))
        var v = mx, s = mx > 0 ? (mx - mn) / mx : 0, h = 0.0
        if mx > mn {
            let d = mx - mn
            if r == mx { h = (g - b) / d } else if g == mx { h = 2 + (b - r) / d } else { h = 4 + (r - g) / d }
            if h < 0 { h += 6 }
        }
        let srgb = t.encoding == 1 && t.val > 1
        var ve = srgb ? encodeSRGB(clamp(v)) : v
        let hScale = t.hue < 2 ? 0 : Double(t.hue) / 6, sScale = Double(t.sat - 1), vScale = Double(t.val - 1)
        let maxH = t.hue - 1, maxS = max(0, t.sat - 2), maxV = max(0, t.val - 2)
        let hueStep = t.sat, valStep = t.hue * hueStep
        let hs = h * hScale, ss = s * sScale, vs = ve * vScale
        var h0 = max(0, min(maxH, Int(hs)))
        let s0 = max(0, min(maxS, Int(ss))), v0 = t.val < 2 ? 0 : max(0, min(maxV, Int(vs)))
        var h1 = h0 + 1
        if h0 >= maxH { h0 = maxH; h1 = 0 }
        let hf1 = hs - Double(h0), sf1 = t.sat < 2 ? 0 : ss - Double(s0), vf1 = t.val < 2 ? 0 : vs - Double(v0)
        let hf0 = 1 - hf1, sf0 = 1 - sf1, vf0 = 1 - vf1
        let sStep = t.sat > 1 ? 3 : 0, vStep = t.val > 1 ? valStep * 3 : 0
        var out = [0.0, 0.0, 0.0]
        for k in 0..<3 {
            let e00 = (v0 * valStep + h0 * hueStep + s0) * 3 + k, e01 = (v0 * valStep + h1 * hueStep + s0) * 3 + k
            let d = t.deltas
            func at(_ i: Int) -> Double { Double(d[i]) }
            let low0: Double = hf0 * at(e00) + hf1 * at(e01)
            let high0: Double = hf0 * at(e00 + vStep) + hf1 * at(e01 + vStep)
            let low1: Double = hf0 * at(e00 + sStep) + hf1 * at(e01 + sStep)
            let high1: Double = hf0 * at(e00 + sStep + vStep) + hf1 * at(e01 + sStep + vStep)
            let a0: Double = vf0 * low0 + vf1 * high0
            let a1: Double = vf0 * low1 + vf1 * high1
            out[k] = sf0 * a0 + sf1 * a1
        }
        let hueShift = out[0] * amount, satScale = 1 + (out[1] - 1) * amount, valScale = 1 + (out[2] - 1) * amount
        h += hueShift * 6 / 360
        s = min(s * satScale, 1)
        ve = clamp(ve * valScale)
        v = srgb ? decodeSRGB(ve) : ve
        h = h.truncatingRemainder(dividingBy: 6)
        if h < 0 { h += 6 }
        let i = Int(h), f = h - Double(i), pp = v * (1 - s), q = v * (1 - s * f), tt = v * (1 - s * (1 - f))
        let rgbOut: [Double]
        switch i {
        case 0: rgbOut = [v, tt, pp]
        case 1: rgbOut = [q, v, pp]
        case 2: rgbOut = [pp, v, tt]
        case 3: rgbOut = [pp, q, v]
        case 4: rgbOut = [tt, pp, v]
        default: rgbOut = [v, pp, q]
        }
        c = mul(fromPro, rgbOut)
    }

    // MARK: Perfil resolvido

    /// Perfil pronto para aplicar: tabelas lidas e intensidades finais.
    public struct Profile {
        let key: String
        let rgb: RGBTable?, look: LookTable?
        let rgbAmount: Double, lookAmount: Double
        let grayscale: Bool
        public var active: Bool { (rgb != nil && rgbAmount > 0) || (look != nil && lookAmount > 0) }
    }

    /// Perfil a partir da referência do preset; `amount` é o slider (100 = como veio, 0–200).
    /// Tabela ausente = perfil ignorado, como um preset sem perfil.
    public static func resolve(_ ref: CreativeLook, amount: Double) -> Profile? {
        var rgbT: RGBTable?, lookT: LookTable?
        if let text = load(ref.rgbTable), case .rgb(let t)? = try? decode(text) { rgbT = t }
        if let text = load(ref.lookTable), case .look(let t)? = try? decode(text) { lookT = t }
        guard rgbT != nil || lookT != nil else { return nil }
        let a = max(0, min(2, amount / 100))
        var ra = rgbT.map { max($0.minAmount, min($0.maxAmount, ref.rgbAmount * a)) } ?? 0
        var la = lookT.map { max($0.minAmount, min($0.maxAmount, a)) } ?? 0
        if a == 0 { ra = 0; la = 0 }
        let key = "\(rgbT == nil ? "-" : ref.rgbTable!.uppercased())/\(lookT == nil ? "-" : ref.lookTable!.uppercased())/\(Int((ra * 1000).rounded()))/\(Int((la * 1000).rounded()))"
        return Profile(key: key, rgb: rgbT, look: lookT, rgbAmount: ra, lookAmount: la, grayscale: ref.grayscale)
    }

    /// Tabela `bakeSize`³ no sRGB do JPEG (entrada e saída 0–1): o perfil inteiro numa consulta por pixel.
    static func bake(_ p: Profile) -> [Float] {
        lock.lock()
        if let cached = baked[p.key] { lock.unlock(); return cached }
        lock.unlock()
        let n = bakeSize
        var t = [Float](repeating: 0, count: n * n * n * 3)
        let enc = p.rgb.map { fromSRGB($0.primaries) }, dec = enc.map { inv($0) }
        let toPro = fromSRGB(proPhoto), fromPro = inv(toPro)
        var k = 0
        for ri in 0..<n { for gi in 0..<n { for bi in 0..<n {
            var c = [decodeSRGB(Double(ri) / Double(n - 1)), decodeSRGB(Double(gi) / Double(n - 1)), decodeSRGB(Double(bi) / Double(n - 1))]
            if let lt = p.look, p.lookAmount > 0 { look(lt, p.lookAmount, toPro, fromPro, &c) }
            if let rt = p.rgb, p.rgbAmount > 0, let enc, let dec { rgb(rt, p.rgbAmount, enc, dec, &c) }
            for j in 0..<3 { t[k] = Float(encodeSRGB(clamp(c[j]))); k += 1 }
        } } }
        lock.lock()
        baked[p.key] = t
        bakedOrder.append(p.key)
        if bakedOrder.count > 4 { baked[bakedOrder.removeFirst()] = nil }
        lock.unlock()
        return t
    }

    /// Foto com o perfil aplicado.
    public static func apply(_ image: PixelImage, _ p: Profile?) -> PixelImage {
        guard let p, p.active, !image.pixels.isEmpty else { return image }
        let t = bake(p), n = bakeSize, w = image.width, h = image.height
        var out = image.pixels
        t.withUnsafeBufferPointer { table in
            out.withUnsafeMutableBufferPointer { buf in
                let px = buf.baseAddress!
                Develop.parallel(h) { y0, y1 in
                    for i in (y0 * w)..<min(buf.count, y1 * w) {
                        let v = px[i]
                        let wt = weights(n, Double((v >> 16) & 255) / 255, Double((v >> 8) & 255) / 255, Double(v & 255) / 255)
                        func channel(_ c: Int) -> UInt32 {
                            let a0: Double = wt.w0 * Double(table[wt.base + c])
                            let a1: Double = wt.w1 * Double(table[wt.base + wt.o1 + c])
                            let a2: Double = wt.w2 * Double(table[wt.base + wt.o2 + c])
                            let a3: Double = wt.f3 * Double(table[wt.base + wt.o111 + c])
                            let x: Double = (a0 + a1 + a2 + a3) * 255
                            return UInt32(max(0, min(255, x.rounded())))
                        }
                        px[i] = (v & 0xFF00_0000) | channel(0) << 16 | channel(1) << 8 | channel(2)
                    }
                }
            }
        }
        return PixelImage(width: w, height: h, pixels: out)
    }
}
