import SwiftUI
import UIKit

// MARK: - Galeria

/// Galeria do evento: filtros, favoritos, seleção múltipla e ações em lote.
struct GallerySheet: View {
    enum Filter: String, CaseIterable, Identifiable {
        case all = "Todas", favorites = "Favoritas", approved = "Aprovadas", review = "Revisão", pending = "Não enviadas", sent = "Enviadas"
        var id: String { rawValue }

        var icon: String {
            switch self {
            case .all: return "square.grid.2x2"
            case .favorites: return "star.fill"
            case .approved: return "checkmark.circle"
            case .review: return "exclamationmark.triangle"
            case .pending: return "icloud.slash"
            case .sent: return "checkmark.icloud"
            }
        }

        func matches(_ p: PhotoRecord) -> Bool {
            switch self {
            case .all: return true
            case .favorites: return p.favorite
            case .approved: return p.stage == .approved
            case .review: return p.stage == .review || p.stage == .failed
            case .pending: return p.stage == .approved && !p.isSent
            case .sent: return p.isSent
            }
        }
    }

    @EnvironmentObject private var model: AppModel
    @State private var filter: Filter = .all
    @State private var selecting = false
    @State private var selection = Set<String>()
    @State private var viewer: ViewerContext?
    @State private var confirmDelete = false
    /// Seleção arrastando o dedo: foto onde começou, se o arraste marca ou desmarca e a seleção de antes.
    @State private var dragAnchor: Int?
    @State private var dragAdding = true
    @State private var dragBase = Set<String>()
    @State private var dragHorizontal: Bool?
    @State private var applying = false
    private let spacing: CGFloat = 3
    private let minTile: CGFloat = 108

