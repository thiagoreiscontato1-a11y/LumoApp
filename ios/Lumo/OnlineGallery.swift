import CoreImage
import CoreImage.CIFilterBuiltins
import Foundation
import ImageIO
import UIKit
import UniformTypeIdentifiers

/// Galeria online do Lumo para os convidados (Cloudflare Worker em `online-gallery/worker`), igual à do Android 0.18.0:
/// cada foto aprovada do evento vai em três variantes (miniatura 640 px, tela cheia 2048 px e o JPEG editado completo).
/// O servidor confere tamanho e SHA-256 de cada arquivo e só publica depois do "complete". Corre ao lado do Fotto.
struct OnlineGallery: Codable, Equatable, Identifiable {
    var id: String
    var slug: String
    var title: String
    var url: String
    var hasPin: Bool

    init(id: String, slug: String, title: String, url: String, hasPin: Bool) {
        self.id = id; self.slug = slug; self.title = title; self.url = url; self.hasPin = hasPin
    }

    init?(json: [String: Any]) {
        guard let id = json["id"] as? String, !id.isEmpty else { return nil }
        self.init(id: id, slug: json["slug"] as? String ?? "", title: json["title"] as? String ?? "", url: json["url"] as? String ?? "",
                  hasPin: json["hasPin"] as? Bool ?? false)
    }
}

enum OnlineGalleryError: LocalizedError {
    case message(String)
    var errorDescription: String? { if case .message(let m) = self { return m }; return nil }
}

/// Cliente HTTP da galeria online. Endereço e chave ficam no Keychain.
enum OnlineGalleryAPI {
    static let thumbEdge = 640, previewEdge = 2048

    static var server: String {
        get { Keychain.get("lgServer").flatMap { String(data: $0, encoding: .utf8) } ?? "" }
        set { Keychain.set(newValue.isEmpty ? nil : Data(newValue.utf8), for: "lgServer") }
    }
    static var token: String {
        get { Keychain.get("lgToken").flatMap { String(data: $0, encoding: .utf8) } ?? "" }
        set { Keychain.set(newValue.isEmpty ? nil : Data(newValue.utf8), for: "lgToken") }
    }
    static var configured: Bool { !server.isEmpty && !token.isEmpty }

    /// Aceita "lumo-galeria.conta.workers.dev", com ou sem https:// e barra final.
    static func normalize(_ value: String) -> String {
        var s = value.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.isEmpty { return "" }
        if !s.lowercased().hasPrefix("http://") && !s.lowercased().hasPrefix("https://") { s = "https://" + s }
        while s.hasSuffix("/") { s.removeLast() }
        if let r = s.range(of: "/admin"), r.lowerBound > s.startIndex { s = String(s[..<r.lowerBound]) }
        return s
    }

    /// Galeria ligada a cada evento local.
    static func gallery(for eventID: String) -> OnlineGallery? {
        guard let data = UserDefaults.standard.data(forKey: "lumo.onlineGallery." + eventID) else { return nil }
        return try? JSONDecoder().decode(OnlineGallery.self, from: data)
    }
    static func save(_ g: OnlineGallery?, for eventID: String) {
        let key = "lumo.onlineGallery." + eventID
        if let g, let data = try? JSONEncoder().encode(g) { UserDefaults.standard.set(data, forKey: key) } else { UserDefaults.standard.removeObject(forKey: key) }
    }

    static func call(_ method: String, _ path: String, body: [String: Any]? = nil, server: String = server, token: String = token) async throws -> [String: Any] {
        guard let url = URL(string: server + path) else { throw OnlineGalleryError.message("Endereço da galeria inválido.") }
        var req = URLRequest(url: url, timeoutInterval: 45)
        req.httpMethod = method
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        req.setValue("Bearer " + token, forHTTPHeaderField: "Authorization")
        if let body {
            req.httpBody = try JSONSerialization.data(withJSONObject: body)
            req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        }
        return try await send(req)
    }

