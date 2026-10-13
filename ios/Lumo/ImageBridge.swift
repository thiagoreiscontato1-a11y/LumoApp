import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

enum ImageBridgeError: LocalizedError {
    case decode, encode, tooLarge

    var errorDescription: String? {
        switch self {
        case .decode: return "Não foi possível abrir o JPEG."
        case .encode: return "Falha ao gerar JPEG."
        case .tooLarge: return "Foto muito grande para editar neste aparelho."
        }
    }
}

/// Conversão entre arquivos (JPEG/HEIC) e `PixelImage`, aplicando a orientação EXIF.
enum ImageBridge {
    /// Decodifica com o maior lado limitado a `maxEdge` (0 = original) e orientação já aplicada.
    static func decode(_ data: Data, maxEdge: Int) throws -> PixelImage {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { throw ImageBridgeError.decode }
        return try decode(source: source, maxEdge: maxEdge)
    }

    static func decode(url: URL, maxEdge: Int) throws -> PixelImage {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil) else { throw ImageBridgeError.decode }
        return try decode(source: source, maxEdge: maxEdge)
    }

    static func pixelSize(_ source: CGImageSource) -> (Int, Int) {
        let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any] ?? [:]
        let w = (props[kCGImagePropertyPixelWidth] as? NSNumber)?.intValue ?? 0
        let h = (props[kCGImagePropertyPixelHeight] as? NSNumber)?.intValue ?? 0
        return (w, h)
    }

    static func decode(source: CGImageSource, maxEdge: Int) throws -> PixelImage {
        let (w, h) = pixelSize(source)
        guard w > 0, h > 0 else { throw ImageBridgeError.decode }
        let edge = maxEdge > 0 ? min(maxEdge, max(w, h)) : max(w, h)
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: edge,
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { throw ImageBridgeError.decode }
        return try pixels(from: image)
    }

    static var bitmapInfo: UInt32 { CGImageAlphaInfo.noneSkipFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue }

    /// Desenha em memória BGRX little-endian, que lida como `UInt32` vira 0xXXRRGGBB.
    static func pixels(from image: CGImage) throws -> PixelImage {
        let w = image.width, h = image.height
        guard w > 0, h > 0 else { throw ImageBridgeError.decode }
        guard w * h <= 40_000_000 else { throw ImageBridgeError.tooLarge }
        var buffer = [UInt32](repeating: 0, count: w * h)
        let ok = buffer.withUnsafeMutableBytes { raw -> Bool in
            guard let ctx = CGContext(data: raw.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                                      space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: bitmapInfo) else { return false }
            ctx.interpolationQuality = .high
            ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
            return true
        }
        guard ok else { throw ImageBridgeError.decode }
        for i in 0..<buffer.count { buffer[i] |= 0xFF00_0000 }
        return PixelImage(width: w, height: h, pixels: buffer)
    }

    static func cgImage(_ image: PixelImage) throws -> CGImage {
        let data = image.pixels.withUnsafeBytes { Data($0) }
        guard let provider = CGDataProvider(data: data as CFData),
              let cg = CGImage(width: image.width, height: image.height, bitsPerComponent: 8, bitsPerPixel: 32,
                               bytesPerRow: image.width * 4, space: CGColorSpace(name: CGColorSpace.sRGB)!,
                               bitmapInfo: CGBitmapInfo(rawValue: bitmapInfo), provider: provider, decode: nil,
                               shouldInterpolate: true, intent: .defaultIntent) else { throw ImageBridgeError.encode }
        return cg
    }

    /// Grava JPEG (qualidade 95%) preservando EXIF/IPTC do original, com orientação normalizada.
    static func writeJPEG(_ image: PixelImage, to url: URL, metadataFrom original: Data? = nil, quality: Double = 0.95) throws {
        let cg = try cgImage(image)
        guard let dest = CGImageDestinationCreateWithURL(url as CFURL, UTType.jpeg.identifier as CFString, 1, nil) else {
            throw ImageBridgeError.encode
        }
        var props: [CFString: Any] = [:]
        if let original, let src = CGImageSourceCreateWithData(original as CFData, nil),
           let p = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any] {
            for key in [kCGImagePropertyExifDictionary, kCGImagePropertyIPTCDictionary, kCGImagePropertyGPSDictionary] {
                if let v = p[key] { props[key] = v }
            }
            if var tiff = p[kCGImagePropertyTIFFDictionary] as? [CFString: Any] {
                tiff[kCGImagePropertyTIFFOrientation] = 1
                props[kCGImagePropertyTIFFDictionary] = tiff
            }
        }
        props[kCGImagePropertyOrientation] = 1
        props[kCGImageDestinationLossyCompressionQuality] = quality
        CGImageDestinationAddImage(dest, cg, props as CFDictionary)
        guard CGImageDestinationFinalize(dest) else { throw ImageBridgeError.encode }
    }

    /// Data de captura do EXIF (DateTimeOriginal), quando existir.
    static func captureDate(_ data: Data) -> Date? {
        guard let src = CGImageSourceCreateWithData(data as CFData, nil),
              let p = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any],
              let exif = p[kCGImagePropertyExifDictionary] as? [CFString: Any],
              let s = exif[kCGImagePropertyExifDateTimeOriginal] as? String else { return nil }
        return PTPDate.parse(s)
    }

    /// Converte HEIC/PNG/etc. para JPEG mantendo resolução (fotos vindas do app Fotos).
    static func jpegData(from data: Data) throws -> Data {
        guard let src = CGImageSourceCreateWithData(data as CFData, nil) else { throw ImageBridgeError.decode }
        if let type = CGImageSourceGetType(src) as String?, type == UTType.jpeg.identifier { return data }
        let image = try decode(source: src, maxEdge: 0)
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".jpg")
        defer { try? FileManager.default.removeItem(at: tmp) }
        try writeJPEG(image, to: tmp, metadataFrom: data)
        return try Data(contentsOf: tmp)
    }
}
