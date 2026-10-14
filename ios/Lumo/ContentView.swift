import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

/// Painéis que abrem por cima da tela ao vivo.
enum LumoSheet: Identifiable, Hashable {
    case events, camera, review, gallery, delivery, settings
    case photo(String)

    var id: String {
        switch self {
        case .photo(let id): return "photo-" + id
        default: return String(describing: self)
        }
    }
}

/// Tela única "ao vivo": a última foto em destaque, estado de relance e uma ação principal.
struct ContentView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var capture: CaptureController
    @State private var sheet: LumoSheet?
    @State private var showTour = false

    private var showingMessage: Binding<Bool> {
        Binding(get: { model.message != nil }, set: { if !$0 { model.message = nil } })
    }

    /// Fotos abrem no visualizador em tela cheia; o resto em painéis.
    private var panelBinding: Binding<LumoSheet?> {
        Binding(get: { if case .photo = sheet { return nil }; return sheet }, set: { sheet = $0 })
    }

    private var viewerBinding: Binding<ViewerContext?> {
        Binding(get: {
            if case .photo(let id) = sheet { return ViewerContext(ids: model.recent.map(\.id), start: id) }
            return nil
        }, set: { if $0 == nil { sheet = nil } })
    }

    var body: some View {
        LiveView(sheet: $sheet)
            .background(Lumo.background.ignoresSafeArea())
            .sheet(item: panelBinding) { sheet in
                sheetContent(sheet)
                    .presentationDragIndicator(.visible)
                    .presentationBackground(Lumo.background)
            }
            .fullScreenCover(item: viewerBinding) { ctx in PhotoViewer(ids: ctx.ids, start: ctx.start) }
            .fullScreenCover(isPresented: $showTour, onDismiss: {
                model.settings.tourSeen = true
                if model.events.isEmpty { sheet = .events }
            }) { TourView() }
            .alert("Lumo", isPresented: showingMessage) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(model.message ?? "")
            }
            .onChange(of: capture.phase) { old, phase in
                // Mantém a tela ligada enquanto a câmera estiver conectada.
                UIApplication.shared.isIdleTimerDisabled = model.settings.eventMode || phase.keepsScreenOn
                if case .failed = phase, old != phase { Haptics.error() }
            }
            .onChange(of: model.demoOpenPhoto) { _, id in if let id { sheet = .photo(id) } }
            .onChange(of: model.photos.count) { old, new in
                if new > old { Haptics.arrived() }
            }
            .onChange(of: model.underReview.count) { old, new in
                if new > old { Haptics.warning() }
            }
            .onAppear {
                UIApplication.shared.isIdleTimerDisabled = model.settings.eventMode
                if !model.settings.tourSeen && !DemoMode.enabled { showTour = true }
                else if model.events.isEmpty && !DemoMode.enabled { sheet = .events }
                if let start = DemoMode.initialSheet { sheet = start }
            }
    }

    @ViewBuilder
    private func sheetContent(_ sheet: LumoSheet) -> some View {
        switch sheet {
        case .events: EventsSheet()
        case .camera: CameraSheet()
        case .review: ReviewSheet()
        case .gallery: GallerySheet()
        case .delivery: DeliveryView()
        case .settings: SettingsView()
        case .photo:
            EmptyView()
        }
    }
}

struct LiveView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var capture: CaptureController
    @Binding var sheet: LumoSheet?

    var body: some View {
        GeometryReader { geo in
            if geo.size.width > geo.size.height {
                // Deitado (iPhone no suporte da câmera): foto à esquerda, controles à direita.
                HStack(spacing: 14) {
                    HeroView(sheet: $sheet)
                    VStack(spacing: 12) {
                        TopBar(sheet: $sheet, compact: true)
                        DisconnectBanner()
                        StatsRail(sheet: $sheet)
                        Filmstrip(sheet: $sheet)
                        Spacer(minLength: 0)
                        ActionBar(sheet: $sheet)
                    }
                    .frame(width: min(340, geo.size.width * 0.42))
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
            } else {
                VStack(spacing: 12) {
                    TopBar(sheet: $sheet, compact: false)
                    DisconnectBanner()
                    HeroView(sheet: $sheet)
                    Filmstrip(sheet: $sheet)
                    StatsRail(sheet: $sheet)
                    ActionBar(sheet: $sheet)
                }
                .padding(.horizontal, 16)
                .padding(.top, 4)
                .padding(.bottom, 8)
            }
        }
    }
}

