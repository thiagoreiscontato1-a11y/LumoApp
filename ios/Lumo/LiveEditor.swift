import SwiftUI
import UIKit

/// Renderiza a prévia do editor fora da thread principal: a foto é decodificada uma vez e cada
/// movimento de controle renderiza automático + preset + ajuste manual de uma vez, como o
/// Lightroom; renderizações antigas são canceladas. A foto inteira fica guardada para o modo Corte;
/// a fonte da revelação é a foto recortada com o perfil criativo.
@MainActor
final class EditorEngine: ObservableObject {
    @Published private(set) var before: UIImage?
    @Published private(set) var rendered: UIImage?
    @Published private(set) var loading = true
    /// Básicos do automático + preset: o valor que os sliders mostram sem ajuste manual.
    @Published private(set) var suggested = EditControls()
    /// Preset em uso, para mostrar o valor dos demais sliders.
    @Published private(set) var preset: [String: Double]?
    /// Perfil criativo do preset em uso.
    @Published private(set) var look: CreativeLook?
    /// Histogramas da foto original e da prévia atual.
    @Published private(set) var beforeHistogram: Histogram?
    @Published private(set) var histogram: Histogram?
    /// Máscara de recorte (toque no histograma): realces sem detalhe em vermelho, sombras em azul.
    @Published private(set) var clipping = false
    @Published private(set) var beforeClipped: UIImage?
    @Published private(set) var renderedClipped: UIImage?
    /// Modo Corte: foto inteira revelada e girada pelo ângulo atual.
    @Published private(set) var cropPreview: UIImage?
    /// Tamanho da foto inteira (base das coordenadas do corte).
    @Published private(set) var fullSize = CGSize(width: 1, height: 1)

    private var source: Develop.Source?
    private var full: PixelImage?
    private var cropped: PixelImage?
    private var fullDeveloped: PixelImage?
    private var crop = Crop.none
    /// Intensidade do perfil já aplicada em `source` (NaN = sem perfil).
    private var sourceLook = Double.nan
    private var options = EditOptions()
    private var manual = EditControls()
    private var extra: [String: Double] = [:]
    private var renderTask: Task<Void, Never>?
    private var loadTask: Task<Void, Never>?
    private var cropTask: Task<Void, Never>?
    static let previewEdge = 1280

    func load(url: URL, options: EditOptions, manual: EditControls, extra: [String: Double], crop: Crop) {
        loading = true
        setOptions(options)
        self.manual = manual
        self.extra = extra
        self.crop = crop
        loadTask?.cancel()
        loadTask = Task {
            let decoded = await Task.detached(priority: .userInitiated) { try? ImageBridge.decode(url: url, maxEdge: Self.previewEdge) }.value
            guard let decoded, !Task.isCancelled else { loading = false; return }
            full = decoded
            fullSize = CGSize(width: decoded.width, height: decoded.height)
            await rebuildSource()
            loading = false
            render(self.manual, self.extra)
        }
    }

    /// Troca o preset (ou as opções do evento) e renderiza de novo.
    func rebase(options: EditOptions, manual: EditControls, extra: [String: Double]) {
        setOptions(options)
        self.manual = manual
        self.extra = extra
        Task {
            await rebuildSource()
            render(manual, extra)
        }
    }

    /// Novo corte: refaz a foto recortada, o "antes" e a fonte.
    func setCrop(_ c: Crop) {
        guard c != crop else { return }
        crop = c
        Task {
            await rebuildSource()
            render(manual, extra)
        }
    }

    private func setOptions(_ options: EditOptions) {
        var o = options
        o.manual = nil
        o.extra = nil
        o.crop = nil
        self.options = o
        preset = o.preset
        look = o.look
    }

    private func lookAmount(_ extra: [String: Double]) -> Double {
        options.look == nil ? .nan : Develop.value("LookAmount", in: PhotoEditor.merged(options.preset, extra))
    }

    /// Foto recortada, "antes", histograma e fonte com o perfil (fora da thread principal).
    private func rebuildSource() async {
        guard let full else { return }
        var o = options
        o.extra = extra.isEmpty ? nil : extra
        let c = crop, amount = lookAmount(extra)
        let built = await Task.detached(priority: .userInitiated) { () -> (PixelImage, Develop.Source, Histogram) in
            let cut = c.isIdentity ? full : c.constrained(full.width, full.height).apply(full)
            let profiled = CreativeProfile.apply(cut, PhotoEditor.profile(o))
            return (cut, Develop.Source(profiled), Histogram(cut))
        }.value
        cropped = built.0
        source = built.1
        sourceLook = amount
        before = Self.image(built.0)
        beforeHistogram = built.2
        if clipping { beforeClipped = await Self.clippedImage(built.0) } else { beforeClipped = nil }
        updateSuggested()
    }

