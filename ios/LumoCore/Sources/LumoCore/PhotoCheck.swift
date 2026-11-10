import Foundation

/// Verificações da curadoria e correção de exposição (porta de `PhotoCheck.java` e da decisão de `SmartCurator.java`).
///
/// Regras da curadoria (Android 0.19.0 / iOS 0.5.0):
/// - Muito clara ou escura depois do preset: o Lumo corrige a exposição, não recusa.
/// - Desfocada: vai para Revisão. A nitidez é medida na foto ORIGINAL (antes de preset, nitidez e grão), região por
///   região, e basta uma região nítida (o assunto) para passar. Fundo desfocado de retrato não reprova.
/// - Cortada: vai para Revisão quando um rosto principal sai do quadro pela lateral ou por baixo.
/// - Foto praticamente preta ou toda branca: Revisão (não há o que corrigir).
public enum PhotoCheck {
    /// Lado da versão usada para medir a nitidez: perto do que o convidado vê no celular ou na galeria.
    public static let detailEdge = 1600
    /// Lado da prévia usada para ajustar a exposição.
    public static let previewEdge = 512

    /// Reduz pela média de blocos k×k (k inteiro) até o maior lado caber em `maxEdge`.
    public static func shrink(_ image: PixelImage, maxEdge: Int) -> PixelImage {
        let w = image.width, h = image.height
        let k = max(1, Int((Double(max(w, h)) / Double(maxEdge)).rounded(.up)))
        if k == 1 { return image }
        let ow = max(1, w / k), oh = max(1, h / k), n = k * k
        var out = [UInt32](repeating: 0, count: ow * oh)
        image.pixels.withUnsafeBufferPointer { px in
            out.withUnsafeMutableBufferPointer { buf in
                let o = buf.baseAddress!
                Develop.parallel(oh) { y0, y1 in
                    for oy in y0..<y1 {
                        for ox in 0..<ow {
                            var r = 0, g = 0, b = 0
                            for dy in 0..<k {
                                let row = (oy * k + dy) * w + ox * k
                                for dx in 0..<k {
                                    let c = px[row + dx]
                                    r += Int((c >> 16) & 255); g += Int((c >> 8) & 255); b += Int(c & 255)
                                }
                            }
                            o[oy * ow + ox] = 0xFF00_0000 | UInt32((r + n / 2) / n) << 16 | UInt32((g + n / 2) / n) << 8 | UInt32((b + n / 2) / n)
                        }
                    }
                }
            }
        }
        return PixelImage(width: ow, height: oh, pixels: out)
    }

    // MARK: Nitidez

    /// Nitidez por região. Em cada região compara a energia das diferenças finas (2 px) com a das grossas (4 px), em
    /// cada direção: borda nítida dá 2, borda borrada tende a 1, textura fina passa de 2. Não depende de contraste nem
    /// de brilho. Tremido numa direção derruba aquela direção, então vale a pior das duas. O ruído (ISO alto) é estimado
    /// na região mais lisa e descontado. Regiões lisas (céu, parede) não contam.
    /// `best` é a região mais nítida; `known` é falso quando não há detalhe para julgar (aí nunca reprova).
    public struct Sharpness: Equatable {
        public var best: Double
        public var regions: Int
        public var noise: Double
        public var known: Bool { regions > 0 }
    }

    /// Energia mínima (níveis², por pixel e direção, nas diferenças de 4 px) para uma região ter detalhe de verdade.
    static let minEnergy = 6.0

