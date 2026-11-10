import Foundation

/// Recorte e endireitar, aplicados nos pixels antes da revelação (a vinheta do Lightroom é "pós-corte").
/// A foto gira `angle` graus em torno do centro (positivo = sentido horário) numa tela do mesmo tamanho e o
/// retângulo `left, top, right, bottom` (0–1 dessa tela) é recortado, sempre dentro da foto girada.
/// Mesma conta de `Crop.java`.
public struct Crop: Codable, Hashable {
    public static let maxAngle = 45.0
    public private(set) var angle: Double
    public private(set) var left: Double
    public private(set) var top: Double
    public private(set) var right: Double
    public private(set) var bottom: Double

    public init(angle: Double = 0, left: Double = 0, top: Double = 0, right: Double = 1, bottom: Double = 1) {
        self.angle = max(-Self.maxAngle, min(Self.maxAngle, angle.isFinite ? angle : 0))
        var l = Self.clamp01(left), t = Self.clamp01(top), r = Self.clamp01(right), b = Self.clamp01(bottom)
        if r - l < 0.02 { let c = (l + r) / 2; l = max(0, c - 0.01); r = min(1, l + 0.02) }
        if b - t < 0.02 { let c = (t + b) / 2; t = max(0, c - 0.01); b = min(1, t + 0.02) }
        self.left = l; self.top = t; self.right = r; self.bottom = b
    }

    public static let none = Crop()
    public var isIdentity: Bool { abs(angle) < 1e-6 && left <= 1e-6 && top <= 1e-6 && right >= 1 - 1e-6 && bottom >= 1 - 1e-6 }
    static func clamp01(_ v: Double) -> Double { v.isNaN ? 0 : max(0, min(1, v)) }

    /// Ponto da tela (pixels) → ponto da foto original (pixels).
    public static func toSource(_ px: Double, _ py: Double, _ w: Int, _ h: Int, _ angle: Double) -> (Double, Double) {
        let a = angle * .pi / 180, c = cos(a), s = sin(a), cx = Double(w) / 2, cy = Double(h) / 2, dx = px - cx, dy = py - cy
        return (cx + c * dx + s * dy, cy - s * dx + c * dy)
    }
    static func inside(_ nx: Double, _ ny: Double, _ w: Int, _ h: Int, _ angle: Double) -> Bool {
        let q = toSource(nx * Double(w), ny * Double(h), w, h, angle)
        return q.0 >= -1e-6 && q.0 <= Double(w) + 1e-6 && q.1 >= -1e-6 && q.1 <= Double(h) + 1e-6
    }
    public static func fits(_ l: Double, _ t: Double, _ r: Double, _ b: Double, _ w: Int, _ h: Int, _ angle: Double) -> Bool {
        inside(l, t, w, h, angle) && inside(r, t, w, h, angle) && inside(l, b, w, h, angle) && inside(r, b, w, h, angle)
    }

    /// O mesmo recorte encolhido em torno do centro (e, se preciso, levado ao centro) até caber na foto girada.
    public func constrained(_ w: Int, _ h: Int) -> Crop {
        if Self.fits(left, top, right, bottom, w, h, angle) { return self }
        let cx = (left + right) / 2, cy = (top + bottom) / 2, hw = (right - left) / 2, hh = (bottom - top) / 2
        for step in 0...20 {
            let k = Double(step) / 20, x = cx + (0.5 - cx) * k, y = cy + (0.5 - cy) * k
            guard Self.inside(x, y, w, h, angle) else { continue }
            var lo = 0.0, hi = 1.0
            for _ in 0..<40 {
                let m = (lo + hi) / 2
                if Self.fits(x - hw * m, y - hh * m, x + hw * m, y + hh * m, w, h, angle) { lo = m } else { hi = m }
            }
            if lo > 0 { return Crop(angle: angle, left: x - hw * lo, top: y - hh * lo, right: x + hw * lo, bottom: y + hh * lo) }
        }
        return Crop(angle: angle, left: 0.49, top: 0.49, right: 0.51, bottom: 0.51)
    }

    /// Maior recorte centrado com a proporção pedida (largura/altura em pixels; 0 = a da foto) que cabe na foto girada.
    public static func largest(_ w: Int, _ h: Int, angle: Double, aspect: Double) -> Crop {
        let ratio = aspect > 0 ? aspect : Double(w) / Double(max(1, h))
        var nw = 1.0, nh = (Double(w) / ratio) / Double(max(1, h))
        if nh > 1 { nh = 1; nw = (Double(h) * ratio) / Double(max(1, w)) }
        var lo = 0.0, hi = 1.0
        for _ in 0..<40 {
            let m = (lo + hi) / 2
            if fits(0.5 - nw * m / 2, 0.5 - nh * m / 2, 0.5 + nw * m / 2, 0.5 + nh * m / 2, w, h, angle) { lo = m } else { hi = m }
        }
        return Crop(angle: angle, left: 0.5 - nw * lo / 2, top: 0.5 - nh * lo / 2, right: 0.5 + nw * lo / 2, bottom: 0.5 + nh * lo / 2)
    }

