import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public struct FottoGallery: Identifiable, Hashable, Codable {
    public var id: String
    public var title: String
    public init(id: String, title: String) { self.id = id; self.title = title }
}

public struct FottoGalleryListing: Equatable {
    public var items: [FottoGallery]
    /// O servidor confirmou o fim da lista.
    public var complete: Bool
    public var warning: String
}

/// Credencial capturada no login web: API key direta (cabeçalho cru) ou access token Bearer.
public struct FottoCredential: Codable, Equatable {
    public var token: String
    public var bearer: Bool
    public init(token: String, bearer: Bool) { self.token = FottoParsing.stripBearer(token); self.bearer = bearer }
}

public enum FottoError: LocalizedError, Equatable {
    case notConnected
    case sessionExpired
    case http(Int, String)
    case invalidResponse(String)
    case invalidGalleryID

    public var errorDescription: String? {
        switch self {
        case .notConnected: return "Conecte sua conta Fotto novamente."
        case .sessionExpired: return "Sessão Fotto expirada. Toque em Conectar com Fotto novamente."
        case .http(let code, let detail): return "Fotto HTTP \(code)\(detail.isEmpty ? "" : " · " + detail)"
        case .invalidResponse(let m): return m
        case .invalidGalleryID: return "Informe um ID de evento válido, obtido no painel Fotto."
        }
    }
}

/// Interpretação das respostas da API Fotto (porta de `FottoApi.java`).
/// A API não é documentada; por isso os formatos aceitos são amplos.
public enum FottoParsing {
    public static func stripBearer(_ s: String) -> String {
        let t = s.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.lowercased().hasPrefix("bearer ") { return String(t.dropFirst(7)).trimmingCharacters(in: .whitespaces) }
        return t
    }

    /// Valor plausível para token capturado da página de login.
    public static func isTokenCandidate(_ raw: String?) -> Bool {
        var s = stripBearer(raw ?? "")
        if s.count > 2, s.hasPrefix("\""), s.hasSuffix("\"") { s = String(s.dropFirst().dropLast()) }
        let low = s.lowercased()
        return s.count >= 16 && low != "undefined" && low != "null" && !low.hasPrefix("http")
    }

    public static func json(_ data: Data) throws -> Any {
        try JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
    }

    public static func string(_ v: Any?) -> String {
        switch v {
        case nil, is NSNull: return ""
        case let s as String: return s
        case let n as NSNumber:
            if isBoolean(n) { return n.boolValue ? "true" : "false" }
            // Evita "12.0" para IDs numéricos.
            let d = n.doubleValue
            if d == d.rounded(), abs(d) < 1e15 { return String(Int64(d)) }
            return n.stringValue
        default: return String(describing: v!)
        }
    }

    /// Distingue `true/false` de números JSON (no Darwin, `NSNumber(2) is Bool` é verdadeiro).
    public static func isBoolean(_ v: Any) -> Bool {
        #if canImport(Darwin)
        if let n = v as? NSNumber { return CFGetTypeID(n) == CFBooleanGetTypeID() }
        #endif
        return v is Bool
    }

    public static func findArray(_ v: Any?, key: String) -> [Any]? {
        if let o = v as? [String: Any] {
            if let direct = o[key] as? [Any] { return direct }
            for k in o.keys.sorted() { if let a = findArray(o[k], key: key) { return a } }
        } else if let a = v as? [Any] {
            for x in a { if let r = findArray(x, key: key) { return r } }
        }
        return nil
    }

    public static func findObject(_ v: Any?, key: String) -> [String: Any]? {
        if let o = v as? [String: Any] {
            if o[key] != nil { return o }
            for k in o.keys.sorted() { if let x = findObject(o[k], key: key) { return x } }
        } else if let a = v as? [Any] {
            for x in a { if let r = findObject(x, key: key) { return r } }
        }
        return nil
    }

    public static func galleryArray(_ root: Any) -> [Any]? {
        if let a = root as? [Any] { return a }
        guard let obj = root as? [String: Any] else { return nil }
        if let a = obj["galleries"] as? [Any] { return a }
        if let a = obj["data"] as? [Any] { return a }
        if let d = obj["data"] as? [String: Any] {
            if let a = d["galleries"] as? [Any] { return a }
            if let a = d["items"] as? [Any] { return a }
        }
        if let a = obj["items"] as? [Any] { return a }
        return findArray(root, key: "galleries")
    }