    public static func sharpness(_ image: PixelImage) -> Sharpness {
        let w = image.width, h = image.height
        if w < 16 || h < 16 { return Sharpness(best: 0, regions: 0, noise: 0) }
        let l = image.pixels.map { Float(luminance255($0)) }
        let tile = max(24, max(w, h) / 8), cols = (w + tile - 1) / tile, rows = (h + tile - 1) / tile
        var e = [[Double]?](repeating: nil, count: cols * rows)
        var minFine = Double.greatestFiniteMagnitude
        l.withUnsafeBufferPointer { l in
            for ty in 0..<rows {
                for tx in 0..<cols {
                    let x0 = max(2, tx * tile), x1 = min(w - 2, tx * tile + tile), y0 = max(2, ty * tile), y1 = min(h - 2, ty * tile + tile)
                    if x1 - x0 < 8 || y1 - y0 < 8 { continue }
                    var fx2 = 0.0, fy2 = 0.0, cx2 = 0.0, cy2 = 0.0, n = 0.0
                    for y in y0..<y1 {
                        let row = y * w
                        for x in x0..<x1 {
                            let i = row + x
                            let fx = Double(l[i + 1] - l[i - 1]), fy = Double(l[i + w] - l[i - w])
                            let cx = Double(l[i + 2] - l[i - 2]) * 0.5, cy = Double(l[i + 2 * w] - l[i - 2 * w]) * 0.5
                            fx2 += fx * fx; fy2 += fy * fy; cx2 += cx * cx; cy2 += cy * cy; n += 1
                        }
                    }
                    e[ty * cols + tx] = [fx2 / n, fy2 / n, cx2 / n, cy2 / n]
                    minFine = min(minFine, (fx2 + fy2) / n)
                }
            }
        }
        // Ruído branco de variância v soma 2v às diferenças finas e v/2 às grossas, em cada direção.
        let v = minFine == .greatestFiniteMagnitude ? 0 : minFine / 4
        // Primeiro só regiões com detalhe nas duas direções; se não houver nenhuma, as de uma direção só.
        for pass in 0..<2 {
            var best = 0.0, regions = 0
            for case let t? in e {
                let fx = t[0] - 2 * v, fy = t[1] - 2 * v, cx = t[2] - v / 2, cy = t[3] - v / 2
                let ex = cx >= minEnergy, ey = cy >= minEnergy
                if pass == 0 ? !(ex && ey) : !(ex || ey) { continue }
                var r = Double.greatestFiniteMagnitude
                if ex { r = min(r, max(0, fx) / cx) }
                if ey { r = min(r, max(0, fy) / cy) }
                regions += 1
                best = max(best, r)
            }
            if regions > 0 { return Sharpness(best: best, regions: regions, noise: v.squareRoot()) }
        }
        return Sharpness(best: 0, regions: 0, noise: v.squareRoot())
    }

    /// Abaixo disto a foto inteira está desfocada (Baixa, Média, Alta). Calibrado em fotos reais com desfoque conhecido.
    public static let blurLimit = [1.25, 1.40, 1.60]

    public static func blurry(_ s: Sharpness, sensitivity: Int) -> Bool {
        s.known && s.best < blurLimit[max(0, min(2, sensitivity))]
    }

    // MARK: Rosto cortado

    /// Rosto encontrado: centro entre os olhos (pixels, origem no alto à esquerda), distância entre os olhos e confiança.
    public struct Face: Equatable {
        public var x: Double, y: Double, eye: Double, confidence: Double
        public init(x: Double, y: Double, eye: Double, confidence: Double) {
            self.x = x; self.y = y; self.eye = eye; self.confidence = confidence
        }
    }

    /// Rosto principal: olhos separados por pelo menos 2,5% do maior lado (quem está em destaque, não o fundo).
    public static func principal(eye: Double, width w: Int, height h: Int) -> Bool { eye >= 0.025 * Double(max(w, h)) }

    /// Fração do rosto (da testa ao queixo, de orelha a orelha) que ficou fora do quadro pela lateral ou por baixo.
    /// Cortar o alto da cabeça é enquadramento comum em retrato fechado e não conta.
    public static func cutFraction(x cx: Double, y cy: Double, eye: Double, width w: Int, height h: Int) -> Double {
        let x0 = cx - eye * 1.25, x1 = cx + eye * 1.25, y0 = cy - eye * 1.2, y1 = cy + eye * 2.0
        let area = (x1 - x0) * (y1 - y0)
        if area <= 0 { return 0 }
        let vx = max(0, min(Double(w), x1) - max(0, x0)), vy = max(0, min(Double(h), y1) - y0)
        return max(0, 1 - vx * vy / area)
    }

    /// Fração cortada a partir da qual vai para Revisão (Baixa, Média, Alta).
    public static let cutLimit = [0.40, 0.25, 0.15]

    public static func cut(_ fraction: Double, sensitivity: Int) -> Bool { fraction > cutLimit[max(0, min(2, sensitivity))] }

    /// Curadoria por rostos: reprova só quando um rosto principal saiu do quadro.
    public static func faceCut(_ faces: [Face], width w: Int, height h: Int, sensitivity: Int) -> (review: Bool, note: String) {
        var worst = 0.0
        for f in faces where f.confidence >= 0.4 && principal(eye: f.eye, width: w, height: h) {
            worst = max(worst, cutFraction(x: f.x, y: f.y, eye: f.eye, width: w, height: h))
        }
        let review = cut(worst, sensitivity: sensitivity)
        return (review, review ? "Rosto cortado na borda" : worst > 0.05 ? "Rosto encostado na borda" : "")
    }

