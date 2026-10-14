import Foundation

/// Controles manuais/automáticos no mesmo intervalo do Lightroom/Android
/// (exposição em EV de -5 a 5; demais de -100 a 100).
public struct EditControls: Codable, Hashable {
    public var exposure: Double = 0
    public var contrast: Double = 0
    public var highlights: Double = 0
    public var shadows: Double = 0
    public var whites: Double = 0
    public var blacks: Double = 0
    public var temperature: Double = 0
    public var tint: Double = 0
    public var vibrance: Double = 0
    public var saturation: Double = 0

    public init() {}

    public static let zero = EditControls()

    public var isNeutral: Bool { self == .zero }

    static let keyPaths: [WritableKeyPath<EditControls, Double>] = [
        \.exposure, \.contrast, \.highlights, \.shadows, \.whites,
        \.blacks, \.temperature, \.tint, \.vibrance, \.saturation,
    ]

    /// Soma limitada, como `PhotoEditor.sumControls`.
    public static func + (a: EditControls, b: EditControls) -> EditControls {
        var r = EditControls()
        for k in keyPaths {
            let (lo, hi) = k == \EditControls.exposure ? (-5.0, 5.0) : (-100.0, 100.0)
            r[keyPath: k] = limit(a[keyPath: k] + b[keyPath: k], lo, hi)
        }
        return r
    }

    public func scaled(by s: Double) -> EditControls {
        var r = self
        for k in Self.keyPaths { r[keyPath: k] = self[keyPath: k] * s }
        return r
    }
}

/// Opções de edição por foto (equivalente ao JSON `settings` do Android).
public struct EditOptions: Codable, Hashable {
    public var auto: Bool = true
    public var autoStrength: Double = 1
    /// Ajustes Camera Raw do XMP (chaves como `Exposure2012`).
    public var preset: [String: Double]?
    public var presetName: String = ""
    public var manual: EditControls?
    /// Maior lado do JPEG editado; 0 mantém o original.
    public var edge: Int = 2560
    public var curation: Bool = true
    /// 0 = tolerante, 1 = equilibrada, 2 = rigorosa.
    public var curationSensitivity: Int = 1

    public init() {}
}

/// Porta fiel de `PhotoEditor.java`.
public enum PhotoEditor {
    public static let colors = ["Red", "Orange", "Yellow", "Green", "Aqua", "Blue", "Purple", "Magenta"]

    // MARK: Pipeline

    public static func edit(_ image: inout PixelImage, options: EditOptions) {
        if options.auto { auto(&image.pixels, strength: options.autoStrength) }
        if let preset = options.preset { applyPreset(&image, preset) }
        if let manual = options.manual { applyManual(&image.pixels, manual) }
    }

    // MARK: Automático

    static func percentile(_ hist: [Int], _ count: Int, _ fraction: Double) -> Double {
        var total = 0
        for i in 0..<256 {
            total += hist[i]
            if Double(total) >= Double(count) * fraction { return Double(i) / 255 }
        }
        return 1
    }

    struct Stats {
        var hist = [Int](repeating: 0, count: 256)
        var count = 0, neutral = 0
        var saturation = 0.0, rs = 0.0, gs = 0.0, bs = 0.0
    }

    static func stats(_ data: [UInt32]) -> Stats {
        var s = Stats()
        let stride = max(1, data.count / 65536)
        var i = 0
        while i < data.count {
            let v = data[i]
            let r = channelR(v), g = channelG(v), b = channelB(v)
            let l = 0.2126 * r + 0.7152 * g + 0.0722 * b
            let mx = max(r, max(g, b)), mn = min(r, min(g, b))
            let sat = mx == 0 ? 0 : (mx - mn) / mx
            s.hist[Int((l * 255).rounded())] += 1
            s.count += 1
            s.saturation += sat
            if l > 0.18 && l < 0.82 && sat < 0.18 { s.neutral += 1; s.rs += r; s.gs += g; s.bs += b }
            i += stride
        }
        return s
    }