    private func updateSuggested() {
        guard let source else { return }
        suggested = PhotoEditor.autoValues(source, options: options) + PhotoEditor.presetControls(options.preset)
    }

    /// Renderiza a prévia com o ajuste manual atual.
    func render(_ manual: EditControls, _ extra: [String: Double]) {
        self.manual = manual
        self.extra = extra
        guard let current = source else { return }
        var o = options
        o.manual = manual.isNeutral ? nil : manual
        o.extra = extra.isEmpty ? nil : extra
        let clip = clipping
        // Intensidade do perfil criativo mudou: a fonte (foto recortada com o perfil) é refeita antes de revelar.
        let want = lookAmount(extra)
        let relook = !want.isNaN && want != sourceLook ? cropped : nil
        renderTask?.cancel()
        renderTask = Task {
            let frame = await Task.detached(priority: .userInitiated) { () -> (UIImage?, Histogram, UIImage?, Develop.Source?) in
                var src = current, fresh: Develop.Source?
                if let relook { src = Develop.Source(CreativeProfile.apply(relook, PhotoEditor.profile(o))); fresh = src }
                let pixels = PhotoEditor.render(src, options: o)
                return (Self.image(pixels), Histogram(pixels), clip ? Self.image(Histogram.clipping(pixels)) : nil, fresh)
            }.value
            if let fresh = frame.3, source === current { source = fresh; sourceLook = want }
            guard !Task.isCancelled, let image = frame.0 else { return }
            rendered = image
            histogram = frame.1
            renderedClipped = frame.2
        }
    }

    // MARK: Modo Corte

    /// Revela a foto inteira (sem corte) para a prévia do Corte.
    func enterCrop(angle: Double) {
        guard let full else { return }
        var o = options
        o.manual = manual.isNeutral ? nil : manual
        o.extra = extra.isEmpty ? nil : extra
        cropTask?.cancel()
        cropTask = Task {
            let developed = await Task.detached(priority: .userInitiated) { () -> PixelImage in
                PhotoEditor.render(Develop.Source(CreativeProfile.apply(full, PhotoEditor.profile(o))), options: o)
            }.value
            guard !Task.isCancelled else { return }
            fullDeveloped = developed
            rotateCrop(angle: angle)
        }
    }

    /// Gira a prévia do Corte pelo ângulo do endireitar.
    func rotateCrop(angle: Double) {
        guard let base = fullDeveloped ?? full else { return }
        Task {
            let image = await Task.detached(priority: .userInitiated) { Self.image(Crop.rotated(base, angle: angle, fill: 0xFF07_090C)) }.value
            cropPreview = image
        }
    }

    func leaveCrop() {
        cropTask?.cancel()
        fullDeveloped = nil
        cropPreview = nil
    }

    // MARK: Recorte (histograma)

    /// Liga ou desliga a máscara de recorte e refaz a prévia com ela.
    func setClipping(_ on: Bool) {
        clipping = on
        if !on {
            beforeClipped = nil
            renderedClipped = nil
            return
        }
        if let cropped {
            Task {
                let image = await Self.clippedImage(cropped)
                if clipping { beforeClipped = image }
            }
        }
        render(manual, extra)
    }

    /// Imagem na tela: antes ou depois, com a máscara de recorte quando ligada.
    func displayed(before showBefore: Bool) -> UIImage? {
        if showBefore { return clipping ? (beforeClipped ?? before) : before }
        let after = rendered ?? before
        return clipping ? (renderedClipped ?? beforeClipped ?? after) : after
    }

    nonisolated static func clippedImage(_ p: PixelImage) async -> UIImage? {
        await Task.detached(priority: .userInitiated) { Self.image(Histogram.clipping(p)) }.value
    }

    nonisolated static func image(_ p: PixelImage) -> UIImage? {
        (try? ImageBridge.cgImage(p)).map { UIImage(cgImage: $0) }
    }
}

