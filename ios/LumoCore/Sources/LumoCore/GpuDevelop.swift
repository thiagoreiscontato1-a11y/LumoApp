import Foundation
#if canImport(Metal)
import Metal
#endif

/// Revelação na GPU (Metal), com o motor da CPU (`Develop.Plan.rows`) como reserva. Mesmo papel do
/// `GpuDevelop.java` do Android (OpenGL ES): a CPU calcula o que é feito uma vez por foto (campos locais, tabela de
/// tons, curvas) e a GPU faz o trabalho por pixel com o kernel abaixo, que é a porta linha a linha de `Plan.pixel`.
/// A nitidez continua na CPU, por cima do resultado, com o mesmo código.
///
/// Antes do primeiro uso, um autoteste revela a mesma cena na GPU e na CPU e compara (99% dos pixels a até 1 nível e
/// 99,9% a até 2, o mesmo critério do Android). Se divergir, faltar Metal ou algo falhar, a GPU é desligada até o app
/// reiniciar e tudo segue na CPU. Uma foto que falhe na GPU (por exemplo, com o app em segundo plano, onde o iOS não
/// deixa usar a GPU) é revelada na CPU sem desligar a GPU.
public enum GpuDevelop {
    /// Fotos menores revelam rápido na CPU; a GPU compensa a partir daqui.
    public static let minPixels = 2_000_000
    /// Ajustes → "Revelar na GPU".
    public static var userEnabled: Bool {
        get { state.withLock { $0.userEnabled } }
        set { state.withLock { $0.userEnabled = newValue } }
    }

    struct State {
        var userEnabled = true
        var disabled: String?
        var tested = false
        var testResult = ""
    }

    static let state = Locked(State())
    static let testLock = NSLock()

    /// Texto curto para Ajustes e para o registro.
    public static var status: String {
        let s = state.withLock { $0 }
        if !s.userEnabled { return "CPU (GPU desligada em Ajustes)" }
        if let d = s.disabled { return "CPU (\(d))" }
        return s.tested ? "GPU" : "GPU (autoteste pendente)"
    }

    public static var testResult: String { state.withLock { $0.testResult } }

    static func disable(_ reason: String) {
        state.withLock { if $0.disabled == nil { $0.disabled = reason } }
    }

    /// Começa o autoteste em segundo plano, para a primeira foto não esperar por ele.
    public static func warmUp() {
        guard userEnabled, !state.withLock({ $0.tested }) else { return }
        DispatchQueue.global(qos: .utility).async { selfTest() }
    }

    /// Pixels revelados na GPU (antes da nitidez, como `Plan.rows`), ou nil para a CPU com o motivo em `why`.
    static func rows(_ plan: Develop.Plan, minPixels: Int = GpuDevelop.minPixels, why: inout String) -> [UInt32]? {
        if !userEnabled { why = "GPU desligada em Ajustes"; return nil }
        if plan.src.width * plan.src.height < minPixels { why = "foto pequena"; return nil }
        selfTest()
        if let d = state.withLock({ $0.disabled }) { why = d; return nil }
        #if canImport(Metal)
        do {
            return try Engine.shared().run(plan)
        } catch let e as GpuError {
            why = e.description
            if case .failed = e { disable(e.description) }
            return nil
        } catch {
            why = "GPU falhou: \(error)"
            return nil
        }
        #else
        why = "sem Metal"
        return nil
        #endif
    }

    enum GpuError: Error, CustomStringConvertible {
        /// Esta foto não cabe ou não pode ir para a GPU agora (a GPU continua ligada).
        case skipped(String)
        /// A GPU não funciona neste aparelho.
        case failed(String)

        var description: String {
            switch self {
            case .skipped(let s), .failed(let s): return s
            }
        }
    }

    /// Revela a mesma cena (1800×1200, preset completo) na GPU e na CPU e compara.
    public static func selfTest() {
        testLock.lock(); defer { testLock.unlock() }
        if state.withLock({ $0.tested }) { return }
        #if canImport(Metal)
        let w = 1800, h = 1200
        let s = Develop.Settings(testPreset)
        let plan = Develop.Plan(Develop.Source(scene(w, h)), s)
        let t0 = Date()
        let cpu = cpuRows(plan)
        let t1 = Date()
        let gpu: [UInt32]
        do {
            gpu = try Engine.shared().run(plan)
        } catch GpuError.skipped(let why) {
            // Ex.: app em segundo plano. Tenta de novo na próxima foto.
            state.withLock { $0.testResult = "adiado: \(why)" }
            return
        } catch {
            disable("autoteste não rodou: \(error)")
            state.withLock { $0.tested = true; $0.testResult = "indisponível: \(error)" }
            return
        }
        let t2 = Date()
        let c = compare(cpu, gpu)
        let text = String(format: "máx %d · ≤1 %.2f%% · ≤2 %.3f%% · CPU %d ms · GPU %d ms (com compilação)", locale: Locale(identifier: "en_US_POSIX"),
                          c.worst, c.within1, c.within2, Int(t1.timeIntervalSince(t0) * 1000), Int(t2.timeIntervalSince(t1) * 1000))
        state.withLock { $0.tested = true; $0.testResult = text }
        if c.within1 < 99 || c.within2 < 99.9 { disable("autoteste divergiu (\(text))") }
        #else
        disable("sem Metal")
        state.withLock { $0.tested = true; $0.testResult = "indisponível: sem Metal" }
        #endif
    }

