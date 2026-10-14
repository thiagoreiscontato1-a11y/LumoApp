import ImageIO
import SwiftUI

// MARK: - Miniaturas

/// Miniaturas geradas pelo ImageIO, com cache em memória.
final class Thumbnails {
    static let shared = Thumbnails()
    private let cache = NSCache<NSString, UIImage>()

    func image(for url: URL, maxEdge: Int = 360) async -> UIImage? {
        let stamp = (try? url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate?.timeIntervalSince1970) ?? 0
        let key = "\(url.path)#\(maxEdge)#\(stamp)" as NSString
        if let hit = cache.object(forKey: key) { return hit }
        let image = await Task.detached(priority: .userInitiated) { () -> UIImage? in
            guard let src = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
            let opts: [CFString: Any] = [kCGImageSourceCreateThumbnailFromImageAlways: true,
                                         kCGImageSourceCreateThumbnailWithTransform: true,
                                         kCGImageSourceThumbnailMaxPixelSize: maxEdge]
            return CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary).map { UIImage(cgImage: $0) }
        }.value
        if let image { cache.setObject(image, forKey: key) }
        return image
    }
}

/// Miniatura quadrada (corta para preencher).
struct PhotoThumb: View {
    let url: URL
    var maxEdge = 360
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            Lumo.surface
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                ProgressView().tint(Lumo.secondary)
            }
        }
        .clipped()
        .task(id: url) { image = await Thumbnails.shared.image(for: url, maxEdge: maxEdge) }
    }
}

/// Número grande com legenda, usado nos painéis.
struct Stat: View {
    let title: String
    let value: Int

    init(_ title: String, _ value: Int) {
        self.title = title
        self.value = value
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("\(value)").font(.system(.title2, design: .rounded).weight(.heavy)).monospacedDigit()
            Text(title.uppercased()).font(.system(size: 9, weight: .bold, design: .rounded)).tracking(1).foregroundStyle(Lumo.tertiary)
        }
    }
}

// MARK: - Eventos

struct EventsSheet: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @FocusState private var focused: Bool

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack(spacing: 10) {
                        TextField("Nome do novo evento", text: $name)
                            .focused($focused)
                            .submitLabel(.done)
                            .onSubmit(create)
                        Button("Criar", action: create)
                            .buttonStyle(.borderedProminent)
                            .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                    }
                } footer: {
                    Text("Cada evento guarda Originais, Editadas e Sob Revisao em pastas próprias, visíveis no app Arquivos.")
                }
                if !model.events.isEmpty {
                    Section("Eventos") {
                        ForEach(model.events.reversed()) { e in
                            Button {
                                model.select(e)
                                if model.currentEvent?.id == e.id { dismiss() }
                            } label: {
                                HStack {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(e.name).font(.system(.body, design: .rounded).weight(.semibold)).foregroundStyle(.white)
                                        Text("\(e.createdAt.formatted(date: .abbreviated, time: .omitted)) · \(model.photoCount(e)) foto(s)"
                                             + (e.fottoGallery.map { " · Fotto: \($0.title)" } ?? ""))
                                            .font(.caption).foregroundStyle(Lumo.secondary)
                                    }
                                    Spacer()
                                    if e.id == model.currentEvent?.id {
                                        Image(systemName: "checkmark.circle.fill").foregroundStyle(Lumo.cyan)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            .lumoScreen()
            .navigationTitle("Eventos")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { DoneButton() } }
            .onAppear { if model.events.isEmpty { focused = true } }
        }
    }

    private func create() {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        model.createEvent(trimmed)
        name = ""
        if model.currentEvent?.name == trimmed { dismiss() }
    }
}

// MARK: - Câmera

struct CameraSheet: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var capture: CaptureController
    @State private var confirmStop = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    VStack(alignment: .leading, spacing: 10) {
                        HStack(spacing: 10) {
                            PulseDot(color: capture.phase.color, active: capture.phase == .connected)
                            Text(capture.activeCamera.isEmpty ? "Nenhuma câmera" : capture.activeCamera)
                                .font(.system(.title3, design: .rounded).weight(.bold))
                            Spacer()
                            if let mode = capture.mode {
                                Text(mode.rawValue).font(.caption.bold()).foregroundStyle(Lumo.secondary)
                            }
                        }
                        Text(capture.status).font(.callout).foregroundStyle(Lumo.secondary)
                        if case .failed(let reason) = capture.phase {
                            Text(reason).font(.caption).foregroundStyle(Lumo.danger)
                        }
                        HStack(spacing: 22) {
                            Stat("Recebidas", capture.received)
                            Stat("Retroativas", capture.recovered)
                            Stat("Na fila", capture.backlogCount)
                        }
                        .padding(.top, 2)
                    }
                    .padding(.vertical, 4)
                    if capture.isRunning {
                        Button(role: .destructive) { confirmStop = true } label: { Label("Encerrar conexão", systemImage: "stop.circle") }
                    } else {
                        Button { capture.connectUSB() } label: { Label("Conectar câmera USB", systemImage: "cable.connector") }
                            .disabled(model.currentEvent == nil)
                    }
                    if !capture.cameraNames.isEmpty {
                        Text("Detectada: " + capture.cameraNames.joined(separator: ", ")).font(.caption).foregroundStyle(Lumo.secondary)
                    }
                } footer: {
                    Text("USB: Wi-Fi e Bluetooth da câmera desligados, desligamento automático desativado, cabo de dados.")
                }
                Section {
                    HStack {
                        TextField("IP da câmera", text: $model.settings.wifiHost)
                            .keyboardType(.numbersAndPunctuation)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                        Button("Conectar") { capture.connectWiFi(host: model.settings.wifiHost) }
                            .disabled(capture.isRunning || model.currentEvent == nil)
                    }
                } header: {
                    Text("Wi-Fi (PTP/IP)")
                } footer: {
                    Text("Na câmera, conecte ao computador (EOS Utility), ligue o iPhone à rede da câmera e autorize LUMO no visor.")
                }
                Section {
                    HStack {
                        Button {
                            UIPasteboard.general.string = capture.logText
                            model.message = "Registro copiado. Cole na conversa para análise."
                        } label: { Label("Copiar registro", systemImage: "doc.on.doc") }
                        Spacer()
                        ShareLink(item: capture.logText) { Label("Enviar", systemImage: "square.and.arrow.up") }
                    }
                    .buttonStyle(.borderless)
                    ForEach(Array(capture.log.prefix(60).enumerated()), id: \.offset) { _, line in
                        Text(line).font(.caption2.monospaced()).foregroundStyle(Lumo.secondary)
                    }
                } header: {
                    Text("Registro")
                }
            }
            .lumoScreen()
            .navigationTitle("Câmera")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { DoneButton() } }
            .confirmationDialog("Encerrar a conexão com a câmera?", isPresented: $confirmStop, titleVisibility: .visible) {
                Button("Encerrar", role: .destructive) { capture.stop() }
                Button("Continuar conectada", role: .cancel) {}
            } message: {
                Text("Fotos novas deixam de chegar até reconectar.")
            }
        }
    }
}

