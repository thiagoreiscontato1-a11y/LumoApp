import Darwin
import Foundation
import MetricKit
import UIKit

/// Relatório de falhas sem SDK, no mesmo formato do Android (`CrashReporter.java`): o evento vai ao Sentry pelo
/// endpoint HTTP de envelopes, se o build tiver um DSN (`LumoSentryDSN` no Info.plist, preenchido pelo CI a partir do
/// segredo SENTRY_DSN) e o usuário não tiver desligado o envio.
///
/// De onde vêm os relatórios:
/// - Sinal fatal (falha do Swift, acesso inválido, abort): o handler grava a pilha num arquivo já aberto antes de o
///   app morrer; na próxima abertura vira evento, com os últimos passos da sessão que caiu.
/// - Exceção Objective-C não tratada: `NSSetUncaughtExceptionHandler`.
/// - MetricKit (iOS 14+): o próprio iOS entrega na abertura seguinte os relatórios de falha e de travamento (hang),
///   inclusive os que o handler não pega (falta de memória, watchdog).
///
/// Nunca entra no relatório: fotos, conta Fotto, senhas, nomes de eventos (caminhos "Eventos/..." são mascarados) ou e-mails.
final class CrashReporter: NSObject, MXMetricManagerSubscriber {
    static let shared = CrashReporter()
    static let maxPending = 20
    static let maxAge: TimeInterval = 30 * 24 * 3600

    private let queue = DispatchQueue(label: "lumo.crash")
    private var trail: [String] = []
    private var installed = false

    static var dir: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let d = base.appendingPathComponent("Relatorios", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }
    static var lastReport: URL { dir.appendingPathComponent("ultimo-relatorio.txt") }
    static var signalFile: URL { dir.appendingPathComponent("sinal.txt") }
    static var trailFile: URL { dir.appendingPathComponent("trilha.txt") }

    static var enabled: Bool {
        get { UserDefaults.standard.object(forKey: "lumo.crash.send") as? Bool ?? true }
        set {
            UserDefaults.standard.set(newValue, forKey: "lumo.crash.send")
            if newValue { shared.sendPendingInBackground() }
        }
    }

