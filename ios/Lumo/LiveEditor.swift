import SwiftUI
import UIKit

/// Renderiza a prévia do editor fora da thread principal: a foto é decodificada uma vez e cada
/// movimento de controle renderiza automático + preset + ajuste manual de uma vez, como o
/// Lightroom; renderizações antigas são canceladas.
@MainActor
final class EditorEngine: ObservableObject {
    @Published private(set) var before: UIImage?
    @Published private(set) var rendered: UIImage?
    @Published private(set) var loading = true
    /// Básicos do automático + preset: o valor que os sliders mostram sem ajuste manual.
    @Published private(set) var suggested = EditControls()
    /// Preset em uso, para mostrar o valor dos demais sliders.
    @Published private(set) var preset: [String: Double]?

    private var source: Develop.Source?
    private var options = EditOptions()
    private var manual = EditControls()
    private var extra: [String: Double] = [:]
    private var renderTask: Task<Void, Never>?
    private var loadTask: Task<Void, Never>?
    static let previewEdge = 1280

    func load(url: URL, options: EditOptions, manual: EditControls, extra: [String: Double]) {
        loading = true
        setOptions(options)
        self.manual = manual
        self.extra = extra
        loadTask?.cancel()
        loadTask = Task {
            let decoded = await Task.detached(priority: .userInitiated) { try? ImageBridge.decode(url: url, maxEdge: Self.previewEdge) }.value
            guard let decoded, !Task.isCancelled else { loading = false; return }
            let source = Develop.Source(decoded)
            self.source = source
            before = Self.image(decoded)
            updateSuggested()
            loading = false
            render(self.manual, self.extra)
        }
    }

    /// Troca o preset (ou as opções do evento) e renderiza de novo.
    func rebase(options: EditOptions, manual: EditControls, extra: [String: Double]) {
        setOptions(options)
        updateSuggested()
        render(manual, extra)
    }

    private func setOptions(_ options: EditOptions) {
        var o = options
        o.manual = nil
        o.extra = nil
        self.options = o
        preset = o.preset
    }

    private func updateSuggested() {
        guard let source else { return }
        suggested = PhotoEditor.autoValues(source, options: options) + PhotoEditor.presetControls(options.preset)
    }

    /// Renderiza a prévia com o ajuste manual atual.
    func render(_ manual: EditControls, _ extra: [String: Double]) {
        self.manual = manual
        self.extra = extra
        guard let source else { return }
        var o = options
        o.manual = manual.isNeutral ? nil : manual
        o.extra = extra.isEmpty ? nil : extra
        renderTask?.cancel()
        renderTask = Task {
            let image = await Task.detached(priority: .userInitiated) { () -> UIImage? in
                Self.image(PhotoEditor.render(source, options: o))
            }.value
            guard !Task.isCancelled, let image else { return }
            rendered = image
        }
    }

    nonisolated static func image(_ p: PixelImage) -> UIImage? {
        (try? ImageBridge.cgImage(p)).map { UIImage(cgImage: $0) }
    }
}

/// Editor manual com a foto sempre visível: cada ajuste aparece na hora.
/// Segure a foto para ver o original (antes). Como no Lightroom, cada slider mostra o valor final
/// (automático + preset + ajuste); toque duas vezes no número para voltar ao valor do preset.
struct LiveEditor: View {
    enum Panel: String, CaseIterable { case light = "Luz", color = "Cor", effects = "Efeitos", detail = "Detalhe", preset = "Preset" }
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
            if let event = model.currentEvent {
                engine.load(url: model.library.originalURL(photo, in: event), options: currentOptions, manual: controls, extra: extra)
            }
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
                                              customPreset: .some(presetChoice))
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

    private var preview: some View {
        ZStack(alignment: .topLeading) {
            Color.black
            if let image = showingBefore ? engine.before : (engine.rendered ?? engine.before) {
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
        .overlay(alignment: .bottom) {
            Text("Segure a foto para comparar com o original")
                .font(.caption2).foregroundStyle(Lumo.tertiary).padding(.bottom, 6)
        }
    }

    private var controlsPanel: some View {
        VStack(spacing: 10) {
            Picker("Grupo", selection: $group) {
                ForEach(Panel.allCases, id: \.self) { Text($0.rawValue).tag($0) }
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 16)
            ScrollView {
                VStack(spacing: 14) {
                    switch group {
                    case .light: lightPanel
                    case .color: colorPanel
                    case .effects: effectsPanel
                    case .detail: detailPanel
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
            presetRow("Preset do evento" + (model.settings.presetName.isEmpty ? " (nenhum)" : " (\(model.settings.presetName))"), value: nil)
            presetRow("Sem preset", value: "")
            ForEach(model.presets.names, id: \.self) { presetRow($0, value: $0) }
            if model.presets.names.isEmpty {
                Text("Importe presets .xmp em Ajustes para usá-los aqui.").font(.caption).foregroundStyle(Lumo.tertiary)
            }
        }
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
        }
    }

    private var colorPanel: some View {
        Group {
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
        return row(title, value: value, changed: extra[key] != nil, range: range, step: step, format: format,
                   set: { extra[key] = $0 }, reset: { extra[key] = nil })
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
