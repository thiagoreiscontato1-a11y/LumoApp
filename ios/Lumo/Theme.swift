import SwiftUI
import UIKit

/// Identidade visual do Lumo para eventos: sempre escuro, as fotos dão a cor,
/// azul/ciano do logo como destaque e estados com cores inequívocas.
enum Lumo {
    static let background = Color(red: 0.027, green: 0.035, blue: 0.047)      // #07090C
    static let surface = Color.white.opacity(0.06)
    static let surfaceStrong = Color.white.opacity(0.11)
    static let stroke = Color.white.opacity(0.09)
    static let secondary = Color.white.opacity(0.62)
    static let tertiary = Color.white.opacity(0.38)

    static let blue = Color(red: 0.043, green: 0.486, blue: 1.0)              // #0B7CFF
    static let cyan = Color(red: 0.133, green: 0.875, blue: 0.953)            // #22DFF3
    static let indigo = Color(red: 0.141, green: 0.275, blue: 0.847)          // #2446D8
    static let ok = Color(red: 0.20, green: 0.86, blue: 0.52)
    static let warn = Color(red: 1.0, green: 0.73, blue: 0.22)
    static let danger = Color(red: 1.0, green: 0.33, blue: 0.36)

    static let gradient = LinearGradient(colors: [blue, cyan], startPoint: .bottomLeading, endPoint: .topTrailing)

    static let radius: CGFloat = 22
}

extension CaptureController.Phase {
    var color: Color {
        switch self {
        case .connected: return Lumo.ok
        case .connecting, .searching, .busy: return Lumo.warn
        case .failed: return Lumo.danger
        case .idle: return Lumo.tertiary
        }
    }

    /// Texto curto para leitura de relance.
    var short: String {
        switch self {
        case .idle: return "Desconectada"
        case .searching: return "Procurando"
        case .connecting: return "Conectando"
        case .connected: return "Conectada"
        case .busy: return "Ocupada"
        case .failed: return "Sem conexão"
        }
    }
}

/// Cartão translúcido usado em toda a interface.
struct LumoCard<Content: View>: View {
    var padding: CGFloat = 14
    @ViewBuilder var content: Content

    var body: some View {
        content
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Lumo.surface, in: RoundedRectangle(cornerRadius: Lumo.radius, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: Lumo.radius, style: .continuous).stroke(Lumo.stroke, lineWidth: 1))
    }
}

/// Botão principal: grande, ao alcance do polegar.
struct LumoPrimaryButtonStyle: ButtonStyle {
    var tint: AnyShapeStyle = AnyShapeStyle(Lumo.gradient)

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(.headline, design: .rounded).weight(.semibold))
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity, minHeight: 58)
            .background(tint, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .opacity(configuration.isPressed ? 0.85 : 1)
            .animation(.spring(duration: 0.2), value: configuration.isPressed)
    }
}

/// Botão quadrado secundário (importar, galeria…).
struct LumoIconButtonStyle: ButtonStyle {
    var size: CGFloat = 58

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 20, weight: .semibold))
            .foregroundStyle(.white)
            .frame(width: size, height: size)
            .background(Lumo.surfaceStrong, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(Lumo.stroke, lineWidth: 1))
            .scaleEffect(configuration.isPressed ? 0.94 : 1)
            .animation(.spring(duration: 0.2), value: configuration.isPressed)
    }
}

/// Ponto de estado que pulsa enquanto a câmera está conectada.
struct PulseDot: View {
    let color: Color
    var active: Bool
    @State private var pulse = false

    var body: some View {
        ZStack {
            if active {
                Circle().fill(color.opacity(0.35))
                    .frame(width: 18, height: 18)
                    .scaleEffect(pulse ? 1.25 : 0.6)
                    .opacity(pulse ? 0 : 1)
            }
            Circle().fill(color).frame(width: 9, height: 9)
        }
        .frame(width: 18, height: 18)
        .onAppear { pulse = false; withAnimation(.easeOut(duration: 1.4).repeatForever(autoreverses: false)) { pulse = true } }
    }
}

/// Anel do logo, usado nos estados vazios.
struct LumoRing: View {
    var size: CGFloat = 120
    @State private var spin = false

    var body: some View {
        ZStack {
            Circle().stroke(Lumo.blue.opacity(0.25), lineWidth: size * 0.16)
            Circle()
                .trim(from: 0, to: 0.62)
                .stroke(AngularGradient(colors: [Lumo.indigo, Lumo.blue, Lumo.cyan], center: .center),
                        style: StrokeStyle(lineWidth: size * 0.16, lineCap: .round))
                .rotationEffect(.degrees(spin ? 360 : 0))
        }
        .frame(width: size, height: size)
        .onAppear { withAnimation(.linear(duration: 6).repeatForever(autoreverses: false)) { spin = true } }
    }
}

/// Fundo e listas escuras padronizadas para todas as telas secundárias.
extension View {
    func lumoScreen() -> some View {
        self.scrollContentBackground(.hidden)
            .background(Lumo.background.ignoresSafeArea())
            .toolbarBackground(Lumo.background, for: .navigationBar)
    }
}

/// Feedback tátil: o fotógrafo sente a foto chegando sem olhar.
enum Haptics {
    static func arrived() { UINotificationFeedbackGenerator().notificationOccurred(.success) }
    static func warning() { UINotificationFeedbackGenerator().notificationOccurred(.warning) }
    static func error() { UINotificationFeedbackGenerator().notificationOccurred(.error) }
    static func tap() { UIImpactFeedbackGenerator(style: .light).impactOccurred() }
}

/// Botão "OK" para fechar sheets.
struct DoneButton: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        Button("OK") { dismiss() }.fontWeight(.semibold)
    }
}
