import SwiftUI

/// Foto editada + informações de curadoria/entrega + editor manual (refinamento sobre o automático/preset).
struct PhotoDetailView: View {
    @EnvironmentObject private var model: AppModel
    let photoID: String
    @State private var editing = false

    var photo: PhotoRecord? { model.photos.first { $0.id == photoID } }

    var body: some View {
        if let photo, let event = model.currentEvent {
            let edited = model.library.editedURL(photo, in: event)
            List {
                Section {
                    PhotoImage(url: model.displayURL(photo) ?? edited, maxEdge: 2000)
                        .frame(maxWidth: .infinity, minHeight: 300)
                        .listRowInsets(EdgeInsets())
                }
                Section("Curadoria") {
                    LabeledContent("Nota", value: "\(photo.score)/10")
                    Text(photo.reason).font(.callout)
                    if photo.stage == .review {
                        Button { Task { await model.approve(photo) } } label: { Label("Aprovar para entrega", systemImage: "checkmark.circle") }
                    } else if photo.stage == .approved {
                        Button { Task { await model.sendToReview(photo) } } label: { Label("Mover para Revisão", systemImage: "exclamationmark.triangle") }
                    }
                }
                Section("Edição") {
                    LabeledContent("Preset", value: photo.presetName.isEmpty ? "Nenhum" : photo.presetName)
                    LabeledContent("Ajuste manual", value: photo.manual == nil ? "Não" : "Sim")
                    Button { editing = true } label: { Label("Editar manualmente", systemImage: "slider.horizontal.3") }
                    if photo.stage != .failed {
                        ShareLink(item: edited) { Label("Compartilhar JPEG", systemImage: "square.and.arrow.up") }
                    }
                }
                Section("Entrega") {
                    LabeledContent("Situação", value: uploadLabel(photo))
                    if !photo.uploadError.isEmpty { Text(photo.uploadError).font(.caption).foregroundStyle(.secondary) }
                    if photo.stage == .approved, event.fottoGallery != nil, photo.upload != .confirmed, photo.upload != .uploaded {
                        Button { model.startUpload(retryFailed: true, only: [photo.id]) } label: { Label("Enviar esta foto", systemImage: "icloud.and.arrow.up") }
                            .disabled(model.uploading)
                    }
                }
                Section("Arquivo") {
                    LabeledContent("Original", value: photo.originalName)
                    LabeledContent("Capturada", value: photo.capturedAt.formatted(date: .abbreviated, time: .standard))
                    LabeledContent("Tamanho", value: ByteCountFormatter.string(fromByteCount: Int64(photo.size), countStyle: .file))
                }
            }
            .lumoScreen()
            .navigationTitle(photo.baseName)
            .navigationBarTitleDisplayMode(.inline)
            .sheet(isPresented: $editing) { ManualEditorView(photo: photo) }
        } else {
            ContentUnavailableView("Foto indisponível", systemImage: "photo")
        }
    }

    func uploadLabel(_ p: PhotoRecord) -> String {
        switch p.upload {
        case .none: return p.stage == .approved ? "Aguardando envio" : "Bloqueada (revisão)"
        case .pending: return "Na fila"
        case .uploading: return "Enviando"
        case .uploaded: return "Enviada · aguardando confirmação"
        case .confirmed: return "Confirmada no Fotto"
        case .failed: return "Falhou"
        }
    }
}

struct ManualEditorView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let photo: PhotoRecord
    @State private var controls = EditControls()
    @State private var base = EditControls()
    @State private var saving = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("Os valores somam-se ao automático e ao preset (\(model.settings.presetName.isEmpty ? "sem preset" : model.settings.presetName)). Ponto de partida estimado: exposição \(base.exposure, specifier: "%+.2f") EV, temperatura \(base.temperature, specifier: "%+.0f").")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                Section("Luz") {
                    slider("Exposição", \.exposure, -2...2, step: 0.05, format: "%+.2f EV")
                    slider("Contraste", \.contrast, -100...100)
                    slider("Realces", \.highlights, -100...100)
                    slider("Sombras", \.shadows, -100...100)
                    slider("Brancos", \.whites, -100...100)
                    slider("Pretos", \.blacks, -100...100)
                }
                Section("Cor") {
                    slider("Temperatura", \.temperature, -100...100)
                    slider("Matiz", \.tint, -100...100)
                    slider("Vibração", \.vibrance, -100...100)
                    slider("Saturação", \.saturation, -100...100)
                }
                Section {
                    Button("Zerar ajustes", role: .destructive) { controls = EditControls() }
                }
            }
            .lumoScreen()
            .navigationTitle("Editor manual")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancelar") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    if saving { ProgressView() } else {
                        Button("Aplicar") {
                            saving = true
                            Task {
                                await model.reprocess(photo, manual: controls.isNeutral ? nil : controls)
                                saving = false
                                dismiss()
                            }
                        }
                    }
                }
            }
            .onAppear {
                controls = photo.manual ?? EditControls()
                base = model.baseControls(for: photo)
            }
        }
    }

    func slider(_ title: String, _ key: WritableKeyPath<EditControls, Double>, _ range: ClosedRange<Double>,
                step: Double = 1, format: String = "%+.0f") -> some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text(title)
                Spacer()
                Text(String(format: format, controls[keyPath: key])).monospacedDigit().foregroundStyle(.secondary)
            }
            Slider(value: Binding(get: { controls[keyPath: key] }, set: { controls[keyPath: key] = $0 }), in: range, step: step)
        }
    }
}