// MARK: - Revisão

struct ReviewSheet: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        NavigationStack {
            Group {
                if model.underReview.isEmpty {
                    ContentUnavailableView("Nada para revisar", systemImage: "checkmark.seal",
                                           description: Text("Fotos reprovadas pela curadoria ficam aqui e só são entregues depois de aprovadas."))
                } else {
                    ScrollView {
                        LazyVStack(spacing: 12) {
                            ForEach(model.underReview) { photo in
                                ReviewCard(photo: photo)
                            }
                        }
                        .padding(16)
                    }
                }
            }
            .lumoScreen()
            .navigationTitle("Revisão")
            .navigationBarTitleDisplayMode(.inline)
            .navigationDestination(for: String.self) { PhotoDetailView(photoID: $0) }
            .toolbar { ToolbarItem(placement: .topBarTrailing) { DoneButton() } }
        }
    }
}

struct ReviewCard: View {
    @EnvironmentObject private var model: AppModel
    let photo: PhotoRecord

    var body: some View {
        LumoCard(padding: 10) {
            HStack(spacing: 12) {
                NavigationLink(value: photo.id) {
                    if let url = model.displayURL(photo) {
                        PhotoThumb(url: url, maxEdge: 400)
                            .frame(width: 104, height: 104)
                            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                    }
                }
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        Text(photo.originalName).font(.subheadline.bold()).lineLimit(1)
                        Spacer()
                        Text("\(photo.score)/10").font(.system(.subheadline, design: .rounded).weight(.heavy)).foregroundStyle(Lumo.warn)
                    }
                    Text(photo.reason).font(.caption).foregroundStyle(Lumo.secondary).lineLimit(3)
                    if photo.stage == .review {
                        Button { Task { await model.approve(photo) } } label: {
                            Label("Aprovar", systemImage: "checkmark").font(.subheadline.bold()).frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(Lumo.ok.opacity(0.85))
                    }
                }
            }
        }
    }
}

// MARK: - Galeria

struct GallerySheet: View {
    enum Filter: String, CaseIterable { case all = "Todas", approved = "Aprovadas", review = "Revisão" }

    @EnvironmentObject private var model: AppModel
    @State private var filter: Filter = .all
    private let columns = [GridItem(.adaptive(minimum: 110), spacing: 3)]

    var items: [PhotoRecord] {
        switch filter {
        case .all: return model.recent
        case .approved: return model.recent.filter { $0.stage == .approved }
        case .review: return model.recent.filter { $0.stage == .review || $0.stage == .failed }
        }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                Picker("Filtro", selection: $filter) {
                    ForEach(Filter.allCases, id: \.self) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 16)
                .padding(.bottom, 8)
                if items.isEmpty {
                    ContentUnavailableView("Sem fotos", systemImage: "photo", description: Text("As fotos do evento aparecem aqui."))
                        .padding(.top, 60)
                } else {
                    LazyVGrid(columns: columns, spacing: 3) {
                        ForEach(items) { photo in
                            NavigationLink(value: photo.id) {
                                if let url = model.displayURL(photo) {
                                    Color.clear
                                        .aspectRatio(1, contentMode: .fit)
                                        .overlay { PhotoThumb(url: url) }
                                        .clipped()
                                        .overlay(alignment: .topTrailing) { StatusDot(photo: photo).padding(6) }
                                }
                            }
                        }
                    }
                }
            }
            .lumoScreen()
            .navigationTitle("Galeria · \(model.photos.count)")
            .navigationBarTitleDisplayMode(.inline)
            .navigationDestination(for: String.self) { PhotoDetailView(photoID: $0) }
            .toolbar { ToolbarItem(placement: .topBarTrailing) { DoneButton() } }
        }
    }
}