    /// Diferença máxima por canal e porcentagem de pixels a até 1 e 2 níveis.
    public static func compare(_ a: [UInt32], _ b: [UInt32]) -> (worst: Int, within1: Double, within2: Double) {
        guard a.count == b.count, !a.isEmpty else { return (255, 0, 0) }
        var worst = 0, w1 = 0, w2 = 0
        for i in 0..<a.count {
            var d = 0
            for sh: UInt32 in [0, 8, 16] { d = max(d, abs(Int((a[i] >> sh) & 255) - Int((b[i] >> sh) & 255))) }
            worst = max(worst, d)
            if d <= 1 { w1 += 1 }
            if d <= 2 { w2 += 1 }
        }
        return (worst, Double(w1) * 100 / Double(a.count), Double(w2) * 100 / Double(a.count))
    }

    static func cpuRows(_ plan: Develop.Plan) -> [UInt32] {
        var out = [UInt32](repeating: 0xFF00_0000, count: plan.src.width * plan.src.height)
        out.withUnsafeMutableBufferPointer { buf in
            let base = buf.baseAddress!
            Develop.parallel(plan.src.height) { y0, y1 in plan.rows(y0, y1, base) }
        }
        return out
    }

    /// Mesmo preset do autoteste do Android (`GpuDevelop.testPreset`).
    public static let testPreset: [String: Double] = [
        "IncrementalTemperature": -11, "Exposure2012": 0.15, "Contrast2012": 17, "Highlights2012": -70, "Shadows2012": 62, "Whites2012": 30,
        "Blacks2012": -20, "Clarity2012": 35, "Texture": 10, "Dehaze": 8, "Vibrance": 35, "Saturation": -10,
        "ParametricShadows": 10, "ParametricHighlights": -15, "ToneCurvePV2012.0": 0, "ToneCurvePV2012.1": 18, "ToneCurvePV2012.2": 128,
        "ToneCurvePV2012.3": 132, "ToneCurvePV2012.4": 255, "ToneCurvePV2012.5": 245, "HueAdjustmentOrange": -8,
        "SaturationAdjustmentBlue": -29, "LuminanceAdjustmentBlue": -20, "SplitToningShadowHue": 210, "SplitToningShadowSaturation": 15,
        "SplitToningHighlightHue": 45, "SplitToningHighlightSaturation": 12, "BlueSaturation": 25, "RedHue": 10, "Sharpness": 40,
        "PostCropVignetteAmount": -21, "GrainAmount": 12,
    ]

    /// Cena fixa: céu em degradê, pele e folhagem em xadrez, rampa de cinzas com linhas, mais ruído determinístico.
    public static func scene(_ w: Int, _ h: Int) -> PixelImage {
        var p = [UInt32](repeating: 0, count: w * h)
        var seed: UInt32 = 7
        func clamp(_ v: Int) -> UInt32 { UInt32(max(0, min(255, v))) }
        for y in 0..<h {
            for x in 0..<w {
                let u = Double(x) / Double(w - 1), v = Double(y) / Double(h - 1)
                var r: Int, g: Int, b: Int
                if v < 0.33 { r = Int(60 + 90 * u); g = Int(120 + 80 * u); b = Int(190 + 50 * u) }
                else if v < 0.66 {
                    let skin = ((x / 6) + (y / 6)) % 2 == 0
                    r = skin ? Int(200 + 40 * u) : Int(40 + 30 * u); g = skin ? Int(150 + 30 * u) : Int(110 + 60 * u); b = skin ? Int(120 + 20 * u) : Int(40 + 20 * u)
                } else {
                    let k = Int(255 * u); r = k; g = k; b = k
                    if x % 9 == 0 { r = 255 - k; g = 255 - k; b = 255 - k }
                }
                seed = seed &* 1_103_515_245 &+ 12345
                let n = Int((seed >> 16) % 13) - 6
                p[y * w + x] = 0xFF00_0000 | clamp(r + n) << 16 | clamp(g + n) << 8 | clamp(b + n)
            }
        }
        return PixelImage(width: w, height: h, pixels: p)
    }
}

