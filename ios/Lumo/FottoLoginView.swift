import SwiftUI
import WebKit

/// Login no Fotto (Alboom) pela página oficial. Depois do login, o Lumo lê o token
/// da sessão (URL, localStorage/sessionStorage) e o valida em `/me`, como no Android.
struct FottoLoginView: View {
    static let loginURL = URL(string: "https://auth.alboompro.com/login?srv=fotto&redir=/&host=https://fotto.alboompro.com")!

    let client: FottoClient
    let onConnected: (FottoCredential) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var status = "Faça login para continuar"
    @State private var captureRequest = 0

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Text(status)
                    .font(.footnote)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal)
                    .padding(.vertical, 8)
                    .background(Color(.secondarySystemBackground))
                FottoWebView(url: Self.loginURL, client: client, captureRequest: captureRequest,
                             status: { status = $0 },
                             delivered: { credential in
                                 onConnected(credential)
                                 dismiss()
                             })
            }
            .navigationTitle("Conectar ao Fotto")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Fechar") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Usar esta sessão") { captureRequest += 1 } }
            }
        }
    }
}

struct FottoWebView: UIViewRepresentable {
    let url: URL
    let client: FottoClient
    let captureRequest: Int
    let status: (String) -> Void
    let delivered: (FottoCredential) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .default()
        let web = WKWebView(frame: .zero, configuration: config)
        web.navigationDelegate = context.coordinator
        web.customUserAgent = nil
        context.coordinator.web = web
        web.load(URLRequest(url: url))
        return web
    }

    func updateUIView(_ web: WKWebView, context: Context) {
        context.coordinator.parent = self
        if context.coordinator.lastCaptureRequest != captureRequest {
            context.coordinator.lastCaptureRequest = captureRequest
            context.coordinator.startCapture()
        }
    }

    final class Coordinator: NSObject, WKNavigationDelegate {
        var parent: FottoWebView
        weak var web: WKWebView?
        var lastCaptureRequest = 0
        private var tested = Set<String>()
        private var delivered = false
        private var attempts = 0
        private var timer: Timer?

        init(_ parent: FottoWebView) { self.parent = parent }

        deinit { timer?.invalidate() }

        static let readSession = """
        (function(){try{var r={};var keep=function(k){k=(k||'').toLowerCase();return k.indexOf('token')>=0||k.indexOf('api')>=0||k.indexOf('key')>=0||k.indexOf('session')>=0||k.indexOf('auth')>=0;};
        try{for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i);var v=localStorage.getItem(k);if(v&&keep(k))r[k]=v;}}catch(e){}
        try{for(var j=0;j<sessionStorage.length;j++){var k2=sessionStorage.key(j);var v2=sessionStorage.getItem(k2);if(v2&&keep(k2))r['session_'+k2]=v2;}}catch(e){}
        return JSON.stringify(r);}catch(e){return '{}';}})();
        """

        func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            if let url = action.request.url { captureURL(url) }
            decisionHandler(.allow)
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            guard !delivered else { return }
            let onFotto = webView.url?.host?.lowercased().contains("fotto.alboompro.com") == true
            parent.status(onFotto ? "Login concluído. Capturando sessão Fotto…" : "Faça login para continuar")
            if let url = webView.url { captureURL(url) }
            readStorage()
            if onFotto { startCapture() }
        }

        func startCapture() {
            attempts = 0
            timer?.invalidate()
            timer = Timer.scheduledTimer(withTimeInterval: 0.75, repeats: true) { [weak self] t in
                guard let self, !self.delivered else { t.invalidate(); return }
                self.attempts += 1
                self.parent.status("Capturando sessão Fotto… tentativa \(self.attempts)")
                if let url = self.web?.url { self.captureURL(url) }
                self.readStorage()
                if self.attempts >= 24 {
                    t.invalidate()
                    self.parent.status("Sessão ainda não capturada. Deixe a página do Fotto aberta e toque em ‘Usar esta sessão’.")
                }
            }
        }

        private func captureURL(_ url: URL) {
            guard url.host?.lowercased().contains("fotto.alboompro.com") == true,
                  let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems else { return }
            for item in items where ["access_token", "token", "api_key", "apiKey", "at"].contains(item.name) {
                tryCandidate(item.value)
            }
        }

        private func readStorage() {
            web?.evaluateJavaScript(Self.readSession) { [weak self] value, _ in
                guard let self, !self.delivered, let text = value as? String, let data = text.data(using: .utf8),
                      let object = try? JSONSerialization.jsonObject(with: data) else { return }
                var out: [String] = []
                Self.collect(object, into: &out)
                out.forEach { self.tryCandidate($0) }
            }
        }

        /// Prioriza chaves api_key/access_token/token e abre valores JSON aninhados.
        static func collect(_ value: Any, into out: inout [String]) {
            if let o = value as? [String: Any] {
                let priority = ["api_key", "apikey", "access_token", "accesstoken", "token", "session_token"]
                for p in priority { for (k, v) in o where k.lowercased() == p { collect(v, into: &out) } }
                for (k, v) in o {
                    let low = k.lowercased()
                    if ["api", "key", "token", "session", "auth"].contains(where: low.contains) || v is [String: Any] || v is [Any] {
                        collect(v, into: &out)
                    }
                }
            } else if let a = value as? [Any] {
                a.forEach { collect($0, into: &out) }
            } else if let s = value as? String {
                let t = s.trimmingCharacters(in: .whitespaces)
                if (t.hasPrefix("{") || t.hasPrefix("[")), let d = t.data(using: .utf8), let nested = try? JSONSerialization.jsonObject(with: d) {
                    collect(nested, into: &out)
                } else if FottoParsing.isTokenCandidate(t), !out.contains(t) {
                    out.append(t)
                }
            }
        }

        private func tryCandidate(_ raw: String?) {
            guard let raw, FottoParsing.isTokenCandidate(raw), !delivered else { return }
            var token = FottoParsing.stripBearer(raw)
            if token.count > 2, token.hasPrefix("\""), token.hasSuffix("\"") { token = String(token.dropFirst().dropLast()) }
            guard tested.insert(token).inserted else { return }
            parent.status("Validando sessão Fotto…")
            let client = parent.client
            Task { @MainActor [weak self] in
                guard let credential = await client.validate(token: token), let self, !self.delivered else { return }
                self.delivered = true
                self.timer?.invalidate()
                self.parent.status("Conta conectada. Voltando ao LUMO…")
                self.parent.delivered(credential)
            }
        }
    }
}