// MARK: - Topo

struct TopBar: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var capture: CaptureController
    @Binding var sheet: LumoSheet?
    var compact: Bool

    var body: some View {
        HStack(alignment: .center, spacing: 10) {
            Button { sheet = .events } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text("EVENTO")
                        .font(.system(size: 10, weight: .bold, design: .rounded))
                        .tracking(1.4)
                        .foregroundStyle(Lumo.tertiary)
                    HStack(spacing: 6) {
                        Text(model.currentEvent?.name ?? "Criar evento")
                            .font(.system(compact ? .headline : .title3, design: .rounded).weight(.bold))
                            .foregroundStyle(.white)
                            .lineLimit(1)
                            .minimumScaleFactor(0.75)
                        Image(systemName: "chevron.down")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundStyle(Lumo.secondary)
                    }
                }
            }
            .buttonStyle(.plain)
            Spacer(minLength: 8)
            Button { sheet = .camera } label: {
                HStack(spacing: 6) {
                    PulseDot(color: capture.phase.color, active: capture.phase == .connected)
                    Text(capture.phase.short)
                        .font(.system(size: 13, weight: .semibold, design: .rounded))
                        .foregroundStyle(.white)
                }
                .padding(.leading, 4).padding(.trailing, 10).padding(.vertical, 5)
                .background(capture.phase.color.opacity(0.14), in: Capsule())
                .overlay(Capsule().stroke(capture.phase.color.opacity(0.35), lineWidth: 1))
            }
            .buttonStyle(.plain)
            Button { sheet = .settings } label: {
                Image(systemName: "slider.horizontal.3")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(Lumo.secondary)
                    .frame(width: 36, height: 36)
                    .background(Lumo.surface, in: Circle())
            }
            .buttonStyle(.plain)
        }
    }
}

/// Aviso impossível de ignorar quando a câmera cai no meio do evento.
struct DisconnectBanner: View {
    @EnvironmentObject private var capture: CaptureController

    var body: some View {
        if case .failed(let reason) = capture.phase {
            Button { capture.connectUSB() } label: {
                HStack(spacing: 10) {
                    Image(systemName: "bolt.horizontal.circle.fill").font(.title3)
                    VStack(alignment: .leading, spacing: 1) {
                        Text("Câmera sem conexão").font(.system(.subheadline, design: .rounded).weight(.bold))
                        Text(reason).font(.caption).lineLimit(1).opacity(0.85)
                    }
                    Spacer()
                    Text("Reconectar").font(.caption.bold())
                        .padding(.horizontal, 10).padding(.vertical, 6)
                        .background(.white.opacity(0.2), in: Capsule())
                }
                .foregroundStyle(.white)
                .padding(12)
                .background(Lumo.danger, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            }
            .buttonStyle(.plain)
            .transition(.move(edge: .top).combined(with: .opacity))
        }
    }
}

// MARK: - Foto em destaque

struct HeroView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var capture: CaptureController
    @Binding var sheet: LumoSheet?
    @State private var flash = false

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: Lumo.radius + 4, style: .continuous).fill(Color.black)
            if let photo = model.latest, let url = model.displayURL(photo) {
                Button { sheet = .photo(photo.id) } label: {
                    ZStack(alignment: .bottom) {
                        PhotoImage(url: url, maxEdge: 1600)
                            .id(url)
                            .transition(.opacity.combined(with: .scale(scale: 0.985)))
                        HeroCaption(photo: photo)
                    }
                }
                .buttonStyle(.plain)
            } else {
                EmptyHero(sheet: $sheet)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: Lumo.radius + 4, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: Lumo.radius + 4, style: .continuous)
                .stroke(flash ? AnyShapeStyle(Lumo.gradient) : AnyShapeStyle(Lumo.stroke), lineWidth: flash ? 3 : 1)
        )
        .animation(.easeOut(duration: 0.35), value: model.latest?.id)
        .onChange(of: model.latest?.id) { _, _ in
            // Borda acende rapidamente quando chega uma foto nova.
            flash = true
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.7) { withAnimation(.easeOut(duration: 0.6)) { flash = false } }
        }
    }
}