/// Valor protegido por lock.
final class Locked<T>: @unchecked Sendable {
    private var value: T
    private let lock = NSLock()
    init(_ value: T) { self.value = value }
    func withLock<R>(_ body: (inout T) -> R) -> R {
        lock.lock(); defer { lock.unlock() }
        return body(&value)
    }
}

#if canImport(Metal)
extension GpuDevelop {
    /// Dispositivo, fila e kernel compilado. Criado uma vez; o envio é serializado.
    final class Engine {
        private static let made = Locked<Result<Engine, GpuError>?>(nil)

        static func shared() throws -> Engine {
            let r = made.withLock { cached -> Result<Engine, GpuError> in
                if let cached { return cached }
                let r: Result<Engine, GpuError>
                do { r = .success(try Engine()) } catch let e as GpuError { r = .failure(e) } catch { r = .failure(.failed("\(error)")) }
                cached = r
                return r
            }
            return try r.get()
        }

        let device: MTLDevice
        let queue: MTLCommandQueue
        let pipeline: MTLComputePipelineState
        let dummy: MTLBuffer
        private let lock = NSLock()

        init() throws {
            guard let device = MTLCreateSystemDefaultDevice() else { throw GpuError.failed("sem Metal") }
            guard let queue = device.makeCommandQueue() else { throw GpuError.failed("fila Metal indisponível") }
            let options = MTLCompileOptions()
            // Matemática exata (pow, exp2, log2): a saída precisa bater com a CPU.
            if #available(iOS 18.0, macOS 15.0, *) { options.mathMode = .safe } else { options.fastMathEnabled = false }
            let library: MTLLibrary
            do { library = try device.makeLibrary(source: GpuDevelop.kernelSource, options: options) } catch {
                throw GpuError.failed("kernel não compilou: \(error.localizedDescription)")
            }
            guard let function = library.makeFunction(name: "develop") else { throw GpuError.failed("kernel sem a função develop") }
            do { pipeline = try device.makeComputePipelineState(function: function) } catch {
                throw GpuError.failed("pipeline Metal recusado: \(error.localizedDescription)")
            }
            guard let dummy = device.makeBuffer(length: 16, options: .storageModeShared) else { throw GpuError.failed("sem memória na GPU") }
            self.device = device; self.queue = queue; self.dummy = dummy
        }

        func buffer(_ data: [Float]?) throws -> MTLBuffer {
            guard let data, !data.isEmpty else { return dummy }
            guard let b = data.withUnsafeBytes({ device.makeBuffer(bytes: $0.baseAddress!, length: $0.count, options: .storageModeShared) }) else {
                throw GpuError.skipped("sem memória na GPU")
            }
            return b
        }

        func run(_ plan: Develop.Plan) throws -> [UInt32] {
            lock.lock(); defer { lock.unlock() }
            let w = plan.src.width, h = plan.src.height, bytes = w * h * 4
            if bytes > device.maxBufferLength { throw GpuError.skipped("foto maior que o limite de memória da GPU") }
            let params = try buffer(GpuDevelop.parameters(plan))
            guard let src = plan.pixels.withUnsafeBytes({ device.makeBuffer(bytes: $0.baseAddress!, length: bytes, options: .storageModeShared) }),
                  let dst = device.makeBuffer(length: bytes, options: .storageModeShared) else { throw GpuError.skipped("sem memória na GPU") }
            let tone = try buffer(plan.tone.map { $0.map(Float.init) })
            let curves = try buffer(plan.curves.map { $0.flatMap { $0 }.map(Float.init) })
            let fields = try [plan.toneF?.offset, plan.toneF?.scale, plan.clarityF?.offset, plan.clarityF?.scale,
                              plan.textureF?.offset, plan.hazeF?.offset].map { try buffer($0) }
            guard let command = queue.makeCommandBuffer(), let encoder = command.makeComputeCommandEncoder() else {
                throw GpuError.skipped("GPU ocupada")
            }
            encoder.setComputePipelineState(pipeline)
            encoder.setBuffer(params, offset: 0, index: 0)
            encoder.setBuffer(src, offset: 0, index: 1)
            encoder.setBuffer(dst, offset: 0, index: 2)
            encoder.setBuffer(tone, offset: 0, index: 3)
            encoder.setBuffer(curves, offset: 0, index: 4)
            for (i, f) in fields.enumerated() { encoder.setBuffer(f, offset: 0, index: 5 + i) }
            let tw = pipeline.threadExecutionWidth, th = max(1, pipeline.maxTotalThreadsPerThreadgroup / tw)
            let group = MTLSize(width: tw, height: th, depth: 1)
            let groups = MTLSize(width: (w + tw - 1) / tw, height: (h + th - 1) / th, depth: 1)
            encoder.dispatchThreadgroups(groups, threadsPerThreadgroup: group)
            encoder.endEncoding()
            command.commit()
            command.waitUntilCompleted()
            if let error = command.error {
                // Sem permissão (app em segundo plano) não é defeito da GPU: esta foto vai para a CPU.
                if let e = error as? MTLCommandBufferError, e.code == .notPermitted || e.code == .timeout {
                    throw GpuError.skipped("GPU indisponível agora (\(error.localizedDescription))")
                }
                throw GpuError.failed("GPU falhou: \(error.localizedDescription)")
            }
            var out = [UInt32](repeating: 0, count: w * h)
            out.withUnsafeMutableBytes { _ = memcpy($0.baseAddress!, dst.contents(), bytes) }
            return out
        }
    }
}
#endif

