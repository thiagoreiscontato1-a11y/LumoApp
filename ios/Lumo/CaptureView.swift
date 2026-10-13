import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

struct CaptureView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var capture: CaptureController
    @State private var pickerItems: [PhotosPickerItem] = []
    @State private var showFiles = false
    @State private var importing = false

    var body: some View {
        NavigationStack {
            List {
                if model.currentEvent == nil {
                    Section {
                        Label("Crie ou escolha um evento no topo antes de conectar a câmera.", systemImage: "info.circle")
                    }
                }
                Section("Câmera") {
                    VStack(alignment: .leading, spacing: 6) {
                        HStack {
                            Text(capture.activeCamera.isEmpty ? "Nenhuma câmera" : capture.activeCamera).font(.headline)
                            Spacer()
                            if let mode = capture.mode { Text(mode.rawValue).font(.caption).foregroundStyle(.secondary) }
                        }
                        Text(capture.status).font(.callout).foregroundStyle(.secondary)
                        if case .failed(let reason) = capture.phase {
                            Text(reason).font(.caption).foregroundStyle(.red)
                        }
                        HStack(spacing: 16) {
                            Stat("Recebidas", capture.received)
                            Stat("Retroativas", capture.recovered)
                            Stat("Na fila", capture.backlogCount)
                        }
                        .padding(.top, 4)
                    }
                    if capture.isRunning {
                        Button(role: .destructive) { capture.stop() } label: { Label("Encerrar conexão", systemImage: "stop.circle") }
                    } else {
                        Button { capture.connectUSB() } label: { Label("Conectar câmera USB", systemImage: "cable.connector") }
                            .disabled(model.currentEvent == nil)
                    }
                    if !capture.cameraNames.isEmpty {
                        Text("Detectada: " + capture.cameraNames.joined(separator: ", ")).font(.caption).foregroundStyle(.secondary)
                    }
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
                    Text("Ative o Wi-Fi da câmera em modo “Conectar ao computador/EOS Utility”, conecte o iPhone à rede da câmera e autorize LUMO no visor.")
                }
                Section {
                    PhotosPicker(selection: $pickerItems, maxSelectionCount: 200, matching: .images) {
                        Label("Importar do app Fotos", systemImage: "photo.stack")
                    }
                    Button { showFiles = true } label: { Label("Importar do cartão / Arquivos", systemImage: "sdcard") }
                    if importing { ProgressView("Importando…") }
                } header: {
                    Text("Importar")
                } footer: {
                    Text("Com um leitor de cartão USB, escolha a pasta DCIM no app Arquivos. O Lumo percorre as subpastas e importa os JPEGs; RAW é ignorado nesta versão.")
                }
                .disabled(model.currentEvent == nil)
                if !capture.log.isEmpty {
                    Section("Registro") {
                        ForEach(Array(capture.log.prefix(60).enumerated()), id: \.offset) { _, line in
                            Text(line).font(.caption.monospaced())
                        }
                    }
                }
            }
            .navigationTitle("Captura")
            .onAppear { capture.startBrowsing() }
            .onChange(of: pickerItems) { _, items in
                guard !items.isEmpty else { return }
                importing = true
                Task {
                    var loaded: [(Data, String?)] = []
                    for item in items {
                        if let data = try? await item.loadTransferable(type: Data.self) { loaded.append((data, nil)) }
                    }
                    pickerItems = []
                    await model.importPhotoData(loaded)
                    importing = false
                }
            }
            .fileImporter(isPresented: $showFiles, allowedContentTypes: [.folder, .jpeg, .image], allowsMultipleSelection: true) { result in
                switch result {
                case .success(let urls):
                    importing = true
                    Task {
                        await model.importFiles(urls)
                        importing = false
                    }
                case .failure(let error):
                    model.message = "Acesso a Arquivos falhou: \(error.localizedDescription)"
                }
            }
        }
    }
}

struct Stat: View {
    let title: String
    let value: Int

    init(_ title: String, _ value: Int) {
        self.title = title
        self.value = value
    }

    var body: some View {
        VStack(alignment: .leading) {
            Text("\(value)").font(.title3.monospacedDigit().bold())
            Text(title).font(.caption2).foregroundStyle(.secondary)
        }
    }
}