    var items: [PhotoRecord] { model.recent.filter(filter.matches) }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                filterBar
                if items.isEmpty {
                    ContentUnavailableView(filter == .all ? "Sem fotos" : "Nada em “\(filter.rawValue)”", systemImage: filter.icon,
                                           description: Text(filter == .favorites ? "Toque na estrela de uma foto para favoritá-la." : "As fotos do evento aparecem aqui."))
                        .frame(maxHeight: .infinity)
                } else {
                    if selecting {
                        // Altura fixa: a grade não pode pular de lugar no meio de um arraste.
                        Text(selection.isEmpty ? "Toque nas fotos ou arraste o dedo de lado sobre elas" : "Arraste de lado para marcar mais · Preset aplica em todas")
                            .font(.caption).foregroundStyle(Lumo.secondary).lineLimit(1).minimumScaleFactor(0.8)
                            .padding(.horizontal, 16).padding(.bottom, 6)
                    }
                    GeometryReader { geo in
                        let cols = max(3, Int((geo.size.width + spacing) / (minTile + spacing)))
                        let side = max(1, (geo.size.width - CGFloat(cols - 1) * spacing) / CGFloat(cols))
                        ScrollView {
                            LazyVGrid(columns: Array(repeating: GridItem(.fixed(side), spacing: spacing), count: cols), spacing: spacing) {
                                ForEach(items) { photo in tile(photo) }
                            }
                            .padding(.bottom, selecting ? 90 : 12)
                            .contentShape(Rectangle())
                            .simultaneousGesture(dragSelect(cols: cols, side: side), including: selecting ? .all : .subviews)
                        }
                        .scrollDisabled(dragAnchor != nil)
                    }
                }
            }
            .overlay(alignment: .bottom) { if selecting { selectionBar } }
            .lumoScreen()
            .navigationTitle(selecting ? "\(selection.count) selecionada(s)" : "Galeria · \(model.photos.count)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(selecting ? "Cancelar" : "Selecionar") {
                        selecting.toggle()
                        selection.removeAll()
                    }
                }
                if selecting {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button(allSelected ? "Desmarcar todas" : "Selecionar todas") {
                            selection = allSelected ? [] : Set(items.map(\.id))
                            Haptics.tap()
                        }
                        .fontWeight(.semibold)
                    }
                } else {
                    ToolbarItem(placement: .topBarTrailing) { DoneButton() }
                }
            }
            .fullScreenCover(item: $viewer) { ctx in PhotoViewer(ids: ctx.ids, start: ctx.start) }
            .confirmationDialog("Excluir \(selection.count) foto(s) deste evento?", isPresented: $confirmDelete, titleVisibility: .visible) {
                Button("Excluir original e editada", role: .destructive) {
                    model.delete(selection)
                    selection.removeAll()
                    selecting = false
                }
            } message: {
                Text("Os arquivos são apagados do iPhone. Fotos já enviadas continuam no Fotto.")
            }
        }
    }

    private var filterBar: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(Filter.allCases) { f in
                    let count = model.photos.filter(f.matches).count
                    Button {
                        filter = f
                        Haptics.tap()
                    } label: {
                        HStack(spacing: 5) {
                            Image(systemName: f.icon).font(.system(size: 11, weight: .bold))
                            Text(f.rawValue).font(.system(size: 13, weight: .semibold, design: .rounded))
                            Text("\(count)").font(.system(size: 12, weight: .bold, design: .rounded)).monospacedDigit().opacity(0.7)
                        }
                        .foregroundStyle(filter == f ? .black : .white)
                        .padding(.horizontal, 12).padding(.vertical, 8)
                        .background(filter == f ? AnyShapeStyle(Lumo.cyan) : AnyShapeStyle(Lumo.surfaceStrong), in: Capsule())
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
        }
    }

    private var allSelected: Bool { !items.isEmpty && items.allSatisfy { selection.contains($0.id) } }

    /// Índice da foto sob o dedo (a grade tem colunas fixas, então dá para calcular sem medir cada miniatura).
    private func index(at p: CGPoint, cols: Int, side: CGFloat) -> Int? {
        guard !items.isEmpty else { return nil }
        let step = side + spacing
        let col = min(cols - 1, max(0, Int(p.x / step))), row = max(0, Int(p.y / step))
        return min(items.count - 1, row * cols + col)
    }

    /// Como na galeria do iPhone: no modo Selecionar, arrastar o dedo de lado marca (ou desmarca) todas as fotos
    /// entre a primeira e a que está sob o dedo; arrastar para cima/baixo continua rolando a grade.
    private func dragSelect(cols: Int, side: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 10, coordinateSpace: .local)
            .onChanged { v in
                if dragHorizontal == nil {
                    let horizontal = abs(v.translation.width) > abs(v.translation.height)
                    dragHorizontal = horizontal
                    guard horizontal, let start = index(at: v.startLocation, cols: cols, side: side) else { return }
                    dragAnchor = start
                    dragBase = selection
                    dragAdding = !selection.contains(items[start].id)
                    Haptics.tap()
                }
                guard dragHorizontal == true, let anchor = dragAnchor, let current = index(at: v.location, cols: cols, side: side) else { return }
                let ids = Set(items[min(anchor, current)...max(anchor, current)].map(\.id))
                let next = dragAdding ? dragBase.union(ids) : dragBase.subtracting(ids)
                if next != selection { selection = next }
            }
            .onEnded { _ in
                dragHorizontal = nil
                dragAnchor = nil
            }
    }

    private func tile(_ photo: PhotoRecord) -> some View {
        let selected = selection.contains(photo.id)
        return Color.clear
            .aspectRatio(1, contentMode: .fit)
            .overlay { if let url = model.displayURL(photo) { PhotoThumb(url: url) } }
            .clipped()
            .overlay(alignment: .topTrailing) { StatusDot(photo: photo).padding(6) }
            .overlay(alignment: .bottomLeading) {
                if photo.favorite {
                    Image(systemName: "star.fill").font(.system(size: 12, weight: .bold)).foregroundStyle(Lumo.warn)
                        .shadow(radius: 2).padding(6)
                }
            }
            .overlay {
                if selecting {
                    ZStack(alignment: .topLeading) {
                        Rectangle().fill(selected ? Color.black.opacity(0.35) : .clear)
                        Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                            .font(.system(size: 22, weight: .semibold))
                            .foregroundStyle(selected ? Lumo.cyan : .white)
                            .shadow(radius: 2)
                            .padding(6)
                    }
                }
            }
            .contentShape(Rectangle())
            .onTapGesture {
                if selecting {
                    if selected { selection.remove(photo.id) } else { selection.insert(photo.id) }
                } else {
                    viewer = ViewerContext(ids: items.map(\.id), start: photo.id)
                }
            }
            .onLongPressGesture {
                Haptics.tap()
                selecting = true
                selection.insert(photo.id)
            }
    }

    private func run(_ work: @escaping () async -> Void) {
        applying = true
        Task {
            await work()
            applying = false
            selecting = false
            selection.removeAll()
        }
    }

    private var selectionBar: some View {
        let ids = selection
        return HStack(spacing: 4) {
            Menu {
                Section("Aplicar em \(ids.count) foto(s)") {
                    ForEach(model.presets.names, id: \.self) { name in
                        Button(name) { run { await model.applyPreset(name, to: ids) } }
                    }
                    Button("Preset do evento") { run { await model.applyPreset(nil, to: ids) } }
                    Button("Sem preset") { run { await model.applyPreset("", to: ids) } }
                }
            } label: {
                barLabel("Preset", "camera.filters")
            }
            barButton("Colar", "doc.on.clipboard") { run { await model.pasteAdjustments(to: ids) } }
                .disabled(model.clipboard == nil)
                .opacity(model.clipboard == nil ? 0.4 : 1)
            barButton("Aprovar", "checkmark.circle") { Task { await model.approve(ids: ids) } }
            barButton("Enviar", "icloud.and.arrow.up") {
                model.startUpload(retryFailed: true, only: ids)
                selecting = false
            }
            .disabled(model.currentEvent?.fottoGallery == nil || model.uploading)
            Menu {
                Button { model.toggleFavorite(ids) } label: { Label("Favoritar", systemImage: "star") }
                ShareLink(items: model.editedURLs(ids)) { Label("Compartilhar", systemImage: "square.and.arrow.up") }
                ShareLink(items: model.originalURLs(ids)) { Label("Compartilhar originais", systemImage: "photo.stack") }
                Divider()
                Button(role: .destructive) { confirmDelete = true } label: { Label("Excluir", systemImage: "trash") }
            } label: {
                barLabel("Mais", "ellipsis.circle")
            }
        }
        .foregroundStyle(.white)
        .disabled(ids.isEmpty || applying)
        .opacity(ids.isEmpty ? 0.5 : 1)
        .overlay { if applying { ProgressView().tint(.white) } }
        .padding(.vertical, 10)
        .padding(.horizontal, 8)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        .padding(.horizontal, 12)
        .padding(.bottom, 8)
    }

    private func barButton(_ title: String, _ icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) { barLabel(title, icon) }.buttonStyle(.plain)
    }

    private func barLabel(_ title: String, _ icon: String) -> some View {
        VStack(spacing: 3) {
            Image(systemName: icon).font(.system(size: 18, weight: .semibold))
            Text(title).font(.system(size: 10, weight: .semibold))
        }
        .frame(maxWidth: .infinity, minHeight: 44)
        .contentShape(Rectangle())
    }
}