    /// DSN do Sentry gravado no build (vazio sem o segredo SENTRY_DSN).
    static var dsn: String {
        let v = (Bundle.main.object(forInfoDictionaryKey: "LumoSentryDSN") as? String ?? "").trimmingCharacters(in: .whitespaces)
        return v.hasPrefix("$(") ? "" : v
    }
    static var configured: Bool { !dsn.isEmpty }
    static var pending: Int { ((try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []).filter { $0.hasSuffix(".json") }.count }

    // MARK: Instalação

    /// Chamado ao abrir o app, antes de qualquer outra coisa.
    func install() {
        guard !installed else { return }
        installed = true
        // A sessão anterior caiu por sinal? O arquivo tem a pilha; a trilha é a da sessão que caiu.
        let previousTrail = (try? String(contentsOf: Self.trailFile, encoding: .utf8)) ?? ""
        if let text = try? String(contentsOf: Self.signalFile, encoding: .utf8), !text.isEmpty {
            save(signalEvent(text, trail: previousTrail))
        }
        try? FileManager.default.removeItem(at: Self.trailFile)
        lumoCrashFD = open(Self.signalFile.path, O_WRONLY | O_CREAT | O_TRUNC, 0o644)
        for sig in [SIGABRT, SIGSEGV, SIGBUS, SIGILL, SIGTRAP, SIGFPE] { signal(sig, lumoSignalHandler) }
        NSSetUncaughtExceptionHandler { exception in CrashReporter.shared.exception(exception) }
        MXMetricManager.shared.add(self)
        note("app aberto · iOS \(UIDevice.current.systemVersion)")
        sendPendingInBackground()
    }

    /// Rastro curto do que o app fazia (painéis, câmera, edição). Só nomes técnicos, nunca conteúdo do usuário.
    static func note(_ what: String) { shared.note(what) }

    func note(_ what: String) {
        let line = Self.iso(Date()) + " " + Self.scrub(what)
        queue.async {
            if self.trail.count >= 30 { self.trail.removeFirst() }
            self.trail.append(line)
            try? self.trail.joined(separator: "\n").write(to: Self.trailFile, atomically: true, encoding: .utf8)
        }
    }

    private func crumbs(_ lines: [String]) -> [String: Any] {
        ["values": lines.filter { !$0.isEmpty }.map { ["message": $0, "category": "lumo"] }]
    }

    // MARK: Eventos (formato Sentry)

    func base(_ level: String, trail lines: [String]? = nil) -> [String: Any] {
        let info = Bundle.main.infoDictionary ?? [:]
        let version = info["CFBundleShortVersionString"] as? String ?? "?", build = info["CFBundleVersion"] as? String ?? "0"
        let signing = (info["LumoSigning"] as? String ?? "teste").hasPrefix("$(") ? "teste" : (info["LumoSigning"] as? String ?? "teste")
        var system = utsname()
        uname(&system)
        let machine = withUnsafeBytes(of: &system.machine) { String(decoding: $0.prefix { $0 != 0 }, as: UTF8.self) }
        let free = (try? URL(fileURLWithPath: NSHomeDirectory()).resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]))?
            .volumeAvailableCapacityForImportantUsage ?? 0
        let crumbsLines = lines ?? queue.sync { trail }
        return [
            "event_id": UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased(),
            "timestamp": Date().timeIntervalSince1970, "platform": "cocoa", "level": level,
            "release": "lumo-ios@\(version)", "dist": build, "environment": DemoMode.enabled ? "demo" : signing,
            "user": ["id": Self.installID],
            "tags": ["aparelho": machine, "ios": UIDevice.current.systemVersion],
            "contexts": [
                "os": ["name": "iOS", "version": UIDevice.current.systemVersion],
                "device": ["manufacturer": "Apple", "model": machine, "family": UIDevice.current.model,
                           "memory_size": ProcessInfo.processInfo.physicalMemory, "free_storage": free],
                "app": ["app_identifier": Bundle.main.bundleIdentifier ?? "", "app_version": version, "app_build": build],
            ],
            "breadcrumbs": crumbs(crumbsLines),
        ]
    }

    /// Linha de `backtrace_symbols`: "3   Lumo   0x0000000102a3c4f8 símbolo + 123".
    static func frame(fromSymbolLine line: String) -> [String: Any]? {
        let parts = line.split(separator: " ", omittingEmptySubsequences: true)
        guard parts.count >= 3, let addr = parts.first(where: { $0.hasPrefix("0x") }) else { return nil }
        let image = String(parts[1])
        var f: [String: Any] = ["package": image, "instruction_addr": String(addr), "in_app": image == "Lumo"]
        if let i = parts.firstIndex(of: addr), i + 1 < parts.count {
            let rest = parts[(i + 1)...].joined(separator: " ")
            f["function"] = rest.components(separatedBy: " + ").first ?? rest
        }
        return f
    }

    func signalEvent(_ text: String, trail: String) -> [String: Any] {
        var e = base("fatal", trail: trail.components(separatedBy: "\n"))
        let lines = text.components(separatedBy: "\n")
        let sig = Int32(lines.first?.replacingOccurrences(of: "SIG ", with: "") ?? "") ?? 0
        let name = [SIGABRT: "SIGABRT", SIGSEGV: "SIGSEGV", SIGBUS: "SIGBUS", SIGILL: "SIGILL", SIGTRAP: "SIGTRAP", SIGFPE: "SIGFPE"][sig] ?? "SIG\(sig)"
        // Os quadros do próprio handler (e do raise) vêm primeiro; o Sentry quer o mais antigo primeiro.
        let frames = lines.dropFirst().compactMap(Self.frame).filter { ($0["function"] as? String)?.contains("lumoSignalHandler") != true }
        e["exception"] = ["values": [[
            "type": name, "value": "O Lumo fechou por um erro (\(name))",
            "mechanism": ["type": "signal", "handled": false, "meta": ["signal": ["number": sig, "name": name]]],
            "stacktrace": ["frames": Array(frames.reversed())],
        ]]]
        if let attrs = try? FileManager.default.attributesOfItem(atPath: Self.signalFile.path), let date = attrs[.modificationDate] as? Date {
            e["timestamp"] = date.timeIntervalSince1970
        }
        return e
    }

    func exception(_ exception: NSException) {
        var e = base("fatal")
        let frames = exception.callStackSymbols.compactMap(Self.frame)
        e["exception"] = ["values": [[
            "type": exception.name.rawValue, "value": Self.scrub(exception.reason ?? ""),
            "mechanism": ["type": "NSException", "handled": false],
            "stacktrace": ["frames": Array(frames.reversed())],
        ]]]
        save(e)
    }

    /// Relatórios do iOS (MetricKit): falhas e travamentos da sessão anterior, com a pilha da thread atingida.
    func didReceive(_ payloads: [MXDiagnosticPayload]) {
        for payload in payloads {
            for crash in payload.crashDiagnostics ?? [] {
                var e = base("fatal", trail: [])
                let sig = crash.signal?.int32Value ?? 0
                let type = crash.exceptionType.map { "EXC \($0)" } ?? "SIG\(sig)"
                e["exception"] = ["values": [[
                    "type": type, "value": Self.scrub(crash.terminationReason ?? crash.virtualMemoryRegionInfo ?? "O Lumo fechou por um erro"),
                    "mechanism": ["type": "MetricKit", "handled": false],
                    "stacktrace": ["frames": Self.frames(crash.callStackTree)],
                ]]]
                e["timestamp"] = payload.timeStampEnd.timeIntervalSince1970
                var tags = e["tags"] as? [String: Any] ?? [:]
                tags["origem"] = "metrickit"
                tags["versao_do_relatorio"] = crash.applicationVersion
                e["tags"] = tags
                save(e)
            }
            for hang in payload.hangDiagnostics ?? [] {
                var e = base("error", trail: [])
                let seconds = hang.hangDuration.converted(to: .seconds).value
                e["message"] = ["formatted": String(format: "Travamento: o Lumo ficou %.1f s sem responder", seconds)]
                e["exception"] = ["values": [[
                    "type": "Hang", "value": String(format: "%.1f s sem responder", seconds),
                    "mechanism": ["type": "MetricKit", "handled": true],
                    "stacktrace": ["frames": Self.frames(hang.callStackTree)],
                ]]]
                e["fingerprint"] = ["hang"]
                e["timestamp"] = payload.timeStampEnd.timeIntervalSince1970
                save(e)
            }
        }
        sendPendingInBackground()
    }

    /// Pilha da thread atingida no JSON do MetricKit (`callStacks` → quadros aninhados em `subFrames`).
    static func frames(_ tree: MXCallStackTree) -> [[String: Any]] {
        guard let json = try? JSONSerialization.jsonObject(with: tree.jsonRepresentation()) as? [String: Any],
              let stacks = json["callStacks"] as? [[String: Any]] else { return [] }
        let stack = stacks.first { $0["threadAttributed"] as? Bool == true } ?? stacks.first
        var out: [[String: Any]] = []
        var level = stack?["callStackRootFrames"] as? [[String: Any]] ?? []
        while let f = level.first, out.count < 150 {
            let name = f["binaryName"] as? String ?? "?"
            var frame: [String: Any] = ["package": name, "in_app": name == "Lumo"]
            if let a = f["address"] as? NSNumber { frame["instruction_addr"] = String(format: "0x%llx", a.uint64Value) }
            if let o = f["offsetIntoBinaryTextSegment"] as? NSNumber { frame["function"] = "\(name) + \(o)" }
            out.append(frame)
            level = f["subFrames"] as? [[String: Any]] ?? []
        }
        return out.reversed()
    }

    /// Evento de teste, para conferir se o Sentry está recebendo.
    func sendTest() async {
        var e = base("info")
        e["message"] = ["formatted": "Teste do relatório de falhas do Lumo (iPhone)"]
        save(e)
        await sendPending()
    }

    // MARK: Fila local

    func save(_ event: [String: Any]) {
        guard let id = event["event_id"] as? String, let data = try? JSONSerialization.data(withJSONObject: event) else { return }
        try? data.write(to: Self.dir.appendingPathComponent(id + ".json"), options: .atomic)
        try? Self.readable(event).write(to: Self.lastReport, atomically: true, encoding: .utf8)
        let files = Self.pendingFiles()
        if files.count > Self.maxPending {
            for f in files.prefix(files.count - Self.maxPending) { try? FileManager.default.removeItem(at: f) }
        }
    }

    static func pendingFiles() -> [URL] {
        let all = (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.contentModificationDateKey])) ?? []
        func date(_ u: URL) -> Date { (try? u.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast }
        return all.filter { $0.pathExtension == "json" }.sorted { date($0) < date($1) }
    }

    func sendPendingInBackground() { Task.detached(priority: .utility) { await self.sendPending() } }

    private let sending = Locked(false)

    func sendPending() async {
        guard sending.withLock({ busy -> Bool in if busy { return false }; busy = true; return true }) else { return }
        defer { sending.withLock { $0 = false } }
        let now = Date()
        for f in Self.pendingFiles() {
            if let d = try? f.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate, now.timeIntervalSince(d) > Self.maxAge {
                try? FileManager.default.removeItem(at: f)
            }
        }
        let dsn = Self.dsn
        guard !dsn.isEmpty, Self.enabled else { return }
        for f in Self.pendingFiles() {
            guard let data = try? Data(contentsOf: f), let event = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                try? FileManager.default.removeItem(at: f)
                continue
            }
            let code = await Self.post(dsn: dsn, event: event)
            // 400/413: evento recusado; não adianta repetir. 429/5xx/sem rede: tenta na próxima abertura.
            if (200..<300).contains(code) || code == 400 || code == 413 { try? FileManager.default.removeItem(at: f) } else { break }
        }
    }

    /// POST no endpoint de envelopes do Sentry. DSN: https://CHAVE@host/ID_DO_PROJETO
    static func post(dsn: String, event: [String: Any]) async -> Int {
        guard let u = URLComponents(string: dsn), let host = u.host, var key = u.user, !key.isEmpty else { return 400 }
        if let colon = key.firstIndex(of: ":") { key = String(key[..<colon]) }
        let path = u.path
        guard let slash = path.lastIndex(of: "/") else { return 400 }
        let project = String(path[path.index(after: slash)...]), prefix = String(path[..<slash])
        guard !project.isEmpty, let scheme = u.scheme,
              let url = URL(string: "\(scheme)://\(host)\(u.port.map { ":\($0)" } ?? "")\(prefix)/api/\(project)/envelope/") else { return 400 }
        guard let item = try? JSONSerialization.data(withJSONObject: event),
              let header = try? JSONSerialization.data(withJSONObject: ["event_id": event["event_id"] ?? "", "sent_at": iso(Date()), "dsn": dsn]),
              let itemHeader = try? JSONSerialization.data(withJSONObject: ["type": "event", "length": item.count]) else { return 400 }
        var body = header
        body.append(10); body.append(itemHeader); body.append(10); body.append(item); body.append(10)
        var req = URLRequest(url: url, timeoutInterval: 20)
        req.httpMethod = "POST"
        req.setValue("application/x-sentry-envelope", forHTTPHeaderField: "Content-Type")
        req.setValue("Sentry sentry_version=7, sentry_client=lumo-ios/1.0, sentry_key=\(key)", forHTTPHeaderField: "X-Sentry-Auth")
        guard let result = try? await URLSession.shared.upload(for: req, from: body) else { return -1 }
        return (result.1 as? HTTPURLResponse)?.statusCode ?? -1
    }

    // MARK: Utilitários

    /// Texto para o testador compartilhar quando o Sentry não está configurado.
    static func readable(_ e: [String: Any]) -> String {
        var s = "Relatório de falha do Lumo (iPhone)\n"
        let when = Date(timeIntervalSince1970: e["timestamp"] as? Double ?? 0)
        s += "\(iso(when)) · \(e["release"] ?? "") (\(e["dist"] ?? "")) · \(e["environment"] ?? "")\n"
        if let ctx = e["contexts"] as? [String: Any], let d = ctx["device"] as? [String: Any], let os = ctx["os"] as? [String: Any] {
            s += "\(d["model"] ?? "") · iOS \(os["version"] ?? "")\n"
        }
        if let m = e["message"] as? [String: Any], let f = m["formatted"] as? String { s += "\n\(f)\n" }
        if let ex = (e["exception"] as? [String: Any])?["values"] as? [[String: Any]] {
            for x in ex.reversed() {
                s += "\n\(x["type"] ?? ""): \(x["value"] ?? "")\n"
                let frames = ((x["stacktrace"] as? [String: Any])?["frames"] as? [[String: Any]] ?? []).reversed().prefix(30)
                for f in frames { s += "  \(f["package"] ?? "") \(f["instruction_addr"] ?? "") \(f["function"] ?? "")\n" }
            }
        }
        if let bc = (e["breadcrumbs"] as? [String: Any])?["values"] as? [[String: Any]], !bc.isEmpty {
            s += "\nÚltimos passos:\n"
            for c in bc { s += "  \(c["message"] ?? "")\n" }
        }
        return s
    }

    /// Remove o que pode identificar o cliente: nomes de eventos nos caminhos, e-mails e tokens.
    static func scrub(_ s: String) -> String {
        var out = s
        let rules: [(String, String)] = [
            ("Eventos/[^/\\s:]+", "Eventos/<evento>"),
            ("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", "<email>"),
            ("(?i)(token|bearer|authorization|api[_-]?key|password|senha)([\"'=: ]+)[^\\s,;\"']+", "$1$2<oculto>"),
        ]
        for (pattern, template) in rules {
            out = out.replacingOccurrences(of: pattern, with: template, options: .regularExpression)
        }
        return out
    }

    static var installID: String {
        if let id = UserDefaults.standard.string(forKey: "lumo.crash.installId") { return id }
        let id = UUID().uuidString
        UserDefaults.standard.set(id, forKey: "lumo.crash.installId")
        return id
    }

    static func iso(_ d: Date) -> String {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f.string(from: d)
    }
}

// MARK: Handler de sinal (só funções seguras dentro do sinal: write, backtrace, signal, raise)

private var lumoCrashFD: Int32 = -1
private let lumoCrashFrames = UnsafeMutablePointer<UnsafeMutableRawPointer?>.allocate(capacity: 128)

private func lumoSignalHandler(_ sig: Int32) {
    let fd = lumoCrashFD
    if fd >= 0 {
        // "SIG <número>\n" montado na pilha, sem alocar memória.
        withUnsafeTemporaryAllocation(of: UInt8.self, capacity: 16) { buf in
            var n = Int(max(0, sig)), i = 15
            buf[i] = 10
            repeat { i -= 1; buf[i] = UInt8(48 + n % 10); n /= 10 } while n > 0 && i > 4
            i -= 1; buf[i] = 32
            i -= 1; buf[i] = 71
            i -= 1; buf[i] = 73
            i -= 1; buf[i] = 83
            _ = write(fd, buf.baseAddress! + i, 16 - i)
        }
        let count = backtrace(lumoCrashFrames, 128)
        backtrace_symbols_fd(lumoCrashFrames, count, fd)
        close(fd)
    }
    signal(sig, SIG_DFL)
    raise(sig)
}