extension GpuDevelop {
    // Índices do vetor de parâmetros (igual no kernel).
    static let paramCount = 160

    /// Constantes da revelação no formato do kernel.
    static func parameters(_ p: Develop.Plan) -> [Float] {
        var v = [Float](repeating: 0, count: paramCount)
        func put(_ i: Int, _ x: Double) { v[i] = Float(x) }
        func flag(_ i: Int, _ b: Bool) { v[i] = b ? 1 : 0 }
        let s = p.s, fx = p.fx
        put(0, Double(p.src.width)); put(1, Double(p.src.height))
        put(2, p.gr); put(3, p.gg); put(4, p.gb); put(5, p.expo); put(6, p.dehaze)
        flag(7, p.tonal); flag(8, p.local && p.logY != nil); put(9, p.sh); put(10, p.hl); put(11, p.whites)
        put(12, p.clarity); put(13, p.texture); put(14, p.rollPower); put(15, p.top)
        flag(16, p.tone != nil); flag(17, p.curves != nil); flag(18, p.color)
        put(19, p.sat); put(20, p.vib); flag(21, s.grayscale); flag(22, p.mixing); flag(23, p.hsl); flag(24, p.calib); flag(25, p.grade)
        for j in 0..<3 { put(26 + j, p.calHue[j]); put(29 + j, p.calSat[j]) }
        for (k, t) in [p.tS, p.tM, p.tH, p.tG, p.tShadow].enumerated() { put(32 + 3 * k, t.0); put(33 + 3 * k, t.1); put(34 + 3 * k, t.2) }
        put(47, p.mid); put(48, p.width); flag(49, p.sharpen)
        flag(50, fx.vignette); put(51, fx.amount); put(52, fx.inner); put(53, fx.width); put(54, fx.roundness); put(55, fx.power)
        put(56, fx.highlights); put(57, Double(fx.style)); put(58, fx.cx); put(59, fx.cy); put(60, fx.hw); put(61, fx.hh); put(62, fx.hc)
        flag(63, fx.grain); put(64, fx.grainAmount); put(65, fx.cell); put(66, fx.rough)
        put(67, s.shadowLum); put(68, s.midtoneLum); put(69, s.highlightLum); put(70, s.globalLum)
        for (k, f) in [p.toneF, p.clarityF, p.textureF, p.hazeF].enumerated() {
            let b = 71 + 5 * k
            flag(b, f != nil)
            if let f { put(b + 1, Double(f.gw)); put(b + 2, Double(f.gh)); put(b + 3, Double(f.cell)); flag(b + 4, f.scale != nil) }
        }
        for i in 0..<8 { put(91 + i, s.hue[i]); put(99 + i, s.sat[i]); put(107 + i, s.lum[i]); put(115 + i, s.mix[i]) }
        for i in 0..<9 { put(123 + i, Develop.toPP[i]); put(132 + i, Develop.fromPP[i]) }
        return v
    }

    /// Porta em Metal de `Develop.Plan.pixel` (e de `Effects.apply`). Mantenha os dois em sincronia.
    static let kernelSource = #"""
    #include <metal_stdlib>
    using namespace metal;

    constant float CENTERS[9] = {0.0, 30.0, 60.0, 120.0, 180.0, 240.0, 270.0, 300.0, 360.0};

