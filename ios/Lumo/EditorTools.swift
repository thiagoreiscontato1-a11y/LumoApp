import SwiftUI

/// Curva de tons de pontos, como a do Lightroom: toque na linha cria um ponto, arraste para mover, arraste para fora
/// (ou toque duas vezes) para apagar. Pontos em 0–255 ({x0, y0, x1, y1…}); a linha usa a spline do motor.
struct ToneCurveEditor: View {
    let points: [Double]
    let color: Color
    let histogram: Histogram?
    let onChange: ([Double]) -> Void

    @State private var working: [Double] = []
    @State private var dragging: Int?
    @State private var began = false
    @State private var ignore = false
    @State private var lastTap = Date.distantPast
    private let pad: CGFloat = 10

    var body: some View {
        GeometryReader { geo in
            let size = geo.size
            Canvas { context, _ in draw(context, size) }
                .contentShape(Rectangle())
                .gesture(DragGesture(minimumDistance: 0).onChanged { v in changed(v, size) }.onEnded { _ in ended() })
        }
        .aspectRatio(1, contentMode: .fit)
        .background(Color.black, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        .accessibilityLabel("Curva de tons. Toque na linha para criar um ponto e arraste para ajustar")
    }

    private var shown: [Double] { began ? working : (points.count >= 4 ? points : [0, 0, 255, 255]) }
    private func gx(_ x: Double, _ s: CGSize) -> CGFloat { pad + CGFloat(x / 255) * (s.width - 2 * pad) }
    private func gy(_ y: Double, _ s: CGSize) -> CGFloat { s.height - pad - CGFloat(y / 255) * (s.height - 2 * pad) }
    private func vx(_ x: CGFloat, _ s: CGSize) -> Double { max(0, min(255, Double((x - pad) / (s.width - 2 * pad)) * 255)) }
    private func vy(_ y: CGFloat, _ s: CGSize) -> Double { max(0, min(255, Double((s.height - pad - y) / (s.height - 2 * pad)) * 255)) }
    private func curveY(_ pts: [Double], _ x: Double) -> Double { 255 * Develop.spline(pts.map { $0 / 255 }, x / 255) }

    private func draw(_ c: GraphicsContext, _ s: CGSize) {
        if let histogram, histogram.total > 0 {
            let lv = histogram.levels()
            var p = Path()
            p.move(to: CGPoint(x: gx(0, s), y: gy(0, s)))
            for i in 0..<256 { p.addLine(to: CGPoint(x: gx(Double(i), s), y: gy(255 * max(lv[0][i], max(lv[1][i], lv[2][i])) * 0.85, s))) }
            p.addLine(to: CGPoint(x: gx(255, s), y: gy(0, s)))
            p.closeSubpath()
            c.fill(p, with: .color(.white.opacity(0.13)))
        }
        var grid = Path()
        for i in 1..<4 {
            let x = pad + (s.width - 2 * pad) * CGFloat(i) / 4, y = pad + (s.height - 2 * pad) * CGFloat(i) / 4
            grid.move(to: CGPoint(x: x, y: pad)); grid.addLine(to: CGPoint(x: x, y: s.height - pad))
            grid.move(to: CGPoint(x: pad, y: y)); grid.addLine(to: CGPoint(x: s.width - pad, y: y))
        }
        c.stroke(grid, with: .color(.white.opacity(0.15)), lineWidth: 1)
        var diag = Path()
        diag.move(to: CGPoint(x: gx(0, s), y: gy(0, s))); diag.addLine(to: CGPoint(x: gx(255, s), y: gy(255, s)))
        c.stroke(diag, with: .color(.white.opacity(0.25)), lineWidth: 1)
        let pts = shown
        var line = Path()
        for i in 0...128 {
            let x = Double(i) / 128 * 255, pt = CGPoint(x: gx(x, s), y: gy(curveY(pts, x), s))
            if i == 0 { line.move(to: pt) } else { line.addLine(to: pt) }
        }
        c.stroke(line, with: .color(color), lineWidth: 2)
        for i in stride(from: 0, to: pts.count, by: 2) {
            let r = CGRect(x: gx(pts[i], s) - 6, y: gy(pts[i + 1], s) - 6, width: 12, height: 12)
            c.fill(Path(ellipseIn: r), with: .color(i / 2 == dragging ? color : .black))
            c.stroke(Path(ellipseIn: r), with: .color(color), lineWidth: 2)
        }
    }

    private func nearest(_ p: CGPoint, _ pts: [Double], _ s: CGSize) -> Int? {
        var best: Int?, bd: CGFloat = 24
        for i in stride(from: 0, to: pts.count, by: 2) {
            let d = hypot(gx(pts[i], s) - p.x, gy(pts[i + 1], s) - p.y)
            if d < bd { bd = d; best = i / 2 }
        }
        return best
    }

    private func remove(_ k: Int) {
        guard working.count > 4, k > 0, k < working.count / 2 - 1 else { return }
        working.removeSubrange((2 * k)...(2 * k + 1))
    }

    private func changed(_ v: DragGesture.Value, _ s: CGSize) {
        if !began {
            began = true
            ignore = false
            working = points.count >= 4 ? points : [0, 0, 255, 255]
            if let k = nearest(v.startLocation, working, s) {
                if Date().timeIntervalSince(lastTap) < 0.32 {
                    remove(k); lastTap = .distantPast; ignore = true; dragging = nil; onChange(working); return
                }
                lastTap = Date()
                dragging = k
            } else {
                lastTap = Date()
                let x = vx(v.startLocation.x, s), y = curveY(working, x)
                guard abs(gy(y, s) - v.startLocation.y) <= 28 else { ignore = true; return }
                var k = 0
                while k < working.count / 2 && working[2 * k] < x { k += 1 }
                if k < working.count / 2 && abs(working[2 * k] - x) < 1 { ignore = true; return }
                working.insert(contentsOf: [x, y], at: 2 * k)
                dragging = k
                onChange(working)
            }
        }
        guard !ignore, let k = dragging, hypot(v.translation.width, v.translation.height) > 4 else { return }
        let last = working.count / 2 - 1
        // Arrastar um ponto do meio para bem longe da área apaga o ponto, como no Lightroom.
        if k > 0 && k < last && (v.location.y < -36 || v.location.y > s.height + 36) {
            remove(k); dragging = nil; ignore = true; onChange(working); return
        }
        let lo = k == 0 ? 0 : working[2 * (k - 1)] + 1, hi = k == last ? 255 : working[2 * (k + 1)] - 1
        working[2 * k] = max(lo, min(hi, vx(v.location.x, s)))
        working[2 * k + 1] = vy(v.location.y, s)
        onChange(working)
    }

    private func ended() {
        began = false
        ignore = false
        dragging = nil
    }
}

/// Roda de cor da Gradação de cores: o ângulo é a matiz (0–360, vermelho à direita, girando no sentido anti-horário)
/// e a distância do centro é a saturação (0–100). Toque duas vezes para zerar a saturação.
struct ColorWheel: View {
    let hue: Double
    let sat: Double
    let onChange: (Double, Double) -> Void
    @State private var began = false
    @State private var lastTap = Date.distantPast

