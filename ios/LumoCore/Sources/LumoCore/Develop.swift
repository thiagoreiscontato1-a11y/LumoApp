import Foundation
import Dispatch

/// Motor de revelação do Lumo. Modela o pipeline do Lightroom/Camera Raw (Process Version 2012+)
/// aplicado a JPEG, na mesma ordem do Lightroom:
/// balanço de branco → exposição → dehaze → tons locais (realces, sombras, claridade, textura) e
/// brancos, com recuperação do que estourou → curva de tons (contraste, pretos) → curva paramétrica
/// e de pontos → vibração/saturação → calibração e HSL (em HSV no ProPhoto) ou mistura P&B →
/// color grading → nitidez → vinheta → granulação. Constantes ajustadas comparando com
/// exportações reais do Lightroom (foto original + .xmp + resultado do Lightroom).
/// Ajustes zerados devolvem a foto idêntica. Porta linha a linha de `Develop.java` (Android):
/// a mesma foto com o mesmo preset sai igual nas duas plataformas.
public enum Develop {
    public static let colors = ["Red", "Orange", "Yellow", "Green", "Aqua", "Blue", "Purple", "Magenta"]
    static let centers: [Double] = [0, 30, 60, 120, 180, 240, 270, 300]
    /// Curvas de pontos ficam no preset como chaves planas: `Nome.0`=x0, `Nome.1`=y0, `Nome.2`=x1… (0–255).
    public static let curves = ["ToneCurvePV2012", "ToneCurvePV2012Red", "ToneCurvePV2012Green", "ToneCurvePV2012Blue"]
    public static let legacyCurves = ["ToneCurve", "ToneCurveRed", "ToneCurveGreen", "ToneCurveBlue"]
    /// Chaves Camera Raw dos controles básicos, na ordem de `EditControls.keyPaths`.
    public static let basicXMP = ["Exposure2012", "Contrast2012", "Highlights2012", "Shadows2012", "Whites2012", "Blacks2012",
                                  "IncrementalTemperature", "IncrementalTint", "Vibrance", "Saturation"]

    /// Chaves Camera Raw suportadas e seus intervalos válidos.
    public static let ranges: [String: ClosedRange<Double>] = {
        var r: [String: ClosedRange<Double>] = [:]
        func range(_ lo: Double, _ hi: Double, _ keys: [String]) { for k in keys { r[k] = lo...hi } }
        range(-5, 5, ["Exposure2012"])
        range(-100, 100, ["IncrementalTemperature", "IncrementalTint", "Contrast2012", "Highlights2012", "Shadows2012", "Whites2012",
                          "Blacks2012", "Texture", "Clarity2012", "Dehaze", "Vibrance", "Saturation",
                          "ParametricShadows", "ParametricDarks", "ParametricLights", "ParametricHighlights", "SplitToningBalance",
                          "ColorGradeShadowLum", "ColorGradeMidtoneLum", "ColorGradeHighlightLum", "ColorGradeGlobalLum",
                          "ShadowTint", "RedHue", "RedSaturation", "GreenHue", "GreenSaturation", "BlueHue", "BlueSaturation",
                          "PostCropVignetteAmount", "PostCropVignetteRoundness"])
        range(0, 100, ["ParametricShadowSplit", "ParametricMidtoneSplit", "ParametricHighlightSplit", "SplitToningShadowSaturation",
                       "SplitToningHighlightSaturation", "ColorGradeMidtoneSat", "ColorGradeGlobalSat", "ColorGradeBlending",
                       "SharpenDetail", "SharpenEdgeMasking", "PostCropVignetteMidpoint", "PostCropVignetteFeather",
                       "PostCropVignetteHighlightContrast", "GrainAmount", "GrainSize", "GrainFrequency"])
        range(0, 360, ["SplitToningShadowHue", "SplitToningHighlightHue", "ColorGradeMidtoneHue", "ColorGradeGlobalHue"])
        range(0, 150, ["Sharpness"])
        range(0.5, 3, ["SharpenRadius"])
        range(1, 3, ["PostCropVignetteStyle"])
        range(0, 1, ["ConvertToGrayscale"])
        for c in colors { range(-100, 100, ["HueAdjustment" + c, "SaturationAdjustment" + c, "LuminanceAdjustment" + c, "GrayMixer" + c]) }
        return r
    }()

    // MARK: Ajustes

    /// Ajustes resolvidos, nas unidades do Lightroom. Ausente no preset = valor padrão do Lightroom.
    public struct Settings: Equatable {
        public var temperature = 0.0, tint = 0.0, exposure = 0.0, contrast = 0.0, highlights = 0.0, shadows = 0.0
        public var whites = 0.0, blacks = 0.0, texture = 0.0, clarity = 0.0, dehaze = 0.0, vibrance = 0.0, saturation = 0.0
        public var paramShadows = 0.0, paramDarks = 0.0, paramLights = 0.0, paramHighlights = 0.0
        public var shadowSplit = 25.0, midtoneSplit = 50.0, highlightSplit = 75.0
        /// Curvas de pontos normalizadas [x0, y0, x1, y1…] (0–1) ou nil: geral, vermelho, verde, azul.
        public var curves: [[Double]?] = [nil, nil, nil, nil]
        public var hue = [Double](repeating: 0, count: 8), sat = [Double](repeating: 0, count: 8)
        public var lum = [Double](repeating: 0, count: 8), mix = [Double](repeating: 0, count: 8)
        public var grayscale = false
        public var shadowHue = 0.0, shadowSat = 0.0, midtoneHue = 0.0, midtoneSat = 0.0, highlightHue = 0.0, highlightSat = 0.0
        public var globalHue = 0.0, globalSat = 0.0, shadowLum = 0.0, midtoneLum = 0.0, highlightLum = 0.0, globalLum = 0.0
        public var blending = 50.0, balance = 0.0
        public var shadowTint = 0.0, redHue = 0.0, redSat = 0.0, greenHue = 0.0, greenSat = 0.0, blueHue = 0.0, blueSat = 0.0
        public var sharpness = 0.0, sharpenRadius = 1.0, sharpenDetail = 25.0, sharpenMasking = 0.0
        public var vignette = 0.0, vignetteMidpoint = 50.0, vignetteRoundness = 0.0, vignetteFeather = 50.0
        public var vignetteHighlights = 0.0, vignetteStyle = 1.0
        public var grain = 0.0, grainSize = 25.0, grainRoughness = 50.0

        public init() {}