    static float luma(float r, float g, float b) { return 0.2126 * r + 0.7152 * g + 0.0722 * b; }
    static float clamp01(float v) { return clamp(v, 0.0, 1.0); }
    static float sm(float a, float b, float x) { if (x <= a) return 0.0; if (x >= b) return 1.0; float t = (x - a) / (b - a); return t * t * (3.0 - 2.0 * t); }
    static float exp2c(float x) { return exp2(clamp(x, -24.0, 8.0)); }
    static float decodeS(float v) { if (!(v > 0.0)) return 0.0; if (v >= 1.0) return 1.0; return v <= 0.04045 ? v / 12.92 : pow((v + 0.055) / 1.055, 2.4); }
    static float encodeS(float x) { if (!(x > 0.0)) return 0.0; if (x >= 1.0) return 1.0; return x <= 0.0031308 ? x * 12.92 : 1.055 * pow(x, 1.0 / 2.4) - 0.055; }
    static float ppEncode(float x) { if (!(x > 0.0)) return 0.0; if (x >= 1.0) return 1.0; return pow(x, 1.0 / 1.8); }
    static float ppDecode(float v) { if (v > 1.0) return pow(v, 1.8); if (!(v > 0.0)) return 0.0; return pow(v, 1.8); }
    static float soft(float d, float lim) { float q = d / lim; return d / sqrt(1.0 + q * q); }
    static float wrap360(float h) { return fmod(fmod(h, 360.0) + 360.0, 360.0); }

    static float tableAt(device const float* t, int n, float x) {
        if (!(x > 0.0)) return t[0];
        if (x >= 1.0) return t[n - 1];
        float f = x * float(n - 1); int i = int(f);
        return t[i] + (t[i + 1] - t[i]) * (f - float(i));
    }

    static float fieldAt(device const float* off, device const float* sc, bool hasScale, int gw, int gh, int cell, int x, int y, float guide) {
        if (cell == 1) { int i = y * gw + x; return hasScale ? sc[i] * guide + off[i] : off[i]; }
        float c = float(cell);
        float gx = clamp((float(x) + 0.5) / c - 0.5, 0.0, float(gw - 1)), gy = clamp((float(y) + 0.5) / c - 0.5, 0.0, float(gh - 1));
        int x0 = int(gx), y0 = int(gy), x1 = min(gw - 1, x0 + 1), y1 = min(gh - 1, y0 + 1);
        float fx = gx - float(x0), fy = gy - float(y0);
        float o = (off[y0 * gw + x0] * (1.0 - fx) + off[y0 * gw + x1] * fx) * (1.0 - fy) + (off[y1 * gw + x0] * (1.0 - fx) + off[y1 * gw + x1] * fx) * fy;
        if (!hasScale) return o;
        float s = (sc[y0 * gw + x0] * (1.0 - fx) + sc[y0 * gw + x1] * fx) * (1.0 - fy) + (sc[y1 * gw + x0] * (1.0 - fx) + sc[y1 * gw + x1] * fx) * fy;
        return s * guide + o;
    }

    static float hueOf(float r, float g, float b, float mx, float mn) {
        float d = mx - mn;
        if (d <= 0.0) return 0.0;
        float h = mx == r ? (g - b) / d : mx == g ? (b - r) / d + 2.0 : (r - g) / d + 4.0;
        h *= 60.0;
        return h < 0.0 ? h + 360.0 : h;
    }

    static int band(float h) { for (int k = 0; k < 7; k++) { if (h < CENTERS[k + 1]) return k; } return 7; }
    static float bandMix(float h, int k) { float c0 = CENTERS[k], c1 = CENTERS[k + 1]; return sm(0.2, 0.8, (h - c0) / (c1 - c0)); }

    static float3 hsv(float h, float s, float v) {
        float k = h / 60.0, ch = v * s, x = ch * (1.0 - fabs(fmod(k, 2.0) - 1.0)), m = v - ch; int i = min(5, int(k));
        float r = (i == 0 || i == 5) ? ch : (i == 1 || i == 4) ? x : 0.0;
        float g = (i == 1 || i == 2) ? ch : (i == 0 || i == 3) ? x : 0.0;
        float b = (i == 3 || i == 4) ? ch : (i == 2 || i == 5) ? x : 0.0;
        return float3(r + m, g + m, b + m);
    }

    static float3 fitc(float3 c, float y) {
        float mx = max(c.r, max(c.g, c.b)), mn = min(c.r, min(c.g, c.b));
        if (mx <= 1.0 && mn >= 0.0) return c;
        if (y >= 1.0) return float3(1.0);
        if (y <= 0.0) return float3(0.0);
        float t = 1.0;
        if (mx > 1.0) t = min(t, (1.0 - y) / (mx - y));
        if (mn < 0.0) t = min(t, y / (y - mn));
        return clamp(float3(y) + (c - float3(y)) * t, 0.0, 1.0);
    }