    public static func galleries(from rows: [Any]) -> [FottoGallery] {
        rows.compactMap { row in
            guard let g = row as? [String: Any] else { return nil }
            let id = string(g["id"])
            guard !id.isEmpty else { return nil }
            var title = (g["title"] as? String) ?? (g["name"] as? String) ?? ""
            if title.isEmpty { title = "Evento \(id)" }
            return FottoGallery(id: id, title: title)
        }
    }

    static let scopeKeys = ["meta", "pagination", "paging", "pageInfo", "page_info", "links"]

    static func paginationScopes(_ root: Any) -> [[String: Any]] {
        guard let top = root as? [String: Any] else { return [] }
        var scopes = [top]
        for k in scopeKeys { if let n = top[k] as? [String: Any] { scopes.append(n) } }
        if let data = top["data"] as? [String: Any] {
            scopes.append(data)
            for k in scopeKeys { if let n = data[k] as? [String: Any] { scopes.append(n) } }
        }
        return scopes
    }

    static func field(_ root: Any, _ keys: [String]) -> Any? {
        for o in paginationScopes(root) {
            for k in keys { if let v = o[k], !(v is NSNull) { return v } }
        }
        return nil
    }

    static func number(_ root: Any, _ keys: [String]) -> Int? {
        guard let v = field(root, keys) else { return nil }
        if let n = v as? NSNumber, !isBoolean(n) { return n.intValue }
        return Int(string(v))
    }

    static func hasPaginationInfo(_ root: Any) -> Bool {
        field(root, ["page", "currentPage", "current_page", "lastPage", "totalPages", "total_pages", "last_page", "nextPage",
                     "next_page", "hasNextPage", "has_more", "hasMore", "nextCursor", "next_cursor"]) != nil
    }

    static func paginationEnded(_ root: Any) -> Bool {
        let more = field(root, ["hasNextPage", "hasMore", "has_more"])
        if let m = more, isBoolean(m), (m as? Bool) == false { return true }
        if let s = more as? String, s.lowercased() == "false" { return true }
        if let page = number(root, ["page", "currentPage", "current_page"]),
           let total = number(root, ["lastPage", "totalPages", "last_page", "total_pages"]) { return page >= total }
        return false
    }

    /// Caminho relativo da próxima página, aceitando somente o host da API.
    public static func nextGalleryPath(_ root: Any) -> String? {
        guard var next = field(root, ["next", "nextPageUrl", "next_page_url", "nextPage", "next_page", "nextCursor", "next_cursor"]) else { return nil }
        if let o = next as? [String: Any] {
            guard let n = o["href"] ?? o["url"] else { return nil }
            next = n
        }
        if next is NSNull || isBoolean(next) { return nil }
        var v = string(next).trimmingCharacters(in: .whitespaces)
        if v.isEmpty || v.lowercased() == "null" { return nil }
        if v.allSatisfy(\.isNumber) { return "/me/galleries?page=" + v }
        if v.hasPrefix("?") { return "/me/galleries" + v }
        if v.hasPrefix("http://") || v.hasPrefix("https://") {
            guard let c = URLComponents(string: v), c.scheme?.lowercased() == "https", c.host?.lowercased() == "api.fotto.com.br" else { return nil }
            v = c.percentEncodedPath + (c.percentEncodedQuery.map { "?" + $0 } ?? "")
        }
        if v.hasPrefix("/api/me/galleries") { v = String(v.dropFirst(4)) }
        if v == "/me/galleries" || v.hasPrefix("/me/galleries?") { return v }
        if !v.contains("/") && !v.contains("?") { return "/me/galleries?cursor=" + encode(v) }
        return nil
    }

