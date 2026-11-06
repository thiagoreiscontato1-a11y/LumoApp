import Foundation
#if canImport(FoundationXML)
import FoundationXML
#endif

public enum XMPPresetError: LocalizedError, Equatable {
    case invalid
    case empty
    case value(String)

    public var errorDescription: String? {
        switch self {
        case .invalid: return "XMP inválido ou maior que 1 MB."
        case .empty: return "Nenhum ajuste compatível encontrado no XMP."
        case .value(let key): return "Valor inválido: \(key)"
        }
    }
}

/// Leitura de presets Lightroom/Camera Raw (.xmp), como `Develop.parse` no Android: ajustes em
/// atributos ou elementos e curvas de tons em `rdf:Seq` (gravadas como chaves planas `Nome.0`, `Nome.1`…).
public enum XMPPreset {
    public static let namespace = "http://ns.adobe.com/camera-raw-settings/1.0/"

    /// Chaves Camera Raw com valor numérico suportadas pelo motor.
    public static var keys: Set<String> { Set(Develop.ranges.keys) }

    public static func parse(_ xml: String) throws -> [String: Double] {
        guard xml.utf8.count <= 1024 * 1024, !xml.contains("<!DOCTYPE"), !xml.contains("<!ENTITY"),
              let data = xml.data(using: .utf8) else { throw XMPPresetError.invalid }
        return try parse(data)
    }

    public static func parse(_ data: Data) throws -> [String: Double] {
        guard data.count <= 1024 * 1024 else { throw XMPPresetError.invalid }
        if let text = String(data: data, encoding: .utf8), text.contains("<!DOCTYPE") || text.contains("<!ENTITY") {
            throw XMPPresetError.invalid
        }
        let delegate = Delegate()
        let parser = XMLParser(data: data)
        parser.shouldProcessNamespaces = true
        parser.shouldResolveExternalEntities = false
        parser.delegate = delegate
        let ok = parser.parse()
        if let error = delegate.error { throw error }
        guard ok else { throw XMPPresetError.invalid }
        var result = delegate.result
        for i in 0..<4 {
            guard let points = delegate.curves[Develop.curves[i]] ?? delegate.curves[Develop.legacyCurves[i]], points.count >= 2 else { continue }
            for (j, p) in points.enumerated() {
                result["\(Develop.curves[i]).\(2 * j)"] = p.0
                result["\(Develop.curves[i]).\(2 * j + 1)"] = p.1
            }
        }
        guard !result.isEmpty else { throw XMPPresetError.empty }
        return result
    }

    static func validated(_ key: String, _ text: String) throws -> Double? {
        guard let range = Develop.ranges[key] else { return nil }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let v: Double
        if trimmed.lowercased() == "true" { v = 1 }
        else if trimmed.lowercased() == "false" { v = 0 }
        else {
            guard let n = number(trimmed) else { throw XMPPresetError.value(key) }
            v = n
        }
        guard v.isFinite, range.contains(v) else { throw XMPPresetError.value(key) }
        return v
    }

    static func number(_ text: String) -> Double? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return Double(t.hasPrefix("+") ? String(t.dropFirst()) : t)
    }

    static func point(_ text: String) throws -> (Double, Double) {
        let parts = text.split(separator: ",", omittingEmptySubsequences: false)
        guard parts.count == 2, let x = number(String(parts[0])), let y = number(String(parts[1])),
              (0...255).contains(x), (0...255).contains(y) else { throw XMPPresetError.value("curva") }
        return (x, y)
    }

    static func isCurve(_ name: String) -> Bool { Develop.curves.contains(name) || Develop.legacyCurves.contains(name) }

    private final class Delegate: NSObject, XMLParserDelegate {
        var result: [String: Double] = [:]
        var curves: [String: [(Double, Double)]] = [:]
        var error: XMPPresetError?
        private var element: String?
        private var curve: String?
        private var inItem = false
        private var text = ""

        func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?,
                    qualifiedName qName: String?, attributes attributeDict: [String: String] = [:]) {
            // Conforme a plataforma, o XMLParser entrega atributos com ou sem o
            // prefixo ("crs:Exposure2012" ou "Exposure2012"); os nomes são exclusivos do Camera Raw.
            for (name, value) in attributeDict {
                let local = name.split(separator: ":").last.map(String.init) ?? name
                guard Develop.ranges[local] != nil else { continue }
                store(local, value, parser)
            }
            if curve != nil {
                if elementName == "li" { inItem = true; text = "" }
                return
            }
            if namespaceURI == XMPPreset.namespace, XMPPreset.isCurve(elementName) {
                curve = elementName
                curves[elementName] = []
                element = nil
            } else if namespaceURI == XMPPreset.namespace, Develop.ranges[elementName] != nil {
                element = elementName
                text = ""
            } else {
                element = nil
            }
        }

        func parser(_ parser: XMLParser, foundCharacters string: String) {
            if element != nil || inItem { text += string }
        }

        func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) {
            if let name = curve {
                if inItem && elementName == "li" {
                    inItem = false
                    do {
                        let p = try XMPPreset.point(text)
                        if curves[name, default: []].count < 64 { curves[name, default: []].append(p) }
                    } catch let e as XMPPresetError {
                        fail(e, parser)
                    } catch {}
                } else if elementName == name && namespaceURI == XMPPreset.namespace {
                    curve = nil
                }
                return
            }
            if let current = element, current == elementName { store(current, text, parser) }
            element = nil
        }

        private func store(_ key: String, _ value: String, _ parser: XMLParser) {
            guard error == nil else { return }
            do {
                if let v = try XMPPreset.validated(key, value) { result[key] = v }
            } catch let e as XMPPresetError {
                fail(e, parser)
            } catch {}
        }

        private func fail(_ e: XMPPresetError, _ parser: XMLParser) {
            guard error == nil else { return }
            error = e
            parser.abortParsing()
        }
    }
}
