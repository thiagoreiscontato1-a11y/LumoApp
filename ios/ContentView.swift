import SwiftUI
import PhotosUI
import CoreImage
import UIKit
import UniformTypeIdentifiers
import ExternalAccessory

struct ContentView: View {
    @State private var eventName = ""
    @State private var events: [String] = []
    @State private var photoItem: PhotosPickerItem?
    @State private var photo: UIImage?
    @State private var brightness = 0.0
    @State private var contrast = 1.0
    @State private var saturation = 1.0
    @State private var exportURL: URL?
    @State private var showCameraFiles = false
    @State private var cameraImportStatus = "Nenhuma foto importada por Arquivos."
    @State private var usbDiagnostic = "Diagnóstico ainda não executado."
    var body: some View {
        NavigationStack {
            Form {
                Section("Eventos") {
                    HStack {
                        TextField("Nome", text: $eventName)
                        Button("Criar") {
                            guard !eventName.isEmpty else { return }
                            events.append(eventName)
                            eventName = ""
                        }
                    }
                    ForEach(events, id: \.self) { Text($0) }
                }
                Section("Canon via USB — teste de acesso") {
                    Text("Teste inicial: abra Arquivos e verifique se o iPhone disponibiliza as imagens da câmera conectada. Isto NÃO detecta a câmera diretamente nem ativa captura automática.")
                        .font(.footnote)
                    Button("Procurar fotos da câmera em Arquivos") { showCameraFiles = true }
                    Text(cameraImportStatus).font(.caption)
                }
                Section("Diagnóstico USB — acessórios") {
                    Text("Verifica somente acessórios expostos pelo ExternalAccessory. Câmeras USB/PTP podem não aparecer nesta lista, mesmo reconhecidas pelo app Fotos.")
                        .font(.footnote)
                    Button("Verificar acessórios disponíveis") {
                        let accessories = EAAccessoryManager.shared().connectedAccessories
                        if accessories.isEmpty {
                            usbDiagnostic = "Nenhum acessório ExternalAccessory encontrado. Isto NÃO confirma ausência da Canon no USB."
                        } else {
                            usbDiagnostic = accessories.map { accessory in
                                let protocols = accessory.protocolStrings.joined(separator: ", ")
                                return "\(accessory.name) — \(accessory.manufacturer) — protocolos: \(protocols)"
                            }.joined(separator: "\n")
                    }
                    Text(usbDiagnostic).font(.caption)
                }
                Section("Foto") {
                    PhotosPicker("Importar foto", selection: $photoItem, matching: .images)
                    if let photo { Image(uiImage: photo).resizable().scaledToFit() }
                }
                if photo != nil {
                    Section("Edição") {
                        Text("Brilho")
                        Slider(value: $brightness, in: -1...1)
                        Text("Contraste")
                        Slider(value: $contrast, in: 0.5...1.5)
                        Text("Saturação")
                        Slider(value: $saturation, in: 0...2)
                        if let edited = render() {
                            Image(uiImage: edited).resizable().scaledToFit()
                            Button("Preparar JPEG") {
                                guard let data = edited.jpegData(compressionQuality: 0.9) else { return }
                                let url = FileManager.default.temporaryDirectory.appendingPathComponent("lumo.jpg")
                                try? data.write(to: url)
                                exportURL = url
                            }
                            if let exportURL { ShareLink("Salvar ou compartilhar JPEG", item: exportURL) }
                        }
                    }
                }
            }
            .navigationTitle("Lumo")
            .fileImporter(isPresented: $showCameraFiles, allowedContentTypes: [.image, .jpeg, .png, .rawImage], allowsMultipleSelection: false) { result in
                switch result {
                case .success(let urls):
                    guard let url = urls.first else { return }
                    let accessed = url.startAccessingSecurityScopedResource()
                    defer { if accessed { url.stopAccessingSecurityScopedResource() } }
                    do {
                        let data = try Data(contentsOf: url)
                        if let image = UIImage(data: data) {
                            photo = image
                            cameraImportStatus = "Imagem importada de Arquivos: \(url.lastPathComponent)"
                        } else {
                            cameraImportStatus = "Arquivo selecionado, mas formato não suportado na prévia."
                        }
                    } catch {
                        cameraImportStatus = "Não foi possível ler o arquivo: \(error.localizedDescription)"
                    }
                case .failure(let error):
                    cameraImportStatus = "Acesso a Arquivos falhou: \(error.localizedDescription)"
                }
            }
            .onChange(of: photoItem) { _, item in
                Task {
                    if let data = try? await item?.loadTransferable(type: Data.self) {
                        photo = UIImage(data: data)
                    }
                }
            }
        }
    }
    func render() -> UIImage? {
        guard let photo, let input = CIImage(image: photo),
              let filter = CIFilter(name: "CIColorControls") else { return nil }
        filter.setValue(input, forKey: kCIInputImageKey)
        filter.setValue(brightness, forKey: kCIInputBrightnessKey)
        filter.setValue(contrast, forKey: kCIInputContrastKey)
        filter.setValue(saturation, forKey: kCIInputSaturationKey)
        let context = CIContext()
        guard let output = filter.outputImage, let cg = context.createCGImage(output, from: output.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}