    /// Tamanho do resultado em pixels para uma foto w×h.
    public func size(_ w: Int, _ h: Int) -> (Int, Int) {
        (max(1, Int(((right - left) * Double(w)).rounded())), max(1, Int(((bottom - top) * Double(h)).rounded())))
    }

    /// Foto recortada (e girada) com interpolação bilinear.
    public func apply(_ image: PixelImage) -> PixelImage {
        let w = image.width, h = image.height
        let (ow, oh) = size(w, h)
        if isIdentity && ow == w && oh == h { return image }
        let a = angle * .pi / 180, c = cos(a), s = sin(a), cx = Double(w) / 2, cy = Double(h) / 2, x0 = left * Double(w), y0 = top * Double(h)
        let rotate = abs(angle) > 1e-9
        var out = [UInt32](repeating: 0xFF00_0000, count: ow * oh)
        image.pixels.withUnsafeBufferPointer { src in
            out.withUnsafeMutableBufferPointer { dst in
                let d = dst.baseAddress!
                Develop.parallel(oh) { ya, yb in
                    for y in ya..<yb {
                        let py = y0 + Double(y) + 0.5 - cy
                        for x in 0..<ow {
                            let px = x0 + Double(x) + 0.5 - cx
                            let sx = rotate ? cx + c * px + s * py - 0.5 : cx + px - 0.5
                            let sy = rotate ? cy - s * px + c * py - 0.5 : cy + py - 0.5
                            d[y * ow + x] = Self.sample(src, w, h, sx, sy)
                        }
                    }
                }
            }
        }
        return PixelImage(width: ow, height: oh, pixels: out)
    }

    /// Foto inteira girada na tela do mesmo tamanho, com `fill` fora dela (prévia do modo Corte).
    public static func rotated(_ image: PixelImage, angle: Double, fill: UInt32) -> PixelImage {
        guard abs(angle) > 1e-9 else { return image }
        let w = image.width, h = image.height, a = angle * .pi / 180, c = cos(a), s = sin(a), cx = Double(w) / 2, cy = Double(h) / 2
        var out = [UInt32](repeating: fill, count: w * h)
        image.pixels.withUnsafeBufferPointer { src in
            out.withUnsafeMutableBufferPointer { dst in
                let d = dst.baseAddress!
                Develop.parallel(h) { ya, yb in
                    for y in ya..<yb {
                        let py = Double(y) + 0.5 - cy
                        for x in 0..<w {
                            let px = Double(x) + 0.5 - cx, sx = cx + c * px + s * py, sy = cy - s * px + c * py
                            if sx >= 0 && sy >= 0 && sx <= Double(w) && sy <= Double(h) { d[y * w + x] = sample(src, w, h, sx - 0.5, sy - 0.5) }
                        }
                    }
                }
            }
        }
        return PixelImage(width: w, height: h, pixels: out)
    }

    static func sample(_ px: UnsafeBufferPointer<UInt32>, _ w: Int, _ h: Int, _ xIn: Double, _ yIn: Double) -> UInt32 {
        let x = min(Double(w - 1), max(0, xIn)), y = min(Double(h - 1), max(0, yIn))
        let x0 = Int(x), y0 = Int(y), x1 = min(w - 1, x0 + 1), y1 = min(h - 1, y0 + 1)
        let fx = x - Double(x0), fy = y - Double(y0)
        let a = px[y0 * w + x0]
        if fx == 0 && fy == 0 { return a }
        let b = px[y0 * w + x1], c = px[y1 * w + x0], d = px[y1 * w + x1]
        let w00 = (1 - fx) * (1 - fy), w10 = fx * (1 - fy), w01 = (1 - fx) * fy, w11 = fx * fy
        func ch(_ shift: UInt32) -> UInt32 {
            let p0: Double = Double((a >> shift) & 255) * w00
            let p1: Double = Double((b >> shift) & 255) * w10
            let p2: Double = Double((c >> shift) & 255) * w01
            let p3: Double = Double((d >> shift) & 255) * w11
            let v: Double = p0 + p1 + p2 + p3
            return UInt32(max(0, min(255, v.rounded())))
        }
        return 0xFF00_0000 | ch(16) << 16 | ch(8) << 8 | ch(0)
    }
}
