import XCTest
@testable import LumoCore
#if canImport(Metal)
import Metal
#endif

/// Mesmas verificações de `tests/check/PhotoCheckTest.java` (curadoria que corrige em vez de recusar).
final class PhotoCheckTests: XCTestCase {
    static func rgb(_ r: Int, _ g: Int, _ b: Int) -> UInt32 {
        0xFF00_0000 | UInt32(max(0, min(255, r))) << 16 | UInt32(max(0, min(255, g))) << 8 | UInt32(max(0, min(255, b)))
    }

    /// Cena com textura de verdade (folhagem/tecido) e algum ruído de sensor.
    static func scene(_ w: Int, _ h: Int) -> PixelImage {
        var img = PixelImage(width: w, height: h)
        var seed: UInt32 = 3
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
                let t = Int(18 * sin(Double(x) * 0.9 + sin(Double(y) * 0.37) * 3) * cos(Double(y) * 0.7)) + Int((seed >> 16) % 5) - 2
                img[x, y] = rgb(r + t, g + t, b + t)
            }
        }
        return img
    }

    static func blur(_ img: PixelImage, _ sigma: Double, outsideCenterOnly: Bool) -> PixelImage {
        let w = img.width, h = img.height
        var ch = [[Float]](repeating: [Float](repeating: 0, count: w * h), count: 3)
        for i in 0..<img.pixels.count {
            let p = img.pixels[i]
            ch[0][i] = Float((p >> 16) & 255); ch[1][i] = Float((p >> 8) & 255); ch[2][i] = Float(p & 255)
        }
        for c in 0..<3 { ch[c] = Develop.blur(ch[c], w, h, sigma) }
        var out = img
        for y in 0..<h {
            for x in 0..<w {
                let center = x > w * 3 / 8 && x < w * 5 / 8 && y > h / 3 && y < 2 * h / 3
                if outsideCenterOnly && center { continue }
                let i = y * w + x
                out.pixels[i] = rgb(Int(ch[0][i].rounded()), Int(ch[1][i].rounded()), Int(ch[2][i].rounded()))
            }
        }
        return out
    }

    func testSharpnessByRegion() {
        let w = 800, h = 533
        let sharp = Self.scene(w, h)
        let s0 = PhotoCheck.sharpness(sharp)
        let s3 = PhotoCheck.sharpness(Self.blur(sharp, 3, outsideCenterOnly: false))
        let bokeh = PhotoCheck.sharpness(Self.blur(sharp, 4, outsideCenterOnly: true))
        for sens in 0..<3 {
            XCTAssertFalse(PhotoCheck.blurry(s0, sensitivity: sens), "nítida passa (\(sens)) \(s0.best)")
            XCTAssertFalse(PhotoCheck.blurry(bokeh, sensitivity: sens), "fundo desfocado passa (\(sens)) \(bokeh.best)")
            XCTAssertTrue(PhotoCheck.blurry(s3, sensitivity: sens), "desfoque de 3 px reprova (\(sens)) \(s3.best)")
        }
        let flat = PixelImage(width: w, height: h, fill: 0xFF80_8890)
        XCTAssertFalse(PhotoCheck.sharpness(flat).known, "foto lisa não é julgada")
        XCTAssertFalse(PhotoCheck.blurry(PhotoCheck.sharpness(flat), sensitivity: 2))
    }

    func testShrinkUsesWholeBlocks() {
        let small = PhotoCheck.shrink(PixelImage(width: 1600, height: 1066), maxEdge: 512)
        XCTAssertEqual(small.width, 400)
        XCTAssertEqual(small.height, 266)
    }

    func testFaceCut() {
        XCTAssertEqual(PhotoCheck.cutFraction(x: 800, y: 500, eye: 60, width: 1600, height: 1066), 0)
        XCTAssertTrue(PhotoCheck.cut(PhotoCheck.cutFraction(x: 1600, y: 500, eye: 60, width: 1600, height: 1066), sensitivity: 1), "metade fora pela direita")
        XCTAssertFalse(PhotoCheck.cut(PhotoCheck.cutFraction(x: 800, y: 30, eye: 60, width: 1600, height: 1066), sensitivity: 2), "testa cortada no alto")
        XCTAssertTrue(PhotoCheck.cut(PhotoCheck.cutFraction(x: 800, y: 1040, eye: 60, width: 1600, height: 1066), sensitivity: 1), "queixo fora por baixo")
        XCTAssertTrue(PhotoCheck.principal(eye: 60, width: 1600, height: 1066))
        XCTAssertFalse(PhotoCheck.principal(eye: 20, width: 1600, height: 1066))
        let small = PhotoCheck.Face(x: 1600, y: 500, eye: 20, confidence: 1)
        XCTAssertFalse(PhotoCheck.faceCut([small], width: 1600, height: 1066, sensitivity: 2).review, "rosto pequeno do fundo não reprova")
    }

    /// Prévia 512 px: rosto de pele clara no centro, fundo médio.
    static func portrait() -> PixelImage {
        let pw = 512, ph = 340
        var photo = PixelImage(width: pw, height: ph)
        for y in 0..<ph {
            for x in 0..<pw {
                let face = abs(x - 256) < 40 && y > 130 && y < 230
                let v = 90 + (x * 7 + y * 3) % 40
                photo[x, y] = face ? rgb(205, 160, 140) : rgb(v, v + 5, v + 10)
            }
        }
        return photo
    }

    static func preset(_ ev: Double) -> Develop.Settings { Develop.Settings(["Exposure2012": ev]) }
    let finder: (PixelImage) -> [PhotoCheck.Face] = { _ in [PhotoCheck.Face(x: 256, y: 165, eye: 50, confidence: 0.8)] }

    func testExposureIsFixedAfterPreset() {
        let photo = Self.portrait()
        var bright = Self.preset(1.6)
        let e = PhotoCheck.fixExposure(photo, settings: &bright, faces: finder)
        XCTAssertLessThan(e.ev, 0)
        XCTAssertLessThan(bright.exposure, 1.6)
        XCTAssertTrue(e.note.contains("rosto"), e.note)
        let after = PhotoCheck.Light.of(Develop.Source(photo).render(bright), PhotoCheck.faceBoxes(finder(photo), photo.width, photo.height))
        XCTAssertLessThanOrEqual(after.faceClip, 0.12)
        XCTAssertLessThanOrEqual(after.faceMean, 222)

        var normal = Self.preset(0.2)
        XCTAssertEqual(PhotoCheck.fixExposure(photo, settings: &normal, faces: finder).ev, 0, "foto boa não é mexida")
        XCTAssertEqual(normal.exposure, 0.2)
        var airy = Self.preset(0.5)
        XCTAssertEqual(PhotoCheck.fixExposure(photo, settings: &airy, faces: { _ in [] }).ev, 0, "preset claro sem estourar")
    }

    func testDarkFaceIsLiftedAndBlackIsUnrecoverable() {
        var dark = Self.portrait()
        for y in 131..<230 { for x in 217..<296 { dark[x, y] = Self.rgb(40, 30, 26) } }
        var none = Self.preset(0)
        let d = PhotoCheck.fixExposure(dark, settings: &none, faces: finder)
        XCTAssertGreaterThan(d.ev, 0)
        XCTAssertLessThanOrEqual(d.ev, 1.2)

        var white = Self.portrait()
        for y in 0..<white.height { for x in 0..<160 { white[x, y] = 0xFFFF_FFFF } }
        var light = Self.preset(0.1)
        XCTAssertEqual(PhotoCheck.fixExposure(white, settings: &light, faces: { _ in [] }).ev, 0, "fundo branco da câmera não é corrigido")

        var zero = Self.preset(0)
        let black = PixelImage(width: 512, height: 340, fill: 0xFF05_0505)
        XCTAssertFalse(PhotoCheck.fixExposure(black, settings: &zero, faces: finder).unrecoverable.isEmpty)
    }

    func testDecisionOnlyReviewsWhatEditingCannotFix() {
        let tech = Curator.Result(review: true, reason: "Contraste muito baixo", sharpness: 3, brightness: 200, contrast: 5,
                                  clipping: 0.1, framing: 0.9, score: 3)
        let sharp = PhotoCheck.Sharpness(best: 1.9, regions: 10, noise: 1)
        var ok = PhotoCheck.decide(tech: tech, faceReview: false, faceNote: "", sharp: sharp, exposure: PhotoCheck.Exposure(),
                                   duplicate: true, sensitivity: 1)
        XCTAssertFalse(ok.review, "contraste baixo e duplicata na Média não reprovam: \(ok.reason)")
        XCTAssertTrue(ok.reason.hasPrefix("Aprovada"))
        XCTAssertTrue(ok.reason.contains("Parecida"))
        ok = PhotoCheck.decide(tech: tech, faceReview: false, faceNote: "", sharp: sharp, exposure: nil, duplicate: true, sensitivity: 2)
        XCTAssertTrue(ok.review, "duplicata reprova na Alta")
        let blurry = PhotoCheck.decide(tech: tech, faceReview: false, faceNote: "", sharp: PhotoCheck.Sharpness(best: 1.1, regions: 10, noise: 1),
                                       exposure: nil, duplicate: false, sensitivity: 1)
        XCTAssertTrue(blurry.review)
        XCTAssertTrue(blurry.reason.hasPrefix("Desfocada"))
        let cut = PhotoCheck.decide(tech: tech, faceReview: true, faceNote: "Rosto cortado na borda", sharp: sharp, exposure: nil,
                                    duplicate: false, sensitivity: 1)
        XCTAssertTrue(cut.review)
    }

    func testExposureFixOnlyInAutomatic() {
        var o = EditOptions()
        XCTAssertTrue(PhotoCheck.exposureFix(o))
        o.manual = EditControls()
        XCTAssertTrue(PhotoCheck.exposureFix(o), "manual neutro continua automático")
        o.manual?.exposure = 0.3
        XCTAssertFalse(PhotoCheck.exposureFix(o))
        o.manual = nil
        o.auto = false
        XCTAssertFalse(PhotoCheck.exposureFix(o))
    }
}

