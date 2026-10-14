import SwiftUI
import UIKit

/// Renderiza a prévia do editor fora da thread principal: a base (automático + preset)
/// é calculada uma vez; cada movimento de controle aplica só o ajuste manual.
@MainActor
final class EditorEngine: ObservableObject {
    @Published private(set) var before: UIImage?
    @Published private(set) var rendered: UIImage?
    @Published private(set) var loading = true
    @Published private(set) var suggested = EditControls()

    private var original: PixelImage?
    private var base: PixelImage?
    private var renderTask: Task<Void, Never>?
    private var baseTask: Task<Void, Never>?
    static let previewEdge = 1280

    func load(url: URL, options: EditOptions, manual: EditControls) {
        loading = true
        baseTask?.cancel()
        baseTask = Task {
            let decoded = await Task.detached(priority: .userInitiated) { try? ImageBridge.decode(url: url, maxEdge: Self.previewEdge) }.value
            guard let decoded, !Task.isCancelled else { loading = false; return }
            original = decoded
            before = Self.image(decoded)
            suggested = PhotoEditor.baseControls(decoded.downsampled(maxEdge: 480).pixels, options: options)
            rebase(options: options, manual: manual)
        }
    }

    /// Recalcula a base quando o preset muda.
    func rebase(options: EditOptions, manual: EditControls) {
        guard let original else { return }
        loading = true
        baseTask?.cancel()
        baseTask = Task {
            var opts = options
            opts.manual = nil
            let result = await Task.detached(priority: .userInitiated) { () -> PixelImage in
                var img = original
                PhotoEditor.edit(&img, options: opts)
                return img
            }.value
            guard !Task.isCancelled else { return }
            base = result
            loading = false
            render(manual)
        }
    }

    /// Aplica os controles manuais sobre a base; renderizações antigas são canceladas.
    func render(_ manual: EditControls) {
        guard let base else { return }
        renderTask?.cancel()
        renderTask = Task {
            let image = await Task.detached(priority: .userInitiated) { () -> UIImage? in
                var px = base
                PhotoEditor.applyManual(&px.pixels, manual)
                return Self.image(px)
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
/// Segure a foto para ver o original (antes).
struct LiveEditor: View {
    enum Panel: String, CaseIterable { case light = "Luz", color = "Cor", preset = "Preset" }

    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let photo: PhotoRecord
    @StateObject private var engine = EditorEngine()
    @State private var controls = EditControls()
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
            presetChoice = photo.customPreset
            if let event = model.currentEvent {
                engine.load(url: model.library.originalURL(photo, in: event), options: currentOptions, manual: controls)
            }
        }
        .onChange(of: controls) { _, c in engine.render(c) }
        .onChange(of: presetChoice) { _, _ in engine.rebase(options: currentOptions, manual: controls) }
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
                        await model.reprocess(photo, manual: controls.isNeutral ? nil : controls, customPreset: .some(presetChoice))
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
                    case .light:
                        slider("Exposição", \.exposure, -2...2, step: 0.05, format: "%+.2f")
                        slider("Contraste", \.contrast)
                        slider("Realces", \.highlights)
                        slider("Sombras", \.shadows)
                        slider("Brancos", \.whites)
                        slider("Pretos", \.blacks)
                    case .color:
                        slider("Temperatura", \.temperature)
                        slider("Matiz", \.tint)
                        slider("Vibração", \.vibrance)
                        slider("Saturação", \.saturation)
                    case .preset:
                        presetList
                    }
                }
                .padding(.horizontal, 18)
                .padding(.vertical, 6)
            }
            HStack(spacing: 10) {
                Button { controls = EditControls() } label: { Label("Zerar", systemImage: "arrow.counterclockwise") }
                Spacer()
                Button {
                    var p = photo
                    p.manual = controls.isNeutral ? nil : controls
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

    private func slider(_ title: String, _ key: WritableKeyPath<EditControls, Double>, _ range: ClosedRange<Double> = -100...100,
                        step: Double = 1, format: String = "%+.0f") -> some View {
        VStack(spacing: 4) {
            HStack {
                Text(title).font(.subheadline.weight(.medium))
                Spacer()
                Text(String(format: format, controls[keyPath: key]))
                    .font(.subheadline.monospacedDigit())
                    .foregroundStyle(controls[keyPath: key] == 0 ? Lumo.tertiary : Lumo.cyan)
                    .onTapGesture(count: 2) { controls[keyPath: key] = 0 }
            }
            Slider(value: Binding(get: { controls[keyPath: key] }, set: { controls[keyPath: key] = $0 }), in: range, step: step)
                .tint(Lumo.cyan)
        }
    }
}
