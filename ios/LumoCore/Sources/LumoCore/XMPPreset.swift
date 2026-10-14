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

/// Leitura de presets Lightroom/Camera Raw (.xmp), como `PhotoEditor.parse`.
public enum XMPPreset {
    public static let namespace = "http://ns.adobe.com/camera-raw-settings/1.0/"

    public static let keys: Set<String> = {
        var k: Set<String> = [
            "IncrementalTemperature", "IncrementalTint", "Exposure2012", "Contrast2012", "Highlights2012",
            "Shadows2012", "Whites2012", "Blacks2012", "Clarity2012", "Vibrance", "Saturation", "Sharpness",
            "PostCropVignetteAmount",
        ]
        for c in PhotoEditor.colors {
            for p in ["HueAdjustment", "SaturationAdjustment", "LuminanceAdjustment"] { k.insert(p + c) }
        }
        return k
    }()

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
        guard !delegate.result.isEmpty else { throw XMPPresetError.empty }
        return delegate.result
    }

    static func validated(_ key: String, _ text: String) throws -> Double? {
        guard keys.contains(key) else { return nil }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let v = Double(trimmed.hasPrefix("+") ? String(trimmed.dropFirst()) : trimmed) else {
            throw XMPPresetError.value(key)
        }
        let lo = key == "Exposure2012" ? -5.0 : key == "Sharpness" ? 0 : -100
        let hi = key == "Exposure2012" ? 5.0 : key == "Sharpness" ? 150 : 100
        guard v.isFinite, v >= lo, v <= hi else { throw XMPPresetError.value(key) }
        return v
    }

    private final class Delegate: NSObject, XMLParserDelegate {
        var result: [String: Double] = [:]
        var error: XMPPresetError?
        private var element: String?
        private var text = ""

        func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?,
                    qualifiedName qName: String?, attributes attributeDict: [String: String] = [:]) {
            // Conforme a plataforma, o XMLParser entrega atributos com ou sem o
            // prefixo ("crs:Exposure2012" ou "Exposure2012"); os nomes são exclusivos do Camera Raw.
            for (name, value) in attributeDict {
                let local = name.split(separator: ":").last.map(String.init) ?? name
                guard keys.contains(local) else { continue }
                store(local, value, parser)
            }
            if namespaceURI == XMPPreset.namespace, keys.contains(elementName) {
                element = elementName
                text = ""
            } else {
                element = nil
            }
        }

        func parser(_ parser: XMLParser, foundCharacters string: String) {
            if element != nil { text += string }
        }

        func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) {
            if let current = element, current == elementName { store(current, text, parser) }
            element = nil
        }

        private func store(_ key: String, _ value: String, _ parser: XMLParser) {
            guard error == nil else { return }
            do {
                if let v = try XMPPreset.validated(key, value) { result[key] = v }
            } catch let e as XMPPresetError {
                error = e
                parser.abortParsing()
            } catch {}
        }
    }
}
