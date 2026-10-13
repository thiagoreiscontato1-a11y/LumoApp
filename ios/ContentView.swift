import SwiftUI
import PhotosUI
import UIKit

struct LumoEvent: Identifiable, Codable {
    var id = UUID()
    var name: String
    var createdAt = Date()
}

struct ContentView: View {
    @State private var events: [LumoEvent] = []
    @State private var eventName = ""
    @State private var selectedEvent: UUID?
    @State private var selectedPhotos: [PhotosPickerItem] = []
    @State private var photos: [UIImage] = []
    @State private var exposure = 0.0
    @State private var contrast = 1.0
    @State private var saturation = 1.0
    @State private var exported: UIImage?
    @State private var showingShare = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text("Eventos").font(.headline)
                    HStack {
                        TextField("Nome do evento", text: $eventName).textFieldStyle(.roundedBorder)
                        Button("Criar") {
                            let name = eventName.trimmingCharacters(in: .whitespacesAndNewlines)
                            guard !name.isEmpty else { return }
                            let event = LumoEvent(name: name)
                            events.append(event)
                            selectedEvent = event.id
                            eventName = ""
                            saveEvents()
                        }
                    }
                    ForEach(events) { event in
                        Button(event.name + (selectedEvent == event.id ? " ✓" : "")) {
                            selectedEvent = event.id
                            photos = []
                        }
                    }
                    if selectedEvent != nil {
                        PhotosPicker("Importar fotos", selection: $selectedPhotos, maxSelectionCount: 20, matching: .images)
                        if !photos.isEmpty {
                            Text("Fotos importadas: \\(photos.count)")
                            ForEach(photos.indices, id: \\.self) { index in
                                Image(uiImage: photos[index]).resizable().scaledToFit().frame(maxHeight: 260)
                            }
                            Text("Editar primeira foto").font(.headline)
                            Slider(value: $exposure, in: -1...1) { Text("Exposição") }
                            Text("Exposição: \\(exposure, specifier: "%.2f")")
                            Slider(value: $contrast, in: 0.5...1.5) { Text("Contraste") }
                            Text("Contraste: \\(contrast, specifier: "%.2f")")
                            Slider(value: $saturation, in: 0...2) { Text("Saturação") }
                            Text("Saturação: \\(saturation, specifier: "%.2f")")
                            if let preview = renderedPhoto() {
                                Image(uiImage: preview).resizable().scaledToFit().frame(maxHeight: 300)
                                ShareLink(item: Image(uiImage: preview), preview: SharePreview("Lumo", image: Image(uiImage: preview))) {
                                    Text("Compartilhar foto editada")
                                }
                                Button("Salvar JPEG em Arquivos") {
                                    guard let data = preview.jpegData(compressionQuality: 0.92) else { return }
                                    let url = FileManager.default.temporaryDirectory.appendingPathComponent("lumo-\\(UUID().uuidString).jpg")
                                    try? data.write(to: url)
                                    exportURL = url
                                    showingShare = true
                                }
                            }
                        }
                    }
                }.padding()
            }
            .navigationTitle("Lumo")
            .onAppear(perform: loadEvents)
            .onChange(of: selectedPhotos) { _, items in
                Task {
                    var images: [UIImage] = []
                    for item in items {
                        if let data = try? await item.loadTransferable(type: Data.self), let image = UIImage(data: data) { images.append(image) }
                    }
                    photos = images
                }
            }
            .sheet(isPresented: $showingShare) {
                if let exportURL { ShareSheet(items: [exportURL]) }
            }
        }
    }
    @State private var exportURL: URL?
    private func renderedPhoto() -> UIImage? {
        guard let original = photos.first, let input = CIImage(image: original) else { return nil }
        let color = CIFilter(name: "CIColorControls")!
        color.setValue(input, forKey: kCIInputImageKey)
        color.setValue(contrast, forKey: kCIInputContrastKey)
        color.setValue(saturation, forKey: kCIInputSaturationKey)
        color.setValue(exposure, forKey: kCIInputBrightnessKey)
        let context = CIContext()
        guard let output = color.outputImage, let cg = context.createCGImage(output, from: output.extent) else { return nil }
        return UIImage(cgImage: cg, scale: original.scale, orientation: original.imageOrientation)
    }
    private func saveEvents() {
        if let data = try? JSONEncoder().encode(events) { UserDefaults.standard.set(data, forKey: "lumo.events") }
    }
    private func loadEvents() {
        if let data = UserDefaults.standard.data(forKey: "lumo.events"), let value = try? JSONDecoder().decode([LumoEvent].self, from: data) { events = value }
    }
}

struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController { UIActivityViewController(activityItems: items, applicationActivities: nil) }
    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
