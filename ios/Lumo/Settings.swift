import Foundation
import Security

/// Preferências do fotógrafo, persistidas em UserDefaults.
struct LumoSettings: Codable, Equatable {
    var autoEnabled = true
    var autoStrength = 1.0
    var presetName = ""
    var curation = true
    var curationSensitivity = 1
    var edge = 2560
    /// Recuperar fotos que já estavam no cartão ao conectar.
    var backfill = true
    var fottoAuto = false
    var wifiHost = "192.168.1.2"
    var activeEventID = ""

    static let key = "lumo.settings.v1"

    static func load() -> LumoSettings {
        guard let data = UserDefaults.standard.data(forKey: key),
              let s = try? JSONDecoder().decode(LumoSettings.self, from: data) else { return LumoSettings() }
        return s
    }

    func save() {
        if let data = try? JSONEncoder().encode(self) { UserDefaults.standard.set(data, forKey: Self.key) }
    }

    func editOptions(presets: PresetStore) -> EditOptions {
        var o = EditOptions()
        o.auto = autoEnabled
        o.autoStrength = autoStrength
        o.presetName = presetName
        o.preset = presetName.isEmpty ? nil : presets.preset(named: presetName)
        o.edge = edge
        o.curation = curation
        o.curationSensitivity = curationSensitivity
        return o
    }
}

/// Biblioteca de presets XMP importados (nome → ajustes Camera Raw).
struct PresetStore {
    static let key = "lumo.presets.v1"

    var all: [String: [String: Double]]

    static func load() -> PresetStore {
        guard let data = UserDefaults.standard.data(forKey: key),
              let all = try? JSONDecoder().decode([String: [String: Double]].self, from: data) else { return PresetStore(all: [:]) }
        return PresetStore(all: all)
    }

    var names: [String] { all.keys.sorted { $0.localizedCaseInsensitiveCompare($1) == .orderedAscending } }

    func preset(named name: String) -> [String: Double]? { all[name] }

    mutating func put(_ name: String, _ values: [String: Double]) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        all[trimmed] = values
        save()
    }

    mutating func remove(_ name: String) {
        all[name] = nil
        save()
    }

    func save() {
        if let data = try? JSONEncoder().encode(all) { UserDefaults.standard.set(data, forKey: Self.key) }
    }
}

/// Credencial Fotto e identidade PTP/IP no Keychain do aparelho.
enum Keychain {
    static let service = "com.lumo.app.ios"

    static func set(_ data: Data?, for account: String) {
        let query: [CFString: Any] = [kSecClass: kSecClassGenericPassword, kSecAttrService: service, kSecAttrAccount: account]
        SecItemDelete(query as CFDictionary)
        guard let data else { return }
        var add = query
        add[kSecValueData] = data
        add[kSecAttrAccessible] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(add as CFDictionary, nil)
    }

    static func get(_ account: String) -> Data? {
        let query: [CFString: Any] = [kSecClass: kSecClassGenericPassword, kSecAttrService: service, kSecAttrAccount: account,
                                      kSecReturnData: true, kSecMatchLimit: kSecMatchLimitOne]
        var out: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &out) == errSecSuccess else { return nil }
        return out as? Data
    }

    static var fottoCredential: FottoCredential? {
        get { get("fotto").flatMap { try? JSONDecoder().decode(FottoCredential.self, from: $0) } }
        set { set(newValue.flatMap { try? JSONEncoder().encode($0) }, for: "fotto") }
    }

    /// GUID estável apresentado à câmera no pareamento Wi-Fi.
    static var wifiGUID: UUID {
        if let d = get("wifiGuid"), let s = String(data: d, encoding: .utf8), let u = UUID(uuidString: s) { return u }
        let u = UUID()
        set(Data(u.uuidString.utf8), for: "wifiGuid")
        return u
    }
}