/// Imagem ajustada ao quadro (sem cortar a foto).
struct PhotoImage: View {
    let url: URL
    var maxEdge = 1600
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            Color.black
            if let image {
                Image(uiImage: image).resizable().scaledToFit()
            } else {
                ProgressView().tint(.white)
            }
        }
        .task(id: url) { image = await Thumbnails.shared.image(for: url, maxEdge: maxEdge) }
    }
}

struct HeroCaption: View {
    let photo: PhotoRecord

    var body: some View {
        HStack(alignment: .bottom, spacing: 10) {
            VStack(alignment: .leading, spacing: 4) {
                StageChip(photo: photo)
                Text(photo.capturedAt.formatted(date: .omitted, time: .standard) + " · " + photo.originalName)
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(Lumo.secondary)
                    .lineLimit(1)
            }
            Spacer()
            if photo.stage == .approved || photo.stage == .review {
                ScoreBadge(score: photo.score)
            }
        }
        .padding(14)
        .background(LinearGradient(colors: [.clear, .black.opacity(0.75)], startPoint: .top, endPoint: .bottom))
    }
}

struct StageChip: View {
    let photo: PhotoRecord

    var body: some View {
        let (text, icon, color): (String, String, Color) = {
            switch photo.stage {
            case .received: return ("Editando", "wand.and.stars", Lumo.cyan)
            case .approved:
                switch photo.upload {
                case .confirmed: return ("No Fotto", "checkmark.icloud.fill", Lumo.ok)
                case .uploaded, .uploading: return ("Enviando", "icloud.and.arrow.up", Lumo.cyan)
                case .failed: return ("Falha no envio", "exclamationmark.icloud", Lumo.danger)
                default: return ("Aprovada", "checkmark.circle.fill", Lumo.ok)
                }
            case .review: return ("Revisão", "exclamationmark.triangle.fill", Lumo.warn)
            case .failed: return ("Falha na edição", "xmark.octagon.fill", Lumo.danger)
            }
        }()
        Label(text, systemImage: icon)
            .font(.system(.subheadline, design: .rounded).weight(.bold))
            .foregroundStyle(color)
            .padding(.horizontal, 10).padding(.vertical, 5)
            .background(.black.opacity(0.55), in: Capsule())
    }
}

struct ScoreBadge: View {
    let score: Int

    var body: some View {
        VStack(spacing: 0) {
            Text("\(score)").font(.system(size: 26, weight: .heavy, design: .rounded)).monospacedDigit()
            Text("/10").font(.system(size: 10, weight: .bold, design: .rounded)).foregroundStyle(Lumo.secondary)
        }
        .foregroundStyle(.white)
        .frame(width: 54, height: 54)
        .background(.black.opacity(0.55), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(score >= 7 ? Lumo.ok.opacity(0.6) : Lumo.warn.opacity(0.6), lineWidth: 1.5))
    }
}