struct ViewerContext: Identifiable {
    let ids: [String]
    let start: String
    var id: String { start }
}

// MARK: - Visualizador

/// Foto em tela cheia: deslizar entre fotos, pinça/duplo toque para zoom, favoritar, editar, aprovar e enviar.
struct PhotoViewer: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State var ids: [String]
    @State private var current: String
    @State private var chrome = true
    @State private var editing: PhotoRecord?
    @State private var info: String?
    @State private var confirmDelete = false

    init(ids: [String], start: String) {
        _ids = State(initialValue: ids)
        _current = State(initialValue: start)
    }

    var photo: PhotoRecord? { model.photos.first { $0.id == current } }
    var position: Int { (ids.firstIndex(of: current) ?? 0) + 1 }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            TabView(selection: $current) {
                ForEach(ids, id: \.self) { id in
                    Group {
                        if let p = model.photos.first(where: { $0.id == id }), let url = model.displayURL(p) {
                            ZoomablePhoto(url: url) { withAnimation(.easeInOut(duration: 0.2)) { chrome.toggle() } }
                        } else {
                            Color.black
                        }
                    }
                    .tag(id)
                    .ignoresSafeArea()
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .ignoresSafeArea()
            if chrome, let photo {
                VStack(spacing: 0) {
                    topBar(photo)
                    Spacer()
                    bottomBar(photo)
                }
                .transition(.opacity)
            }
        }
        .statusBarHidden(!chrome)
        .fullScreenCover(item: $editing) { p in LiveEditor(photo: p) }
        .sheet(item: Binding(get: { info.map { InfoID(id: $0) } }, set: { info = $0?.id })) { item in
            NavigationStack {
                PhotoDetailView(photoID: item.id)
                    .toolbar { ToolbarItem(placement: .topBarTrailing) { DoneButton() } }
            }
            .presentationBackground(Lumo.background)
        }
        .confirmationDialog("Excluir esta foto do evento?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Excluir original e editada", role: .destructive) { deleteCurrent() }
        }
        .onChange(of: current) { _, _ in Haptics.tap() }
        .onAppear { if DemoMode.arguments.contains("-LumoEditor") { editing = photo } }
    }

    struct InfoID: Identifiable { let id: String }

    private func topBar(_ photo: PhotoRecord) -> some View {
        HStack(spacing: 12) {
            circleButton("xmark") { dismiss() }
            Spacer()
            Text("\(position) de \(ids.count)")
                .font(.system(.subheadline, design: .rounded).weight(.semibold)).monospacedDigit()
                .foregroundStyle(.white)
                .padding(.horizontal, 12).padding(.vertical, 6)
                .background(.black.opacity(0.45), in: Capsule())
            Spacer()
            circleButton(photo.favorite ? "star.fill" : "star", tint: photo.favorite ? Lumo.warn : .white) {
                model.toggleFavorite([photo.id])
            }
            circleButton("info") { info = photo.id }
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .background(LinearGradient(colors: [.black.opacity(0.6), .clear], startPoint: .top, endPoint: .bottom).ignoresSafeArea())
    }

    private func bottomBar(_ photo: PhotoRecord) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .bottom) {
                VStack(alignment: .leading, spacing: 4) {
                    StageChip(photo: photo)
                    Text(photo.reason).font(.caption).foregroundStyle(Lumo.secondary).lineLimit(2)
                }
                Spacer()
                if photo.stage == .approved || photo.stage == .review { ScoreBadge(score: photo.score) }
            }
            HStack(spacing: 6) {
                action("Editar", "slider.horizontal.3") { editing = photo }
                if photo.stage == .review {
                    action("Aprovar", "checkmark.circle", tint: Lumo.ok) { Task { await model.approve(photo) } }
                } else if photo.stage == .approved {
                    if model.currentEvent?.fottoGallery != nil && !photo.isSent {
                        action("Enviar", "icloud.and.arrow.up", tint: Lumo.cyan) { model.startUpload(retryFailed: true, only: [photo.id]) }
                    } else {
                        action("Revisão", "exclamationmark.triangle") { Task { await model.sendToReview(photo) } }
                    }
                }
                if let url = model.displayURL(photo) {
                    ShareLink(item: url) { actionLabel("Compartilhar", "square.and.arrow.up", tint: .white) }
                }
                action("Excluir", "trash", tint: Lumo.danger) { confirmDelete = true }
            }
        }
        .padding(16)
        .background(LinearGradient(colors: [.clear, .black.opacity(0.8)], startPoint: .top, endPoint: .bottom).ignoresSafeArea())
    }

    private func circleButton(_ icon: String, tint: Color = .white, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon)
                .font(.system(size: 16, weight: .bold))
                .foregroundStyle(tint)
                .frame(width: 40, height: 40)
                .background(.black.opacity(0.45), in: Circle())
        }
        .buttonStyle(.plain)
    }

    private func action(_ title: String, _ icon: String, tint: Color = .white, run: @escaping () -> Void) -> some View {
        Button(action: run) { actionLabel(title, icon, tint: tint) }.buttonStyle(.plain)
    }

    private func actionLabel(_ title: String, _ icon: String, tint: Color) -> some View {
        VStack(spacing: 4) {
            Image(systemName: icon).font(.system(size: 19, weight: .semibold))
            Text(title).font(.system(size: 11, weight: .semibold))
        }
        .foregroundStyle(tint)
        .frame(maxWidth: .infinity, minHeight: 54)
        .background(.white.opacity(0.1), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    private func deleteCurrent() {
        guard let i = ids.firstIndex(of: current) else { return }
        let removed = current
        model.delete([removed])
        ids.remove(at: i)
        if ids.isEmpty { dismiss(); return }
        current = ids[min(i, ids.count - 1)]
    }
}

/// Imagem com zoom por pinça, duplo toque e arraste (UIScrollView), compatível com o deslizar entre fotos.
struct ZoomablePhoto: UIViewRepresentable {
    let url: URL
    var onSingleTap: () -> Void = {}

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> UIScrollView {
        let scroll = ZoomScrollView()
        scroll.delegate = context.coordinator
        scroll.minimumZoomScale = 1
        scroll.maximumZoomScale = 6
        scroll.showsVerticalScrollIndicator = false
        scroll.showsHorizontalScrollIndicator = false
        scroll.backgroundColor = .black
        scroll.contentInsetAdjustmentBehavior = .never
        // Sem zoom, o arraste horizontal fica com o TabView (passar para a próxima foto).
        scroll.isScrollEnabled = false
        let image = UIImageView()
        image.contentMode = .scaleAspectFit
        image.isUserInteractionEnabled = true
        scroll.addSubview(image)
        scroll.imageView = image
        context.coordinator.imageView = image
        context.coordinator.scroll = scroll
        let double = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.doubleTap(_:)))
        double.numberOfTapsRequired = 2
        scroll.addGestureRecognizer(double)
        let single = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.singleTap))
        single.require(toFail: double)
        scroll.addGestureRecognizer(single)
        context.coordinator.load(url)
        return scroll
    }

    func updateUIView(_ scroll: UIScrollView, context: Context) {
        context.coordinator.parent = self
        context.coordinator.layout()
        if context.coordinator.loadedURL != url { context.coordinator.load(url) }
    }

    final class Coordinator: NSObject, UIScrollViewDelegate {
        var parent: ZoomablePhoto
        weak var imageView: UIImageView?
        weak var scroll: UIScrollView?
        var loadedURL: URL?

        init(_ parent: ZoomablePhoto) { self.parent = parent }

        func load(_ url: URL) {
            loadedURL = url
            Task { @MainActor in
                // Prévia rápida e, em seguida, resolução alta para o zoom.
                if let quick = await Thumbnails.shared.image(for: url, maxEdge: 1200), loadedURL == url { imageView?.image = quick }
                if let full = await Thumbnails.shared.image(for: url, maxEdge: 3600), loadedURL == url { imageView?.image = full }
            }
        }

        func layout() { scroll?.setNeedsLayout() }

        func viewForZooming(in scrollView: UIScrollView) -> UIView? { imageView }

        func scrollViewDidZoom(_ scrollView: UIScrollView) {
            scrollView.isScrollEnabled = scrollView.zoomScale > 1.01
        }

        @objc func doubleTap(_ g: UITapGestureRecognizer) {
            guard let scroll, let imageView else { return }
            if scroll.zoomScale > 1.01 {
                scroll.setZoomScale(1, animated: true)
            } else {
                let point = g.location(in: imageView)
                let scale: CGFloat = 2.8
                let size = CGSize(width: scroll.bounds.width / scale, height: scroll.bounds.height / scale)
                scroll.zoom(to: CGRect(x: point.x - size.width / 2, y: point.y - size.height / 2, width: size.width, height: size.height), animated: true)
            }
        }

        @objc func singleTap() { parent.onSingleTap() }
    }
}

/// Mantém a imagem do tamanho da tela enquanto não há zoom.
final class ZoomScrollView: UIScrollView {
    weak var imageView: UIImageView?

    override func layoutSubviews() {
        super.layoutSubviews()
        if zoomScale <= 1.001, let imageView, imageView.frame.size != bounds.size {
            imageView.frame = CGRect(origin: .zero, size: bounds.size)
            contentSize = bounds.size
        }
    }
}
