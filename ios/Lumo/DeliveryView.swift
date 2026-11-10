import SwiftUI
import UniformTypeIdentifiers

struct DeliveryView: View {
    @EnvironmentObject private var model: AppModel
    @State private var showLogin = false
    @State private var search = ""
    @State private var manualID = ""
    @State private var choosing = false
    @State private var onlineConnect = false
    @State private var onlineCreate = false
    @State private var showQR = false

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
                onlineSection
            }
            .lumoScreen()
            .navigationTitle("Entrega")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { DoneButton() } }
            .sheet(isPresented: $showLogin) {
                FottoLoginView(client: model.client) { model.connectFotto($0) }
            }
            .sheet(isPresented: $choosing) { galleryPicker }
            .sheet(isPresented: $onlineConnect) { OnlineConnectForm() }
            .sheet(isPresented: $onlineCreate) { OnlineCreateForm(title: model.currentEvent?.name ?? "") }
            .sheet(isPresented: $showQR) {
                if let g = model.onlineGallery { OnlineQRView(gallery: g) }
            }
        }
    }

    /// Galeria online para os convidados (link/QR), em paralelo ao Fotto.
    @ViewBuilder private var onlineSection: some View {
        Section {
            if !model.onlineConfigured {
                Button { onlineConnect = true } label: { Label("Conectar servidor da galeria", systemImage: "link.badge.plus") }
            } else if let g = model.onlineGallery {
                LabeledContent("Galeria", value: g.title)
                HStack(spacing: 16) {
                    Stat("Publicadas", model.onlineDone)
                    Stat("Aguardando", model.onlinePending)
                }
                if !model.onlineStatus.isEmpty { Text(model.onlineStatus).font(.caption).foregroundStyle(.secondary) }
                if let url = URL(string: g.url) {
                    ShareLink(item: url, subject: Text(g.title), message: Text("Fotos do evento \(g.title)")) {
                        Label("Compartilhar link" + (g.hasPin ? " (lembre de mandar a senha)" : ""), systemImage: "square.and.arrow.up")
                    }
                }
                Button { UIPasteboard.general.string = g.url; model.onlineStatus = "Link copiado." } label: { Label("Copiar link", systemImage: "doc.on.doc") }
                Button { showQR = true } label: { Label("Mostrar QR code", systemImage: "qrcode") }
                if let admin = URL(string: OnlineGalleryAPI.server + "/admin#g=" + g.id) {
                    Link(destination: admin) { Label("Painel no navegador", systemImage: "safari") }
                }
                Toggle("Publicar automaticamente", isOn: $model.settings.onlineAuto)
                Button { model.kickOnline(manual: true) } label: {
                    Label(model.onlinePublishing ? "Publicando…" : "Enviar pendentes agora", systemImage: "icloud.and.arrow.up")
                }
                .disabled(model.onlinePublishing)
                Button("Trocar galeria deste evento", role: .destructive) { model.forgetOnlineGallery() }
                    .disabled(model.onlinePublishing)
            } else {
                Button { onlineCreate = true } label: { Label("Criar galeria deste evento", systemImage: "plus.circle") }
                    .disabled(model.currentEvent == nil)
                if !model.onlineStatus.isEmpty { Text(model.onlineStatus).font(.caption).foregroundStyle(.secondary) }
            }
            if model.onlineConfigured {
                Button { onlineConnect = true } label: {
                    Label("Trocar servidor ou chave · " + OnlineGalleryAPI.server.replacingOccurrences(of: "https://", with: ""), systemImage: "server.rack")
                }
                .font(.footnote)
            }
        } header: {
            Text("Galeria online")
        } footer: {
            Text("Os convidados abrem um link ou QR code, veem, favoritam e baixam as fotos aprovadas. Complementa o Fotto. Guia em online-gallery/README.md.")
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
    @EnvironmentObject private var lock: LumoLock
    @State private var importingPreset = false
    @State private var showTour = false
    @State private var sendingTest = false
    @State private var crashNote = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Toggle(isOn: Binding(get: { model.settings.eventMode }, set: { model.setEventMode($0) })) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Modo evento").fontWeight(.semibold)
                            Text("Tela sempre ligada, retroativo, curadoria e alarme de desconexão").font(.caption).foregroundStyle(Lumo.secondary)
                        }
                    }
                    Toggle("Alarme se a câmera desconectar", isOn: $model.settings.disconnectAlarm)
                    Button("Testar alarme") { Alerts.playAlarm() }
                    Picker("Perfil de trabalho", selection: Binding(get: { model.settings.workProfile }, set: { model.applyProfile($0) })) {
                        ForEach(LumoSettings.profiles, id: \.self) { Text($0).tag($0) }
                    }
                } header: {
                    Text("Evento")
                } footer: {
                    Text("O alarme toca três bipes mesmo com o iPhone no silencioso. Perfis ajustam rigor da curadoria e intensidade do automático.")
                }
                Section("Edição automática") {
                    Toggle("Automático (exposição, contraste, balanço)", isOn: $model.settings.autoEnabled)
                    if model.settings.autoEnabled {
                        VStack(alignment: .leading) {
                            Text("Intensidade: \(Int(model.settings.autoStrength * 100))%")
                            Slider(value: $model.settings.autoStrength, in: 0...1.5, step: 0.05)
                        }
                    }
                    Picker("Tamanho do JPEG editado", selection: $model.settings.edge) {
                        Text("Original (recomendado)").tag(0)
                        Text("3840 px").tag(3840)
                        Text("2560 px").tag(2560)
                        Text("2048 px").tag(2048)
                    }
                }
                Section {
                    Picker("Preset", selection: $model.settings.presetName) {
                        Text("Nenhum").tag("")
                        ForEach(model.presets.names, id: \.self) { Text($0).tag($0) }
                    }
                    Button { importingPreset = true } label: { Label("Importar preset .xmp", systemImage: "square.and.arrow.down") }
                    if !model.settings.presetName.isEmpty {
                        if let url = model.presetFileURL(model.settings.presetName) {
                            ShareLink(item: url) { Label("Exportar preset selecionado (.xmp)", systemImage: "square.and.arrow.up") }
                        }
                        Button("Remover preset selecionado", role: .destructive) { model.removePreset(model.settings.presetName) }
                    }
                } header: {
                    Text("Preset Lightroom")
                } footer: {
                    Text("Compatível com o Camera Raw: tons, curvas, cor, HSL, gradação, efeitos, nitidez e perfis criativos. Para criar um preset, edite uma foto e toque em Preset → Criar preset com esta edição. O .xmp exportado abre no Lightroom e no Lumo do Android.")
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
                    Text("Vai para Revisão só o que a edição não conserta: desfocada, rosto principal cortado na lateral ou embaixo e foto quase preta ou toda branca. Clara ou escura demais depois do preset: a exposição é corrigida. Duplicata só reprova no Rigorosa. A nota é sempre calculada.")
                }
                Section {
                    Toggle("Revelar na GPU", isOn: $model.settings.gpuEnabled)
                    LabeledContent("Motor", value: GpuDevelop.status)
                    if !GpuDevelop.testResult.isEmpty {
                        Text("Autoteste: \(GpuDevelop.testResult)").font(.caption).foregroundStyle(.secondary)
                    }
                } header: {
                    Text("Revelação")
                } footer: {
                    Text("Fotos grandes são reveladas na GPU (Metal). Um autoteste compara com a CPU antes da primeira foto; se divergir, tudo volta para a CPU sozinho.")
                }
                Section("Captura") {
                    Toggle("Recuperar fotos já existentes no cartão", isOn: $model.settings.backfill)
                }
                Section {
                    Toggle("Bloquear com \(LumoLock.biometryName)", isOn: Binding(get: { model.settings.lockEnabled }, set: { on in
                        model.settings.lockEnabled = on
                        if !on { lock.disabled() }
                    }))
                    .disabled(!LumoLock.available && !model.settings.lockEnabled)
                } header: {
                    Text("Conta")
                } footer: {
                    Text(LumoLock.available ? "Pede \(LumoLock.biometryName) ao abrir o Lumo e ao voltar depois de 5 minutos fora. O código do iPhone serve de alternativa. Só a tela é bloqueada: captura, edição e envio continuam."
                         : "Configure Face ID, Touch ID ou um código no iPhone para usar o bloqueio.")
                }
                Section {
                    LabeledContent("Envio ao Sentry", value: CrashReporter.configured ? "configurado" : "não configurado neste build")
                    Toggle("Enviar relatórios automaticamente", isOn: $model.settings.crashReports)
                    if CrashReporter.configured {
                        Button(sendingTest ? "Enviando…" : "Enviar relatório de teste") {
                            sendingTest = true
                            Task {
                                await CrashReporter.shared.sendTest()
                                sendingTest = false
                                crashNote = CrashReporter.pending == 0 ? "Teste enviado. Confira em Issues no Sentry." : "Sem conexão com o Sentry agora; o teste vai na próxima abertura."
                            }
                        }
                        .disabled(sendingTest || !model.settings.crashReports)
                    }
                    if FileManager.default.fileExists(atPath: CrashReporter.lastReport.path) {
                        ShareLink(item: CrashReporter.lastReport) { Label("Compartilhar último relatório", systemImage: "square.and.arrow.up") }
                    }
                    if !crashNote.isEmpty { Text(crashNote).font(.caption).foregroundStyle(.secondary) }
                } header: {
                    Text("Relatórios de falha")
                } footer: {
                    Text("Quando o Lumo fecha por erro ou trava, o relatório (aparelho, versões, onde quebrou e os últimos passos, sem fotos, conta ou nomes de eventos) é enviado na próxima abertura. Veja RELATORIOS-DE-FALHA.md.")
                }
                Section {
                    Button("Reeditar todas as fotos do evento") { Task { await model.reprocessAll() } }
                        .disabled(model.currentEvent == nil || model.processing > 0)
                } footer: {
                    Text("Aplica o preset e os ajustes atuais às fotos que ainda não foram enviadas.")
                }
                Section("Evento atual") {
                    if let url = model.manifestURL() {
                        ShareLink(item: url) { Label("Exportar manifesto da galeria online", systemImage: "doc.text") }
                    }
                    Link(destination: URL(string: "https://fotto.alboompro.com")!) {
                        Label("Abrir painel do Fotto (web)", systemImage: "safari")
                    }
                }
                Section {
                    Button { showTour = true } label: { Label("Ver o tour do Lumo", systemImage: "questionmark.circle") }
                    LabeledContent("Versão", value: AppModel.version)
                    Text("As pastas Originais, Editadas e Sob Revisao de cada evento aparecem no app Arquivos em “No meu iPhone › Lumo”.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
            .fullScreenCover(isPresented: $showTour) { TourView() }
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

/// Endereço do servidor (workers.dev) e a chave de acesso da galeria online.
struct OnlineConnectForm: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var server = OnlineGalleryAPI.server.replacingOccurrences(of: "https://", with: "")
    @State private var token = ""
    @State private var error = ""
    @State private var busy = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("lumo-galeria.suaconta.workers.dev", text: $server)
                        .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                    SecureField("Chave de acesso (ADMIN_TOKEN)", text: $token)
                } footer: {
                    Text(error.isEmpty ? "Endereço do servidor publicado na Cloudflare e a mesma chave usada no painel." : error)
                        .foregroundStyle(error.isEmpty ? Color.secondary : Lumo.danger)
                }
            }
            .lumoScreen()
            .navigationTitle("Servidor da galeria")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancelar") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    if busy { ProgressView() } else {
                        Button("Conectar") {
                            busy = true
                            Task {
                                let problem = await model.connectOnline(server: server, token: token)
                                busy = false
                                if let problem { error = problem } else { dismiss() }
                            }
                        }
                    }
                }
            }
        }
    }
}