        public init(_ preset: [String: Double]?) {
            guard let p = preset else { return }
            func get(_ k: String, _ def: Double = 0) -> Double {
                guard let v = p[k], v.isFinite else { return def }
                guard let r = Develop.ranges[k] else { return v }
                return limit(v, r.lowerBound, r.upperBound)
            }
            temperature = get("IncrementalTemperature"); tint = get("IncrementalTint"); exposure = get("Exposure2012"); contrast = get("Contrast2012")
            highlights = get("Highlights2012"); shadows = get("Shadows2012"); whites = get("Whites2012"); blacks = get("Blacks2012")
            texture = get("Texture"); clarity = get("Clarity2012"); dehaze = get("Dehaze"); vibrance = get("Vibrance"); saturation = get("Saturation")
            paramShadows = get("ParametricShadows"); paramDarks = get("ParametricDarks"); paramLights = get("ParametricLights"); paramHighlights = get("ParametricHighlights")
            shadowSplit = get("ParametricShadowSplit", 25); midtoneSplit = get("ParametricMidtoneSplit", 50); highlightSplit = get("ParametricHighlightSplit", 75)
            for i in 0..<4 { curves[i] = Settings.curve(p, Develop.curves[i]) }
            for i in 0..<8 {
                let c = Develop.colors[i]
                hue[i] = get("HueAdjustment" + c); sat[i] = get("SaturationAdjustment" + c); lum[i] = get("LuminanceAdjustment" + c); mix[i] = get("GrayMixer" + c)
            }
            grayscale = get("ConvertToGrayscale") >= 0.5
            shadowHue = get("SplitToningShadowHue"); shadowSat = get("SplitToningShadowSaturation")
            highlightHue = get("SplitToningHighlightHue"); highlightSat = get("SplitToningHighlightSaturation")
            midtoneHue = get("ColorGradeMidtoneHue"); midtoneSat = get("ColorGradeMidtoneSat"); globalHue = get("ColorGradeGlobalHue"); globalSat = get("ColorGradeGlobalSat")
            shadowLum = get("ColorGradeShadowLum"); midtoneLum = get("ColorGradeMidtoneLum"); highlightLum = get("ColorGradeHighlightLum"); globalLum = get("ColorGradeGlobalLum")
            balance = get("SplitToningBalance")
            // Presets antigos (só Split Toning) abrem no Lightroom 10+ com mistura 100.
            let grade = p.keys.contains { $0.hasPrefix("ColorGrade") }
            blending = get("ColorGradeBlending", grade ? 50 : 100)
            shadowTint = get("ShadowTint"); redHue = get("RedHue"); redSat = get("RedSaturation"); greenHue = get("GreenHue")
            greenSat = get("GreenSaturation"); blueHue = get("BlueHue"); blueSat = get("BlueSaturation")
            sharpness = get("Sharpness"); sharpenRadius = get("SharpenRadius", 1); sharpenDetail = get("SharpenDetail", 25); sharpenMasking = get("SharpenEdgeMasking")
            vignette = get("PostCropVignetteAmount"); vignetteMidpoint = get("PostCropVignetteMidpoint", 50); vignetteRoundness = get("PostCropVignetteRoundness")
            vignetteFeather = get("PostCropVignetteFeather", 50); vignetteHighlights = get("PostCropVignetteHighlightContrast"); vignetteStyle = get("PostCropVignetteStyle", 1)
            grain = get("GrainAmount"); grainSize = get("GrainSize", 25); grainRoughness = get("GrainFrequency", 50)
        }

        static func curve(_ p: [String: Double], _ name: String) -> [Double]? {
            var v: [Double] = []
            while let x = p["\(name).\(v.count)"], let y = p["\(name).\(v.count + 1)"], v.count < 128 { v.append(x); v.append(y) }
            guard v.count >= 4 else { return nil }
            var identity = true
            var out = [Double](repeating: 0, count: v.count)
            for i in stride(from: 0, to: v.count, by: 2) {
                out[i] = limit(v[i] / 255, 0, 1); out[i + 1] = limit(v[i + 1] / 255, 0, 1)
                if abs(out[i] - out[i + 1]) > 1e-9 { identity = false }
            }
            return identity ? nil : out
        }

        /// Controles básicos (os sliders do editor manual).
        public var basic: EditControls {
            get {
                var c = EditControls()
                c.exposure = exposure; c.contrast = contrast; c.highlights = highlights; c.shadows = shadows; c.whites = whites
                c.blacks = blacks; c.temperature = temperature; c.tint = tint; c.vibrance = vibrance; c.saturation = saturation
                return c
            }
            set {
                exposure = limit(newValue.exposure, -5, 5); contrast = limit(newValue.contrast, -100, 100)
                highlights = limit(newValue.highlights, -100, 100); shadows = limit(newValue.shadows, -100, 100)
                whites = limit(newValue.whites, -100, 100); blacks = limit(newValue.blacks, -100, 100)
                temperature = limit(newValue.temperature, -100, 100); tint = limit(newValue.tint, -100, 100)
                vibrance = limit(newValue.vibrance, -100, 100); saturation = limit(newValue.saturation, -100, 100)
            }
        }
    }

    // MARK: Funções de cor

    static let lin: [Double] = (0..<256).map { decodeExact(Double($0) / 255) }
    static let tableSize = 8192
    static let encodeTable: [Double] = (0...tableSize).map { encodeExact(Double($0) / Double(tableSize)) }
    static let decodeTable: [Double] = (0...tableSize).map { decodeExact(Double($0) / Double(tableSize)) }
    static let exp2Table: [Double] = (0...(32 * 256)).map { pow(2, Double($0) / 256 - 24) }
    static let ln2 = log(2.0)

