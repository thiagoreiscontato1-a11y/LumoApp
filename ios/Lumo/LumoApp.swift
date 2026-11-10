import SwiftUI

@main
struct LumoApp: App {
    @StateObject private var model: AppModel
    @StateObject private var lock = LumoLock()
    @Environment(\.scenePhase) private var scenePhase

    init() {
        // Antes de tudo: o relatório de falhas precisa estar instalado quando o resto do app começa.
        CrashReporter.shared.install()
        _model = StateObject(wrappedValue: AppModel())
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(model)
                .environmentObject(model.capture)
                .environmentObject(lock)
                .preferredColorScheme(.dark)
                .tint(Lumo.cyan)
                .task { await DemoMode.startIfRequested(model) }
                .onAppear { lock.appeared(enabled: model.settings.lockEnabled) }
        }
        .onChange(of: scenePhase) { _, phase in
            lock.phaseChanged(phase, enabled: model.settings.lockEnabled)
            if phase == .active { model.kickOnline() }
        }
    }
}