    public static func encode(_ s: String) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return s.addingPercentEncoding(withAllowedCharacters: allowed) ?? s
    }

    /// Mídias do evento: id → nome original, somente as já processadas.
    public static func processedMedia(_ root: Any) -> [String: String] {
        var arr = findArray(root, key: "medias")
        if arr == nil, let o = root as? [String: Any] {
            if let d = o["data"] as? [Any] { arr = d } else if let d = o["data"] { arr = findArray(d, key: "medias") }
        }
        var out: [String: String] = [:]
        for row in arr ?? [] {
            guard let m = row as? [String: Any], let p = m["processed"], isBoolean(p), (p as? Bool) == true else { continue }
            let id = string(m["id"])
            let original = (m["originalFileName"] as? String) ?? (m["name"] as? String) ?? ""
            if !id.isEmpty { out[id] = original }
        }
        return out
    }

    /// Corpo de POST /me/galleries/{id}/medias: uma LISTA JSON (formato observado no HAR).
    public static func createMediaBody(name: String, size: Int64) throws -> Data {
        try JSONSerialization.data(withJSONObject: [["originalFileName": name, "mediaSize": size] as [String: Any]])
    }

    public static func signedUpload(_ root: Any) throws -> (url: URL, mediaID: String) {
        guard let media = findObject(root, key: "signedUrl") else {
            throw FottoError.invalidResponse("CREATE_MEDIA_ARRAY · o Fotto não retornou signedUrl.")
        }
        let signed = string(media["signedUrl"])
        guard !signed.isEmpty, let url = URL(string: signed) else { throw FottoError.invalidResponse("CREATE_MEDIA_ARRAY · URL assinada vazia.") }
        return (url, string(media["id"]))
    }

    public static func compact(_ s: String) -> String {
        let c = s.split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
        return c.count > 350 ? String(c.prefix(350)) + "…" : c
    }

    /// Aceita o ID puro ou um link do painel contendo `/galleries/ID`.
    public static func galleryID(fromInput raw: String) -> String? {
        var s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if let r = s.range(of: "/galleries/") {
            s = String(s[r.upperBound...].prefix(while: { $0 != "/" && $0 != "?" && $0 != "#" }))
        }
        let ok = (3...120).contains(s.count) && s.unicodeScalars.allSatisfy {
            CharacterSet.alphanumerics.contains($0) && $0.isASCII || $0 == "_" || $0 == "-"
        }
        return ok ? s : nil
    }
}

/// Camada HTTP substituível nos testes.
public protocol FottoHTTP {
    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse)
    func upload(_ request: URLRequest, fromFile file: URL) async throws -> (Data, HTTPURLResponse)
}

public struct URLSessionFottoHTTP: FottoHTTP {
    public let session: URLSession
    public init(session: URLSession = .shared) { self.session = session }

    public func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw FottoError.invalidResponse("Resposta sem HTTP.") }
        return (data, http)
    }

    public func upload(_ request: URLRequest, fromFile file: URL) async throws -> (Data, HTTPURLResponse) {
        let (data, response) = try await session.upload(for: request, fromFile: file)
        guard let http = response as? HTTPURLResponse else { throw FottoError.invalidResponse("Resposta sem HTTP.") }
        return (data, http)
    }
}

/// Cliente da API Fotto: lista eventos, cria mídia, envia direto ao S3 e confirma.
public final class FottoClient {
    public static let base = "https://api.fotto.com.br/api"
    public static let userAgent = "Lumo/0.2.0 iOS"

    public var credential: FottoCredential?
    let http: FottoHTTP
    let sleep: (UInt64) async -> Void

    public init(credential: FottoCredential?, http: FottoHTTP = URLSessionFottoHTTP(), sleep: ((UInt64) async -> Void)? = nil) {
        self.credential = credential
        self.http = http
        self.sleep = sleep ?? { ns in try? await Task.sleep(nanoseconds: ns) }
    }