struct EmptyHero: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var capture: CaptureController
    @Binding var sheet: LumoSheet?

    var body: some View {
        VStack(spacing: 18) {
            LumoRing(size: 84)
            VStack(spacing: 6) {
                Text(title).font(.system(.title2, design: .rounded).weight(.bold)).foregroundStyle(.white)
                Text(subtitle).font(.subheadline).foregroundStyle(Lumo.secondary).multilineTextAlignment(.center)
            }
            .padding(.horizontal, 24)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    var title: String {
        if model.currentEvent == nil { return "Crie o evento" }
        switch capture.phase {
        case .connected: return "Pronto. Pode fotografar."
        case .connecting, .searching: return "Conectando à câmera"
        default: return "Pronto para o evento"
        }
    }

    var subtitle: String {
        if model.currentEvent == nil { return "Toque no nome do evento, no topo, para começar." }
        switch capture.phase {
        case .connected: return "Cada foto aparece aqui, editada e avaliada, em segundos."
        case .connecting, .searching: return capture.status
        default: return "Ligue a câmera pelo cabo USB (Wi-Fi e Bluetooth da câmera desligados) e toque em Conectar."
        }
    }
}

// MARK: - Tira das últimas fotos

struct Filmstrip: View {
    @EnvironmentObject private var model: AppModel
    @Binding var sheet: LumoSheet?

    var body: some View {
        let recent = Array(model.recent.prefix(40))
        if !recent.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 6) {
                    ForEach(recent) { photo in
                        Button { sheet = .photo(photo.id) } label: {
                            ZStack(alignment: .topTrailing) {
                                if let url = model.displayURL(photo) {
                                    PhotoThumb(url: url, maxEdge: 200)
                                        .frame(width: 58, height: 58)
                                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                                }
                                StatusDot(photo: photo).padding(4)
                            }
                            .overlay(
                                RoundedRectangle(cornerRadius: 12, style: .continuous)
                                    .stroke(photo.id == model.latest?.id ? Lumo.cyan : .clear, lineWidth: 2)
                            )
                        }
                        .buttonStyle(.plain)
                    }
                    Button { sheet = .gallery } label: {
                        Image(systemName: "square.grid.2x2")
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundStyle(Lumo.secondary)
                            .frame(width: 58, height: 58)
                            .background(Lumo.surface, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    }
                    .buttonStyle(.plain)
                }
            }
            .frame(height: 58)
        }
    }
}

struct StatusDot: View {
    let photo: PhotoRecord

    var body: some View {
        let color: Color? = {
            switch photo.stage {
            case .review: return Lumo.warn
            case .failed: return Lumo.danger
            case .received: return Lumo.cyan
            case .approved:
                switch photo.upload {
                case .confirmed: return Lumo.ok
                case .failed: return Lumo.danger
                default: return nil
                }
            }
        }()
        if let color {
            Circle().fill(color).frame(width: 9, height: 9)
                .overlay(Circle().stroke(.black.opacity(0.6), lineWidth: 1.5))
        }
    }
}

// MARK: - Números de relance

struct StatsRail: View {
    @EnvironmentObject private var model: AppModel
    @Binding var sheet: LumoSheet?

    var body: some View {
        HStack(spacing: 8) {
            StatTile(title: "FOTOS", value: "\(model.photos.count)",
                     caption: model.processing + model.pendingEdit > 0 ? "editando \(model.processing + model.pendingEdit)" : "\(model.approved.count) aprovadas",
                     tint: .white) { sheet = .gallery }
            StatTile(title: "REVISÃO", value: "\(model.underReview.count)",
                     caption: model.underReview.isEmpty ? "tudo certo" : "toque para ver",
                     tint: model.underReview.isEmpty ? .white : Lumo.warn) { sheet = .review }
            StatTile(title: "FOTTO", value: fottoValue, caption: fottoCaption,
                     tint: model.failedUploads > 0 ? Lumo.danger : (model.uploading ? Lumo.cyan : .white),
                     progress: fottoProgress) { sheet = .delivery }
        }
    }

    var fottoValue: String {
        guard model.currentEvent?.fottoGallery != nil else { return "—" }
        return "\(model.confirmedCount)"
    }

    var fottoCaption: String {
        guard model.fottoConnected else { return "conectar" }
        guard model.currentEvent?.fottoGallery != nil else { return "escolher destino" }
        if model.failedUploads > 0 { return "\(model.failedUploads) falha(s)" }
        if model.uploading { return "enviando…" }
        if model.pendingUploads > 0 { return "\(model.pendingUploads) na fila" }
        return model.settings.fottoAuto ? "automático" : "confirmadas"
    }

    var fottoProgress: Double? {
        guard model.currentEvent?.fottoGallery != nil else { return nil }
        let total = model.confirmedCount + model.pendingUploads + (model.sentCount - model.confirmedCount)
        guard total > 0 else { return nil }
        return Double(model.confirmedCount) / Double(total)
    }
}