    static float hashi(int x, int y, uint seed) {
        uint h = (uint(x) * 0x27d4eb2du) ^ (uint(y) * 0x165667b1u) ^ seed;
        h ^= h >> 15u; h *= 0x85ebca6bu; h ^= h >> 13u; h *= 0xc2b2ae35u; h ^= h >> 16u;
        return float(h & 0xffffffu) / 16777215.0 * 2.0 - 1.0;
    }
    static float noise(float fx, float fy, uint seed) {
        float ix = floor(fx), iy = floor(fy), tx = fx - ix, ty = fy - iy; int x = int(ix), y = int(iy);
        tx = tx * tx * (3.0 - 2.0 * tx); ty = ty * ty * (3.0 - 2.0 * ty);
        float a = hashi(x, y, seed), b = hashi(x + 1, y, seed), c = hashi(x, y + 1, seed), d = hashi(x + 1, y + 1, seed);
        return (a + (b - a) * tx) * (1.0 - ty) + (c + (d - c) * tx) * ty;
    }

    static uint pack(float r, float g, float b) {
        uint ri = uint(floor(clamp01(r) * 255.0 + 0.5)), gi = uint(floor(clamp01(g) * 255.0 + 0.5)), bi = uint(floor(clamp01(b) * 255.0 + 0.5));
        return 0xFF000000u | (ri << 16) | (gi << 8) | bi;
    }

    // Develop.Effects.apply: vinheta pós-corte e granulação.
    static uint effects(device const float* P, float r, float g, float b, int x, int y) {
        if (P[50] > 0.5) {
            float px = float(x) + 0.5, py = float(y) + 0.5;
            float u = (px - P[58]) / P[60], v = (py - P[59]) / P[61];
            float rd = P[54];
            if (rd > 0.0) { float k = rd * rd; u += ((px - P[58]) / P[62] - u) * k; v += ((py - P[59]) / P[62] - v) * k; }
            float pw = P[55];
            float d = pw == 2.0 ? sqrt(u * u + v * v) : pow(pow(fabs(u), pw) + pow(fabs(v), pw), 1.0 / pw);
            float m = sm(P[52], P[52] + P[53], d);
            if (m > 0.0) {
                float amount = P[51];
                if (amount < 0.0) {
                    if (P[57] == 3.0) { float f = 1.0 + amount * m * 0.9; r *= f; g *= f; b *= f; }
                    else {
                        float l = clamp01(luma(r, g, b));
                        float f = exp2c(amount * m * 1.5);
                        f = 1.0 - (1.0 - f) * (1.0 - P[56] * sm(0.5, 1.0, l));
                        r *= f; g *= f; b *= f;
                    }
                } else {
                    float k = amount * m * (P[57] == 3.0 ? 0.9 : 0.75);
                    r += (1.0 - r) * k; g += (1.0 - g) * k; b += (1.0 - b) * k;
                }
            }
        }
        if (P[63] > 0.5) {
            float l = clamp01(luma(r, g, b)), cell = P[65], rough = P[66];
            float nz = noise(float(x) / cell, float(y) / cell, 0x5eedu) * (1.0 - 0.5 * rough) + noise(float(x) / (cell * 0.5), float(y) / (cell * 0.5), 0x1cedu) * 0.5 * rough;
            float a = P[64] * nz * (0.35 + 0.65 * 4.0 * l * (1.0 - l));
            r += a; g += a; b += a;
        }
        return pack(r, g, b);
    }