    func request(_ method: String, _ path: String, body: Data?, credential c: FottoCredential) throws -> URLRequest {
        guard let url = URL(string: Self.base + path) else { throw FottoError.invalidResponse("Caminho inválido: \(path)") }
        var r = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 45)
        r.httpMethod = method
        r.setValue("application/json", forHTTPHeaderField: "Accept")
        r.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        r.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
        r.setValue("fotto", forHTTPHeaderField: "App-Code")
        r.setValue(c.bearer ? "Bearer " + c.token : c.token, forHTTPHeaderField: "Authorization")
        r.httpBody = body
        return r
    }

    /// Chamada com retentativas suaves para 429/502/503/504 e falhas de rede.
    public func call(_ method: String, _ path: String, body: Data? = nil, credential override: FottoCredential? = nil) async throws -> Data {
        guard let c = override ?? credential else { throw FottoError.notConnected }
        var last: Error = FottoError.invalidResponse("Falha de comunicação com o Fotto.")
        for attempt in 0..<4 {
            do {
                let (data, response) = try await http.send(try request(method, path, body: body, credential: c))
                let code = response.statusCode
                if (200..<300).contains(code) { return data }
                if code == 401 { throw FottoError.sessionExpired }
                let text = FottoParsing.compact(String(decoding: data.prefix(4096), as: UTF8.self))
                let error = FottoError.http(code, "em \(path)" + (text.isEmpty ? "" : " · " + text))
                if [429, 502, 503, 504].contains(code) && attempt < 3 {
                    last = error
                    var wait = 900 * Double(attempt + 1)
                    if let retry = response.value(forHTTPHeaderField: "Retry-After").flatMap(Double.init) { wait = max(wait, retry * 1000) }
                    await sleep(UInt64(min(wait, 6000)) * 1_000_000)
                    continue
                }
                throw error
            } catch let e as FottoError {
                // Erros HTTP definitivos e sessão expirada não são repetidos.
                throw e
            } catch {
                last = error
                if attempt >= 3 { throw error }
                await sleep(UInt64(700 * (attempt + 1)) * 1_000_000)
            }
        }
        throw last
    }

    /// Testa um token capturado: primeiro como API key, depois como Bearer.
    public func validate(token raw: String) async -> FottoCredential? {
        for bearer in [false, true] {
            let c = FottoCredential(token: raw, bearer: bearer)
            if (try? await call("GET", "/me", credential: c)) != nil { return c }
        }
        return nil
    }

    /// Lista completa de eventos, seguindo a paginação (explícita ou inferida) como no Android 0.15.21.
    public func galleries() async throws -> FottoGalleryListing {
        _ = try await call("GET", "/me")
        var unique: [FottoGallery] = []
        var seenIDs = Set<String>()
        var path = "/me/galleries"
        var requested = Set<String>()
        var triedPageOne = false, implicitPaging = false, complete = false
        var warning = ""
        var inferredPage = 1
        for request in 0..<300 {
            guard requested.insert(path).inserted else { warning = "Paginação repetida pelo Fotto."; break }
            let root: Any
            do { root = try FottoParsing.json(try await call("GET", path)) } catch {
                if unique.isEmpty { throw error }
                warning = "A busca foi interrompida na página \(inferredPage): \(error.localizedDescription)"
                break
            }
            guard let rows = FottoParsing.galleryArray(root) else {
                warning = "A API do Fotto não retornou a lista esperada de eventos."; break
            }
            var added = 0
            for g in FottoParsing.galleries(from: rows) where seenIDs.insert(g.id).inserted {
                unique.append(g); added += 1
            }
            if rows.isEmpty { complete = true; break }
            if unique.count >= 20000 { warning = "Mais de 20 mil eventos: limite de proteção atingido."; break }
            if let next = FottoParsing.nextGalleryPath(root) {
                if added == 0 && request > 0 { warning = "O Fotto repetiu uma página de eventos."; break }
                path = next; implicitPaging = false; inferredPage += 1; continue
            }
            if FottoParsing.paginationEnded(root) { complete = true; break }
            if FottoParsing.hasPaginationInfo(root) {
                let current = FottoParsing.number(root, ["page", "currentPage", "current_page"])
                let totalPages = FottoParsing.number(root, ["totalPages", "lastPage", "total_pages", "last_page", "pages"])
                if let current, let totalPages, current >= totalPages { complete = true; break }
                if added == 0 && request > 0 { warning = "O Fotto repetiu a página \(inferredPage)."; break }
                inferredPage = current.map { $0 + 1 } ?? inferredPage + 1
                path = "/me/galleries?page=\(inferredPage)"; continue
            }
            // Paginação não documentada: testa page=1 uma vez (API 0 ou 1-based).
            if !triedPageOne {
                triedPageOne = true; implicitPaging = true; path = "/me/galleries?page=1"; continue
            }
            if added == 0 {
                if implicitPaging && inferredPage == 1 { inferredPage = 2; path = "/me/galleries?page=2"; continue }
                complete = false
                warning = "O Fotto não confirmou a paginação; alguns eventos antigos podem não vir pela API."
                break
            }
            inferredPage = implicitPaging && inferredPage == 1 ? 2 : inferredPage + 1
            implicitPaging = false
            path = "/me/galleries?page=\(inferredPage)"
        }
        if requested.count >= 300 && !complete { warning = "Limite de 300 páginas atingido." }
        return FottoGalleryListing(items: unique, complete: complete, warning: warning)
    }

    public func gallery(id raw: String) async throws -> FottoGallery {
        guard let id = FottoParsing.galleryID(fromInput: raw) else { throw FottoError.invalidGalleryID }
        let root = try FottoParsing.json(try await call("GET", "/me/galleries/" + FottoParsing.encode(id)))
        var gallery: [String: Any]?
        if let top = root as? [String: Any] {
            gallery = top["gallery"] as? [String: Any]
            if gallery == nil, let data = top["data"] as? [String: Any] { gallery = (data["gallery"] as? [String: Any]) ?? data }
            if gallery == nil { gallery = top }
        }
        guard let g = gallery, FottoParsing.string(g["id"]) == id else {
            throw FottoError.invalidResponse("O Fotto não confirmou o ID deste evento. Confira o ID no painel do fotógrafo.")
        }
        let title = (g["title"] as? String) ?? (g["name"] as? String) ?? ""
        return FottoGallery(id: id, title: title.isEmpty ? "Evento \(id)" : title)
    }

    /// Cria a mídia e envia o arquivo direto para a URL assinada. Devolve o ID da mídia.
    public func upload(file: URL, name: String, galleryID: String) async throws -> String {
        let attributes = try FileManager.default.attributesOfItem(atPath: file.path)
        let size = (attributes[.size] as? NSNumber)?.int64Value ?? 0
        guard size > 0 else { throw FottoError.invalidResponse("CREATE_MEDIA_ARRAY · arquivo vazio: \(name)") }
        let path = "/me/galleries/" + FottoParsing.encode(galleryID) + "/medias"
        let response: Data
        do {
            response = try await call("POST", path, body: try FottoParsing.createMediaBody(name: name, size: size))
        } catch {
            throw FottoError.invalidResponse("CREATE_MEDIA_ARRAY · \(name) · \(size) bytes · \(error.localizedDescription)")
        }
        let (signed, mediaID) = try FottoParsing.signedUpload(try FottoParsing.json(response))
        var put = URLRequest(url: signed, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 120)
        put.httpMethod = "PUT"
        put.setValue(name.lowercased().hasSuffix(".png") ? "image/png" : "image/jpeg", forHTTPHeaderField: "Content-Type")
        put.setValue("attachment; filename*=UTF-8''" + FottoParsing.encode(name), forHTTPHeaderField: "Content-Disposition")
        let (body, http) = try await self.http.upload(put, fromFile: file)
        guard (200..<300).contains(http.statusCode) else {
            let text = FottoParsing.compact(String(decoding: body.prefix(4096), as: UTF8.self))
            throw FottoError.invalidResponse("UPLOAD_BINÁRIO HTTP \(http.statusCode)" + (text.isEmpty ? "" : " · " + text))
        }
        return mediaID
    }

    public func processedMedia(galleryID: String) async throws -> [String: String] {
        FottoParsing.processedMedia(try FottoParsing.json(try await call("GET", "/me/galleries/" + FottoParsing.encode(galleryID) + "/medias")))
    }

    /// Pede ao site do Fotto para atualizar o cache do evento (melhor esforço).
    public func revalidate(galleryID: String) async {
        guard let url = URL(string: "https://www.fotto.com.br/api/revalidate/medias/" + FottoParsing.encode(galleryID) + "?type=image") else { return }
        var r = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 10)
        r.setValue("application/json", forHTTPHeaderField: "Accept")
        r.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
        _ = try? await http.send(r)
    }
}
