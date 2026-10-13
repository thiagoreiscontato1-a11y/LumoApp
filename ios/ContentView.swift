import SwiftUI
import PhotosUI
import CoreImage
import UIKit

struct ContentView: View {
    @State private var eventName = ""
    @State private var events: [String] = []
    @State private var photoItem: PhotosPickerItem?
    @State private var photo: UIImage?
    @State private var brightness = 0.0
    @State private var contrast = 1.0
    @State private var saturation = 1.0
    @State private var exportURL: URL?
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