    var body: some View {
        GeometryReader { geo in
            let s = geo.size, r = min(s.width, s.height) / 2 - 10, c = CGPoint(x: s.width / 2, y: s.height / 2)
            ZStack {
                // AngularGradient gira no sentido horário na tela; a matiz cresce no anti-horário.
                Circle()
                    .fill(AngularGradient(colors: (0...12).map { Color(hue: Double((360 - $0 * 30) % 360) / 360, saturation: 0.85, brightness: 0.9) },
                                          center: .center))
                    .frame(width: 2 * r, height: 2 * r)
                Circle()
                    .fill(RadialGradient(colors: [Color(white: 0.42), Color(white: 0.42).opacity(0)], center: .center, startRadius: 0, endRadius: r))
                    .frame(width: 2 * r, height: 2 * r)
                Circle().stroke(Color.white.opacity(0.33), lineWidth: 1).frame(width: 2 * r, height: 2 * r)
                Circle().stroke(Color.white.opacity(0.33), lineWidth: 1).frame(width: r, height: r)
                Circle()
                    .stroke(Color.white, lineWidth: 2)
                    .background(Circle().stroke(Color.black, lineWidth: 4))
                    .frame(width: 16, height: 16)
                    .position(x: c.x + CGFloat(cos(hue * .pi / 180) * sat / 100) * r, y: c.y - CGFloat(sin(hue * .pi / 180) * sat / 100) * r)
            }
            .frame(width: s.width, height: s.height)
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0).onChanged { v in
                if !began {
                    began = true
                    if Date().timeIntervalSince(lastTap) < 0.32 { lastTap = .distantPast; onChange(hue, 0); return }
                    lastTap = Date()
                }
                let dx = Double(v.location.x - c.x), dy = Double(c.y - v.location.y)
                var h = atan2(dy, dx) * 180 / .pi
                if h < 0 { h += 360 }
                onChange(h.rounded().truncatingRemainder(dividingBy: 360), min(100, hypot(dx, dy) / Double(r) * 100).rounded())
            }.onEnded { _ in began = false })
        }
        .aspectRatio(1, contentMode: .fit)
        .frame(maxWidth: 220)
        .accessibilityLabel(String(format: "Roda de cor. Matiz %.0f, saturação %.0f", hue, sat))
    }
}