struct StatTile: View {
    let title: String
    let value: String
    let caption: String
    var tint: Color = .white
    var progress: Double? = nil
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.system(size: 10, weight: .bold, design: .rounded))
                    .tracking(1.2)
                    .foregroundStyle(Lumo.tertiary)
                Text(value)
                    .font(.system(size: 30, weight: .heavy, design: .rounded))
                    .monospacedDigit()
                    .foregroundStyle(tint)
                    .contentTransition(.numericText())
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                Text(caption)
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(Lumo.secondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                if let progress {
                    GeometryReader { g in
                        ZStack(alignment: .leading) {
                            Capsule().fill(Lumo.surfaceStrong)
                            Capsule().fill(Lumo.gradient).frame(width: max(4, g.size.width * progress))
                        }
                    }
                    .frame(height: 4)
                }
            }
            .padding(12)
            .frame(maxWidth: .infinity, minHeight: 96, alignment: .topLeading)
            .background(Lumo.surface, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(Lumo.stroke, lineWidth: 1))
            .animation(.snappy, value: value)
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Ação principal

struct ActionBar: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var capture: CaptureController
    @Binding var sheet: LumoSheet?
    @State private var pickerItems: [PhotosPickerItem] = []
    @State private var showPicker = false
    @State private var showFiles = false

    var body: some View {
        HStack(spacing: 10) {
            primary
            Menu {
                Button { showPicker = true } label: { Label("Importar do app Fotos", systemImage: "photo.stack") }
                Button { showFiles = true } label: { Label("Importar do cartão / Arquivos", systemImage: "sdcard") }
                Button { sheet = .camera } label: { Label("Conectar por Wi-Fi", systemImage: "wifi") }
                Divider()
                Button { sheet = .gallery } label: { Label("Galeria", systemImage: "square.grid.2x2") }
                Button { sheet = .delivery } label: { Label("Entrega Fotto", systemImage: "icloud.and.arrow.up") }
            } label: {
                Image(systemName: "plus")
            }
            .buttonStyle(LumoIconButtonStyle())
            .disabled(model.currentEvent == nil)
        }
        .photosPicker(isPresented: $showPicker, selection: $pickerItems, maxSelectionCount: 200, matching: .images)
        .onChange(of: pickerItems) { _, items in
            guard !items.isEmpty else { return }
            Task {
                var loaded: [(Data, String?)] = []
                for item in items {
                    if let data = try? await item.loadTransferable(type: Data.self) { loaded.append((data, nil)) }
                }
                pickerItems = []
                await model.importPhotoData(loaded)
            }
        }
        .fileImporter(isPresented: $showFiles, allowedContentTypes: [.folder, .jpeg, .image], allowsMultipleSelection: true) { result in
            switch result {
            case .success(let urls): Task { await model.importFiles(urls) }
            case .failure(let error): model.message = "Acesso a Arquivos falhou: \(error.localizedDescription)"
            }
        }
    }

    @ViewBuilder
    private var primary: some View {
        if model.currentEvent == nil {
            Button { sheet = .events } label: { Label("Criar evento", systemImage: "plus.circle.fill") }
                .buttonStyle(LumoPrimaryButtonStyle())
        } else if capture.isRunning {
            // Conectada: o botão abre os detalhes; encerrar exige confirmação no painel.
            Button { sheet = .camera } label: {
                HStack(spacing: 10) {
                    PulseDot(color: capture.phase.color, active: capture.phase == .connected)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(capture.activeCamera.isEmpty ? "Câmera" : capture.activeCamera)
                            .font(.system(.headline, design: .rounded).weight(.semibold))
                        Text(capture.backlogCount > 0 ? "\(capture.received) recebidas · \(capture.backlogCount) do cartão na fila" : "\(capture.received) recebidas nesta conexão")
                            .font(.caption).foregroundStyle(Lumo.secondary)
                    }
                    Spacer()
                    Image(systemName: "chevron.up").font(.caption.bold()).foregroundStyle(Lumo.secondary)
                }
                .padding(.horizontal, 16)
            }
            .buttonStyle(LumoPrimaryButtonStyle(tint: AnyShapeStyle(Lumo.surfaceStrong)))
        } else {
            Button {
                Haptics.tap()
                capture.connectUSB()
            } label: {
                Label("Conectar câmera", systemImage: "cable.connector")
            }
            .buttonStyle(LumoPrimaryButtonStyle())
        }
    }
}