    /// Valores sugeridos pelo automático, para exibir nos controles manuais.
    public static func autoControls(_ data: [UInt32]) -> EditControls {
        var out = EditControls()
        guard !data.isEmpty else { return out }
        let s = stats(data)
        guard s.count > 0 else { return out }
        let lo = percentile(s.hist, s.count, 0.1), mid = percentile(s.hist, s.count, 0.5), hi = percentile(s.hist, s.count, 0.9)
        var ev = 0.0
        if mid > 0.02 && mid < 0.38 { ev = limit(log(0.40 / mid) / log(2) * 0.35, 0, 0.45) }
        else if mid > 0.65 && mid < 0.98 { ev = limit(log(0.60 / mid) / log(2) * 0.28, -0.35, 0) }
        out.exposure = ev
        out.contrast = hi - lo < 0.35 && hi - lo > 0.06 ? 4 : 0
        out.saturation = s.saturation / Double(s.count) < 0.3 ? 4 : 0
        if Double(s.neutral) >= max(32, Double(s.count) * 0.03) {
            let avg = (s.rs + s.gs + s.bs) / 3
            let gr = limit(1 + 0.35 * (avg / s.rs - 1), 0.97, 1.03)
            let gg = limit(1 + 0.35 * (avg / s.gs - 1), 0.97, 1.03)
            let gb = limit(1 + 0.35 * (avg / s.bs - 1), 0.97, 1.03)
            out.temperature = limit((gr - gb) / 0.32 * 100, -20, 20)
            let rb = (gr + gb) / 2
            out.tint = limit((1 - gg / max(0.001, rb)) / 0.12 * 100, -20, 20)
        }
        return out
    }

    /// Ponto de partida do editor manual: automático escalado + preset.
    public static func baseControls(_ data: [UInt32], options: EditOptions) -> EditControls {
        let a = options.auto ? autoControls(data) : EditControls()
        return a.scaled(by: limit(options.autoStrength, 0, 1.5)) + presetControls(options.preset)
    }

    public static func presetControls(_ p: [String: Double]?) -> EditControls {
        var r = EditControls()
        guard let p else { return r }
        r.exposure = p["Exposure2012"] ?? 0
        r.contrast = p["Contrast2012"] ?? 0
        r.highlights = p["Highlights2012"] ?? 0
        r.shadows = p["Shadows2012"] ?? 0
        r.whites = p["Whites2012"] ?? 0
        r.blacks = p["Blacks2012"] ?? 0
        r.temperature = p["IncrementalTemperature"] ?? 0
        r.tint = p["IncrementalTint"] ?? 0
        r.vibrance = p["Vibrance"] ?? 0
        r.saturation = p["Saturation"] ?? 0
        return r
    }

