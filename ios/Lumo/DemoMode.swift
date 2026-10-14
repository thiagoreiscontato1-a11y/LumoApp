import SwiftUI
import UIKit

/// Modo de demonstração para capturas de tela no simulador (CI), ativado só por argumento
/// de inicialização (`-LumoDemo`). Cria um evento, simula a câmera conectada e gera fotos
/// sintéticas que passam pelo pipeline real de edição e curadoria.
enum DemoMode {
    static var arguments: [String] { ProcessInfo.processInfo.arguments }
    static var enabled: Bool { arguments.contains("-LumoDemo") }

    static func value(after flag: String) -> String? {
        guard let i = arguments.firstIndex(of: flag), i + 1 < arguments.count else { return nil }
        return arguments[i + 1]
    }

    static var initialSheet: LumoSheet? {
        guard enabled else { return nil }
        switch value(after: "-LumoSheet") {
        case "review": return .review
        case "gallery": return .gallery
        case "camera": return .camera
        case "delivery": return .delivery
        case "events": return .events
        case "settings": return .settings
        default: return nil
        }
    }

    @MainActor
    static func startIfRequested(_ model: AppModel) async {
        guard enabled else { return }
        if arguments.contains("-LumoLandscape"),
           let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene {
            scene.requestGeometryUpdate(.iOS(interfaceOrientations: .landscapeRight)) { _ in }
        }
        let name = "Casamento Ana & Pedro"
        if let existing = model.events.first(where: { $0.name == name }) {
            model.select(existing)
        } else {
            model.createEvent(name)
        }
        model.capture.demoConnected(camera: "Canon EOS R8")
        guard !arguments.contains("-LumoNoPhotos") else { return }
        if model.photos.count >= scenes.count {
            if arguments.contains("-LumoViewer") || arguments.contains("-LumoEditor") { model.demoOpenPhoto = model.latest?.id }
            return
        }
        for (i, scene) in scenes.enumerated() {
            guard let data = render(scene, seed: i) else { continue }
            let incoming = IncomingPhoto(data: data, originalName: String(format: "IMG_%04d.JPG", 4810 + i),
                                         capturedAt: Date().addingTimeInterval(Double(i - scenes.count) * 37),
                                         source: .usb, sourceKey: "demo:\(i)")
            if await model.ingest(incoming) { model.capture.demoReceived() }
        }
        // Duas favoritas, para a galeria mostrar o filtro e as estrelas.
        let favorites = Set(model.recent.prefix(4).enumerated().filter { $0.offset % 2 == 1 }.map { $0.element.id })
        if model.photos.filter(\.favorite).isEmpty { model.toggleFavorite(favorites) }
        if arguments.contains("-LumoViewer") || arguments.contains("-LumoEditor") { model.demoOpenPhoto = model.latest?.id }
    }

    struct Scene {
        var top: UIColor
        var bottom: UIColor
        var lights: UIColor
        var dark = false
    }

    static let scenes: [Scene] = [
        Scene(top: UIColor(red: 0.98, green: 0.62, blue: 0.36, alpha: 1), bottom: UIColor(red: 0.42, green: 0.16, blue: 0.38, alpha: 1), lights: .white),
        Scene(top: UIColor(red: 0.95, green: 0.84, blue: 0.62, alpha: 1), bottom: UIColor(red: 0.55, green: 0.38, blue: 0.22, alpha: 1), lights: UIColor(red: 1, green: 0.9, blue: 0.6, alpha: 1)),
        Scene(top: UIColor(red: 0.20, green: 0.32, blue: 0.62, alpha: 1), bottom: UIColor(red: 0.05, green: 0.06, blue: 0.16, alpha: 1), lights: UIColor(red: 0.6, green: 0.85, blue: 1, alpha: 1)),
        Scene(top: UIColor(red: 0.07, green: 0.06, blue: 0.08, alpha: 1), bottom: UIColor(red: 0.03, green: 0.03, blue: 0.04, alpha: 1), lights: UIColor(white: 0.2, alpha: 1), dark: true),
        Scene(top: UIColor(red: 0.62, green: 0.82, blue: 0.70, alpha: 1), bottom: UIColor(red: 0.18, green: 0.34, blue: 0.24, alpha: 1), lights: .white),
        Scene(top: UIColor(red: 0.99, green: 0.74, blue: 0.70, alpha: 1), bottom: UIColor(red: 0.60, green: 0.28, blue: 0.40, alpha: 1), lights: UIColor(red: 1, green: 0.85, blue: 0.9, alpha: 1)),
        Scene(top: UIColor(red: 0.30, green: 0.18, blue: 0.48, alpha: 1), bottom: UIColor(red: 0.10, green: 0.05, blue: 0.20, alpha: 1), lights: UIColor(red: 1, green: 0.55, blue: 0.95, alpha: 1)),
        Scene(top: UIColor(red: 0.98, green: 0.88, blue: 0.55, alpha: 1), bottom: UIColor(red: 0.85, green: 0.45, blue: 0.20, alpha: 1), lights: .white),
    ]