    static func send(_ req: URLRequest, upload: Data? = nil) async throws -> [String: Any] {
        let data: Data, response: URLResponse
        do {
            if let upload { (data, response) = try await URLSession.shared.upload(for: req, from: upload) } else { (data, response) = try await URLSession.shared.data(for: req) }
        } catch let e as URLError where e.code == .cannotFindHost || e.code == .dnsLookupFailed {
            throw OnlineGalleryError.message("Endereço não encontrado: \(req.url?.host ?? "")")
        }
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        if (200..<300).contains(code) { return json }
        let msg = json["error"] as? String ?? ""
        if code == 401 { throw OnlineGalleryError.message("Chave da galeria recusada. Confira a chave em Galeria online.") }
        if code == 404 && msg.isEmpty { throw OnlineGalleryError.message("Servidor da galeria não encontrado. Confira o endereço.") }
        throw OnlineGalleryError.message(msg.isEmpty ? "Galeria online respondeu \(code)" : msg)
    }

    /// Confere endereço e chave; devolve o nome do estúdio configurado no servidor.
    static func check(server: String, token: String) async throws -> String {
        try await call("GET", "/v1/me", server: server, token: token)["studio"] as? String ?? "Lumo"
    }

    static func createGallery(title: String, clientKey: String, pin: String, allowDownload: Bool) async throws -> OnlineGallery {
        var body: [String: Any] = ["title": title, "clientKey": clientKey, "allowDownload": allowDownload]
        let p = pin.trimmingCharacters(in: .whitespaces)
        if !p.isEmpty { body["pin"] = p }
        guard let g = (try await call("POST", "/v1/galleries", body: body))["gallery"] as? [String: Any], let gallery = OnlineGallery(json: g) else {
            throw OnlineGalleryError.message("Resposta inválida da galeria online.")
        }
        return gallery
    }

    /// Publica uma foto editada. Seguro para repetir: o servidor reconhece variantes já recebidas.
    static func publish(galleryID: String, photoID: String, revision: Int64, name: String, takenAt: Date, file: URL) async throws -> String {
        let full = try Data(contentsOf: file)
        guard let source = CGImageSourceCreateWithData(full as CFData, nil) else { throw ImageBridgeError.decode }
        // A editada é gravada com orientação normal: o tamanho do arquivo é o tamanho de exibição.
        let (width, height) = ImageBridge.pixelSize(source)
        let large = try ImageBridge.decode(source: source, maxEdge: previewEdge)
        let preview = try jpeg(large, quality: 0.86)
        let thumb = try jpeg(large.downsampled(maxEdge: thumbEdge), quality: 0.80)
        let files = ["thumb": thumb, "preview": preview, "full": full]
        var variants: [String: Any] = [:]
        for (k, v) in files { variants[k] = ["size": v.count, "sha256": PhotoHash.sha256Hex(v)] }
        let body: [String: Any] = ["galleryId": galleryID, "clientPhotoId": photoID, "revision": revision, "filename": name,
                                   "takenAt": Int64(takenAt.timeIntervalSince1970 * 1000), "width": width, "height": height,
                                   "variants": variants]
        let plan = try await call("POST", "/v1/photos", body: body)
        guard let remote = plan["photoId"] as? String else { throw OnlineGalleryError.message("Resposta inválida da galeria online.") }
        if plan["ready"] as? Bool == true { return remote }
        for up in plan["uploads"] as? [[String: Any]] ?? [] where up["done"] as? Bool != true {
            guard let variant = up["variant"] as? String, let data = files[variant], let path = up["url"] as? String,
                  let url = URL(string: server + path) else { continue }
            var req = URLRequest(url: url, timeoutInterval: 120)
            req.httpMethod = "PUT"
            req.setValue("Bearer " + token, forHTTPHeaderField: "Authorization")
            req.setValue("image/jpeg", forHTTPHeaderField: "Content-Type")
            _ = try await send(req, upload: data)
        }
        guard let complete = plan["completeUrl"] as? String else { throw OnlineGalleryError.message("Resposta inválida da galeria online.") }
        _ = try await call("POST", complete, body: ["revision": revision])
        return remote
    }

    static func jpeg(_ image: PixelImage, quality: Double) throws -> Data {
        let cg = try ImageBridge.cgImage(image)
        let out = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(out, UTType.jpeg.identifier as CFString, 1, nil) else { throw ImageBridgeError.encode }
        CGImageDestinationAddImage(dest, cg, [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary)
        guard CGImageDestinationFinalize(dest) else { throw ImageBridgeError.encode }
        return out as Data
    }

    /// QR code do link, para mostrar ou imprimir.
    static func qrCode(_ text: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 12, y: 12)),
              let cg = CIContext().createCGImage(output, from: output.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}
