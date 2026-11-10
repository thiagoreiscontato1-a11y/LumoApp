import LocalAuthentication
import SwiftUI
import UIKit

/// Bloqueio com Face ID / Touch ID (ou o código do iPhone), como o bloqueio com digital do Android 0.17.0:
/// pede ao abrir e ao voltar depois de 5 minutos fora. Só a tela é bloqueada: captura, edição e envio continuam.
@MainActor
final class LumoLock: ObservableObject {
    static let away: TimeInterval = 5 * 60

    @Published private(set) var locked = false {
        didSet { if locked != oldValue { locked ? showWindow() : hideWindow() } }
    }
    /// Janela própria acima de tudo (painéis e telas cheias também ficam cobertos).
    private var window: UIWindow?
    @Published private(set) var failure = ""
    private var leftAt: Date?
    private var asking = false

    /// Nome do recurso do aparelho ("Face ID", "Touch ID" ou "código"), para os textos.
    static var biometryName: String {
        let context = LAContext()
        _ = context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil)
        switch context.biometryType {
        case .faceID: return "Face ID"
        case .touchID: return "Touch ID"
        default: return "código do iPhone"
        }
    }

    /// O aparelho tem Face ID/Touch ID ou pelo menos um código configurado.
    static var available: Bool { LAContext().canEvaluatePolicy(.deviceOwnerAuthentication, error: nil) }

    func appeared(enabled: Bool) {
        guard enabled, Self.available, !DemoMode.enabled else { return }
        locked = true
        unlock()
    }

    func phaseChanged(_ phase: ScenePhase, enabled: Bool) {
        switch phase {
        case .background:
            if !locked { leftAt = Date() }
        case .active:
            guard enabled, Self.available, !DemoMode.enabled else { locked = false; return }
            if let leftAt, Date().timeIntervalSince(leftAt) >= Self.away { locked = true }
            self.leftAt = nil
            if locked { unlock() }
        default:
            break
        }
    }

    /// Bloqueio desligado em Ajustes: libera a tela na hora.
    func disabled() { locked = false; leftAt = nil }

    private func showWindow() {
        guard window == nil, let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first else { return }
        let w = UIWindow(windowScene: scene)
        w.windowLevel = .alert + 1
        let host = UIHostingController(rootView: LockScreen(lock: self))
        host.view.backgroundColor = .clear
        w.rootViewController = host
        w.makeKeyAndVisible()
        window = w
    }

    private func hideWindow() {
        window?.isHidden = true
        window = nil
    }

    func unlock() {
        guard locked, !asking else { return }
        asking = true
        failure = ""
        let context = LAContext()
        context.localizedCancelTitle = "Agora não"
        context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: "Desbloquear o Lumo") { ok, error in
            Task { @MainActor in
                self.asking = false
                if ok { self.locked = false } else { self.failure = (error as? LAError)?.code == .userCancel ? "" : (error?.localizedDescription ?? "") }
            }
        }
    }
}

/// Tela de bloqueio por cima do app.
struct LockScreen: View {
    @ObservedObject var lock: LumoLock

    var body: some View {
        ZStack {
            Lumo.background.ignoresSafeArea()
            VStack(spacing: 18) {
                Image(systemName: "lock.fill").font(.system(size: 44, weight: .semibold)).foregroundStyle(Lumo.cyan)
                Text("Lumo bloqueado").font(.title2.weight(.bold)).foregroundStyle(.white)
                Text("A captura, a edição e o envio continuam em segundo plano.")
                    .font(.callout).foregroundStyle(Lumo.secondary).multilineTextAlignment(.center)
                if !lock.failure.isEmpty { Text(lock.failure).font(.caption).foregroundStyle(Lumo.danger).multilineTextAlignment(.center) }
                Button { lock.unlock() } label: {
                    Label("Desbloquear com \(LumoLock.biometryName)", systemImage: "faceid")
                        .font(.headline).foregroundStyle(.black)
                        .padding(.horizontal, 22).padding(.vertical, 14)
                        .background(Lumo.cyan, in: Capsule())
                }
                .buttonStyle(.plain)
            }
            .padding(32)
        }
    }
}