    static func decodeExact(_ v: Double) -> Double { v <= 0.04045 ? v / 12.92 : pow((v + 0.055) / 1.055, 2.4) }
    static func encodeExact(_ x: Double) -> Double { x <= 0.0031308 ? x * 12.92 : 1.055 * pow(x, 1 / 2.4) - 0.055 }
    /// Tabela com interpolação linear sobre [0,1].
    @inline(__always) static func table(_ t: [Double], _ x: Double) -> Double {
        if !(x > 0) { return t[0] }
        if x >= 1 { return t[t.count - 1] }
        let f = x * Double(t.count - 1)
        let i = Int(f)
        return t[i] + (t[i + 1] - t[i]) * (f - Double(i))
    }
    /// Linear → sRGB (0–1).
    @inline(__always) static func encode(_ x: Double) -> Double { table(encodeTable, x) }
    /// sRGB → linear (0–1).
    @inline(__always) static func decode(_ v: Double) -> Double { table(decodeTable, v) }
    @inline(__always) static func smooth(_ a: Double, _ b: Double, _ x: Double) -> Double {
        if x <= a { return 0 }
        if x >= b { return 1 }
        let t = (x - a) / (b - a)
        return t * t * (3 - 2 * t)
    }
    @inline(__always) static func luma(_ r: Double, _ g: Double, _ b: Double) -> Double { 0.2126 * r + 0.7152 * g + 0.0722 * b }
    @inline(__always) static func log2v(_ x: Double) -> Double { log(x) / ln2 }
    /// Limita suavemente `d` a ±limit (quase linear perto de zero).
    @inline(__always) static func soft(_ d: Double, _ limit: Double) -> Double { let q = d / limit; return d / (1 + q * q).squareRoot() }
    /// 2^x por tabela (−24…8), igual nas duas plataformas.
    @inline(__always) static func exp2v(_ x: Double) -> Double {
        let f = (limit(x, -24, 8) + 24) * 256
        let i = Int(f)
        if i >= exp2Table.count - 1 { return exp2Table[exp2Table.count - 1] }
        return exp2Table[i] + (exp2Table[i + 1] - exp2Table[i]) * (f - Double(i))
    }
    /// Matiz HSV (0–360).
    @inline(__always) static func hue(_ r: Double, _ g: Double, _ b: Double, _ mx: Double, _ mn: Double) -> Double {
        let d = mx - mn
        if d <= 0 { return 0 }
        var h = mx == r ? (g - b) / d : mx == g ? (b - r) / d + 2 : (r - g) / d + 4
        h *= 60
        return h < 0 ? h + 360 : h
    }
    /// Cor HSV de saturação e valor máximos.
    static func hueColor(_ hue: Double) -> (Double, Double, Double) {
        let h = (hue.truncatingRemainder(dividingBy: 360) + 360).truncatingRemainder(dividingBy: 360) / 60
        let x = 1 - abs(h.truncatingRemainder(dividingBy: 2) - 1)
        switch Int(h) {
        case 0: return (1, x, 0)
        case 1: return (x, 1, 0)
        case 2: return (0, 1, x)
        case 3: return (0, x, 1)
        case 4: return (x, 0, 1)
        default: return (1, 0, x)
        }
    }
    /// Direção de cor neutra em luminância para tingir (color grading, tom de sombra).
    static func tint(_ hue: Double, _ amount: Double) -> (Double, Double, Double) {
        let c = hueColor(hue)
        let y = luma(c.0, c.1, c.2)
        return ((c.0 - y) * amount, (c.1 - y) * amount, (c.2 - y) * amount)
    }
    /// Encaixa a cor no intervalo 0–1 reduzindo a saturação e mantendo a luminância `y`,
    /// como o Lightroom faz ao estourar canais.
    @inline(__always) static func fit(_ r: inout Double, _ g: inout Double, _ b: inout Double, _ y: Double) {
        let mx = max(r, max(g, b)), mn = min(r, min(g, b))
        if mx <= 1 && mn >= 0 { return }
        if y >= 1 { r = 1; g = 1; b = 1; return }
        if y <= 0 { r = 0; g = 0; b = 0; return }
        var t = 1.0
        if mx > 1 { t = min(t, (1 - y) / (mx - y)) }
        if mn < 0 { t = min(t, y / (y - mn)) }
        r = clamp01(y + (r - y) * t); g = clamp01(y + (g - y) * t); b = clamp01(y + (b - y) * t)
    }
    /// Faixa HSL de uma matiz e peso da faixa seguinte (cada cor tem um platô em volta do centro).
    @inline(__always) static func band(_ h: Double) -> Int {
        for k in 0..<7 where h < centers[k + 1] { return k }
        return 7
    }
    @inline(__always) static func bandMix(_ h: Double, _ k: Int) -> Double {
        let c0 = centers[k], c1 = k == 7 ? 360 : centers[k + 1]
        return smooth(0.2, 0.8, (h - c0) / (c1 - c0))
    }