/// Editor manual com a foto sempre visível: cada ajuste aparece na hora.
/// Segure a foto para ver o original (antes). Como no Lightroom, cada slider mostra o valor final
/// (automático + preset + ajuste); toque duas vezes no número para voltar ao valor do preset.
struct LiveEditor: View {
    enum Panel: String, CaseIterable { case light = "Luz", color = "Cor", effects = "Efeitos", detail = "Detalhe", crop = "Corte", preset = "Preset" }
    static let curveNames = ["RGB", "Vermelho", "Verde", "Azul"]
    static let curveColors: [Color] = [.white, Color(red: 1, green: 0.35, blue: 0.35), Color(red: 0.3, green: 0.85, blue: 0.4), Color(red: 0.3, green: 0.55, blue: 1)]
    static let gradeNames = ["Sombras", "Meios-tons", "Realces", "Global"]
    static let gradeKeys = [["SplitToningShadowHue", "SplitToningShadowSaturation", "ColorGradeShadowLum"],
                            ["ColorGradeMidtoneHue", "ColorGradeMidtoneSat", "ColorGradeMidtoneLum"],
                            ["SplitToningHighlightHue", "SplitToningHighlightSaturation", "ColorGradeHighlightLum"],
                            ["ColorGradeGlobalHue", "ColorGradeGlobalSat", "ColorGradeGlobalLum"]]
    static let aspectNames = ["Livre", "Original", "1:1", "4:5", "3:2", "16:9"]
    /// Proporções (lado maior/lado menor), aplicadas na orientação da foto; -1 = a da foto.
    static let aspects: [Double] = [0, -1, 1, 5.0 / 4, 3.0 / 2, 16.0 / 9]
    static let mixNames = ["Vermelho", "Laranja", "Amarelo", "Verde", "Turquesa", "Azul", "Roxo", "Magenta"]
    static let mixHues: [Double] = [0, 28, 52, 120, 180, 220, 270, 310]

    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let photo: PhotoRecord
    @StateObject private var engine = EditorEngine()
    /// Diferença dos básicos em relação ao automático + preset.
    @State private var controls = EditControls()
    /// Valores finais dos demais ajustes alterados no editor (chaves Camera Raw).
    @State private var extra: [String: Double] = [:]
    @State private var mixColor = 0
    /// `nil` = preset do evento; "" = sem preset.
    @State private var presetChoice: String?
    @State private var group: Panel = .light
    @State private var showingBefore = false
    @State private var saving = false
    /// Recorte desta foto (aplicado ao sair da aba Corte) e a proporção travada (0 = livre).
    @State private var crop = Crop.none
    @State private var cropAspect = 0.0
    @State private var curveChannel = 0
    @State private var gradeRange = 1
    @State private var naming = false
    @State private var newPresetName = ""

    var body: some View {
        VStack(spacing: 0) {
            header
            preview
            controlsPanel
        }
        .background(Color.black.ignoresSafeArea())
        .onAppear {
            controls = photo.manual ?? EditControls()
            extra = photo.extra ?? [:]
            presetChoice = photo.customPreset
            crop = photo.crop ?? .none
            if let event = model.currentEvent {
                engine.load(url: model.library.originalURL(photo, in: event), options: currentOptions, manual: controls, extra: extra, crop: crop)
            }
        }
        .onChange(of: group) { old, new in
            if new == .crop { engine.enterCrop(angle: crop.angle) }
            if old == .crop && new != .crop { engine.leaveCrop(); engine.setCrop(crop) }
        }
        .onChange(of: controls) { _, c in engine.render(c, extra) }
        .onChange(of: extra) { _, e in engine.render(controls, e) }
        .onChange(of: presetChoice) { _, _ in engine.rebase(options: currentOptions, manual: controls, extra: extra) }
    }

    private var currentOptions: EditOptions {
        var p = photo
        p.customPreset = presetChoice
        return model.options(for: p)
    }

    private var header: some View {
        HStack {
            Button("Cancelar") { dismiss() }.foregroundStyle(Lumo.secondary)
            Spacer()
            Text("Editar").font(.system(.headline, design: .rounded))
            Spacer()
            if saving {
                ProgressView().tint(.white)
            } else {
                Button("Aplicar") {
                    saving = true
                    Task {
                        await model.reprocess(photo, manual: controls.isNeutral ? nil : controls, extra: .some(extra.isEmpty ? nil : extra),
                                              customPreset: .some(presetChoice), crop: .some(crop.isIdentity ? nil : crop))
                        saving = false
                        dismiss()
                    }
                }
                .fontWeight(.bold)
                .foregroundStyle(Lumo.cyan)
            }
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 10)
    }

    @ViewBuilder private var preview: some View {
        if group == .crop { cropPreview } else { photoPreview }
    }

