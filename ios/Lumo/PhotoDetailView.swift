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
                    LabeledContent("Ajuste manual", value: photo.manual == nil && photo.extra == nil ? "Não" : "Sim")
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
            .fullScreenCover(isPresented: $editing) { LiveEditor(photo: photo) }
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