    /// Cena abstrata de festa: gradiente, luzes desfocadas, confete e duas silhuetas.
    static func render(_ scene: Scene, seed: Int) -> Data? {
        var rng = SplitMix(seed: UInt64(seed + 7))
        let size = CGSize(width: 1800, height: 1200)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let image = UIGraphicsImageRenderer(size: size, format: format).image { ctx in
            let cg = ctx.cgContext
            let colors = [scene.top.cgColor, scene.bottom.cgColor] as CFArray
            if let g = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colors, locations: [0, 1]) {
                cg.drawLinearGradient(g, start: .zero, end: CGPoint(x: 0, y: size.height), options: [])
            }
            // Luzes desfocadas (bokeh).
            for _ in 0..<38 {
                let r = CGFloat(rng.next(18, 80))
                let c = CGPoint(x: rng.next(0, Double(size.width)), y: rng.next(0, Double(size.height) * 0.65))
                for step in stride(from: 1.0, to: 0.0, by: -0.2) {
                    scene.lights.withAlphaComponent(0.06).setFill()
                    let rr = r * CGFloat(step)
                    cg.fillEllipse(in: CGRect(x: c.x - rr, y: c.y - rr, width: rr * 2, height: rr * 2))
                }
            }
            // Confete nítido (dá textura para a curadoria).
            if !scene.dark {
                for _ in 0..<750 {
                    let hue = CGFloat(rng.next(0, 1))
                    UIColor(hue: hue, saturation: 0.6, brightness: rng.next(0, 1) > 0.5 ? 1 : 0.35, alpha: 1).setFill()
                    cg.saveGState()
                    cg.translateBy(x: rng.next(0, Double(size.width)), y: rng.next(0, Double(size.height)))
                    cg.rotate(by: rng.next(0, .pi))
                    cg.fill(CGRect(x: -11, y: -4, width: 22, height: 8))
                    cg.restoreGState()
                }
            }
            // Duas silhuetas.
            let silhouette = scene.dark ? UIColor(white: 0.02, alpha: 1) : UIColor(red: 0.06, green: 0.05, blue: 0.08, alpha: 0.92)
            silhouette.setFill()
            let base = size.height
            for (dx, h) in [(-150.0, 0.62), (130.0, 0.56)] {
                let cx = size.width / 2 + CGFloat(dx) + CGFloat(rng.next(-60, 60))
                let height = base * CGFloat(h)
                let head = height * 0.16
                cg.fillEllipse(in: CGRect(x: cx - head / 2, y: base - height, width: head, height: head * 1.15))
                let body = UIBezierPath(roundedRect: CGRect(x: cx - head * 1.05, y: base - height + head * 1.05, width: head * 2.1, height: height),
                                        cornerRadius: head * 0.8)
                body.fill()
            }
        }
        return image.jpegData(compressionQuality: 0.9)
    }

    /// Gerador pseudoaleatório determinístico (as capturas saem sempre iguais).
    struct SplitMix {
        var state: UInt64
        init(seed: UInt64) { state = seed }

        mutating func next() -> UInt64 {
            state &+= 0x9E37_79B9_7F4A_7C15
            var z = state
            z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
            z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
            return z ^ (z >> 31)
        }

        mutating func next(_ lo: Double, _ hi: Double) -> Double {
            lo + (hi - lo) * Double(next() >> 11) / Double(1 << 53)
        }
    }
}