    public static func auto(_ data: inout [UInt32], strength rawStrength: Double = 1) {
        guard !data.isEmpty else { return }
        let strength = limit(rawStrength, 0, 1.5)
        let original: [UInt32]? = strength == 1 ? nil : data
        let s = stats(data)
        let lo = percentile(s.hist, s.count, 0.1), mid = percentile(s.hist, s.count, 0.5), hi = percentile(s.hist, s.count, 0.9)
        var gamma = 1.0
        if mid > 0.02 && mid < 0.38 { gamma = max(0.82, min(1, log(0.4) / log(mid))) }
        else if mid > 0.65 && mid < 0.98 { gamma = max(1, min(1.14, log(0.6) / log(mid))) }
        let contrast = hi - lo < 0.35 && hi - lo > 0.06 ? 1.04 : 1
        let sat = s.saturation / Double(s.count) < 0.3 ? 1.04 : 1
        var gains = [1.0, 1.0, 1.0]
        if Double(s.neutral) >= max(32, Double(s.count) * 0.03) {
            let avg = (s.rs + s.gs + s.bs) / 3
            let sums = [s.rs, s.gs, s.bs]
            for i in 0..<3 { gains[i] = max(0.97, min(1.03, 1 + 0.35 * (avg / sums[i] - 1))) }
        }
        var lut = [Double](repeating: 0, count: 256)
        for i in 0..<256 {
            let l = Double(i) / 255
            lut[i] = (pow(l, gamma) - l) * (gamma < 1 ? 1 - l * l : 1)
        }
        let g0 = gains[0], g1 = gains[1], g2 = gains[2]
        data.withUnsafeMutableBufferPointer { buf in
            for i in 0..<buf.count {
                let v = buf[i]
                var r = channelR(v), g = channelG(v), b = channelB(v)
                let l = (0.2126 * r + 0.7152 * g + 0.0722 * b) * 255
                let a = Int(l)
                let frac = l - Double(a)
                let delta = lut[a] * (1 - frac) + lut[min(255, a + 1)] * frac
                r = clamp01((clamp01((r + delta) * g0) - 0.5) * contrast + 0.5)
                g = clamp01((clamp01((g + delta) * g1) - 0.5) * contrast + 0.5)
                b = clamp01((clamp01((b + delta) * g2) - 0.5) * contrast + 0.5)
                let y = 0.2126 * r + 0.7152 * g + 0.0722 * b
                buf[i] = packRGB(y + (r - y) * sat, y + (g - y) * sat, y + (b - y) * sat)
            }
        }
        if let original {
            for i in 0..<data.count {
                let a = original[i], b = data[i], t = strength
                func mix(_ shift: UInt32) -> UInt32 {
                    let x = Double((a >> shift) & 255) * (1 - t) + Double((b >> shift) & 255) * t
                    return UInt32(max(0, min(255, Int(x.rounded()))))
                }
                data[i] = 0xFF00_0000 | mix(16) << 16 | mix(8) << 8 | mix(0)
            }
        }
    }

    // MARK: Preset (XMP Camera Raw)