    /// sRGB linear ↔ ProPhoto linear (D50, linhas normalizadas para o branco continuar branco).
    /// O Lightroom mede as faixas de HSL e a calibração nesse espaço: nele a pele cai em Laranja
    /// (não em Vermelho) e um amarelo-ocre fica perto do centro do Amarelo.
    static let toPP: [Double] = [0.5293, 0.3301, 0.1406, 0.098390, 0.873413, 0.028197, 0.0169, 0.1177, 0.8654]
    static let fromPP: [Double] = invert(toPP)
    static func invert(_ m: [Double]) -> [Double] {
        let a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5], g = m[6], h = m[7], i = m[8]
        let det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        return [(e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det, (f * g - d * i) / det, (a * i - c * g) / det,
                (c * d - a * f) / det, (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det]
    }
    /// Gama 1.8 do ProPhoto, por tabela.
    static let ppEncodeTable: [Double] = (0...tableSize).map { pow(Double($0) / Double(tableSize), 1 / 1.8) }
    static let ppDecodeTable: [Double] = (0...tableSize).map { pow(Double($0) / Double(tableSize), 1.8) }
    @inline(__always) static func ppDecode(_ v: Double) -> Double { v > 1 ? pow(v, 1.8) : table(ppDecodeTable, v) }
    /// HSV → RGB.
    @inline(__always) static func hsv(_ h: Double, _ s: Double, _ v: Double) -> (Double, Double, Double) {
        let k = h / 60, ch = v * s, x = ch * (1 - abs(k.truncatingRemainder(dividingBy: 2) - 1)), m = v - ch, i = min(5, Int(k))
        let r = i == 0 || i == 5 ? ch : i == 1 || i == 4 ? x : 0
        let g = i == 1 || i == 2 ? ch : i == 0 || i == 3 ? x : 0
        let b = i == 3 || i == 4 ? ch : i == 2 || i == 5 ? x : 0
        return (r + m, g + m, b + m)
    }

    /// Curva de tons global (contraste e pretos) sobre a luminância em sRGB. Os brancos atuam antes,
    /// em luz linear, junto da recuperação de realces.
    static func toneTable(_ s: Settings) -> [Double]? {
        if s.contrast == 0 && s.blacks == 0 { return nil }
        let c = s.contrast / 100, k = s.blacks / 100, g = c >= 0 ? 1 + 0.8 * c : 1 / (1 - 0.8 * c), p = 0.5
        var t = [Double](repeating: 0, count: 1025)
        for i in 0...1024 {
            var l = Double(i) / 1024
            if c != 0 { l = l < p ? p * pow(l / p, g) : 1 - (1 - p) * pow((1 - l) / (1 - p), g) }
            if k != 0 { var u = 1 - l; u = u * (1 - (k > 0 ? 0.18 : 0.30) * k * u * u); l = 1 - u }
            t[i] = clamp01(l)
        }
        return t
    }

    static func hump(_ x: Double, _ a: Double, _ c: Double, _ b: Double) -> Double {
        if x <= a || x >= b { return 0 }
        return x < c ? smooth(a, c, x) : 1 - smooth(c, b, x)
    }

    /// Tabelas das curvas (paramétrica + pontos) por canal; nil quando todas são neutras.
    static func curveTables(_ s: Settings) -> [[Double]]? {
        let param = s.paramShadows != 0 || s.paramDarks != 0 || s.paramLights != 0 || s.paramHighlights != 0
        if !param && s.curves.allSatisfy({ $0 == nil }) { return nil }
        let s1 = s.shadowSplit / 100, s2 = max(s1, s.midtoneSplit / 100), s3 = max(s2, s.highlightSplit / 100)
        let c0 = s1 / 2, c1 = (s1 + s2) / 2, c2 = (s2 + s3) / 2, c3 = (s3 + 1) / 2
        var master = [Double](repeating: 0, count: 1025)
        var prev = 0.0
        for i in 0...1024 {
            let x = Double(i) / 1024
            var y = x
            if param {
                y = x + 0.12 * (s.paramShadows / 100 * hump(x, 0, c0, c1) + s.paramDarks / 100 * hump(x, c0, c1, c2)
                    + s.paramLights / 100 * hump(x, c1, c2, c3) + s.paramHighlights / 100 * hump(x, c2, c3, 1))
            }
            y = clamp01(y)
            if y < prev { y = prev }
            prev = y
            if let curve = s.curves[0] { y = spline(curve, y) }
            master[i] = y
        }
        return (0..<3).map { ch in
            guard let curve = s.curves[ch + 1] else { return master }
            return master.map { spline(curve, $0) }
        }
    }

    /// Spline cúbica natural pelos pontos (como as curvas do Lightroom/Photoshop), constante fora deles.
    static func spline(_ pts: [Double], _ x: Double) -> Double {
        let n = pts.count / 2
        var xs = [Double](repeating: 0, count: n), ys = xs
        var m = 0
        for i in 0..<n {
            let px = pts[2 * i], py = pts[2 * i + 1]
            if m > 0 && px <= xs[m - 1] { continue }
            xs[m] = px; ys[m] = py; m += 1
        }
        if m == 0 { return x }
        if m == 1 || x <= xs[0] { return ys[0] }
        if x >= xs[m - 1] { return ys[m - 1] }
        var d2 = [Double](repeating: 0, count: m), u = d2
        if m > 2 {
            for i in 1..<(m - 1) {
                let sig = (xs[i] - xs[i - 1]) / (xs[i + 1] - xs[i - 1]), p = sig * d2[i - 1] + 2
                d2[i] = (sig - 1) / p
                let dd = (ys[i + 1] - ys[i]) / (xs[i + 1] - xs[i]) - (ys[i] - ys[i - 1]) / (xs[i] - xs[i - 1])
                u[i] = (6 * dd / (xs[i + 1] - xs[i - 1]) - sig * u[i - 1]) / p
            }
        }
        d2[m - 1] = 0
        for i in stride(from: m - 2, through: 0, by: -1) { d2[i] = d2[i] * d2[i + 1] + u[i] }
        var k = 0
        while k < m - 2 && x > xs[k + 1] { k += 1 }
        let h = xs[k + 1] - xs[k], a = (xs[k + 1] - x) / h, b = (x - xs[k]) / h
        return clamp01(a * ys[k] + b * ys[k + 1] + ((a * a * a - a) * d2[k] + (b * b * b - b) * d2[k + 1]) * h * h / 6)
    }

    static func blur(_ src: [Float], _ w: Int, _ h: Int, _ sigma: Double) -> [Float] {
        if sigma < 0.3 { return src }
        let r = Int((sigma * 3).rounded(.up))
        var k = [Double](repeating: 0, count: 2 * r + 1)
        var sum = 0.0
        for i in -r...r { k[i + r] = exp(-Double(i * i) / (2 * sigma * sigma)); sum += k[i + r] }
        for i in 0..<k.count { k[i] /= sum }
        var tmp = [Float](repeating: 0, count: w * h), out = tmp
        src.withUnsafeBufferPointer { s in
            tmp.withUnsafeMutableBufferPointer { t in
                for y in 0..<h {
                    let row = y * w
                    for x in 0..<w {
                        var a = 0.0
                        for i in -r...r { let xx = min(w - 1, max(0, x + i)); a += k[i + r] * Double(s[row + xx]) }
                        t[row + x] = Float(a)
                    }
                }
            }
        }
        tmp.withUnsafeBufferPointer { t in
            out.withUnsafeMutableBufferPointer { o in
                for y in 0..<h {
                    for x in 0..<w {
                        var a = 0.0
                        for i in -r...r { let yy = min(h - 1, max(0, y + i)); a += k[i + r] * Double(t[yy * w + x]) }
                        o[y * w + x] = Float(a)
                    }
                }
            }
        }
        return out
    }

    /// Ruído de valor determinístico (−1…1) para a granulação.
    @inline(__always) static func noise(_ fx: Double, _ fy: Double, _ seed: UInt32) -> Double {
        let ix = fx.rounded(.down), iy = fy.rounded(.down)
        var tx = fx - ix, ty = fy - iy
        let x = Int32(ix), y = Int32(iy)
        tx = tx * tx * (3 - 2 * tx); ty = ty * ty * (3 - 2 * ty)
        let a = hash(x, y, seed), b = hash(x &+ 1, y, seed), c = hash(x, y &+ 1, seed), d = hash(x &+ 1, y &+ 1, seed)
        return (a + (b - a) * tx) * (1 - ty) + (c + (d - c) * tx) * ty
    }
    @inline(__always) static func hash(_ x: Int32, _ y: Int32, _ seed: UInt32) -> Double {
        var h = (UInt32(bitPattern: x) &* 0x27d4_eb2d) ^ (UInt32(bitPattern: y) &* 0x1656_67b1) ^ seed
        h ^= h >> 15; h = h &* 0x85eb_ca6b; h ^= h >> 13; h = h &* 0xc2b2_ae35; h ^= h >> 16
        return Double(h & 0xff_ffff) / 16_777_215 * 2 - 1
    }

    // MARK: Imagem de origem

    /// Campo suave em grade reduzida, amostrado com interpolação bilinear. Com `scale` é um
    /// filtro guiado (valor = A·I + B): segue as bordas fortes da própria foto, então
    /// realces/sombras e claridade não criam halos, como no Lightroom.
    final class Field {
        let offset: [Float], scale: [Float]?, gw: Int, gh: Int, cell: Int
        init(offset: [Float], scale: [Float]?, gw: Int, gh: Int, cell: Int) {
            self.offset = offset; self.scale = scale; self.gw = gw; self.gh = gh; self.cell = cell
        }
        @inline(__always) func at(_ x: Int, _ y: Int, _ guide: Double) -> Double {
            if cell == 1 {
                let i = y * gw + x
                guard let scale else { return Double(offset[i]) }
                return Double(scale[i]) * guide + Double(offset[i])
            }
            let c = Double(cell)
            let gx = limit((Double(x) + 0.5) / c - 0.5, 0, Double(gw - 1)), gy = limit((Double(y) + 0.5) / c - 0.5, 0, Double(gh - 1))
            let x0 = Int(gx), y0 = Int(gy), x1 = min(gw - 1, x0 + 1), y1 = min(gh - 1, y0 + 1)
            let fx = gx - Double(x0), fy = gy - Double(y0)
            let o = bilinear(offset, x0, y0, x1, y1, fx, fy)
            guard let scale else { return o }
            return bilinear(scale, x0, y0, x1, y1, fx, fy) * guide + o
        }
        @inline(__always) func bilinear(_ d: [Float], _ x0: Int, _ y0: Int, _ x1: Int, _ y1: Int, _ fx: Double, _ fy: Double) -> Double {
            (Double(d[y0 * gw + x0]) * (1 - fx) + Double(d[y0 * gw + x1]) * fx) * (1 - fy)
                + (Double(d[y1 * gw + x0]) * (1 - fx) + Double(d[y1 * gw + x1]) * fx) * fy
        }
    }

    /// Foto decodificada com os campos que os ajustes locais precisam, calculados uma vez e
    /// reaproveitados a cada renderização (prévia ao vivo do editor). Pode ser usada de várias threads.
    public final class Source: @unchecked Sendable {
        static let tone = 0, clarity = 1, texture = 2, haze = 3
        public let image: PixelImage
        private let lock = NSLock()
        private var logYCache: [Float]?
        private var fields: [Field?] = [nil, nil, nil, nil]
        private var autoCache: EditControls?

        public init(_ image: PixelImage) { self.image = image }

        public var width: Int { image.width }
        public var height: Int { image.height }

        /// Sugestão do automático para esta foto (sem intensidade aplicada).
        public var autoControls: EditControls {
            lock.lock(); defer { lock.unlock() }
            if let autoCache { return autoCache }
            let a = PhotoEditor.autoControls(image.pixels)
            autoCache = a
            return a
        }

        /// log2 da luminância linear de cada pixel.
        func logY() -> [Float] {
            lock.lock(); defer { lock.unlock() }
            return logYLocked()
        }

        private func logYLocked() -> [Float] {
            if let logYCache { return logYCache }
            let px = image.pixels
            var out = [Float](repeating: 0, count: px.count)
            for i in 0..<px.count {
                let v = px[i]
                out[i] = Float(log2v(max(1 / 4096.0, luma(lin[Int((v >> 16) & 255)], lin[Int((v >> 8) & 255)], lin[Int(v & 255)]))))
            }
            logYCache = out
            return out
        }

        /// Raios relativos ao maior lado, para a prévia e a exportação ficarem iguais.
        func sigma(_ kind: Int) -> Double {
            let edge = Double(max(width, height))
            return kind == Source.tone ? edge / 40 : kind == Source.clarity ? edge / 120 : kind == Source.texture ? max(0.8, edge / 600) : edge / 50
        }

        func field(_ kind: Int) -> Field {
            lock.lock(); defer { lock.unlock() }
            if let f = fields[kind] { return f }
            let w = width, h = height
            let radius = self.sigma(kind), eps = kind == Source.tone ? 0.25 : kind == Source.clarity ? 0.12 : 0
            let cell = max(1, Int(radius / 3)), gw = (w + cell - 1) / cell, gh = (h + cell - 1) / cell, cells = gw * gh
            var mean = [Float](repeating: 0, count: cells), square = mean
            var count = [Int](repeating: 0, count: cells)
            let log: [Float]? = kind == Source.haze ? nil : logYLocked()
            let px = image.pixels
            for y in 0..<h {
                for x in 0..<w {
                    let i = y * w + x, g = (y / cell) * gw + x / cell
                    let v: Double
                    if let log { v = Double(log[i]) } else {
                        let p = px[i]
                        v = min(lin[Int((p >> 16) & 255)], min(lin[Int((p >> 8) & 255)], lin[Int(p & 255)]))
                    }
                    mean[g] = Float(Double(mean[g]) + v); square[g] = Float(Double(square[g]) + v * v); count[g] += 1
                }
            }
            for i in 0..<cells { mean[i] /= Float(count[i]); square[i] /= Float(count[i]) }
            let s = radius / Double(cell)
            mean = blur(mean, gw, gh, s)
            let f: Field
            if eps <= 0 {
                f = Field(offset: mean, scale: nil, gw: gw, gh: gh, cell: cell)
            } else {
                // Filtro guiado: A = var/(var+eps), B = média·(1−A), ambos suavizados.
                square = blur(square, gw, gh, s)
                var a = [Float](repeating: 0, count: cells), b = a
                for i in 0..<cells {
                    let m = Double(mean[i]), variance = max(0, Double(square[i]) - m * m), k = variance / (variance + eps)
                    a[i] = Float(k); b[i] = Float(m - k * m)
                }
                f = Field(offset: blur(b, gw, gh, s), scale: blur(a, gw, gh, s), gw: gw, gh: gh, cell: cell)
            }
            fields[kind] = f
            return f
        }

        /// Renderiza a foto com os ajustes. As linhas são divididas entre os núcleos.
        public func render(_ s: Settings) -> PixelImage {
            let plan = Plan(self, s)
            if plan.identity { return image }
            let w = width, h = height
            var out = [UInt32](repeating: 0xFF00_0000, count: w * h)
            out.withUnsafeMutableBufferPointer { buf in
                let base = buf.baseAddress!
                parallel(h) { y0, y1 in plan.rows(y0, y1, base) }
            }
            if plan.sharpen {
                var l = [Float](repeating: 0, count: w * h)
                for i in 0..<out.count {
                    let v = out[i]
                    l[i] = Float(luma(Double((v >> 16) & 255), Double((v >> 8) & 255), Double(v & 255)) / 255)
                }
                let bl = blur(l, w, h, max(0.5, s.sharpenRadius * 0.8))
                out.withUnsafeMutableBufferPointer { buf in
                    let base = buf.baseAddress!
                    parallel(h) { y0, y1 in plan.sharpenRows(y0, y1, base, l, bl) }
                }
            }
            return PixelImage(width: w, height: h, pixels: out)
        }
    }

    /// Executa faixas de linhas em paralelo e espera todas.
    static func parallel(_ h: Int, _ task: (Int, Int) -> Void) {
        let threads = max(1, min(8, ProcessInfo.processInfo.activeProcessorCount))
        let chunks = min(h, threads * 4)
        if chunks <= 1 || threads == 1 { task(0, h); return }
        DispatchQueue.concurrentPerform(iterations: chunks) { k in task(h * k / chunks, h * (k + 1) / chunks) }
    }

    /// Constantes de uma renderização e o pipeline por pixel.
    final class Plan {
        let src: Source, s: Settings, w: Int
        let tone: [Double]?, curves: [[Double]]?, calHue: [Double], calSat: [Double]
        let tS: (Double, Double, Double), tM: (Double, Double, Double), tH: (Double, Double, Double), tG: (Double, Double, Double), tShadow: (Double, Double, Double)
        let gr: Double, gg: Double, gb: Double, expo: Double, knee = 0.6, top: Double, rollPower: Double
        let dehaze: Double, sh: Double, hl: Double, whites: Double, clarity: Double, texture: Double, sat: Double, vib: Double
        let mid: Double, width: Double, amount: Double, limitValue: Double, mask: Double
        let local: Bool, tonal: Bool, hsl: Bool, calib: Bool, mixing: Bool, grade: Bool, color: Bool, sharpen: Bool, identity: Bool
        let logY: [Float]?, toneF: Field?, clarityF: Field?, textureF: Field?, hazeF: Field?
        let fx: Effects
        let pixels: [UInt32]

        init(_ src: Source, _ s: Settings) {
            self.src = src; self.s = s; w = src.width; pixels = src.image.pixels
            // Calibração: gira e satura as cores em volta de cada primário (faixas largas de 120°).
            calHue = [s.redHue * 0.4, s.greenHue * 0.4, s.blueHue * 0.4]
            calSat = [s.redSat / 100, s.greenSat / 100, s.blueSat / 100]
            calib = s.redHue != 0 || s.redSat != 0 || s.greenHue != 0 || s.greenSat != 0 || s.blueHue != 0 || s.blueSat != 0
            // Temperatura/matiz incrementais (JPEG): ganhos por canal que preservam o brilho.
            let t = s.temperature / 100, ti = s.tint / 100, e = pow(2, s.exposure)
            var r = e, g = e, b = e
            if t != 0 || ti != 0 {
                let r0 = pow(2, 0.5 * t + 0.12 * ti), g0 = pow(2, 0.04 * t - 0.42 * ti), b0 = pow(2, -0.76 * t + 0.12 * ti), norm = luma(r0, g0, b0)
                r = r0 * e / norm; g = g0 * e / norm; b = b0 * e / norm
            }
            dehaze = s.dehaze / 100; sh = s.shadows / 100; hl = s.highlights / 100; whites = s.whites / 100
            clarity = s.clarity / 100; texture = s.texture / 100
            // Como no Lightroom, o que a exposição estourou volta comprimido para dentro do branco
            // (brancos negativos baixam esse teto), sem virar cinza.
            expo = e; gr = r; gg = g; gb = b; top = e * exp2v(whites < 0 ? whites * 0.62 : 0); rollPower = top > 1 ? (top - 0.6) / (1 - 0.6) : 1
            local = sh != 0 || hl != 0 || clarity != 0 || texture != 0
            tone = toneTable(s); curves = curveTables(s)
            tonal = rollPower > 1 || local || whites != 0 || tone != nil
            sat = s.saturation / 100; vib = s.vibrance / 100
            var anyHsl = false, anyMix = false
            for i in 0..<8 {
                if s.hue[i] != 0 || s.sat[i] != 0 || s.lum[i] != 0 { anyHsl = true }
                if s.mix[i] != 0 { anyMix = true }
            }
            hsl = anyHsl; mixing = anyMix
            grade = s.shadowSat != 0 || s.midtoneSat != 0 || s.highlightSat != 0 || s.globalSat != 0 || s.shadowLum != 0
                || s.midtoneLum != 0 || s.highlightLum != 0 || s.globalLum != 0 || s.shadowTint != 0
            tS = tint(s.shadowHue, s.shadowSat / 100 * 0.3); tM = tint(s.midtoneHue, s.midtoneSat / 100 * 0.3)
            tH = tint(s.highlightHue, s.highlightSat / 100 * 0.3); tG = tint(s.globalHue, s.globalSat / 100 * 0.3)
            mid = 0.5 - 0.3 * s.balance / 100; width = 0.1 + 0.6 * s.blending / 100
            tShadow = tint(s.shadowTint > 0 ? 300 : 120, abs(s.shadowTint) / 100 * 0.1)
            color = sat != 0 || vib != 0 || hsl || calib || s.grayscale || grade
            sharpen = s.sharpness > 0; amount = s.sharpness / 100 * 1.2; limitValue = 0.03 + 0.3 * s.sharpenDetail / 100; mask = s.sharpenMasking / 100 * 0.05
            fx = Effects(s, src.width, src.height)
            identity = !calib && gr == 1 && gg == 1 && gb == 1 && dehaze == 0 && !tonal && curves == nil && !color && !sharpen && !fx.active
            logY = local && !identity ? src.logY() : nil
            toneF = (sh != 0 || hl != 0) && !identity ? src.field(Source.tone) : nil
            clarityF = clarity != 0 && !identity ? src.field(Source.clarity) : nil
            textureF = texture != 0 && !identity ? src.field(Source.texture) : nil
            hazeF = dehaze > 0 && !identity ? src.field(Source.haze) : nil
        }

        func rows(_ y0: Int, _ y1: Int, _ out: UnsafeMutablePointer<UInt32>) {
            pixels.withUnsafeBufferPointer { px in
                for y in y0..<y1 {
                    for x in 0..<w {
                        let i = y * w + x
                        out[i] = pixel(px[i], i, x, y)
                    }
                }
            }
        }

        @inline(__always) func pixel(_ v: UInt32, _ i: Int, _ x: Int, _ y: Int) -> UInt32 {
            var r = lin[Int((v >> 16) & 255)], g = lin[Int((v >> 8) & 255)], b = lin[Int(v & 255)]
            // Balanço de branco e exposição em luz linear.
            r *= gr; g *= gg; b *= gb
            // Dehaze: modelo de névoa atmosférica com canal escuro borrado.
            if dehaze > 0, let hazeF {
                let dark = min(min(r, min(g, b)), hazeF.at(x, y, 0) * expo), tr = max(0.15, 1 - 0.85 * dehaze * min(1, dark)), a = 1 - tr
                r = (r - a) / tr; g = (g - a) / tr; b = (b - a) / tr
            } else if dehaze < 0 {
                let k = -dehaze * 0.45
                r += (0.85 - r) * k; g += (0.85 - g) * k; b += (0.85 - b) * k
            }
            if tonal {
                let lum = luma(r, g, b), lp = encode(min(1, lum))
                var yt = lum, ev = 0.0
                if local, let logY {
                    let ly = log2v(max(yt, 1e-6)), l0 = Double(logY[i])
                    if let toneF {
                        let base = ly - soft(l0 - toneF.at(x, y, l0), 1), bl = encode(min(1, exp2v(base)))
                        if sh != 0 { ev += sh * (sh > 0 ? 1.35 : 1) * (1 - smooth(0.13, 0.45, bl)) * (0.55 + 0.45 * smooth(0, 0.15, bl)) }
                        // Realces poupam o que já está quase branco: a recuperação fica com a compressão abaixo.
                        if hl != 0 { ev += hl * (hl < 0 ? 0.75 : 0.9) * smooth(0.4, 0.9, bl) * (1 - smooth(0.85, 1, lp)) }
                    }
                    if let clarityF { ev += clarity * 0.65 * soft(l0 - clarityF.at(x, y, l0), 1.5) * 4 * lp * (1 - lp) }
                    if let textureF { ev += texture * 0.6 * soft(l0 - textureF.at(x, y, l0), 1) * (1 - smooth(0.85, 1, lp)) }
                }
                if whites != 0 { ev += whites * 0.62 * smooth(0.3, 1, lp) }
                if ev != 0 { yt *= exp2v(ev) }
                // O que passou do branco é comprimido suavemente em vez de estourar.
                if rollPower > 1 && yt > knee {
                    let q = (yt - knee) / (top - knee)
                    yt = q >= 1 ? 1 : knee + (1 - knee) * (1 - pow(1 - q, rollPower))
                }
                if let tone { yt = decode(table(tone, encode(min(1, yt)))) }
                if lum > 1e-9 { let k = yt / lum; r *= k; g *= k; b *= k } else { r = yt; g = yt; b = yt }
            }
            let y0 = luma(r, g, b)
            fit(&r, &g, &b, y0)
            var R = encode(r), G = encode(g), B = encode(b)
            if let curves { R = table(curves[0], R); G = table(curves[1], G); B = table(curves[2], B) }
            if color {
                let yg = luma(R, G, B)
                if sat != 0 || vib != 0 {
                    let mx = max(R, max(G, B)), mn = min(R, min(G, B)), sv = mx <= 0 ? 0 : (mx - mn) / mx
                    var f = 1 + sat
                    if vib > 0 {
                        let hh = hue(R, G, B, mx, mn), z = (hh - 25) / 20, skin = exp(-z * z)
                        f *= 1 + vib * 0.6 * (1 - sv).squareRoot() * (1 - 0.6 * skin)
                    } else if vib < 0 {
                        f *= 1 + vib * 0.8 * (0.3 + 0.7 * sv)
                    }
                    f = max(0, f)
                    R = yg + (R - yg) * f; G = yg + (G - yg) * f; B = yg + (B - yg) * f
                    fit(&R, &G, &B, yg)
                }
                let mx = max(R, max(G, B)), mn = min(R, min(G, B)), chroma = mx - mn
                if s.grayscale {
                    var m = 0.0
                    if mixing && chroma > 1e-6 {
                        let hh = hue(R, G, B, mx, mn), k = band(hh), k2 = (k + 1) % 8, q = bandMix(hh, k)
                        m = (s.mix[k] * (1 - q) + s.mix[k2] * q) / 100 * smooth(0, 0.35, chroma)
                    }
                    let v = clamp01(yg * exp2v(m * 0.8))
                    R = v; G = v; B = v
                } else if (hsl || calib) && chroma > 1e-6 {
                    // Calibração e HSL em HSV no ProPhoto (gama 1.8), como o Lightroom: mudar a matiz não
                    // preserva a luminância (um amarelo puxado para laranja escurece).
                    let lr = decode(R), lg = decode(G), lb = decode(B), m = toPP
                    let pr = table(ppEncodeTable, m[0] * lr + m[1] * lg + m[2] * lb)
                    let pg = table(ppEncodeTable, m[3] * lr + m[4] * lg + m[5] * lb)
                    let pb = table(ppEncodeTable, m[6] * lr + m[7] * lg + m[8] * lb)
                    let pmax = max(pr, max(pg, pb)), pmin = min(pr, min(pg, pb))
                    if pmax - pmin > 1e-6 {
                        var hh = hue(pr, pg, pb, pmax, pmin), sv = (pmax - pmin) / pmax, val = pmax
                        if calib {
                            var ch = 0.0, cs = 0.0
                            for j in 0..<3 {
                                let d = abs((hh - 120 * Double(j) + 540).truncatingRemainder(dividingBy: 360) - 180), wgt = smooth(0, 1, 1 - d / 120)
                                ch += wgt * calHue[j]; cs += wgt * calSat[j]
                            }
                            hh = ((hh + ch).truncatingRemainder(dividingBy: 360) + 360).truncatingRemainder(dividingBy: 360)
                            sv = clamp01(sv * max(0, 1 + cs))
                        }
                        if hsl {
                            let k = band(hh), k2 = (k + 1) % 8, q = bandMix(hh, k)
                            let dh = (s.hue[k] * (1 - q) + s.hue[k2] * q) * 0.43, dl = (s.lum[k] * (1 - q) + s.lum[k2] * q) / 100
                            var ds = (s.sat[k] * (1 - q) + s.sat[k2] * q) / 100
                            // Saturação −100 ainda zera a cor; valores pequenos atuam com metade da força.
                            ds *= 0.5 + 0.5 * abs(ds)
                            val *= exp2v(dl * 0.3 * smooth(0, 0.35, sv))
                            hh = ((hh + dh).truncatingRemainder(dividingBy: 360) + 360).truncatingRemainder(dividingBy: 360)
                            sv = clamp01(sv * max(0, 1 + ds))
                        }
                        let c = hsv(hh, sv, val), a = ppDecode(c.0), b1 = ppDecode(c.1), b2 = ppDecode(c.2), n = fromPP
                        var lr2 = n[0] * a + n[1] * b1 + n[2] * b2, lg2 = n[3] * a + n[4] * b1 + n[5] * b2, lb2 = n[6] * a + n[7] * b1 + n[8] * b2
                        fit(&lr2, &lg2, &lb2, clamp01(luma(lr2, lg2, lb2)))
                        R = encode(lr2); G = encode(lg2); B = encode(lb2)
                    }
                }
                if grade {
                    let l = luma(R, G, B), ws = 1 - smooth(mid - width, mid, l), wh = smooth(mid, mid + width, l), wm = 1 - ws - wh
                    let edge = min(1, min(l, 1 - l) * 12)
                    let dl = (s.shadowLum * ws + s.midtoneLum * wm + s.highlightLum * wh + s.globalLum) / 100 * 0.2, shadowW = (1 - l) * (1 - l)
                    R = R + (tS.0 * ws + tM.0 * wm + tH.0 * wh + tG.0) * edge + tShadow.0 * shadowW + dl
                    G = G + (tS.1 * ws + tM.1 * wm + tH.1 * wh + tG.1) * edge + tShadow.1 * shadowW + dl
                    B = B + (tS.2 * ws + tM.2 * wm + tH.2 * wh + tG.2) * edge + tShadow.2 * shadowW + dl
                    fit(&R, &G, &B, clamp01(l + dl))
                }
            }
            return sharpen ? packRGB(R, G, B) : fx.apply(R, G, B, x, y)
        }

        /// Nitidez (máscara de nitidez na luminância), depois vinheta e granulação.
        func sharpenRows(_ y0: Int, _ y1: Int, _ out: UnsafeMutablePointer<UInt32>, _ l: [Float], _ bl: [Float]) {
            for y in y0..<y1 {
                for x in 0..<w {
                    let i = y * w + x, v = out[i]
                    let d = Double(l[i]) - Double(bl[i]), weight = mask > 0 ? smooth(mask * 0.5, mask * 1.5, abs(d)) : 1
                    let add = amount * limit(d, -limitValue, limitValue) * weight
                    out[i] = fx.apply(Double((v >> 16) & 255) / 255 + add, Double((v >> 8) & 255) / 255 + add, Double(v & 255) / 255 + add, x, y)
                }
            }
        }
    }

    /// Vinheta pós-corte e granulação.
    struct Effects {
        let active: Bool, vignette: Bool, grain: Bool
        let amount: Double, inner: Double, width: Double, roundness: Double, power: Double, highlights: Double
        let cx: Double, cy: Double, hw: Double, hh: Double, hc: Double, grainAmount: Double, cell: Double, rough: Double
        let style: Int

        init(_ s: Settings, _ w: Int, _ h: Int) {
            amount = s.vignette / 100; vignette = amount != 0; style = Int(s.vignetteStyle.rounded())
            inner = 0.2 + s.vignetteMidpoint / 100 * 0.95; width = 0.08 + s.vignetteFeather / 100 * 1.1
            roundness = s.vignetteRoundness / 100; power = roundness < 0 ? 2 - roundness * 6 : 2; highlights = s.vignetteHighlights / 100
            cx = Double(w) / 2; cy = Double(h) / 2; hw = Double(w) / 2; hh = Double(h) / 2; hc = (Double(w) * Double(h)).squareRoot() / 2
            grainAmount = s.grain / 100 * 0.1; grain = grainAmount > 0
            cell = max(1, (1 + s.grainSize / 100 * 3) * Double(max(w, h)) / 2560); rough = s.grainRoughness / 100
            active = vignette || grain
        }

        @inline(__always) func apply(_ r0: Double, _ g0: Double, _ b0: Double, _ x: Int, _ y: Int) -> UInt32 {
            var r = r0, g = g0, b = b0
            if vignette {
                let px = Double(x) + 0.5, py = Double(y) + 0.5
                var u = (px - cx) / hw, v = (py - cy) / hh
                if roundness > 0 { u += ((px - cx) / hc - u) * roundness; v += ((py - cy) / hc - v) * roundness }
                let d = power == 2 ? (u * u + v * v).squareRoot() : pow(pow(abs(u), power) + pow(abs(v), power), 1 / power)
                let m = smooth(inner, inner + width, d)
                if m > 0 {
                    if amount < 0 {
                        if style == 3 {
                            let f = 1 + amount * m * 0.9
                            r *= f; g *= f; b *= f
                        } else {
                            let l = clamp01(luma(r, g, b))
                            var f = exp2v(amount * m * 0.75)
                            f = 1 - (1 - f) * (1 - highlights * smooth(0.5, 1, l))
                            r *= f; g *= f; b *= f
                        }
                    } else {
                        let k = amount * m * (style == 3 ? 0.9 : 0.75)
                        r += (1 - r) * k; g += (1 - g) * k; b += (1 - b) * k
                    }
                }
            }
            if grain {
                let l = clamp01(luma(r, g, b))
                let nz = noise(Double(x) / cell, Double(y) / cell, 0x5eed) * (1 - 0.5 * rough) + noise(Double(x) / (cell * 0.5), Double(y) / (cell * 0.5), 0x1ced) * 0.5 * rough
                let a = grainAmount * nz * (0.35 + 0.65 * 4 * l * (1 - l))
                r += a; g += a; b += a
            }
            return packRGB(r, g, b)
        }
    }
}
