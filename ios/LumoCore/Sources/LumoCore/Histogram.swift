import Foundation

/// Histograma RGB da foto revelada, como o do Lightroom: um canal por cor, com aviso de recorte nos
/// dois extremos (triângulos) e a máscara de recorte (realces estourados em vermelho, sombras sem
/// detalhe em azul). Mesma conta de `Histogram.java`.
public struct Histogram: Equatable {
    public static let bins = 256
    /// Pixel recortado: canal em 254–255 (realces) ou 0–1 (sombras), com a folga da compressão JPEG.
    public static let highCut = 254, lowCut = 1
    /// Amostras contadas no máximo; a prévia do editor (~1280 px) é lida inteira quase sempre.
    public static let maxSamples = 1 << 20
    public static let white: UInt32 = 0xFFFF_FFFF
    public static let clipHigh: UInt32 = 0xFFFF_2A2A
    public static let clipLow: UInt32 = 0xFF2A_6BFF

    /// Contagem por valor (0–255) dos canais R, G e B.
    public private(set) var channels = [[Int]](repeating: [Int](repeating: 0, count: 256), count: 3)
    /// Pixels contados; recortados nos três canais ao mesmo tempo.
    public private(set) var total = 0, highAll = 0, lowAll = 0
    /// Pixels recortados por canal (R, G, B).
    public private(set) var high = [0, 0, 0], low = [0, 0, 0]

    public init() {}
    public init(_ image: PixelImage) { self.init(pixels: image.pixels) }

    public init(pixels: [UInt32]) {
        guard !pixels.isEmpty else { return }
        let step = max(1, (pixels.count + Self.maxSamples - 1) / Self.maxSamples)
        var r = [Int](repeating: 0, count: 256), g = r, b = r
        var hi = [0, 0, 0], lo = [0, 0, 0], hiAll = 0, loAll = 0, n = 0
        pixels.withUnsafeBufferPointer { px in
            var i = 0
            while i < px.count {
                let v = px[i]
                let R = Int((v >> 16) & 255), G = Int((v >> 8) & 255), B = Int(v & 255)
                r[R] += 1; g[G] += 1; b[B] += 1; n += 1
                var h = 0, l = 0
                if R >= Self.highCut { hi[0] += 1; h += 1 }
                if G >= Self.highCut { hi[1] += 1; h += 1 }
                if B >= Self.highCut { hi[2] += 1; h += 1 }
                if R <= Self.lowCut { lo[0] += 1; l += 1 }
                if G <= Self.lowCut { lo[1] += 1; l += 1 }
                if B <= Self.lowCut { lo[2] += 1; l += 1 }
                if h == 3 { hiAll += 1 }
                if l == 3 { loAll += 1 }
                i += step
            }
        }
        channels = [r, g, b]
        total = n; highAll = hiAll; lowAll = loAll; high = hi; low = lo
    }

    /// Altura de cada barra (0–1) dos três canais, suavizada em 3 barras. A referência é o maior pico fora dos
    /// extremos: um mar de pixels pretos ou estourados não achata o resto, e as pontas só encostam no topo.
    public func levels() -> [[Double]] {
        let n = Self.bins
        var out = [[Double]](repeating: [Double](repeating: 0, count: n), count: 3)
        var ref = 0.0
        for c in 0..<3 {
            let h = channels[c]
            for i in 0..<n {
                let v = Double(h[max(0, i - 1)] + 2 * h[i] + h[min(n - 1, i + 1)]) / 4
                out[c][i] = v
                if i > 1 && i < n - 2 { ref = max(ref, v) }
            }
        }
        if ref <= 0 { ref = out.flatMap { $0 }.max() ?? 0 }
        if ref > 0 { for c in 0..<3 { for i in 0..<n { out[c][i] = min(1, out[c][i] / ref) } } }
        return out
    }

    /// Cor do triângulo de realces: branco se os três canais estouram juntos, a cor dos canais recortados, ou 0 (sem recorte).
    public var highlightWarning: UInt32 { Self.warning(highAll, high) }
    /// Cor do triângulo de sombras, com a mesma regra.
    public var shadowWarning: UInt32 { Self.warning(lowAll, low) }

    static func warning(_ all: Int, _ channel: [Int]) -> UInt32 {
        if all > 0 { return white }
        let r = channel[0] > 0, g = channel[1] > 0, b = channel[2] > 0
        if !r && !g && !b { return 0 }
        return 0xFF00_0000 | UInt32(r ? 0xFF : 0x40) << 16 | UInt32(g ? 0xFF : 0x40) << 8 | UInt32(b ? 0xFF : 0x40)
    }

    /// Fração (0–1) da foto sem detalhe nos realces (três canais estourados) e nas sombras.
    public var highlightFraction: Double { total == 0 ? 0 : Double(highAll) / Double(total) }
    public var shadowFraction: Double { total == 0 ? 0 : Double(lowAll) / Double(total) }

    /// Cópia com a máscara de recorte: realces sem detalhe em vermelho, sombras sem detalhe em azul.
    public static func clipping(_ image: PixelImage) -> PixelImage {
        var out = image
        for i in out.pixels.indices {
            let v = out.pixels[i]
            let R = Int((v >> 16) & 255), G = Int((v >> 8) & 255), B = Int(v & 255)
            if R >= highCut && G >= highCut && B >= highCut { out.pixels[i] = clipHigh }
            else if R <= lowCut && G <= lowCut && B <= lowCut { out.pixels[i] = clipLow }
        }
        return out
    }
}