    public static func applyPreset(_ image: inout PixelImage, _ p: [String: Double]) {
        let w = image.width, h = image.height
        guard w > 0, h > 0 else { return }
        func pv(_ k: String) -> Double { p[k] ?? 0 }
        let step = 16, sw = (w + 15) / 16, sh = (h + 15) / 16
        var gray = [UInt8](repeating: 0, count: w * h)
        var small = [Float](repeating: 0, count: sw * sh), blur = [Float](repeating: 0, count: sw * sh)
        var counts = [Int](repeating: 0, count: sw * sh)
        for y in 0..<h {
            for x in 0..<w {
                let n = y * w + x
                let g = luminance255(image.pixels[n])
                gray[n] = UInt8(Int(g))
                let b = y / step * sw + x / step
                small[b] += Float(g / 255)
                counts[b] += 1
            }
        }
        for i in 0..<small.count { small[i] /= Float(counts[i]) }
        for y in 0..<sh {
            for x in 0..<sw {
                var sum: Float = 0, count = 0
                for yy in max(0, y - 2)...min(sh - 1, y + 2) {
                    for xx in max(0, x - 2)...min(sw - 1, x + 2) { sum += small[yy * sw + xx]; count += 1 }
                }
                blur[y * sw + x] = sum / Float(count)
            }
        }
        let centers: [Double] = [0, 30, 60, 120, 180, 240, 270, 300, 360]
        var sats = [Double](repeating: 0, count: 9), lums = sats, hues = sats
        for i in 0..<9 {
            let c = colors[i % 8]
            sats[i] = pv("SaturationAdjustment" + c) / 100
            lums[i] = pv("LuminanceAdjustment" + c) / 100
            hues[i] = pv("HueAdjustment" + c) * 0.3
        }
        let temp = pv("IncrementalTemperature") / 100, exposure = pow(2, pv("Exposure2012")), tint = pv("IncrementalTint") / 100
        let contrast = 1 + pv("Contrast2012") / 100 * 0.45, clarity = pv("Clarity2012") / 100, sharp = pv("Sharpness") / 100
        let saturation = pv("Saturation") / 100, vibrance = pv("Vibrance") / 100, vg = pv("PostCropVignetteAmount") / 100
        var tone = [Double](repeating: 0, count: 256)
        for i in 0..<256 {
            let l = Double(i) / 255
            tone[i] = pv("Shadows2012") / 100 * 0.22 * pow(1 - l, 2) + pv("Highlights2012") / 100 * 0.18 * l * l
                + pv("Whites2012") / 100 * 0.12 * pow(l, 4) + pv("Blacks2012") / 100 * 0.1 * pow(1 - l, 4)
        }
        image.pixels.withUnsafeMutableBufferPointer { data in
            for y in 0..<h {
                for x in 0..<w {
                    let n = y * w + x, v = data[n], grayValue = Int(gray[n])
                    var l = Double(grayValue) / 255
                    let gx = Double(x) / 16, gy = Double(y) / 16
                    let bx = min(sw - 1, Int(gx)), by = min(sh - 1, Int(gy))
                    let fx = gx - Double(bx), fy = gy - Double(by)
                    let bx1 = min(sw - 1, bx + 1), by1 = min(sh - 1, by + 1)
                    let top = Double(blur[by * sw + bx]) * (1 - fx) + Double(blur[by * sw + bx1]) * fx
                    let bottom = Double(blur[by1 * sw + bx]) * (1 - fx) + Double(blur[by1 * sw + bx1]) * fx
                    let base = top * (1 - fy) + bottom * fy
                    let near = (Double(gray[y * w + max(0, x - 1)]) + Double(gray[y * w + min(w - 1, x + 1)])
                        + Double(gray[max(0, y - 1) * w + x]) + Double(gray[min(h - 1, y + 1) * w + x])) / 1020
                    let detail = (l - base) * clarity * 0.7 * 4 * l * (1 - l) + (l - near) * sharp * 0.55
                    let delta = tone[grayValue]
                    let r = clamp01((clamp01(channelR(v) * exposure * (1 + temp * 0.18) + delta + detail) - 0.5) * contrast + 0.5)
                    let g = clamp01((clamp01(channelG(v) * exposure * (1 - tint * 0.12) + delta + detail) - 0.5) * contrast + 0.5)
                    let b = clamp01((clamp01(channelB(v) * exposure * (1 - temp * 0.18) + delta + detail) - 0.5) * contrast + 0.5)
                    let mx = max(r, max(g, b)), mn = min(r, min(g, b)), d = mx - mn
                    var hue = 0.0, sat = 0.0
                    l = (mx + mn) / 2
                    if d > 1e-7 {
                        sat = d / (1 - abs(2 * l - 1))
                        if mx == r { hue = ((g - b) / d).truncatingRemainder(dividingBy: 6) }
                        else if mx == g { hue = (b - r) / d + 2 }
                        else { hue = (r - g) / d + 4 }
                        hue = (hue * 60 + 360).truncatingRemainder(dividingBy: 360)
                    }
                    var band = 0
                    while band < 7 && hue >= centers[band + 1] { band += 1 }
                    let mix = (hue - centers[band]) / (centers[band + 1] - centers[band])
                    sat = clamp01(sat * (1 + saturation) * (1 + vibrance * (1 - sat)) * (1 + sats[band] * (1 - mix) + sats[band + 1] * mix))
                    l = clamp01(l + (lums[band] * (1 - mix) + lums[band + 1] * mix) * 0.3 * sat)
                    hue = (hue + hues[band] * (1 - mix) + hues[band + 1] * mix + 360).truncatingRemainder(dividingBy: 360)
                    let c = (1 - abs(2 * l - 1)) * sat, hp = hue / 60
                    let z = c * (1 - abs(hp.truncatingRemainder(dividingBy: 2) - 1)), m = l - c / 2
                    var rr = 0.0, gg = 0.0, bb = 0.0
                    if hp < 1 { rr = c; gg = z } else if hp < 2 { rr = z; gg = c } else if hp < 3 { gg = c; bb = z }
                    else if hp < 4 { gg = z; bb = c } else if hp < 5 { rr = z; bb = c } else { rr = c; bb = z }
                    let radius = pow((Double(x) + 0.5) / Double(w) * 2 - 1, 2) + pow((Double(y) + 0.5) / Double(h) * 2 - 1, 2)
                    let edge = clamp01((radius - 0.3) / 1.7)
                    let vignette = 1 + vg * 0.85 * edge * edge * (3 - 2 * edge)
                    data[n] = packRGB((rr + m) * vignette, (gg + m) * vignette, (bb + m) * vignette)
                }
            }
        }
    }