    // MARK: Exposição depois do preset

    public struct Exposure: Equatable {
        public var ev = 0.0
        public var note = ""
        public var unrecoverable = ""
        public init() {}
    }

    /// Medidas da prévia revelada: luz estourada na foto e nos rostos, e brilho médio dos rostos.
    struct Light {
        var clip = 0.0, faceClip = 0.0, faceMean = -1.0

        static func of(_ image: PixelImage, _ boxes: [[Int]]) -> Light {
            var m = Light()
            let px = image.pixels, w = image.width
            var clipped = 0
            for c in px where luminance255(c) >= 250 { clipped += 1 }
            m.clip = Double(clipped) / Double(max(1, px.count))
            var sum = 0.0, area = 0.0, fc = 0, fn = 0
            for b in boxes {
                var s = 0.0, n = 0, k = 0
                for y in b[1]..<b[3] {
                    for x in b[0]..<b[2] {
                        let v = luminance255(px[y * w + x])
                        s += v; n += 1
                        if v >= 245 { k += 1 }
                    }
                }
                if n == 0 { continue }
                sum += s; area += Double(n); fc += k; fn += n
            }
            if area > 0 { m.faceMean = sum / area; m.faceClip = Double(fc) / Double(fn) }
            return m
        }
    }

    /// Caixas da parte central do rosto (sem cabelo nem fundo), dentro da imagem.
    static func faceBoxes(_ faces: [Face], _ w: Int, _ h: Int) -> [[Int]] {
        var boxes: [[Int]] = []
        for f in faces where f.confidence >= 0.3 {
            let e = max(2, f.eye)
            let x0 = max(0, Int(f.x - e * 0.8)), x1 = min(w, Int(f.x + e * 0.8))
            let y0 = max(0, Int(f.y - e * 0.3)), y1 = min(h, Int(f.y + e * 1.3))
            if x1 - x0 >= 3 && y1 - y0 >= 3 { boxes.append([x0, y0, x1, y1]) }
        }
        return boxes
    }

    static func percentile(_ image: PixelImage, _ p: Double) -> Double {
        var hist = [Int](repeating: 0, count: 256)
        for c in image.pixels { hist[min(255, Int(luminance255(c).rounded()))] += 1 }
        let need = Int((Double(image.pixels.count) * p).rounded(.up))
        var acc = 0
        for i in 0..<256 {
            acc += hist[i]
            if acc >= need { return Double(i) }
        }
        return 255
    }

    /// Ajusta `settings.exposure` olhando a foto JÁ COM o preset (prévia pequena), não a original:
    /// - Clara demais: rosto estourado ou muita área que estava com detalhe e estourou. Escurece o mínimo necessário.
    ///   Um preset claro de propósito continua claro; só não deixa perder detalhe no rosto.
    /// - Escura demais: rosto muito escuro. Clareia até o rosto ficar visível, sem estourar nada.
    /// Foto praticamente preta ou toda branca não tem correção: volta em `unrecoverable`.
    /// `faces` recebe a prévia revelada e devolve os rostos encontrados nela.
    public static func fixExposure(_ small: PixelImage, settings s: inout Develop.Settings,
                                   faces finder: ((PixelImage) -> [Face])?) -> Exposure {
        var r = Exposure()
        let p95 = percentile(small, 0.95), p05 = percentile(small, 0.05)
        if p95 < 20 { r.unrecoverable = "Foto escura demais (quase preta)"; return r }
        if p05 > 247 { r.unrecoverable = "Foto estourada (quase toda branca)"; return r }
        let src = Develop.Source(small)
        let base = s.exposure
        let out = src.render(s)
        let boxes = faceBoxes(finder?(out) ?? [], small.width, small.height)
        let before = Light.of(small, boxes), now = Light.of(out, boxes)
        let clipLimit = max(0.06, before.clip * 1.5 + 0.03), faceClipLimit = max(0.12, before.faceClip + 0.05)
        let faces = now.faceMean >= 0
        func light(_ exposure: Double) -> Light {
            var t = s
            t.exposure = limit(exposure, -5, 5)
            return Light.of(src.render(t), boxes)
        }
        func tooBright(_ m: Light, _ faces: Bool) -> Bool {
            m.clip > clipLimit || (faces && (m.faceClip > faceClipLimit || m.faceMean > 222))
        }
        if tooBright(now, faces) {
            // Menor escurecimento que resolve (busca binária entre 0 e −2 EV).
            var ok = Double.nan, lo = -2.0, hi = 0.0
            let atLo = light(base + lo)
            if !tooBright(atLo, faces) {
                ok = lo
                for _ in 0..<6 {
                    let mid = (lo + hi) / 2
                    if tooBright(light(base + mid), faces) { hi = mid } else { ok = mid; lo = mid }
                }
            } else if atLo.clip < now.clip * 0.5 || (faces && atLo.faceClip < now.faceClip * 0.5) {
                ok = lo
            }
            if !ok.isNaN {
                r.ev = (ok * 20).rounded(.down) / 20
                let why = faces && now.faceClip > faceClipLimit ? "rosto estourado depois do preset"
                    : faces && now.faceMean > 222 ? "rosto claro demais depois do preset" : "luz estourada depois do preset"
                r.note = String(format: "Exposição %+.2f EV: ", locale: Locale(identifier: "en_US_POSIX"), r.ev) + why
            }
        } else if faces && now.faceMean < 55 {
            // Rosto muito escuro: menor clareamento que deixa o rosto visível (~80) sem estourar.
            let target = 80.0
            var ok = Double.nan, lo = 0.0, hi = 1.2
            let atHi = light(base + hi)
            if atHi.faceMean >= target && !tooBright(atHi, true) {
                ok = hi
                for _ in 0..<6 {
                    let mid = (lo + hi) / 2
                    let m = light(base + mid)
                    if m.faceMean >= target && !tooBright(m, true) { ok = mid; hi = mid } else { lo = mid }
                }
            } else if !tooBright(atHi, true) {
                ok = hi
            }
            if !ok.isNaN && ok > 0 {
                r.ev = (ok * 20).rounded(.up) / 20
                r.note = String(format: "Exposição %+.2f EV: rosto escuro demais", locale: Locale(identifier: "en_US_POSIX"), r.ev)
            }
        }
        s.exposure = limit(base + r.ev, -5, 5)
        return r
    }