/// Moldura do modo Corte por cima da foto inteira (já girada): cantos e bordas recortam, o meio move.
/// O recorte nunca sai da foto girada; com proporção travada, os cantos mantêm a proporção.
struct CropFrame: View {
    let crop: Crop
    let imageSize: CGSize
    let aspect: Double
    let onChange: (Crop) -> Void

    @State private var start: Crop?
    @State private var mode = -1
    static let move = 0, l = 1, t = 2, r = 4, b = 8

    var body: some View {
        GeometryReader { geo in
            let img = fitted(geo.size), box = rect(crop, img)
            ZStack {
                Path { p in p.addRect(CGRect(origin: .zero, size: geo.size)); p.addRect(box) }
                    .fill(Color.black.opacity(0.66), style: FillStyle(eoFill: true))
                Path { p in
                    for i in 1..<3 {
                        let x = box.minX + box.width * CGFloat(i) / 3, y = box.minY + box.height * CGFloat(i) / 3
                        p.move(to: CGPoint(x: x, y: box.minY)); p.addLine(to: CGPoint(x: x, y: box.maxY))
                        p.move(to: CGPoint(x: box.minX, y: y)); p.addLine(to: CGPoint(x: box.maxX, y: y))
                    }
                }
                .stroke(Color.white.opacity(0.5), lineWidth: 1)
                Path { p in p.addRect(box) }.stroke(Color.white, lineWidth: 1.5)
                Path { p in
                    let k: CGFloat = 18
                    for (x, y, sx, sy) in [(box.minX, box.minY, 1.0, 1.0), (box.maxX, box.minY, -1.0, 1.0), (box.minX, box.maxY, 1.0, -1.0), (box.maxX, box.maxY, -1.0, -1.0)] {
                        p.move(to: CGPoint(x: x + CGFloat(sx) * k, y: y)); p.addLine(to: CGPoint(x: x, y: y)); p.addLine(to: CGPoint(x: x, y: y + CGFloat(sy) * k))
                    }
                }
                .stroke(Color.white, lineWidth: 4)
            }
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0).onChanged { v in drag(v, img) }.onEnded { _ in start = nil; mode = -1 })
        }
        .accessibilityLabel("Moldura de recorte. Arraste os cantos para recortar")
    }

    private var iw: Int { max(1, Int(imageSize.width)) }
    private var ih: Int { max(1, Int(imageSize.height)) }

    /// Área da foto na view (como `scaledToFit`).
    private func fitted(_ s: CGSize) -> CGRect {
        let k = min(s.width / CGFloat(iw), s.height / CGFloat(ih)), w = CGFloat(iw) * k, h = CGFloat(ih) * k
        return CGRect(x: (s.width - w) / 2, y: (s.height - h) / 2, width: w, height: h)
    }
    private func rect(_ c: Crop, _ img: CGRect) -> CGRect {
        CGRect(x: img.minX + CGFloat(c.left) * img.width, y: img.minY + CGFloat(c.top) * img.height,
               width: CGFloat(c.right - c.left) * img.width, height: CGFloat(c.bottom - c.top) * img.height)
    }

    private func hit(_ p: CGPoint, _ box: CGRect) -> Int {
        let r: CGFloat = 28
        var m = 0
        if abs(p.x - box.minX) < r { m |= Self.l } else if abs(p.x - box.maxX) < r { m |= Self.r }
        if abs(p.y - box.minY) < r { m |= Self.t } else if abs(p.y - box.maxY) < r { m |= Self.b }
        let inX = p.x > box.minX - r && p.x < box.maxX + r, inY = p.y > box.minY - r && p.y < box.maxY + r
        if m != 0 && inX && inY { return m }
        return box.contains(p) ? Self.move : -1
    }

    /// Recorte proposto pelo arraste (dx, dy em fração da foto) a partir de `start`.
    private func proposal(_ s: Crop, _ m: Int, _ dx: Double, _ dy: Double) -> Crop {
        var l = s.left, t = s.top, r = s.right, b = s.bottom
        if m == Self.move {
            l += dx; r += dx; t += dy; b += dy
            if l < 0 { r -= l; l = 0 }
            if r > 1 { l -= r - 1; r = 1 }
            if t < 0 { b -= t; t = 0 }
            if b > 1 { t -= b - 1; b = 1 }
            return Crop(angle: s.angle, left: l, top: t, right: r, bottom: b)
        }
        if m & Self.l != 0 { l += dx }
        if m & Self.r != 0 { r += dx }
        if m & Self.t != 0 { t += dy }
        if m & Self.b != 0 { b += dy }
        let minSide = 0.05
        if r - l < minSide { if m & Self.l != 0 { l = r - minSide } else { r = l + minSide } }
        if b - t < minSide { if m & Self.t != 0 { t = b - minSide } else { b = t + minSide } }
        if aspect > 0 {
            // Proporção em pixels: largura·W / (altura·H) = aspect. Borda sozinha ajusta a outra dimensão em torno do centro.
            let want = aspect * Double(ih) / Double(iw)
            if m & (Self.l | Self.r) != 0 && m & (Self.t | Self.b) != 0 {
                var w = r - l, hh = b - t
                if w / hh > want { hh = w / want } else { w = hh * want }
                if m & Self.l != 0 { l = r - w } else { r = l + w }
                if m & Self.t != 0 { t = b - hh } else { b = t + hh }
            } else if m & (Self.l | Self.r) != 0 {
                let hh = (r - l) / want, cy = (t + b) / 2
                t = cy - hh / 2; b = cy + hh / 2
            } else {
                let w = (b - t) * want, cx = (l + r) / 2
                l = cx - w / 2; r = cx + w / 2
            }
        }
        return Crop(angle: s.angle, left: l, top: t, right: r, bottom: b)
    }

    private func ok(_ c: Crop, _ raw: (Double, Double, Double, Double)) -> Bool {
        raw.0 >= -1e-9 && raw.1 >= -1e-9 && raw.2 <= 1 + 1e-9 && raw.3 <= 1 + 1e-9 && Crop.fits(c.left, c.top, c.right, c.bottom, iw, ih, c.angle)
    }

    private func drag(_ v: DragGesture.Value, _ img: CGRect) {
        if start == nil {
            mode = hit(v.startLocation, rect(crop, img))
            start = crop
        }
        guard let s = start, mode >= 0 else { return }
        let dx = Double(v.translation.width / img.width), dy = Double(v.translation.height / img.height)
        // Maior parte do arraste que ainda cabe na foto girada.
        func candidate(_ k: Double) -> Crop? {
            let c = proposal(s, mode, dx * k, dy * k)
            return ok(c, (c.left, c.top, c.right, c.bottom)) ? c : nil
        }
        var best = candidate(1)
        if best == nil {
            var lo = 0.0, hi = 1.0
            for _ in 0..<18 {
                let m = (lo + hi) / 2
                if let c = candidate(m) { lo = m; best = c } else { hi = m }
            }
        }
        if let best { onChange(best) }
    }
}