    // MARK: Manual

    static func smooth(_ a: Double, _ b: Double, _ x: Double) -> Double {
        if x <= a { return 0 }
        if x >= b { return 1 }
        let t = (x - a) / (b - a)
        return t * t * (3 - 2 * t)
    }

    public static func applyManual(_ data: inout [UInt32], _ m: EditControls) {
        guard !m.isNeutral else { return }
        let exposure = pow(2, m.exposure), contrast = 1 + m.contrast / 100 * 0.72
        let high = m.highlights / 100, shad = m.shadows / 100, white = m.whites / 100, black = m.blacks / 100
        let temp = m.temperature / 100, tint = m.tint / 100, vib = m.vibrance / 100, satAdj = m.saturation / 100
        data.withUnsafeMutableBufferPointer { buf in
            for i in 0..<buf.count {
                let v = buf[i]
                // Exposição e balanço de branco continuam globais.
                var r = clamp01(channelR(v) * exposure * (1 + temp * 0.16))
                var g = clamp01(channelG(v) * exposure * (1 - tint * 0.12))
                var b = clamp01(channelB(v) * exposure * (1 - temp * 0.16))
                // Controles tonais usam máscaras de luminosidade.
                var l = 0.2126 * r + 0.7152 * g + 0.0722 * b
                let shadowMask = smooth(0.015, 0.12, l) * (1 - smooth(0.38, 0.66, l))
                let highlightMask = smooth(0.44, 0.72, l)
                let whiteMask = smooth(0.74, 0.97, l)
                let blackMask = 1 - smooth(0.025, 0.27, l)
                let tone = shad * 0.30 * shadowMask + high * 0.24 * highlightMask + white * 0.16 * whiteMask + black * 0.14 * blackMask
                var target = clamp01(l + tone), diff = target - l
                if l > 0.018 { let s = target / l; r = clamp01(r * s); g = clamp01(g * s); b = clamp01(b * s) }
                else { r = clamp01(r + diff); g = clamp01(g + diff); b = clamp01(b + diff) }
                // Contraste em torno da luminância, com menos desvio de cor.
                l = 0.2126 * r + 0.7152 * g + 0.0722 * b
                target = clamp01((l - 0.5) * contrast + 0.5); diff = target - l
                if l > 0.018 { let s = target / l; r = clamp01(r * s); g = clamp01(g * s); b = clamp01(b * s) }
                else { r = clamp01(r + diff); g = clamp01(g + diff); b = clamp01(b + diff) }
                let y = 0.2126 * r + 0.7152 * g + 0.0722 * b
                let mx = max(r, max(g, b)), mn = min(r, min(g, b))
                let current = mx <= 0 ? 0 : (mx - mn) / mx
                let amount = max(0, 1 + satAdj + vib * (1 - current))
                buf[i] = packRGB(y + (r - y) * amount, y + (g - y) * amount, y + (b - y) * amount)
            }
        }
    }
}
