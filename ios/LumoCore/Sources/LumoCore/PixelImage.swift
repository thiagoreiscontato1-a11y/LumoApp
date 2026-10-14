import Foundation

/// Imagem em memória no mesmo formato usado pelo Android (`int` ARGB 0xAARRGGBB).
/// Manter o formato idêntico permite portar os algoritmos de edição e curadoria
/// linha a linha e comparar resultados entre as plataformas.
public struct PixelImage: Equatable {
    public let width: Int
    public let height: Int
    public var pixels: [UInt32]

    public init(width: Int, height: Int, pixels: [UInt32]) {
        precondition(width >= 0 && height >= 0 && pixels.count == width * height, "Dimensões inconsistentes")
        self.width = width
        self.height = height
        self.pixels = pixels
    }

    public init(width: Int, height: Int, fill: UInt32 = 0xFF00_0000) {
        self.init(width: width, height: height, pixels: Array(repeating: fill, count: width * height))
    }

    public subscript(x: Int, y: Int) -> UInt32 {
        get { pixels[y * width + x] }
        set { pixels[y * width + x] = newValue }
    }

    /// Redução por média de blocos (área), usada para análises rápidas.
    public func downsampled(maxEdge: Int) -> PixelImage {
        let edge = max(width, height)
        guard maxEdge > 0, edge > maxEdge, width > 0, height > 0 else { return self }
        let scale = Double(maxEdge) / Double(edge)
        let w = max(1, Int((Double(width) * scale).rounded()))
        let h = max(1, Int((Double(height) * scale).rounded()))
        return resized(width: w, height: h)
    }

    /// Redimensionamento por média de área (box filter). Suficiente para hashes e análise.
    public func resized(width w: Int, height h: Int) -> PixelImage {
        guard w > 0, h > 0, width > 0, height > 0 else { return PixelImage(width: max(0, w), height: max(0, h)) }
        var out = [UInt32](repeating: 0xFF00_0000, count: w * h)
        for oy in 0..<h {
            let y0 = oy * height / h
            let y1 = max(y0 + 1, (oy + 1) * height / h)
            for ox in 0..<w {
                let x0 = ox * width / w
                let x1 = max(x0 + 1, (ox + 1) * width / w)
                var r = 0, g = 0, b = 0, n = 0
                for y in y0..<min(y1, height) {
                    let row = y * width
                    for x in x0..<min(x1, width) {
                        let v = pixels[row + x]
                        r += Int((v >> 16) & 255); g += Int((v >> 8) & 255); b += Int(v & 255); n += 1
                    }
                }
                n = max(1, n)
                out[oy * w + ox] = 0xFF00_0000 | UInt32(r / n) << 16 | UInt32(g / n) << 8 | UInt32(b / n)
            }
        }
        return PixelImage(width: w, height: h, pixels: out)
    }
}

@inline(__always) func channelR(_ v: UInt32) -> Double { Double((v >> 16) & 255) / 255 }
@inline(__always) func channelG(_ v: UInt32) -> Double { Double((v >> 8) & 255) / 255 }
@inline(__always) func channelB(_ v: UInt32) -> Double { Double(v & 255) / 255 }
@inline(__always) func clamp01(_ v: Double) -> Double { max(0, min(1, v)) }
@inline(__always) func limit(_ v: Double, _ lo: Double, _ hi: Double) -> Double { max(lo, min(hi, v)) }

@inline(__always) func packRGB(_ r: Double, _ g: Double, _ b: Double) -> UInt32 {
    let ri = UInt32((clamp01(r) * 255).rounded())
    let gi = UInt32((clamp01(g) * 255).rounded())
    let bi = UInt32((clamp01(b) * 255).rounded())
    return 0xFF00_0000 | ri << 16 | gi << 8 | bi
}

@inline(__always) func luminance255(_ v: UInt32) -> Double {
    0.2126 * Double((v >> 16) & 255) + 0.7152 * Double((v >> 8) & 255) + 0.0722 * Double(v & 255)
}
