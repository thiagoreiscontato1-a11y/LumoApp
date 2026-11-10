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
    /// Demais ajustes do editor (HSL, calibração, efeitos, detalhe) em chaves Camera Raw, com o
    /// valor final de cada uma: substituem os do preset.
    public var extra: [String: Double]?
    /// Perfil criativo do preset (tabelas guardadas em `CreativeProfile.directory`).
    public var look: CreativeLook?
    /// Recorte e endireitar da foto (aplicados antes da revelação).
    public var crop: Crop?
    /// Maior lado do JPEG editado; 0 mantém o original.
    public var edge: Int = 0
    public var curation: Bool = true
    /// 0 = tolerante, 1 = equilibrada, 2 = rigorosa.
    public var curationSensitivity: Int = 1

    public init() {}
}

/// Edição de fotos do Lumo: resolve automático + preset XMP + ajuste manual e renderiza tudo
/// de uma vez no `Develop` (modelo do pipeline do Lightroom). O ajuste manual é um delta somado
/// aos controles básicos do preset/automático, como mover os sliders do Lightroom depois de
/// aplicar um preset. Mesma API e resultado de `PhotoEditor.java`.
public enum PhotoEditor {
    public static let colors = Develop.colors

    // MARK: Pipeline

    public static func edit(_ image: inout PixelImage, options: EditOptions) {
        image = render(Develop.Source(prepare(image, options: options)), options: options)
    }

    /// Pixels prontos para revelar: recortados/endireitados e com o perfil criativo. Mesma ordem do Android.
    public static func prepare(_ image: PixelImage, options: EditOptions) -> PixelImage {
        withProfile(cropped(image, options: options), options: options)
    }

    /// Só o recorte/endireitar (a curadoria mede a nitidez aqui, antes do perfil e do preset).
    public static func cropped(_ image: PixelImage, options: EditOptions) -> PixelImage {
        guard let crop = options.crop, !crop.isIdentity else { return image }
        return crop.constrained(image.width, image.height).apply(image)
    }

    /// Aplica o perfil criativo do preset (tabelas de cor), quando houver.
    public static func withProfile(_ image: PixelImage, options: EditOptions) -> PixelImage {
        CreativeProfile.apply(image, profile(options))
    }

    /// Perfil criativo do preset, com a intensidade do editor (`LookAmount` no extra) por cima.
    public static func profile(_ options: EditOptions) -> CreativeProfile.Profile? {
        guard let look = options.look else { return nil }
        return CreativeProfile.resolve(look, amount: Develop.value("LookAmount", in: merged(options.preset, options.extra)))
    }

    public static func render(_ source: Develop.Source, options: EditOptions) -> PixelImage {
        source.render(settings(source, options: options))
    }

    /// Ajustes finais: preset completo com os básicos = automático + preset + delta manual.
    public static func settings(_ source: Develop.Source, options: EditOptions) -> Develop.Settings {
        var s = Develop.Settings(merged(options.preset, options.extra))
        s.basic = autoValues(source, options: options) + presetControls(options.preset) + (options.manual ?? .zero)
        return s
    }

    /// Preset com os ajustes extras do editor por cima. Uma curva de pontos no extra (`ToneCurvePV2012.0`, `.1`…)
    /// substitui a curva inteira do preset.
    public static func merged(_ preset: [String: Double]?, _ extra: [String: Double]?) -> [String: Double]? {
        guard let extra, !extra.isEmpty else { return preset }
        var base = preset ?? [:]
        for c in Develop.curves where extra["\(c).0"] != nil {
            for k in base.keys where k.hasPrefix(c + ".") { base[k] = nil }
        }
        return base.merging(extra) { _, new in new }
    }

    /// Ajustes de um preset criado a partir de uma edição: o preset da foto com os ajustes do editor por cima e os
    /// básicos = preset + delta manual. O automático fica de fora (é calculado para cada foto) e o recorte também.
    /// Aplicar o preset novo numa foto sem ajuste manual dá o mesmo resultado que a edição de onde ele saiu.
    public static func presetValues(preset: [String: Double]?, extra: [String: Double]?, manual: EditControls?) -> [String: Double] {
        var values = merged(preset, extra) ?? [:]
        let basic = presetControls(preset) + (manual ?? .zero)
        for (k, key) in zip(EditControls.keyPaths, Develop.basicXMP) {
            let v = basic[keyPath: k]
            values[key] = v == 0 ? nil : v
        }
        return values
    }

    /// Chave de ponto de curva (`ToneCurvePV2012Red.4`…).
    public static func isCurveKey(_ k: String) -> Bool {
        guard let dot = k.lastIndex(of: ".") else { return false }
        let base = String(k[..<dot]), n = k[k.index(after: dot)...]
        return !n.isEmpty && n.count <= 3 && n.allSatisfy(\.isNumber) && Develop.curves.contains(base)
    }

    /// Automático da foto, já escalado pela intensidade das opções (zero se desligado).
    public static func autoValues(_ source: Develop.Source, options: EditOptions) -> EditControls {
        options.auto ? source.autoControls.scaled(by: limit(options.autoStrength, 0, 1.5)) : EditControls()
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
        for (k, key) in zip(EditControls.keyPaths, Develop.basicXMP) { r[keyPath: k] = p[key] ?? 0 }
        return r
    }

    /// Aplica só o automático (testes e compatibilidade).
    public static func auto(_ data: inout [UInt32], strength: Double = 1) {
        guard !data.isEmpty else { return }
        var o = EditOptions()
        o.autoStrength = strength
        data = render(Develop.Source(PixelImage(width: data.count, height: 1, pixels: data)), options: o).pixels
    }
}