/// GPU (Metal) × CPU: mesma cena e preset do autoteste do app. No Mac do CI, quando houver Metal.
final class GpuDevelopTests: XCTestCase {
    func testMetalMatchesCPU() throws {
        #if canImport(Metal)
        guard MTLCreateSystemDefaultDevice() != nil else { throw XCTSkip("Sem dispositivo Metal neste Mac") }
        GpuDevelop.selfTest()
        print("GPU autoteste: \(GpuDevelop.testResult) · \(GpuDevelop.status)")
        XCTAssertEqual(GpuDevelop.status, "GPU", GpuDevelop.testResult)
        // Também sem nitidez (a GPU aplica vinheta e grão) e com HSL/calibração/gradação/P&B.
        var preset = GpuDevelop.testPreset
        preset["Sharpness"] = 0
        preset["ColorGradeMidtoneHue"] = 30; preset["ColorGradeMidtoneSat"] = 20; preset["ColorGradeGlobalLum"] = 10
        preset["PostCropVignetteRoundness"] = 40
        for gray in [false, true] {
            var p = preset
            if gray { p["ConvertToGrayscale"] = 1; p["GrayMixerOrange"] = 30; p["GrayMixerBlue"] = -40 }
            let plan = Develop.Plan(Develop.Source(GpuDevelop.scene(1200, 800)), Develop.Settings(p))
            var why = ""
            let gpu = try XCTUnwrap(GpuDevelop.rows(plan, minPixels: 0, why: &why), why)
            let c = GpuDevelop.compare(GpuDevelop.cpuRows(plan), gpu)
            print("GPU × CPU (P&B \(gray)): máx \(c.worst) · ≤1 \(c.within1)% · ≤2 \(c.within2)%")
            XCTAssertGreaterThanOrEqual(c.within1, 99)
            XCTAssertGreaterThanOrEqual(c.within2, 99.9)
        }
        #else
        throw XCTSkip("Sem Metal")
        #endif
    }

