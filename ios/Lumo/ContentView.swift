import SwiftUI

/// Fluxo principal: Captura → Galeria → Revisão → Entrega.
struct ContentView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var capture: CaptureController

    private var showingMessage: Binding<Bool> {
        Binding(get: { model.message != nil }, set: { if !$0 { model.message = nil } })
    }

    var body: some View {
        VStack(spacing: 0) {
            HeaderView()
            TabView {
                CaptureView()
                    .tabItem { Label("Captura", systemImage: "camera") }
                GalleryView()
                    .tabItem { Label("Galeria", systemImage: "photo.on.rectangle") }
                ReviewView()
                    .tabItem { Label("Revisão", systemImage: "exclamationmark.triangle") }
                    .badge(model.underReview.count)
                DeliveryView()
                    .tabItem { Label("Entrega", systemImage: "icloud.and.arrow.up") }
                SettingsView()
                    .tabItem { Label("Ajustes", systemImage: "slider.horizontal.3") }
            }
        }
        .alert("Lumo", isPresented: showingMessage) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(model.message ?? "")
        }
        .onChange(of: capture.phase) { _, phase in
            // Mantém a tela ligada enquanto a câmera estiver conectada.
            UIApplication.shared.isIdleTimerDisabled = phase.keepsScreenOn
        }
    }
}

/// Topo: evento atual + mini-cards com contadores.
struct HeaderView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var capture: CaptureController
    @State private var creating = false
    @State private var newName = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Menu {
                    ForEach(model.events.reversed()) { e in
                        Button {
                            model.select(e)
                        } label: {
                            if e.id == model.currentEvent?.id { Label(e.name, systemImage: "checkmark") } else { Text(e.name) }
                        }
                    }
                    Divider()
                    Button("Novo evento…", systemImage: "plus") { creating = true }
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: "calendar")
                        Text(model.currentEvent?.name ?? "Criar evento").fontWeight(.semibold).lineLimit(1)
                        Image(systemName: "chevron.down").font(.caption)
                    }
                }
                Spacer()
                Text(capture.phase.label)
                    .font(.caption.bold())
                    .padding(.horizontal, 8).padding(.vertical, 4)
                    .background(phaseColor.opacity(0.18), in: Capsule())
                    .foregroundStyle(phaseColor)
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    Chip(title: "RECEBIDAS", value: model.photos.count)
                    Chip(title: "EM EDIÇÃO", value: model.processing + model.pendingEdit)
                    Chip(title: "APROVADAS", value: model.approved.count)
                    Chip(title: "REVISÃO", value: model.underReview.count)
                    Chip(title: "ENVIADAS", value: model.sentCount)
                }
            }
        }
        .padding(.horizontal)
        .padding(.vertical, 8)
        .background(Color(.systemBackground))
        .alert("Novo evento", isPresented: $creating) {
            TextField("Nome do evento", text: $newName)
            Button("Criar") {
                model.createEvent(newName)
                newName = ""
            }
            Button("Cancelar", role: .cancel) { newName = "" }
        } message: {
            Text("As fotos ficam em Eventos/<nome>/Originais, Editadas e Sob Revisao.")
        }
        .onAppear { if model.events.isEmpty { creating = true } }
    }

    var phaseColor: Color {
        switch capture.phase {
        case .connected: return .green
        case .connecting, .searching, .busy: return .orange
        case .failed: return .red
        case .idle: return .secondary
        }
    }
}

struct Chip: View {
    let title: String
    let value: Int

    var body: some View {
        VStack(spacing: 2) {
            Text("\(value)").font(.headline.monospacedDigit())
            Text(title).font(.system(size: 9, weight: .semibold)).foregroundStyle(.secondary)
        }
        .frame(minWidth: 68)
        .padding(.vertical, 6)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 10))
    }
}