    kernel void develop(device const float* P [[buffer(0)]], device const uint* src [[buffer(1)]], device uint* dst [[buffer(2)]],
                        device const float* toneT [[buffer(3)]], device const float* curvesT [[buffer(4)]],
                        device const float* toneO [[buffer(5)]], device const float* toneS [[buffer(6)]],
                        device const float* clarO [[buffer(7)]], device const float* clarS [[buffer(8)]],
                        device const float* texO [[buffer(9)]], device const float* hazeO [[buffer(10)]],
                        uint2 gid [[thread_position_in_grid]]) {
        int w = int(P[0]), h = int(P[1]);
        int x = int(gid.x), y = int(gid.y);
        if (x >= w || y >= h) return;
        int i = y * w + x;
        uint v = src[i];
        float lr = decodeS(float((v >> 16) & 255u) / 255.0), lg = decodeS(float((v >> 8) & 255u) / 255.0), lb = decodeS(float(v & 255u) / 255.0);
        float r = lr * P[2], g = lg * P[3], b = lb * P[4];
        float dehaze = P[6];
        if (dehaze > 0.0 && P[86] > 0.5) {
            float dark = min(min(r, min(g, b)), fieldAt(hazeO, hazeO, false, int(P[87]), int(P[88]), int(P[89]), x, y, 0.0) * P[5]);
            float tr = max(0.15, 1.0 - 0.85 * dehaze * min(1.0, dark)), a = 1.0 - tr;
            r = (r - a) / tr; g = (g - a) / tr; b = (b - a) / tr;
        } else if (dehaze < 0.0) {
            float k = -dehaze * 0.45;
            r += (0.85 - r) * k; g += (0.85 - g) * k; b += (0.85 - b) * k;
        }
        if (P[7] > 0.5) {
            float lum = luma(r, g, b), lp = encodeS(min(1.0, lum));
            float yt = lum, ev = 0.0;
            if (P[8] > 0.5) {
                float ly = log2(max(yt, 1e-6)), l0 = log2(max(1.0 / 4096.0, luma(lr, lg, lb)));
                float sh = P[9], hl = P[10];
                if (P[71] > 0.5) {
                    float base = ly - soft(l0 - fieldAt(toneO, toneS, P[75] > 0.5, int(P[72]), int(P[73]), int(P[74]), x, y, l0), 1.0);
                    float bl = encodeS(min(1.0, exp2c(base)));
                    if (sh != 0.0) ev += sh * (sh > 0.0 ? 1.35 : 1.0) * (1.0 - sm(0.13, 0.45, bl)) * (0.55 + 0.45 * sm(0.0, 0.15, bl));
                    if (hl != 0.0) ev += hl * (hl < 0.0 ? 0.75 : 0.9) * sm(0.4, 0.9, bl) * (1.0 - sm(0.85, 1.0, lp));
                }
                if (P[76] > 0.5) ev += P[12] * 2.0 * soft(l0 - fieldAt(clarO, clarS, P[80] > 0.5, int(P[77]), int(P[78]), int(P[79]), x, y, l0), 1.5) * 4.0 * lp * (1.0 - lp);
                if (P[81] > 0.5) ev += P[13] * 0.6 * soft(l0 - fieldAt(texO, texO, false, int(P[82]), int(P[83]), int(P[84]), x, y, l0), 1.0) * (1.0 - sm(0.85, 1.0, lp));
            }
            float whites = P[11];
            if (whites != 0.0) ev += whites * 0.62 * sm(0.3, 1.0, lp);
            if (ev != 0.0) yt *= exp2c(ev);
            float rollPower = P[14], top = P[15];
            if (rollPower > 1.0 && yt > 0.6) {
                float q = (yt - 0.6) / (top - 0.6);
                yt = q >= 1.0 ? 1.0 : 0.6 + 0.4 * (1.0 - pow(1.0 - q, rollPower));
            }
            if (P[16] > 0.5) yt = decodeS(tableAt(toneT, 1025, encodeS(min(1.0, yt))));
            if (lum > 1e-9) { float k = yt / lum; r *= k; g *= k; b *= k; } else { r = yt; g = yt; b = yt; }
            float vmax = max(r, max(g, b));
            if (vmax > 0.8) {
                float vs = (vmax - min(r, min(g, b))) / vmax, f = 1.0 - vs * (1.0 - (0.8 + 0.2 * (1.0 - exp(-(vmax - 0.8) / 0.2))) / vmax);
                r *= f; g *= f; b *= f;
            }
        }
        float3 c = fitc(float3(r, g, b), luma(r, g, b));
        float R = encodeS(c.r), G = encodeS(c.g), B = encodeS(c.b);
        if (P[17] > 0.5) { R = tableAt(curvesT, 1025, R); G = tableAt(curvesT + 1025, 1025, G); B = tableAt(curvesT + 2050, 1025, B); }
        if (P[18] > 0.5) {
            float yg = luma(R, G, B);
            float sat = P[19], vib = P[20];
            if (sat != 0.0 || vib != 0.0) {
                float mx = max(R, max(G, B)), mn = min(R, min(G, B)), sv = mx <= 0.0 ? 0.0 : (mx - mn) / mx;
                float f = 1.0 + sat;
                if (vib > 0.0) {
                    float hh = hueOf(R, G, B, mx, mn), z = (hh - 25.0) / 20.0, skin = exp(-z * z);
                    f *= 1.0 + vib * 0.6 * sqrt(1.0 - sv) * (1.0 - 0.6 * skin);
                } else if (vib < 0.0) {
                    f *= 1.0 + vib * 0.8 * (0.3 + 0.7 * sv);
                }
                f = max(0.0, f);
                float3 q = fitc(float3(yg + (R - yg) * f, yg + (G - yg) * f, yg + (B - yg) * f), yg);
                R = q.r; G = q.g; B = q.b;
            }
            float mx = max(R, max(G, B)), mn = min(R, min(G, B)), chroma = mx - mn;
            if (P[21] > 0.5) {
                float m = 0.0;
                if (P[22] > 0.5 && chroma > 1e-6) {
                    float hh = hueOf(R, G, B, mx, mn); int k = band(hh), k2 = (k + 1) % 8; float q = bandMix(hh, k);
                    m = (P[115 + k] * (1.0 - q) + P[115 + k2] * q) / 100.0 * sm(0.0, 0.35, chroma);
                }
                float vv = clamp01(yg * exp2c(m * 0.8));
                R = vv; G = vv; B = vv;
            } else if ((P[23] > 0.5 || P[24] > 0.5) && chroma > 1e-6) {
                float lr2 = decodeS(R), lg2 = decodeS(G), lb2 = decodeS(B);
                float pr = ppEncode(P[123] * lr2 + P[124] * lg2 + P[125] * lb2);
                float pg = ppEncode(P[126] * lr2 + P[127] * lg2 + P[128] * lb2);
                float pb = ppEncode(P[129] * lr2 + P[130] * lg2 + P[131] * lb2);
                float pmax = max(pr, max(pg, pb)), pmin = min(pr, min(pg, pb));
                if (pmax - pmin > 1e-6) {
                    float hh = hueOf(pr, pg, pb, pmax, pmin), sv = (pmax - pmin) / pmax, val = pmax;
                    if (P[24] > 0.5) {
                        float ch = 0.0, cs = 0.0;
                        for (int j = 0; j < 3; j++) {
                            float d = fabs(fmod(hh - 120.0 * float(j) + 540.0, 360.0) - 180.0), wgt = sm(0.0, 1.0, 1.0 - d / 120.0);
                            ch += wgt * P[26 + j]; cs += wgt * P[29 + j];
                        }
                        hh = wrap360(hh + ch);
                        sv = clamp01(sv * max(0.0, 1.0 + cs));
                    }
                    if (P[23] > 0.5) {
                        int k = band(hh), k2 = (k + 1) % 8; float q = bandMix(hh, k);
                        float dh = (P[91 + k] * (1.0 - q) + P[91 + k2] * q) * 0.43, dl = (P[107 + k] * (1.0 - q) + P[107 + k2] * q) / 100.0;
                        float ds = (P[99 + k] * (1.0 - q) + P[99 + k2] * q) / 100.0;
                        ds *= 0.5 + 0.5 * fabs(ds);
                        val *= exp2c(dl * 0.3 * sm(0.0, 0.35, sv));
                        hh = wrap360(hh + dh);
                        sv = clamp01(sv * max(0.0, 1.0 + ds));
                    }
                    float3 cc = hsv(hh, sv, val);
                    float a = ppDecode(cc.r), b1 = ppDecode(cc.g), b2 = ppDecode(cc.b);
                    float3 l2 = float3(P[132] * a + P[133] * b1 + P[134] * b2, P[135] * a + P[136] * b1 + P[137] * b2, P[138] * a + P[139] * b1 + P[140] * b2);
                    l2 = fitc(l2, clamp01(luma(l2.r, l2.g, l2.b)));
                    R = encodeS(l2.r); G = encodeS(l2.g); B = encodeS(l2.b);
                }
            }
            if (P[25] > 0.5) {
                float l = luma(R, G, B), mid = P[47], wd = P[48];
                float ws = 1.0 - sm(mid - wd, mid, l), wh = sm(mid, mid + wd, l), wm = 1.0 - ws - wh;
                float edge = min(1.0, min(l, 1.0 - l) * 12.0);
                float dl = (P[67] * ws + P[68] * wm + P[69] * wh + P[70]) / 100.0 * 0.2, shadowW = (1.0 - l) * (1.0 - l);
                R = R + (P[32] * ws + P[35] * wm + P[38] * wh + P[41]) * edge + P[44] * shadowW + dl;
                G = G + (P[33] * ws + P[36] * wm + P[39] * wh + P[42]) * edge + P[45] * shadowW + dl;
                B = B + (P[34] * ws + P[37] * wm + P[40] * wh + P[43]) * edge + P[46] * shadowW + dl;
                float3 q = fitc(float3(R, G, B), clamp01(l + dl));
                R = q.r; G = q.g; B = q.b;
            }
        }
        dst[i] = P[49] > 0.5 ? pack(R, G, B) : effects(P, R, G, B, x, y);
    }
    """#
}