/// Nova galeria para o evento atual: nome, senha opcional e download liberado.
struct OnlineCreateForm: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State var title: String
    @State private var pin = ""
    @State private var allowDownload = true
    @State private var error = ""
    @State private var busy = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Nome que os convidados veem", text: $title)
                    TextField("Senha (opcional, 4 ou mais letras/números)", text: $pin)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                    Toggle("Convidados podem baixar as fotos", isOn: $allowDownload)
                } footer: {
                    if !error.isEmpty { Text(error).foregroundStyle(Lumo.danger) }
                }
            }
            .lumoScreen()
            .navigationTitle("Galeria do evento")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancelar") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    if busy { ProgressView() } else {
                        Button("Criar") {
                            busy = true
                            Task {
                                let problem = await model.createOnlineGallery(title: title, pin: pin, allowDownload: allowDownload)
                                busy = false
                                if let problem { error = problem } else { dismiss() }
                            }
                        }
                    }
                }
            }
        }
    }
}

/// QR code do link da galeria, para mostrar aos convidados ou imprimir.
struct OnlineQRView: View {
    let gallery: OnlineGallery
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            VStack(spacing: 18) {
                if let image = OnlineGalleryAPI.qrCode(gallery.url) {
                    Image(uiImage: image).interpolation(.none).resizable().scaledToFit()
                        .padding(18).background(.white, in: RoundedRectangle(cornerRadius: 18))
                        .frame(maxWidth: 320)
                    ShareLink(item: Image(uiImage: image), preview: SharePreview(gallery.title, image: Image(uiImage: image))) {
                        Label("Compartilhar QR code", systemImage: "square.and.arrow.up")
                    }
                }
                Text(gallery.title).font(.title3.weight(.bold))
                Text(gallery.url).font(.footnote).foregroundStyle(.secondary).textSelection(.enabled)
            }
            .padding(24)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Lumo.background)
            .navigationTitle("QR code")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("OK") { dismiss() } } }
        }
    }
}
