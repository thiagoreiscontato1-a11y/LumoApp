import Foundation

/// Curadoria técnica local + nota de 1 a 10 (porta de `Curator.java`).
public enum Curator {
    public struct Result: Equatable, Codable {
        public var review: Bool
        public var reason: String
        public var sharpness: Double
        public var brightness: Double
        public var contrast: Double
        public var clipping: Double
        public var framing: Double
        public var score: Int

        public init(review: Bool, reason: String, sharpness: Double, brightness: Double, contrast: Double,
                    clipping: Double, framing: Double, score: Int) {
            self.review = review; self.reason = reason; self.sharpness = sharpness; self.brightness = brightness
            self.contrast = contrast; self.clipping = clipping; self.framing = framing
            self.score = max(1, min(10, score))
        }
    }

    /// `image` deve estar reduzida para ~720 px no maior lado, como no Android.
    public static func measure(_ image: PixelImage, sensitivity rawSensitivity: Int) -> Result {
        let sensitivity = max(0, min(2, rawSensitivity))
        let w = image.width, h = image.height
        if w < 16 || h < 16 {
            return Result(review: true, reason: "Imagem pequena demais para análise técnica.", sharpness: 0,
                          brightness: 0, contrast: 0, clipping: 1, framing: 1, score: 1)
        }
        let px = image.pixels
        let step = max(1, max(w, h) / 640)
        var count = 0, sum = 0.0, sum2 = 0.0, clip = 0.0, colorClip = 0.0
        var y = 0
        while y < h {
            var x = 0
            while x < w {
                let c = px[y * w + x]
                let r = Int((c >> 16) & 255), g = Int((c >> 8) & 255), bl = Int(c & 255)
                let l = 0.2126 * Double(r) + 0.7152 * Double(g) + 0.0722 * Double(bl)
                sum += l; sum2 += l * l; count += 1
                if l <= 5 || l >= 250 { clip += 1 }
                if r <= 2 || g <= 2 || bl <= 2 || r >= 253 || g >= 253 || bl >= 253 { colorClip += 1 }
                x += step
            }
            y += step
        }
        let n = Double(max(1, count))
        let mean = sum / n
        let variance = max(0, sum2 / n - mean * mean)
        let contrast = variance.squareRoot()
        let clipRatio = max(clip, colorClip) / max(1, Double(count))

        var gradSum = 0.0, lapSum = 0.0, lap2 = 0.0, energy = 0.0, weightedX = 0.0, weightedY = 0.0
        var edgeCount = 0
        let d = max(1, step)
        y = d
        while y < h - d {
            var x = d
            while x < w - d {
                let i = y * w + x
                let l = luminance255(px[i])
                let left = luminance255(px[i - d]), right = luminance255(px[i + d])
                let up = luminance255(px[i - d * w]), down = luminance255(px[i + d * w])
                let gx = (right - left) / 2, gy = (down - up) / 2
                let grad = (gx * gx + gy * gy).squareRoot(), lap = (left + right + up + down) - 4 * l
                gradSum += grad; lapSum += lap; lap2 += lap * lap; edgeCount += 1
                let e = grad * grad
                energy += e; weightedX += e * Double(x); weightedY += e * Double(y)
                x += d
            }
            y += d
        }
        let en = Double(max(1, edgeCount))
        let meanGrad = gradSum / en, lapMean = lapSum / en
        let lapVar = max(0, lap2 / en - lapMean * lapMean)
        let sharp = 0.55 * lapVar.squareRoot() + 0.45 * meanGrad
        let cx = energy > 0 ? weightedX / energy : Double(w) / 2
        let cy = energy > 0 ? weightedY / energy : Double(h) / 2
        let nx = cx / Double(w), ny = cy / Double(h)
        let edgeDistance = min(min(nx, 1 - nx), min(ny, 1 - ny))
        let framing = max(0, 1 - edgeDistance / 0.5)

        let sharpMin = [7.0, 10.5, 14.5], contrastMin = [13.0, 19.0, 25.0], darkMin = [20.0, 30.0, 40.0]
        let brightMax = [235.0, 225.0, 215.0], clipMax = [0.55, 0.38, 0.26], frameEdge = [0.018, 0.035, 0.065]
        var reasons: [String] = []
        if sharp < sharpMin[sensitivity] { reasons.append("Nitidez baixa/desfoque") }
        if mean < darkMin[sensitivity] { reasons.append("Subexposta") }
        if mean > brightMax[sensitivity] { reasons.append("Superexposta") }
        if contrast < contrastMin[sensitivity] { reasons.append("Contraste muito baixo") }
        if clipRatio > clipMax[sensitivity] { reasons.append("Clipping de luz/cor") }
        if sensitivity > 0 && edgeDistance < frameEdge[sensitivity] { reasons.append("Assunto possivelmente cortado junto à borda") }

        let s = score(sharp: sharp, mean: mean, contrast: contrast, clipping: clipRatio, edgeDistance: edgeDistance)
        let reason = reasons.isEmpty
            ? String(format: "Aprovada · score %d/10 · nitidez %.1f · luz %.0f · contraste %.1f", locale: Locale(identifier: "en_US_POSIX"), s, sharp, mean, contrast)
            : reasons.joined(separator: " · ")
        return Result(review: !reasons.isEmpty, reason: reason, sharpness: sharp, brightness: mean, contrast: contrast,
                      clipping: clipRatio, framing: framing, score: s)
    }

    public static func score(sharp: Double, mean: Double, contrast: Double, clipping: Double, edgeDistance: Double) -> Int {
        var s = 10.0
        if sharp < 18 { s -= min(4.2, (18 - sharp) / 4) }
        s -= min(2, abs(mean - 128) / 58)
        if contrast < 30 { s -= min(1.6, (30 - contrast) / 14) }
        if clipping > 0.08 { s -= min(1.6, (clipping - 0.08) * 4.5) }
        if edgeDistance < 0.055 { s -= min(1, (0.055 - edgeDistance) * 18) }
        return max(1, min(10, Int(s.rounded())))
    }
}

/// Hashes usados para identificar duplicatas (porta de `PhotoHash.java`).
public enum PhotoHash {
    /// dHash de 64 bits a partir de uma redução 9×8.
    public static func perceptual(_ image: PixelImage) -> String {
        let small = image.resized(width: 9, height: 8)
        var bits: UInt64 = 0
        var bit: UInt64 = 0
        for y in 0..<8 {
            for x in 0..<8 {
                if luminance255(small[x, y]) > luminance255(small[x + 1, y]) { bits |= (1 << bit) }
                bit += 1
            }
        }
        return String(format: "%016llx", bits)
    }

    public static func hamming(_ a: String, _ b: String) -> Int {
        guard a.count == 16, b.count == 16, let x = UInt64(a, radix: 16), let y = UInt64(b, radix: 16) else { return 64 }
        return (x ^ y).nonzeroBitCount
    }

    public static func sha256Hex(_ data: Data) -> String {
        LumoSHA256.hash(data).map { String(format: "%02x", $0) }.joined()
    }
}
