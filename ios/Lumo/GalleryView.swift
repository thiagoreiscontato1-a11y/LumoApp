import ImageIO
import SwiftUI

/// Miniaturas geradas pelo ImageIO, com cache em memória.
final class Thumbnails {
    static let shared = Thumbnails()
    private let cache = NSCache<NSString, UIImage>()

    func image(for url: URL, maxEdge: Int = 360) async -> UIImage? {
        let key = "\(url.path)#\(maxEdge)#\((try? url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate?.timeIntervalSince1970) ?? 0)" as NSString
        if let hit = cache.object(forKey: key) { return hit }
        let image = await Task.detached(priority: .userInitiated) { () -> UIImage? in
            guard let src = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
            let opts: [CFString: Any] = [kCGImageSourceCreateThumbnailFromImageAlways: true,
                                         kCGImageSourceCreateThumbnailWithTransform: true,
                                         kCGImageSourceThumbnailMaxPixelSize: maxEdge]
            return CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary).map { UIImage(cgImage: $0) }
        }.value
        if let image { cache.setObject(image, forKey: key) }
        return image
    }
}

struct PhotoThumb: View {
    let url: URL
    var maxEdge = 360
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            Color(.secondarySystemBackground)
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                ProgressView()
            }
        }
        .clipped()
        .task(id: url) { image = await Thumbnails.shared.image(for: url, maxEdge: maxEdge) }
    }
}

struct GalleryView: View {
    @EnvironmentObject private var model: AppModel
    private let columns = [GridItem(.adaptive(minimum: 104), spacing: 2)]

    var body: some View {
        NavigationStack {
            Group {
                if let event = model.currentEvent, !model.approved.isEmpty {
                    ScrollView {
                        LazyVGrid(columns: columns, spacing: 2) {
                            ForEach(model.approved) { photo in
                                NavigationLink(value: photo.id) {
                                    PhotoThumb(url: model.library.editedURL(photo, in: event))
                                        .aspectRatio(1, contentMode: .fit)
                                        .overlay(alignment: .bottomLeading) { UploadBadge(photo: photo) }
                                        .overlay(alignment: .topTrailing) {
                                            Text("\(photo.score)").font(.caption2.bold()).padding(4)
                                                .background(.ultraThinMaterial, in: Circle()).padding(4)
                                        }
                                }
                            }
                        }
                    }
                } else {
                    ContentUnavailableView("Sem fotos aprovadas", systemImage: "photo",
                                           description: Text("As fotos editadas e aprovadas pela curadoria aparecem aqui."))
                }
            }
            .navigationTitle("Galeria")
            .navigationDestination(for: String.self) { id in PhotoDetailView(photoID: id) }
        }
    }
}

struct UploadBadge: View {
    let photo: PhotoRecord

    var body: some View {
        let (icon, color): (String?, Color) = {
            switch photo.upload {
            case .confirmed: return ("checkmark.icloud.fill", .green)
            case .uploaded: return ("icloud.and.arrow.up.fill", .blue)
            case .uploading: return ("arrow.up.circle", .blue)
            case .failed: return ("exclamationmark.icloud.fill", .red)
            default: return (nil, .clear)
            }
        }()
        if let icon {
            Image(systemName: icon).foregroundStyle(color).padding(4)
                .background(.ultraThinMaterial, in: Circle()).padding(4)
        }
    }
}

struct ReviewView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        NavigationStack {
            Group {
                if let event = model.currentEvent, !model.underReview.isEmpty {
                    List(model.underReview) { photo in
                        NavigationLink(value: photo.id) {
                            HStack(spacing: 12) {
                                PhotoThumb(url: photo.stage == .failed ? model.library.originalURL(photo, in: event) : model.library.editedURL(photo, in: event), maxEdge: 200)
                                    .frame(width: 64, height: 64)
                                    .clipShape(RoundedRectangle(cornerRadius: 6))
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(photo.originalName).font(.subheadline.bold())
                                    Text(photo.reason).font(.caption).foregroundStyle(.secondary).lineLimit(3)
                                    Text("Nota \(photo.score)/10").font(.caption2)
                                }
                            }
                        }
                        .swipeActions {
                            if photo.stage == .review {
                                Button("Aprovar") { Task { await model.approve(photo) } }.tint(.green)
                            }
                        }
                    }
                } else {
                    ContentUnavailableView("Nada para revisar", systemImage: "checkmark.seal",
                                           description: Text("Fotos reprovadas pela curadoria ficam aqui e não são enviadas até serem aprovadas."))
                }
            }
            .navigationTitle("Revisão")
            .navigationDestination(for: String.self) { id in PhotoDetailView(photoID: id) }
        }
    }
}