    /// Modo Corte: foto inteira girada e a moldura por cima.
    private var cropPreview: some View {
        ZStack {
            Color.black
            if let image = engine.cropPreview ?? engine.before { Image(uiImage: image).resizable().scaledToFit() }
            CropFrame(crop: crop, imageSize: engine.fullSize, aspect: cropAspect) { crop = $0 }
                .padding(4)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .overlay(alignment: .bottom) {
            Text("Arraste os cantos ou as bordas; arraste o meio para mover")
                .font(.caption2).foregroundStyle(Lumo.tertiary).padding(.bottom, 6)
        }
    }

    private var photoPreview: some View {
        ZStack(alignment: .topLeading) {
            Color.black
            if let image = engine.displayed(before: showingBefore) {
                Image(uiImage: image).resizable().scaledToFit()
            }
            if engine.loading { ProgressView().tint(.white).frame(maxWidth: .infinity, maxHeight: .infinity) }
            Text(showingBefore ? "ANTES" : "DEPOIS")
                .font(.system(size: 11, weight: .heavy, design: .rounded)).tracking(1.2)
                .foregroundStyle(showingBefore ? .black : .white)
                .padding(.horizontal, 9).padding(.vertical, 5)
                .background(showingBefore ? AnyShapeStyle(Color.white) : AnyShapeStyle(Color.black.opacity(0.55)), in: Capsule())
                .padding(12)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .contentShape(Rectangle())
        .onLongPressGesture(minimumDuration: 0.05, maximumDistance: 40, pressing: { pressing in
            showingBefore = pressing
        }, perform: {})
        .overlay(alignment: .topTrailing) {
            if let histogram = showingBefore ? engine.beforeHistogram : (engine.histogram ?? engine.beforeHistogram) {
                HistogramView(histogram: histogram, clipping: engine.clipping)
                    .frame(width: 150, height: 66)
                    .padding(10)
                    .onTapGesture { engine.setClipping(!engine.clipping) }
            }
        }
        .overlay(alignment: .bottom) {
            Text(engine.clipping ? "Recorte: realces sem detalhe em vermelho, sombras em azul" : "Segure a foto para comparar com o original")
                .font(.caption2).foregroundStyle(Lumo.tertiary).padding(.bottom, 6)
        }
    }

    private var controlsPanel: some View {
        VStack(spacing: 10) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(Panel.allCases, id: \.self) { p in
                        Button { group = p } label: {
                            Text(p.rawValue)
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(group == p ? Color.black : Color.white)
                                .padding(.horizontal, 14).frame(minHeight: 36)
                                .background(group == p ? AnyShapeStyle(Lumo.cyan) : AnyShapeStyle(Lumo.surface), in: Capsule())
                        }
                        .buttonStyle(.plain)
                        .accessibilityAddTraits(group == p ? .isSelected : [])
                    }
                }
                .padding(.horizontal, 16)
            }
            ScrollView {
                VStack(spacing: 14) {
                    switch group {
                    case .light: lightPanel
                    case .color: colorPanel
                    case .effects: effectsPanel
                    case .detail: detailPanel
                    case .crop: cropPanel
                    case .preset: presetList
                    }
                }
                .padding(.horizontal, 18)
                .padding(.vertical, 6)
            }
            HStack(spacing: 10) {
                Button {
                    controls = EditControls()
                    extra = [:]
                } label: { Label("Voltar ao preset", systemImage: "arrow.counterclockwise") }
                Spacer()
                Button {
                    var p = photo
                    p.manual = controls.isNeutral ? nil : controls
                    p.extra = extra.isEmpty ? nil : extra
                    p.customPreset = presetChoice
                    model.copyAdjustments(from: p)
                } label: { Label("Copiar ajustes", systemImage: "doc.on.doc") }
            }
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(Lumo.secondary)
            .padding(.horizontal, 18)
            .padding(.bottom, 8)
        }
        .padding(.top, 12)
        .frame(height: 330)
        .background(Lumo.background)
    }

    private var presetList: some View {
        VStack(spacing: 8) {
            Button {
                newPresetName = ""
                naming = true
            } label: {
                Label("Criar preset com esta edição", systemImage: "plus.circle.fill")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.black)
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .background(Lumo.cyan, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            }
            .buttonStyle(.plain)
            .alert("Novo preset", isPresented: $naming) {
                TextField("Nome do preset", text: $newPresetName)
                Button("Cancelar", role: .cancel) {}
                Button("Salvar") { savePreset() }
            } message: {
                Text("Guarda os ajustes desta edição (preset atual + o que você mudou; sem o recorte). Depois aplique em várias fotos pela Galeria.")
            }
            presetRow("Preset do evento" + (model.settings.presetName.isEmpty ? " (nenhum)" : " (\(model.settings.presetName))"), value: nil)
            presetRow("Sem preset", value: "")
            ForEach(model.presets.names, id: \.self) { presetRow($0, value: $0) }
            if model.presets.names.isEmpty {
                Text("Importe presets .xmp em Ajustes para usá-los aqui.").font(.caption).foregroundStyle(Lumo.tertiary)
            }
        }
    }

    /// Salva o preset e passa a foto para ele, sem ajuste manual por cima: o resultado na tela não muda.
    private func savePreset() {
        guard let name = model.createPreset(named: newPresetName, options: currentOptions, manual: controls, extra: extra) else { return }
        presetChoice = name
        controls = EditControls()
        extra = [:]
        Haptics.tap()
    }

    private func presetRow(_ title: String, value: String?) -> some View {
        Button { presetChoice = value } label: {
            HStack {
                Text(title).foregroundStyle(.white)
                Spacer()
                if presetChoice == value { Image(systemName: "checkmark").foregroundStyle(Lumo.cyan) }
            }
            .padding(12)
            .background(Lumo.surface, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
        .buttonStyle(.plain)
    }

    // MARK: Painéis

    private var lightPanel: some View {
        Group {
            slider("Exposição", \.exposure, -5...5, step: 0.01, format: "%+.2f")
            slider("Contraste", \.contrast)
            slider("Realces", \.highlights)
            slider("Sombras", \.shadows)
            slider("Brancos", \.whites)
            slider("Pretos", \.blacks)
            curvePanel
        }
    }

    // MARK: Curva de tons

    private var curvePanel: some View {
        Group {
            sectionTitle("Curva de tons")
            choiceRow(["RGB", "R", "G", "B"], selected: curveChannel) { curveChannel = $0 }
            ToneCurveEditor(points: curvePoints(curveChannel), color: Self.curveColors[curveChannel],
                            histogram: engine.histogram ?? engine.beforeHistogram) { setCurve(curveChannel, $0) }
            Text("Toque na linha para criar um ponto; arraste para fora ou toque duas vezes para apagar.")
                .font(.caption2).foregroundStyle(Lumo.tertiary).frame(maxWidth: .infinity, alignment: .leading)
            Button("Restaurar curva \(Self.curveNames[curveChannel])") { clearCurve(curveChannel) }
                .font(.subheadline.weight(.semibold)).foregroundStyle(Lumo.cyan)
            sectionTitle("Curva paramétrica")
            xmpSlider("Realces", "ParametricHighlights")
            xmpSlider("Claros", "ParametricLights")
            xmpSlider("Escuros", "ParametricDarks")
            xmpSlider("Sombras", "ParametricShadows")
            xmpSlider("Divisão · sombras", "ParametricShadowSplit", 0...100, format: "%.0f")
            xmpSlider("Divisão · meios-tons", "ParametricMidtoneSplit", 0...100, format: "%.0f")
            xmpSlider("Divisão · realces", "ParametricHighlightSplit", 0...100, format: "%.0f")
        }
    }

    /// Pontos da curva do canal: a do editor, a do preset ou a reta.
    private func curvePoints(_ ch: Int) -> [Double] {
        let name = Develop.curves[ch]
        var v: [Double] = []
        if extra["\(name).0"] != nil {
            while let x = extra["\(name).\(v.count)"], v.count < 128 { v.append(x) }
        } else {
            while let x = engine.preset?["\(name).\(v.count)"], v.count < 128 { v.append(x) }
        }
        return v.count >= 4 && v.count % 2 == 0 ? v : [0, 0, 255, 255]
    }

    private func clearCurve(_ ch: Int) {
        let name = Develop.curves[ch]
        for k in extra.keys where k.hasPrefix(name + ".") { extra[k] = nil }
    }

    private func setCurve(_ ch: Int, _ pts: [Double]) {
        var e = extra
        let name = Develop.curves[ch]
        for k in e.keys where k.hasPrefix(name + ".") { e[k] = nil }
        for (i, v) in pts.enumerated() { e["\(name).\(i)"] = (v * 100).rounded() / 100 }
        extra = e
    }

    private func choiceRow(_ labels: [String], selected: Int, pick: @escaping (Int) -> Void) -> some View {
        HStack(spacing: 6) {
            ForEach(labels.indices, id: \.self) { i in
                Button { pick(i) } label: {
                    Text(labels[i])
                        .font(.caption.weight(.semibold))
                        .lineLimit(1).minimumScaleFactor(0.8)
                        .foregroundStyle(i == selected ? Color.black : Color.white)
                        .frame(maxWidth: .infinity, minHeight: 34)
                        .background(i == selected ? AnyShapeStyle(Lumo.cyan) : AnyShapeStyle(Lumo.surface), in: RoundedRectangle(cornerRadius: 9, style: .continuous))
                }
                .buttonStyle(.plain)
            }
        }
    }

    // MARK: Gradação de cores e perfil

    private var gradingPanel: some View {
        let keys = Self.gradeKeys[gradeRange]
        return Group {
            sectionTitle("Gradação de cores · " + Self.gradeNames[gradeRange])
            choiceRow(Self.gradeNames, selected: gradeRange) { gradeRange = $0 }
            ColorWheel(hue: value(keys[0]), sat: value(keys[1])) { h, sat in
                var e = extra
                e[keys[0]] = h
                e[keys[1]] = sat
                touchGrading(&e)
                extra = e
            }
            .frame(maxWidth: .infinity)
            xmpSlider("Matiz", keys[0], 0...360, format: "%.0f°")
            xmpSlider("Saturação", keys[1], 0...100, format: "%.0f")
            xmpSlider("Luminância", keys[2])
            xmpSlider("Mistura", "ColorGradeBlending", 0...100, format: "%.0f")
            xmpSlider("Equilíbrio", "SplitToningBalance")
        }
    }

    private func value(_ key: String) -> Double { extra[key] ?? Develop.value(key, in: engine.preset) }

    /// Mexer na gradação grava a Mistura que está na tela: sem isso, a primeira chave ColorGrade trocaria o padrão do
    /// motor (100 dos presets antigos de Split Toning → 50 do Lightroom 10+) e a foto mudaria sozinha.
    private func touchGrading(_ e: inout [String: Double]) {
        if e["ColorGradeBlending"] == nil { e["ColorGradeBlending"] = Develop.value("ColorGradeBlending", in: engine.preset) }
    }

    @ViewBuilder private var lookPanel: some View {
        if let look = engine.look {
            sectionTitle("Perfil · " + look.name)
            xmpSlider("Intensidade", "LookAmount", 0...200, format: "%.0f")
        }
    }

    // MARK: Corte

    private var cropPanel: some View {
        Group {
            sectionTitle("Endireitar")
            row("Ângulo", value: crop.angle, changed: abs(crop.angle) > 1e-9, range: -45...45, step: 0.1, format: "%+.1f°",
                set: { setAngle($0) }, reset: { setAngle(0) })
            sectionTitle("Proporção")
            choiceRow(Array(Self.aspectNames[0..<3]), selected: aspectIndex < 3 ? aspectIndex : -1) { setAspect(aspectValue(Self.aspects[$0])) }
            choiceRow(Array(Self.aspectNames[3...]), selected: aspectIndex >= 3 ? aspectIndex - 3 : -1) { setAspect(aspectValue(Self.aspects[$0 + 3])) }
            Button("Girar proporção (retrato ↔ paisagem)") { if cropAspect > 0 { setAspect(1 / cropAspect) } }
                .font(.subheadline.weight(.semibold)).foregroundStyle(Lumo.cyan)
            Button("Restaurar corte") { cropAspect = 0; crop = .none; engine.rotateCrop(angle: 0) }
                .font(.subheadline.weight(.semibold)).foregroundStyle(Lumo.secondary)
            Text("O corte é aplicado ao sair desta aba e vale só para esta foto.")
                .font(.caption2).foregroundStyle(Lumo.tertiary).frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var aspectIndex: Int {
        guard cropAspect > 0 else { return 0 }
        for i in 1..<Self.aspects.count {
            let a = aspectValue(Self.aspects[i])
            if abs(a - cropAspect) < 1e-6 || abs(1 / a - cropAspect) < 1e-6 { return i }
        }
        return 0
    }

    /// Proporção em pixels (largura/altura) na orientação da foto: "Original" é a da foto inteira.
    private func aspectValue(_ a: Double) -> Double {
        let w = Double(engine.fullSize.width), h = Double(engine.fullSize.height)
        if a < 0 { return h > 0 ? w / h : 0 }
        if a == 0 || w <= 0 { return a }
        return h > w ? 1 / a : a
    }

    private func setAspect(_ a: Double) {
        cropAspect = a
        crop = Crop.largest(Int(engine.fullSize.width), Int(engine.fullSize.height), angle: crop.angle, aspect: a)
    }

    /// Ângulo do endireitar: o recorte encolhe para continuar dentro da foto girada.
    private func setAngle(_ angle: Double) {
        let w = Int(engine.fullSize.width), h = Int(engine.fullSize.height)
        let largest = Crop.largest(w, h, angle: crop.angle, aspect: cropAspect)
        // Recorte inteiro (sem corte manual) segue o maior que cabe, como no Lightroom.
        let full = crop.isIdentity || abs((crop.right - crop.left) - (largest.right - largest.left)) < 1e-3
        crop = full ? Crop.largest(w, h, angle: angle, aspect: cropAspect)
                    : Crop(angle: angle, left: crop.left, top: crop.top, right: crop.right, bottom: crop.bottom).constrained(w, h)
        engine.rotateCrop(angle: angle)
    }

    private var colorPanel: some View {
        Group {
            lookPanel
            slider("Temperatura", \.temperature)
            slider("Matiz", \.tint)
            slider("Vibração", \.vibrance)
            slider("Saturação", \.saturation)
            Group {
                sectionTitle("Mistura de cores · " + Self.mixNames[mixColor])
                mixChips
                xmpSlider("Matiz", "HueAdjustment" + Develop.colors[mixColor])
                xmpSlider("Saturação", "SaturationAdjustment" + Develop.colors[mixColor])
                xmpSlider("Luminância", "LuminanceAdjustment" + Develop.colors[mixColor])
            }
            gradingPanel
            calibration
        }
    }

    private var calibration: some View {
        Group {
            sectionTitle("Calibração")
            xmpSlider("Vermelho · matiz", "RedHue")
            xmpSlider("Vermelho · saturação", "RedSaturation")
            xmpSlider("Verde · matiz", "GreenHue")
            xmpSlider("Verde · saturação", "GreenSaturation")
            xmpSlider("Azul · matiz", "BlueHue")
            xmpSlider("Azul · saturação", "BlueSaturation")
        }
    }

    private var effectsPanel: some View {
        Group {
            xmpSlider("Textura", "Texture")
            xmpSlider("Claridade", "Clarity2012")
            xmpSlider("Desembaçar", "Dehaze")
            Group {
                sectionTitle("Vinheta")
                xmpSlider("Quantidade", "PostCropVignetteAmount")
                xmpSlider("Ponto médio", "PostCropVignetteMidpoint", 0...100, format: "%.0f")
                xmpSlider("Arredondamento", "PostCropVignetteRoundness")
                xmpSlider("Difusão", "PostCropVignetteFeather", 0...100, format: "%.0f")
                xmpSlider("Realces", "PostCropVignetteHighlightContrast", 0...100, format: "%.0f")
            }
            Group {
                sectionTitle("Granulação")
                xmpSlider("Quantidade", "GrainAmount", 0...100, format: "%.0f")
                xmpSlider("Tamanho", "GrainSize", 0...100, format: "%.0f")
                xmpSlider("Aspereza", "GrainFrequency", 0...100, format: "%.0f")
            }
        }
    }

    private var detailPanel: some View {
        Group {
            xmpSlider("Nitidez", "Sharpness", 0...150, format: "%.0f")
            xmpSlider("Raio", "SharpenRadius", 0.5...3, step: 0.1, format: "%.1f")
            xmpSlider("Detalhe", "SharpenDetail", 0...100, format: "%.0f")
            xmpSlider("Máscara", "SharpenEdgeMasking", 0...100, format: "%.0f")
        }
    }

    private var mixChips: some View {
        HStack(spacing: 0) {
            ForEach(0..<8, id: \.self) { i in
                Circle()
                    .fill(Color(hue: Self.mixHues[i] / 360, saturation: 0.8, brightness: 0.95))
                    .frame(width: 26, height: 26)
                    .overlay(Circle().stroke(Color.white, lineWidth: mixColor == i ? 2.5 : 0))
                    .frame(maxWidth: .infinity)
                    .contentShape(Rectangle())
                    .onTapGesture { mixColor = i }
            }
        }
    }

    private func sectionTitle(_ text: String) -> some View {
        Text(text.uppercased())
            .font(.caption.weight(.bold)).tracking(1)
            .foregroundStyle(Lumo.tertiary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 8)
    }

    // MARK: Sliders

    /// Básico: mostra automático + preset + ajuste; o ajuste guardado é a diferença.
    private func slider(_ title: String, _ key: WritableKeyPath<EditControls, Double>, _ range: ClosedRange<Double> = -100...100,
                        step: Double = 1, format: String = "%+.0f") -> some View {
        let base = engine.suggested[keyPath: key]
        return row(title, value: base + controls[keyPath: key], changed: controls[keyPath: key] != 0, range: range, step: step, format: format,
                   set: { controls[keyPath: key] = $0 - base }, reset: { controls[keyPath: key] = 0 })
    }

    /// Demais ajustes: valor do preset até ser alterado no editor.
    private func xmpSlider(_ title: String, _ key: String, _ range: ClosedRange<Double> = -100...100,
                           step: Double = 1, format: String = "%+.0f") -> some View {
        let value = extra[key] ?? Develop.value(key, in: engine.preset)
        let grading = key.hasPrefix("ColorGrade") || key.hasPrefix("SplitToning")
        return row(title, value: value, changed: extra[key] != nil, range: range, step: step, format: format,
                   set: { v in
                       var e = extra
                       e[key] = v
                       if grading { touchGrading(&e) }
                       extra = e
                   }, reset: { extra[key] = nil })
    }

    private func row(_ title: String, value: Double, changed: Bool, range: ClosedRange<Double>, step: Double, format: String,
                     set: @escaping (Double) -> Void, reset: @escaping () -> Void) -> some View {
        VStack(spacing: 4) {
            HStack {
                Text(title).font(.subheadline.weight(.medium))
                Spacer()
                Text(String(format: format, value))
                    .font(.subheadline.monospacedDigit())
                    .foregroundStyle(changed ? Lumo.cyan : Lumo.secondary)
                    .onTapGesture(count: 2) { reset() }
            }
            Slider(value: Binding(get: { value }, set: { set($0) }), in: range, step: step)
                .tint(Lumo.cyan)
        }
    }
}

/// Histograma do editor, no estilo do Lightroom: os três canais somados (onde se sobrepõem fica cinza/branco)
/// e triângulos de recorte nos cantos, acesos na cor dos canais recortados. Toque para ver o recorte na foto.
struct HistogramView: View {
    let histogram: Histogram
    let clipping: Bool
    static let channels: [Color] = [Color(red: 0.6, green: 0.11, blue: 0.11), Color(red: 0.11, green: 0.54, blue: 0.17), Color(red: 0.12, green: 0.31, blue: 0.66)]

    var body: some View {
        Canvas { context, size in
            let pad: CGFloat = 6, top: CGFloat = 14
            let w = size.width - 2 * pad, h = size.height - top - pad, bottom = size.height - pad
            var grid = Path()
            for i in 1..<4 {
                let x = pad + w * CGFloat(i) / 4
                grid.move(to: CGPoint(x: x, y: top)); grid.addLine(to: CGPoint(x: x, y: bottom))
            }
            context.stroke(grid, with: .color(.white.opacity(0.13)), lineWidth: 1)
            let levels = histogram.levels()
            context.drawLayer { layer in
                layer.blendMode = .plusLighter
                for c in 0..<3 {
                    var path = Path()
                    path.move(to: CGPoint(x: pad, y: bottom))
                    for i in 0..<Histogram.bins {
                        path.addLine(to: CGPoint(x: pad + w * CGFloat(i) / CGFloat(Histogram.bins - 1), y: bottom - h * CGFloat(levels[c][i])))
                    }
                    path.addLine(to: CGPoint(x: pad + w, y: bottom))
                    path.closeSubpath()
                    layer.fill(path, with: .color(Self.channels[c]))
                }
            }
            triangle(context, x: pad, left: true, color: histogram.shadowWarning)
            triangle(context, x: size.width - pad, left: false, color: histogram.highlightWarning)
        }
        .background(Color.black.opacity(0.72), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).stroke(Color.white.opacity(clipping ? 0.8 : 0.12), lineWidth: clipping ? 1.5 : 1))
        .contentShape(Rectangle())
        .accessibilityElement()
        .accessibilityLabel(String(format: "Histograma. Realces sem detalhe %.1f%%, sombras sem detalhe %.1f%%", histogram.highlightFraction * 100, histogram.shadowFraction * 100))
        .accessibilityHint(clipping ? "Toque para esconder o recorte" : "Toque para mostrar o recorte na foto")
        .accessibilityAddTraits(.isButton)
    }

    /// Triângulo de recorte: aceso na cor dos canais recortados, apagado (contorno) sem recorte.
    private func triangle(_ context: GraphicsContext, x: CGFloat, left: Bool, color: UInt32) {
        let s: CGFloat = 9, y: CGFloat = 3
        var p = Path()
        p.move(to: CGPoint(x: x, y: y))
        p.addLine(to: CGPoint(x: left ? x + s : x - s, y: y))
        p.addLine(to: CGPoint(x: x, y: y + s))
        p.closeSubpath()
        if color == 0 {
            context.stroke(p, with: .color(.white.opacity(0.4)), lineWidth: 1)
        } else {
            if clipping { context.stroke(p, with: .color(.white), lineWidth: 2) }
            context.fill(p, with: .color(Color(red: Double((color >> 16) & 255) / 255, green: Double((color >> 8) & 255) / 255, blue: Double(color & 255) / 255)))
        }
    }
}
