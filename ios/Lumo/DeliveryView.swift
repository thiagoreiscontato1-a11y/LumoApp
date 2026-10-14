import SwiftUI
import UniformTypeIdentifiers

struct DeliveryView: View {
    @EnvironmentObject private var model: AppModel
    @State private var showLogin = false
    @State private var search = ""
    @State private var manualID = ""
    @State private var choosing = false

    var body: some View {
        NavigationStack {
            List {
                Section("Conta Fotto") {
                    if model.fottoConnected {
                        Label("Conectada", systemImage: "checkmark.circle.fill").foregroundStyle(.green)
                        Button("Reconectar") { showLogin = true }
                        Button("Desconectar", role: .destructive) { model.disconnectFotto() }
                    } else {
                        Button { showLogin = true } label: { Label("Conectar com Fotto", systemImage: "person.crop.circle.badge.plus") }
                    }
                }
                if model.fottoConnected {
                    Section {
                        if let g = model.currentEvent?.fottoGallery {
                            LabeledContent("Destino", value: g.title)
                            Text("ID \(g.id)").font(.caption).foregroundStyle(.secondary)
                        } else {
                            Text("Nenhum evento Fotto escolhido para este evento local.").foregroundStyle(.secondary)
                        }
                        Button(model.currentEvent?.fottoGallery == nil ? "Escolher evento Fotto" : "Trocar evento Fotto") {
                            choosing = true
                            if model.fottoGalleries.isEmpty { Task { await model.loadGalleries() } }
                        }
                        .disabled(model.currentEvent == nil || model.uploading)
                    } header: {
                        Text("Evento de destino")
                    } footer: {
                        Text("O evento local e o evento do Fotto são independentes; o destino é lembrado por evento local.")
                    }
                    if model.currentEvent?.fottoGallery != nil {
                        Section {
                            Toggle("Envio direto automático", isOn: $model.settings.fottoAuto)
                                .onChange(of: model.settings.fottoAuto) { _, on in if on { model.startUpload() } }
                            HStack(spacing: 16) {
                                Stat("Pendentes", model.pendingUploads)
                                Stat("Enviadas", model.sentCount)
                                Stat("Confirmadas", model.confirmedCount)
                                Stat("Falhas", model.failedUploads)
                            }
                            if model.uploading {
                                Button(role: .destructive) { model.stopUpload() } label: { Label("Encerrar envio", systemImage: "stop.circle") }
                            } else {
                                Button { model.startUpload(retryFailed: true) } label: { Label("Enviar pendentes agora", systemImage: "icloud.and.arrow.up") }
                                Button { model.reconcileNow() } label: { Label("Conferir confirmações", systemImage: "arrow.triangle.2.circlepath") }
                            }
                        } header: {
                            Text("Envio")
                        } footer: {
                            Text("Fluxo: Editadas → criar mídia no Fotto → envio direto ao armazenamento → confirmação. Uma foto aceita pelo armazenamento nunca é reenviada automaticamente. Fotos em Revisão não são enviadas.")
                        }
                    }
                }
                if !model.fottoStatus.isEmpty {
                    Section("Situação") { Text(model.fottoStatus).font(.callout) }
                }
            }
            .lumoScreen()
            .navigationTitle("Entrega")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { DoneButton() } }
            .sheet(isPresented: $showLogin) {
                FottoLoginView(client: model.client) { model.connectFotto($0) }
            }
            .sheet(isPresented: $choosing) { galleryPicker }
        }
    }

    var filtered: [FottoGallery] {
        let q = search.trimmingCharacters(in: .whitespaces).lowercased()
        guard !q.isEmpty else { return model.fottoGalleries }
        return model.fottoGalleries.filter { $0.title.lowercased().contains(q) || $0.id.lowercased().contains(q) }
    }

    var galleryPicker: some View {
        NavigationStack {
            List {
                if !model.fottoListingWarning.isEmpty {
                    Section { Text(model.fottoListingWarning).font(.caption).foregroundStyle(.orange) }
                }
                Section {
                    HStack {
                        TextField("ID ou link do painel (/galleries/ID)", text: $manualID)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                        Button("Verificar") {
                            Task {
                                await model.findGallery(id: manualID)
                                if model.currentEvent?.fottoGallery != nil { choosing = false }
                            }
                        }
                    }
                } header: {
                    Text("Informar ID do evento")
                } footer: {
                    Text("Use se o evento não aparecer na lista. Confira o nome antes de enviar.")
                }
                Section("Eventos (\(filtered.count))") {
                    if model.fottoLoading { ProgressView("Carregando lista completa…") }
                    ForEach(filtered) { g in
                        Button {
                            model.chooseGallery(g)
                            choosing = false
                        } label: {
                            VStack(alignment: .leading) {
                                Text(g.title)
                                Text("ID \(g.id)").font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }
            .searchable(text: $search, prompt: "Buscar por nome ou ID")
            .lumoScreen()
            .navigationTitle("Evento Fotto")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Fechar") { choosing = false } }
                ToolbarItem(placement: .primaryAction) {
                    Button { Task { await model.loadGalleries() } } label: { Image(systemName: "arrow.clockwise") }
                        .disabled(model.fottoLoading)
                }
            }
        }
    }
}

struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @State private var importingPreset = false

    var body: some View {
        NavigationStack {
            Form {
                Section("Edição automática") {
                    Toggle("Automático (exposição, contraste, balanço)", isOn: $model.settings.autoEnabled)
                    if model.settings.autoEnabled {
                        VStack(alignment: .leading) {
                            Text("Intensidade: \(Int(model.settings.autoStrength * 100))%")
                            Slider(value: $model.settings.autoStrength, in: 0...1.5, step: 0.05)
                        }
                    }
                    Picker("Tamanho do JPEG editado", selection: $model.settings.edge) {
                        Text("2048 px").tag(2048)
                        Text("2560 px").tag(2560)
                        Text("3840 px").tag(3840)
                        Text("Original").tag(0)
                    }
                }
                Section {
                    Picker("Preset", selection: $model.settings.presetName) {
                        Text("Nenhum").tag("")
                        ForEach(model.presets.names, id: \.self) { Text($0).tag($0) }
                    }
                    Button { importingPreset = true } label: { Label("Importar preset .xmp", systemImage: "square.and.arrow.down") }
                    if !model.settings.presetName.isEmpty {
                        Button("Remover preset selecionado", role: .destructive) { model.removePreset(model.settings.presetName) }
                    }
                } header: {
                    Text("Preset Lightroom")
                } footer: {
                    Text("Compatível com ajustes básicos do Camera Raw: exposição, contraste, tons, temperatura, HSL, claridade, nitidez e vinheta.")
                }
                Section {
                    Toggle("Curadoria bloqueia fotos ruins", isOn: $model.settings.curation)
                    Picker("Rigor", selection: $model.settings.curationSensitivity) {
                        Text("Tolerante").tag(0)
                        Text("Equilibrada").tag(1)
                        Text("Rigorosa").tag(2)
                    }
                    .pickerStyle(.segmented)
                } header: {
                    Text("Curadoria")
                } footer: {
                    Text("Avalia nitidez, exposição, contraste, clipping, enquadramento e duplicatas. A nota é sempre calculada; a opção controla apenas o bloqueio para Revisão.")
                }
                Section("Captura") {
                    Toggle("Recuperar fotos já existentes no cartão", isOn: $model.settings.backfill)
                }
                Section {
                    Button("Reeditar todas as fotos do evento") { Task { await model.reprocessAll() } }
                        .disabled(model.currentEvent == nil || model.processing > 0)
                } footer: {
                    Text("Aplica o preset e os ajustes atuais às fotos que ainda não foram enviadas.")
                }
                Section {
                    LabeledContent("Versão", value: AppModel.version)
                    Text("As pastas Originais, Editadas e Sob Revisao de cada evento aparecem no app Arquivos em “No meu iPhone › Lumo”.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
            .lumoScreen()
            .navigationTitle("Ajustes")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { DoneButton() } }
            .fileImporter(isPresented: $importingPreset, allowedContentTypes: [.xml, .data], allowsMultipleSelection: false) { result in
                if case .success(let urls) = result, let url = urls.first { model.importPreset(from: url) }
            }
        }
    }
}
