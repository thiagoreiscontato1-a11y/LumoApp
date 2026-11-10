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

    public static func parse(_ data: Data) throws -> [String: Double] { try parseWithLook(data).values }

    /// Atributos do perfil criativo guardados à parte (dentro de crs:Look, e as tabelas também no topo dos .xmp de perfil).
    static let lookKeys: Set<String> = ["Name", "Amount", "RGBTable", "RGBTableAmount", "LookTable", "ConvertToGrayscale"]
    static let tableKeys: Set<String> = ["RGBTable", "RGBTableAmount", "LookTable"]

    /// Como `parse`; o conteúdo de crs:Look não entra nos ajustes: vai para `look`, junto com as tabelas `Table_<MD5>`.
    /// A intensidade do perfil fica em `LookAmount` (0–200). Mesma regra de `Develop.parse` no Android.
    public static func parseWithLook(_ data: Data) throws -> (values: [String: Double], look: [String: String]) {
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
        let look = delegate.look
        if look["RGBTable"] != nil || look["LookTable"] != nil {
            result["LookAmount"] = max(0, min(200, (number(look["Amount"] ?? "1") ?? 1) * 100))
        }
        guard !result.isEmpty else { throw XMPPresetError.empty }
        return (result, look)
    }

    /// Grava um preset no formato .xmp do Lightroom/Camera Raw (mesmo formato que `parse` lê, no iPhone e no Android).
    /// O perfil criativo (tabelas de cor) não vai no arquivo: continua só no preset salvo no app.
    public static func write(_ values: [String: Double], name: String, uuid: UUID = UUID()) -> String {
        func esc(_ s: String) -> String {
            s.replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;")
                .replacingOccurrences(of: ">", with: "&gt;").replacingOccurrences(of: "\"", with: "&quot;")
        }
        func number(_ v: Double, _ key: String) -> String {
            if key == "ConvertToGrayscale" { return v >= 0.5 ? "True" : "False" }
            if v == v.rounded(), abs(v) < 1e9 { return (v > 0 && key == "Exposure2012" ? "+" : "") + String(Int(v)) }
            var t = String(format: "%.4f", locale: Locale(identifier: "en_US_POSIX"), v)
            while t.hasSuffix("0") { t.removeLast() }
            return (v > 0 && key == "Exposure2012" ? "+" : "") + t
        }
        var attributes = ""
        for key in values.keys.sorted() where Develop.ranges[key] != nil && key != "LookAmount" && !PhotoEditor.isCurveKey(key) {
            guard let v = values[key], v.isFinite, Develop.ranges[key]!.contains(v) else { continue }
            attributes += "\n    crs:\(key)=\"\(number(v, key))\""
        }
        var curves = ""
        for c in Develop.curves {
            var points: [String] = []
            while let x = values["\(c).\(2 * points.count)"], let y = values["\(c).\(2 * points.count + 1)"], points.count < 64 {
                points.append("\(Int(x.rounded())), \(Int(y.rounded()))")
            }
            guard points.count >= 2 else { continue }
            curves += "\n   <crs:\(c)>\n    <rdf:Seq>\n" + points.map { "     <rdf:li>\($0)</rdf:li>\n" }.joined() + "    </rdf:Seq>\n   </crs:\(c)>"
        }
        let title = esc(name)
        return """
        <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Lumo">
         <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
          <rdf:Description rdf:about=""
            xmlns:crs="\(namespace)"
            crs:PresetType="Normal" crs:UUID="\(uuid.uuidString.replacingOccurrences(of: "-", with: ""))" crs:SupportsAmount="False"
            crs:SupportsColor="True" crs:SupportsMonochrome="True" crs:SupportsHighDynamicRange="True" crs:SupportsNormalDynamicRange="True"
            crs:SupportsSceneReferred="True" crs:SupportsOutputReferred="True" crs:CameraModelRestriction="" crs:Copyright="" crs:ContactInfo=""
            crs:Version="15.0" crs:ProcessVersion="11.0" crs:HasSettings="True"\(attributes)>
           <crs:Name>
            <rdf:Alt>
             <rdf:li xml:lang="x-default">\(title)</rdf:li>
            </rdf:Alt>
           </crs:Name>\(curves)
          </rdf:Description>
         </rdf:RDF>
        </x:xmpmeta>

        """
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
        var look: [String: String] = [:]
        var error: XMPPresetError?
        private var depth = 0
        private var lookDepth = -1
        private var element: String?
        private var curve: String?
        private var inItem = false
        private var text = ""

        func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?,
                    qualifiedName qName: String?, attributes attributeDict: [String: String] = [:]) {
            depth += 1
            let inLook = lookDepth >= 0
            // Conforme a plataforma, o XMLParser entrega atributos com ou sem o
            // prefixo ("crs:Exposure2012" ou "Exposure2012"); os nomes são exclusivos do Camera Raw.
            for (name, value) in attributeDict {
                let local = name.split(separator: ":").last.map(String.init) ?? name
                if local.hasPrefix("Table_") { look[local] = value.trimmingCharacters(in: .whitespacesAndNewlines); continue }
                if inLook {
                    if XMPPreset.lookKeys.contains(local) { look[local] = value.trimmingCharacters(in: .whitespacesAndNewlines) }
                    continue
                }
                if XMPPreset.tableKeys.contains(local) { look[local] = value.trimmingCharacters(in: .whitespacesAndNewlines); continue }
                guard Develop.ranges[local] != nil else { continue }
                store(local, value, parser)
            }
            if inLook { element = nil; return }
            if namespaceURI == XMPPreset.namespace, elementName == "Look", curve == nil {
                lookDepth = depth
                element = nil
                return
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
            defer { depth -= 1 }
            if lookDepth >= 0 {
                if depth == lookDepth { lookDepth = -1 }
                return
            }
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