    /// Correção de exposição só no automático: desligada com o automático desligado e em fotos com ajuste manual.
    public static func exposureFix(_ options: EditOptions) -> Bool {
        options.auto && (options.manual?.isNeutral ?? true) && (options.extra?.isEmpty ?? true)
    }

    // MARK: Decisão final

    public struct Decision: Equatable {
        public var review: Bool
        public var score: Int
        public var reason: String
    }

    /// Decisão final. Vai para Revisão só o que não tem conserto na edição: desfocada (nenhuma região nítida na foto
    /// original), rosto principal cortado, foto praticamente preta ou branca. Luz, contraste e cores fortes não reprovam
    /// (a exposição já foi corrigida depois do preset). Duplicata só reprova na sensibilidade Alta; nas outras vira nota.
    /// `sharp` e `exposure` podem ser nil (fotos antigas, sem a original).
    public static func decide(tech: Curator.Result, faceReview: Bool, faceNote: String, sharp: Sharpness?, exposure: Exposure?,
                              duplicate: Bool, sensitivity rawSensitivity: Int) -> Decision {
        let s = max(0, min(2, rawSensitivity))
        let isBlurry = sharp.map { blurry($0, sensitivity: s) } ?? false
        let dark = !(exposure?.unrecoverable.isEmpty ?? true)
        var problems: [String] = [], notes: [String] = []
        if isBlurry { problems.append("Desfocada") }
        if faceReview { problems.append(faceNote) } else if !faceNote.isEmpty { notes.append(faceNote) }
        if dark, let exposure { problems.append(exposure.unrecoverable) }
        if duplicate { if s >= 2 { problems.append("Possível duplicata de outra foto") } else { notes.append("Parecida com outra foto") } }
        if let exposure, !exposure.note.isEmpty { notes.append(exposure.note) }
        // Nota informativa (1–10): nitidez, rosto cortado, exposição e duplicata pesam; luz e contraste pesam pouco.
        var score = 10.0
        if let sharp, sharp.known { score -= min(5, max(0, (blurLimit[1] + 0.5 - sharp.best) * 5)) }
        if faceReview { score -= 2 }
        if dark { score -= 4 }
        if duplicate { score -= 1 }
        score -= min(1.5, abs(tech.brightness - 128) / 90)
        if tech.clipping > 0.25 { score -= min(1, (tech.clipping - 0.25) * 3) }
        let final = max(1, min(10, Int(score.rounded())))
        let tail = notes.isEmpty ? "" : " · " + notes.joined(separator: " · ")
        let reason = problems.isEmpty ? "Aprovada · nota \(final)/10" + tail : problems.joined(separator: " · ") + tail
        return Decision(review: !problems.isEmpty, score: final, reason: reason)
    }
}