    func testSmallPhotosStayOnCPU() {
        let plan = Develop.Plan(Develop.Source(GpuDevelop.scene(64, 48)), Develop.Settings(GpuDevelop.testPreset))
        var why = ""
        XCTAssertNil(GpuDevelop.rows(plan, why: &why))
        XCTAssertEqual(why, "foto pequena")
    }
}

/// Criar preset a partir de uma edição e exportar/importar .xmp (mesmas regras do `PresetMaker.java`).
final class PresetMakerTests: XCTestCase {
    func testPresetFromEditMatchesEdit() {
        let preset: [String: Double] = ["Exposure2012": 0.5, "Contrast2012": 20, "Clarity2012": 10,
                                        "ToneCurvePV2012.0": 0, "ToneCurvePV2012.1": 0, "ToneCurvePV2012.2": 255, "ToneCurvePV2012.3": 240]
        let extra: [String: Double] = ["Clarity2012": 35, "Texture": 12, "ToneCurvePV2012.0": 0, "ToneCurvePV2012.1": 20,
                                       "ToneCurvePV2012.2": 128, "ToneCurvePV2012.3": 140, "ToneCurvePV2012.4": 255, "ToneCurvePV2012.5": 250]
        var manual = EditControls()
        manual.exposure = 0.25; manual.contrast = -20; manual.shadows = 15
        let made = PhotoEditor.presetValues(preset: preset, extra: extra, manual: manual)
        XCTAssertEqual(made["Exposure2012"] ?? 0, 0.75, accuracy: 1e-9)
        XCTAssertNil(made["Contrast2012"], "contraste zerado sai do preset")
        XCTAssertEqual(made["Shadows2012"], 15)
        XCTAssertEqual(made["Clarity2012"], 35)
        XCTAssertEqual(made["ToneCurvePV2012.5"], 250)

        // Foto com o preset novo e sem ajuste manual = foto com o preset antigo + edição.
        let img = PhotoCheckTests.scene(96, 64)
        var edited = EditOptions(); edited.auto = false; edited.preset = preset; edited.extra = extra; edited.manual = manual
        var fromPreset = EditOptions(); fromPreset.auto = false; fromPreset.preset = made
        var a = img, b = img
        PhotoEditor.edit(&a, options: edited)
        PhotoEditor.edit(&b, options: fromPreset)
        XCTAssertEqual(a.pixels, b.pixels)
    }

    func testXMPRoundTrip() throws {
        let values: [String: Double] = ["Exposure2012": 0.75, "Shadows2012": 15, "Clarity2012": 35, "ConvertToGrayscale": 1,
                                        "HueAdjustmentOrange": -8.5, "ToneCurvePV2012.0": 0, "ToneCurvePV2012.1": 20,
                                        "ToneCurvePV2012.2": 128, "ToneCurvePV2012.3": 140, "ToneCurvePV2012.4": 255, "ToneCurvePV2012.5": 250]
        let xmp = XMPPreset.write(values, name: "Casamento <Praia> & \"sol\"")
        XCTAssertTrue(xmp.contains("crs:Exposure2012=\"+0.75\""), xmp)
        let parsed = try XMPPreset.parse(xmp)
        XCTAssertEqual(parsed, values)
    }
}
