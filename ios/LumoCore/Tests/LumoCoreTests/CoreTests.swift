import XCTest
@testable import LumoCore

final class HashTests: XCTestCase {
    func testSHA256KnownVectors() {
        XCTAssertEqual(PhotoHash.sha256Hex(Data()), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        XCTAssertEqual(PhotoHash.sha256Hex(Data("abc".utf8)), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        let long = Data(String(repeating: "a", count: 1000).utf8)
        XCTAssertEqual(PhotoHash.sha256Hex(long), "41edece42d63e8d9bf515a9ba6932e1c20cbc9f5a5d134645adb5db1b9737ea3")
    }

    func testPerceptualHashIsStableAndHammingWorks() {
        var img = PixelImage(width: 90, height: 80)
        for y in 0..<80 { for x in 0..<90 { img[x, y] = packRGB(Double(x) / 90, Double(x) / 90, Double(x) / 90) } }
        let a = PhotoHash.perceptual(img)
        XCTAssertEqual(a.count, 16)
        XCTAssertEqual(PhotoHash.hamming(a, PhotoHash.perceptual(img)), 0)
        XCTAssertEqual(PhotoHash.hamming(a, "zz"), 64)
    }
}

final class EditorTests: XCTestCase {
    func gradient(_ w: Int = 64, _ h: Int = 48) -> PixelImage {
        var img = PixelImage(width: w, height: h)
        for y in 0..<h { for x in 0..<w { img[x, y] = packRGB(Double(x) / Double(w) * 0.5, Double(y) / Double(h) * 0.5, 0.25) } }
        return img
    }

    func meanLuminance(_ img: PixelImage) -> Double {
        img.pixels.reduce(0) { $0 + luminance255($1) } / Double(img.pixels.count)
    }

    func render(_ img: PixelImage, _ preset: [String: Double]) -> PixelImage {
        Develop.Source(img).render(Develop.Settings(preset))
    }

    func one(_ color: UInt32, _ preset: [String: Double]) -> UInt32 {
        render(PixelImage(width: 1, height: 1, fill: color), preset).pixels[0]
    }

    func chroma(_ v: UInt32) -> Int {
        let r = Int((v >> 16) & 255), g = Int((v >> 8) & 255), b = Int(v & 255)
        return max(r, max(g, b)) - min(r, min(g, b))
    }

    func testNeutralSettingsKeepImage() {
        let img = gradient()
        XCTAssertEqual(Develop.Source(img).render(Develop.Settings()), img)
        var o = EditOptions()
        o.auto = false
        var edited = img
        PhotoEditor.edit(&edited, options: o)
        XCTAssertEqual(edited, img)
    }

    func testManualExposureBrightens() {
        var img = gradient()
        let before = meanLuminance(img)
        var o = EditOptions()
        o.auto = false
        o.manual = EditControls()
        o.manual?.exposure = 1
        PhotoEditor.edit(&img, options: o)
        XCTAssertGreaterThan(meanLuminance(img), before * 1.25)
    }

    func testManualIsDeltaOverPreset() {
        // O delta manual soma aos sliders do preset, como no Lightroom.
        let img = gradient()
        var a = EditOptions(), b = EditOptions()
        a.auto = false; b.auto = false
        a.preset = ["Exposure2012": 0.6]
        a.manual = EditControls(); a.manual?.exposure = 0.2
        b.preset = ["Exposure2012": 0.8]
        var x = img, y = img
        PhotoEditor.edit(&x, options: a)
        PhotoEditor.edit(&y, options: b)
        XCTAssertEqual(x, y)
    }

    func testAutoLiftsDarkImageModestly() {
        var img = PixelImage(width: 32, height: 32)
        for i in 0..<img.pixels.count { img.pixels[i] = packRGB(0.12 + Double(i % 7) * 0.01, 0.12, 0.13) }
        let before = meanLuminance(img)
        PhotoEditor.auto(&img.pixels)
        let after = meanLuminance(img)
        XCTAssertGreaterThan(after, before)
        XCTAssertLessThan(after, before * 1.6)
    }

    func testAutoKeepsNeutralExtremes() {
        for color: UInt32 in [0xFF00_0000, 0xFFFF_FFFF, 0xFF80_8080] {
            var p = [UInt32](repeating: color, count: 1000)
            PhotoEditor.auto(&p)
            XCTAssertEqual(p[0], color)
        }
        var dark = [UInt32](repeating: 0xFF30_3030, count: 1000)
        PhotoEditor.auto(&dark)
        XCTAssertTrue((49..<90).contains(Int(dark[0] & 255)))
    }

    func testAutoStrengthZeroKeepsImage() {
        var img = gradient()
        let before = img
        PhotoEditor.auto(&img.pixels, strength: 0)
        XCTAssertEqual(img, before)
    }

    func testPresetExposureBrightensAndKeepsSize() {
        let img = gradient(40, 30)
        let out = render(img, ["Exposure2012": 0.7, "Clarity2012": 20, "PostCropVignetteAmount": -10])
        XCTAssertEqual(out.pixels.count, 40 * 30)
        XCTAssertGreaterThan(meanLuminance(out), meanLuminance(img))
    }

    func testToneCurvesStayMonotonic() {
        var ramp = PixelImage(width: 256, height: 1)
        for i in 0..<256 { ramp.pixels[i] = packRGB(Double(i) / 255, Double(i) / 255, Double(i) / 255) }
        for preset in [["Exposure2012": 2.0], ["Contrast2012": 100], ["Contrast2012": -100], ["Whites2012": 100], ["Blacks2012": -100],
                       ["ParametricShadows": 100], ["ParametricHighlights": -100]] {
            let out = render(ramp, preset).pixels
            for i in 1..<256 { XCTAssertGreaterThanOrEqual(out[i] & 255, out[i - 1] & 255, "\(preset) em \(i)") }
        }
    }

    func testColorTools() {
        let blue = packRGB(40 / 255, 80 / 255, 200 / 255), red = packRGB(200 / 255, 40 / 255, 40 / 255)
        XCTAssertLessThan(chroma(one(blue, ["SaturationAdjustmentBlue": -100])), 8)
        XCTAssertGreaterThan(Int((one(red, ["HueAdjustmentRed": 100]) >> 8) & 255), 60)
        XCTAssertEqual(one(red, ["SaturationAdjustmentBlue": -100]), red)
        let bw = one(blue, ["ConvertToGrayscale": 1])
        XCTAssertEqual(chroma(bw), 0)
        XCTAssertEqual(one(0xFFFF_FFFF, ["BlueSaturation": 100, "RedHue": 40]), 0xFFFF_FFFF)
        let shade = one(0xFF30_3030, ["SplitToningShadowHue": 220, "SplitToningShadowSaturation": 60])
        XCTAssertGreaterThan(Int(shade & 255), Int((shade >> 16) & 255) + 8)
        let faded = one(0xFF00_0000, ["ToneCurvePV2012.0": 0, "ToneCurvePV2012.1": 30, "ToneCurvePV2012.2": 255, "ToneCurvePV2012.3": 255])
        XCTAssertEqual(Double(faded & 255), 30, accuracy: 1)
    }

    /// Calibração forte + HSL (preset real): pele e rosa não podem virar laranja, e exposição com
    /// realces/brancos negativos mantém a camisa branca branca (como no Lightroom).
    func testStrongCalibrationKeepsSkinAndWhites() {
        let real: [String: Double] = ["RedHue": 54, "RedSaturation": 47, "GreenHue": 53, "BlueHue": -61, "BlueSaturation": -70,
                                      "HueAdjustmentRed": -53, "HueAdjustmentOrange": 13, "HueAdjustmentYellow": -63, "HueAdjustmentAqua": 70]
        let pink = one(packRGB(251 / 255, 112 / 255, 152 / 255), real)
        XCTAssertGreaterThan(Int(pink & 255), Int((pink >> 8) & 255) + 20)
        let face = one(packRGB(205 / 255, 134 / 255, 122 / 255), real)
        XCTAssertGreaterThan(Int(face & 255), 70)
        let leaf = packRGB(60 / 255, 160 / 255, 60 / 255), teal = one(leaf, ["GreenHue": 100])
        XCTAssertGreaterThan(Int(teal & 255), Int(leaf & 255) + 30)
        let shirt = one(packRGB(235 / 255, 225 / 255, 249 / 255), ["Exposure2012": 0.52, "Highlights2012": -100, "Whites2012": -25])
        XCTAssertGreaterThan(Int((shirt >> 8) & 255), 235)
    }

    func testVignetteDarkensCornersOnly() {
        let img = PixelImage(width: 80, height: 60, fill: 0xFF90_9090)
        let out = render(img, ["PostCropVignetteAmount": -60])
        XCTAssertLessThan(Int(out[0, 0] & 255), 0x90 - 20)
        XCTAssertEqual(out[40, 30], 0xFF90_9090)
    }

    /// Mesma cena e mesmo preset de `tests/com/hisho/tether/DevelopTest.java`: Android e iOS
    /// precisam entregar a mesma foto.
    func testParityWithAndroid() {
        let w = 48, h = 36
        var img = PixelImage(width: w, height: h)
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
                img[x, y] = 0xFF00_0000 | UInt32(r) << 16 | UInt32(g) << 8 | UInt32(b)
            }
        }
        let preset: [String: Double] = [
            "IncrementalTemperature": -11, "Exposure2012": 0.15, "Contrast2012": 17, "Highlights2012": -70, "Shadows2012": 62, "Whites2012": 30,
            "Blacks2012": -20, "Clarity2012": 35, "Texture": 10, "Dehaze": 8, "Vibrance": 35, "Saturation": -10,
            "ParametricShadows": 10, "ParametricHighlights": -15, "ToneCurvePV2012.0": 0, "ToneCurvePV2012.1": 18, "ToneCurvePV2012.2": 128,
            "ToneCurvePV2012.3": 132, "ToneCurvePV2012.4": 255, "ToneCurvePV2012.5": 245,
            "HueAdjustmentOrange": -8, "SaturationAdjustmentBlue": -29, "LuminanceAdjustmentBlue": -20, "SaturationAdjustmentGreen": -30, "HueAdjustmentGreen": 25,
            "SplitToningShadowHue": 210, "SplitToningShadowSaturation": 15, "SplitToningHighlightHue": 45, "SplitToningHighlightSaturation": 12, "ColorGradeBlending": 60,
            "BlueSaturation": 25, "RedHue": 10, "ShadowTint": -5, "Sharpness": 40, "PostCropVignetteAmount": -21, "GrainAmount": 12,
        ]
        let out = render(img, preset).pixels
        let android: [UInt32] = [0xFF36669F, 0xFF3A6CA8, 0xFF4075B1, 0xFF457BB8, 0xFF467DBB, 0xFF487FBD, 0xFF1E6033, 0xFF2B7440, 0xFF2C7641,
                                 0xFFE7B99A, 0xFFCDA386, 0xFFF0C0A0, 0xFF3D4145, 0xFF515458, 0xFF54575A, 0xFF5A5D60, 0xFF585C5F, 0xFF4C4F53]
        for (n, i) in stride(from: 0, to: out.count, by: 97).enumerated() {
            for shift: UInt32 in [16, 8, 0] {
                let a = Int((out[i] >> shift) & 255), b = Int((android[n] >> shift) & 255)
                XCTAssertLessThanOrEqual(abs(a - b), 1, "pixel \(i) canal \(shift)")
            }
        }
        let sum = out.reduce(0) { $0 + Int(($1 >> 16) & 255) + Int(($1 >> 8) & 255) + Int($1 & 255) }
        XCTAssertEqual(Double(sum), 708_264, accuracy: 708_264 * 0.001)
    }

    /// Ajustes do editor (vinheta, HSL, nitidez…) substituem os valores do preset.
    func testEditorExtrasOverridePreset() {
        var o = EditOptions()
        o.auto = false
        o.preset = ["PostCropVignetteAmount": -60]
        var vignetted = PixelImage(width: 80, height: 60, fill: 0xFF90_9090)
        PhotoEditor.edit(&vignetted, options: o)
        XCTAssertLessThan(Int(vignetted[0, 0] & 255), 0x90 - 20)
        o.extra = ["PostCropVignetteAmount": 0]
        var plain = PixelImage(width: 80, height: 60, fill: 0xFF90_9090)
        PhotoEditor.edit(&plain, options: o)
        XCTAssertEqual(plain[0, 0], 0xFF90_9090)
        XCTAssertEqual(PhotoEditor.merged(["Texture": 10], ["Texture": 30])?["Texture"], 30)
        XCTAssertEqual(Develop.value("SharpenDetail", in: nil), 25)
        XCTAssertEqual(Develop.value("SharpenDetail", in: ["SharpenDetail": 40]), 40)
    }

    func testControlsSumIsLimited() {
        var a = EditControls(); a.exposure = 4; a.contrast = 80
        let s = a + a
        XCTAssertEqual(s.exposure, 5)
        XCTAssertEqual(s.contrast, 100)
        XCTAssertEqual(PhotoEditor.presetControls(["Shadows2012": 30]).shadows, 30)
    }
}

final class XMPTests: XCTestCase {
    let attributeXMP = """
    <x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
    <rdf:Description rdf:about="" xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/"
      crs:Exposure2012="+0.35" crs:Contrast2012="-12" crs:SaturationAdjustmentBlue="-20" crs:ProcessVersion="11.0"/>
    </rdf:RDF></x:xmpmeta>
    """

    func testParsesAttributes() throws {
        let p = try XMPPreset.parse(attributeXMP)
        XCTAssertEqual(p["Exposure2012"], 0.35)
        XCTAssertEqual(p["Contrast2012"], -12)
        XCTAssertEqual(p["SaturationAdjustmentBlue"], -20)
        XCTAssertNil(p["ProcessVersion"])
    }

    func testParsesElements() throws {
        let xml = """
        <x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
        <rdf:Description xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/"><crs:Vibrance>15</crs:Vibrance></rdf:Description>
        </rdf:RDF></x:xmpmeta>
        """
        XCTAssertEqual(try XMPPreset.parse(xml)["Vibrance"], 15)
    }

    func testParsesCurvesAndBooleans() throws {
        let xml = """
        <x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
        <rdf:Description xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/" crs:ConvertToGrayscale="True" crs:Temperature="5500">
        <crs:ToneCurvePV2012><rdf:Seq><rdf:li>0, 22</rdf:li><rdf:li>128, 128</rdf:li><rdf:li>255, 255</rdf:li></rdf:Seq></crs:ToneCurvePV2012>
        <crs:SplitToningShadowHue>220</crs:SplitToningShadowHue>
        </rdf:Description></rdf:RDF></x:xmpmeta>
        """
        let p = try XMPPreset.parse(xml)
        XCTAssertEqual(p["ConvertToGrayscale"], 1)
        XCTAssertEqual(p["ToneCurvePV2012.1"], 22)
        XCTAssertEqual(p["ToneCurvePV2012.4"], 255)
        XCTAssertNil(p["ToneCurvePV2012.6"])
        XCTAssertEqual(p["SplitToningShadowHue"], 220)
        XCTAssertNil(p["Temperature"])
    }

    func testRejectsEntitiesAndInvalidValues() {
        XCTAssertThrowsError(try XMPPreset.parse("<!DOCTYPE x [<!ENTITY a 'b'>]><x/>"))
        XCTAssertThrowsError(try XMPPreset.parse(attributeXMP.replacingOccurrences(of: "+0.35", with: "9")))
        XCTAssertThrowsError(try XMPPreset.parse("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"/>")) { error in
            XCTAssertEqual(error as? XMPPresetError, .empty)
        }
    }
}

final class CuratorTests: XCTestCase {
    func testFlatImageGoesToReview() {
        let img = PixelImage(width: 64, height: 64, fill: packRGB(0.5, 0.5, 0.5))
        let r = Curator.measure(img, sensitivity: 1)
        XCTAssertTrue(r.review)
        XCTAssertTrue(r.reason.contains("Nitidez baixa"))
        XCTAssertTrue(r.reason.contains("Contraste muito baixo"))
    }

    func testDetailedImageIsApproved() {
        var img = PixelImage(width: 128, height: 128)
        for y in 0..<128 {
            for x in 0..<128 {
                let center = abs(x - 64) < 30 && abs(y - 64) < 30
                let v = center ? (((x / 3) + (y / 3)) % 2 == 0 ? 0.85 : 0.2) : 0.45 + Double((x * 7 + y * 3) % 10) / 100
                img[x, y] = packRGB(v, v, v)
            }
        }
        let r = Curator.measure(img, sensitivity: 1)
        XCTAssertFalse(r.review, r.reason)
        XCTAssertTrue(r.reason.hasPrefix("Aprovada"))
        XCTAssertGreaterThanOrEqual(r.score, 6)
    }

    func testTinyImage() {
        XCTAssertEqual(Curator.measure(PixelImage(width: 8, height: 8), sensitivity: 1).score, 1)
    }
}

/// Histograma e máscara de recorte do editor (mesmos casos de HistogramTest.java).
final class HistogramTests: XCTestCase {
    func rgb(_ r: UInt32, _ g: UInt32, _ b: UInt32) -> UInt32 { 0xFF00_0000 | r << 16 | g << 8 | b }
    func image(_ pixels: [UInt32]) -> PixelImage { PixelImage(width: pixels.count, height: 1, pixels: pixels) }

    func testEmptyAndGray() {
        let empty = Histogram(pixels: [])
        XCTAssertEqual(empty.total, 0)
        XCTAssertEqual(empty.highlightWarning, 0)
        XCTAssertEqual(empty.shadowWarning, 0)
        let gray = Histogram(image([UInt32](repeating: rgb(128, 128, 128), count: 1000)))
        XCTAssertEqual(gray.total, 1000)
        for c in 0..<3 {
            XCTAssertEqual(gray.channels[c][128], 1000)
            XCTAssertEqual(gray.levels()[c][128], 1, accuracy: 1e-9)
        }
        XCTAssertEqual(gray.highlightWarning, 0)
        XCTAssertEqual(gray.shadowWarning, 0)
    }

    func testClippingWarningsAndLevels() {
        let px = (0..<400).map { i in i < 300 ? rgb(255, 255, 255) : i < 350 ? rgb(0, 0, 0) : rgb(90, 140, 200) }
        let h = Histogram(image(px))
        XCTAssertEqual(h.highlightWarning, Histogram.white)
        XCTAssertEqual(h.shadowWarning, Histogram.white)
        XCTAssertEqual(h.highlightFraction, 0.75, accuracy: 1e-9)
        XCTAssertEqual(h.shadowFraction, 0.125, accuracy: 1e-9)
        let lv = h.levels()
        XCTAssertEqual(lv[0][255], 1)
        XCTAssertEqual(lv[0][0], 1)
        XCTAssertEqual(lv[0][90], 1, "o mar de branco não achata o pico do meio")
        XCTAssertTrue(lv.allSatisfy { $0.allSatisfy { $0 >= 0 && $0 <= 1 } })

        let red = Histogram(image([UInt32](repeating: rgb(255, 0, 0), count: 50)))
        XCTAssertEqual(red.highAll, 0)
        XCTAssertEqual(red.highlightWarning, 0xFFFF_4040)
        XCTAssertEqual(red.shadowWarning, 0xFF40_FFFF)
    }

    func testClippingMask() {
        let src = image([rgb(255, 255, 255), rgb(254, 255, 254), rgb(0, 0, 0), rgb(1, 0, 1), rgb(255, 0, 0), rgb(30, 60, 90)])
        let m = Histogram.clipping(src)
        XCTAssertEqual(Array(m.pixels[0...1]), [Histogram.clipHigh, Histogram.clipHigh])
        XCTAssertEqual(Array(m.pixels[2...3]), [Histogram.clipLow, Histogram.clipLow])
        XCTAssertEqual(m.pixels[4], src.pixels[4])
        XCTAssertEqual(m.pixels[5], src.pixels[5])
    }
}


/// Perfis criativos, crs:Look no XMP, recorte e curvas no extra (mesmos casos de ProfileTest.java).
/// As tabelas abaixo foram geradas pelo codificador do teste Java (zlib + base 85 do DNG SDK), com o resultado
/// esperado calculado pelo CreativeProfile.java: o Swift tem de chegar no mesmo número.
final class ProfileCropTests: XCTestCase {
    static let swap = "t9000l.l5L.t7Ja5(P+o[Ioc=.T06xr'h7M#?2(yZl7[ft'F#'4qE)J3BB9p@B`E2etiVI1u(S-l)P-/{eQ`-L@K$z8p`pI!09iCaKcZvrV7rpky1s8(L7djHIj!he/e@nU7t=L{ybpG=PcepV/V5St'xktukM^4niy2r7Tg?:`U.^)P[g4p04Y9oAEaU6SLp?B^5Uv'Q(nN(YkB#GOuh@|e=[Hds#XtB1AnZRBo%Q}y`^f!uK6v!39PrnB2c9qf2-!+OC$*`#x!fd-nAPO*5izSU0xBp*5z05!$Ho(TWl[Co6%E(TemK^jyaF31-i^WNK!z]`NlO:UP8"
    static let adobeId = "t9000OcImwBRLy1{X$/wCGIxQPWfos6gj09TXrb0{0"
    static let warm = "t9000{=f`:/hP1vPu3o09$|%ru0GeF!W5'Ncbk*L|MIGJ|)e#6F!*gB4s]'bxrGwgJykn`LMWiQ2FIfU`CCn^*hR514kzfRGMWiQc`ij'0vAE]-oZlTQIPQjXVyI(oI|:u0@Wzm"
    static let gray = "'3000KZk$uzz]:0v0%/w55A12G`zvk!x.HQZlA2is?g9I'}1"
    static let rot = "Nr000KZk$uzz]:07d@/wraz6HSQ*GgW3JkRnX9xV7l-4)+OONDgTc)9L7Yuh?yu0Ac?nsSEf0"
    static let swapOut: [UInt32] = [0xFF6432C8, 0xFF000000, 0xFFFFFFFF, 0xFFA05A1E, 0xFF808080]
    static let adobeIdOut: [UInt32] = [0xFFC86432, 0xFF000000, 0xFFFFFFFF, 0xFF1EA05A, 0xFF808080]
    static let warmOut: [UInt32] = [0xFFD95F29, 0xFF390000, 0xFFFFFFEA, 0xFF549E50, 0xFF977D75]
    static let grayOut: [UInt32] = [0xFFA0A0A0, 0xFF000000, 0xFFFFFFFF, 0xFF979797, 0xFF808080]
    static let rotOut: [UInt32] = [0xFF6A9600, 0xFF000000, 0xFFE5E5E5, 0xFF009286, 0xFF737373]
    static let swapHalf: [UInt32] = [0xFF964B7D, 0xFF000000, 0xFFFFFFFF, 0xFF5F7D3C, 0xFF808080]
    static let input: [UInt32] = [0xFFC86432, 0xFF000000, 0xFFFFFFFF, 0xFF1EA05A, 0xFF808080]

    func near(_ a: UInt32, _ b: UInt32, _ tol: Int = 1) -> Bool {
        for s: UInt32 in [16, 8, 0] where abs(Int((a >> s) & 255) - Int((b >> s) & 255)) > tol { return false }
        return true
    }

    func run(_ text: String, _ amount: Double) throws -> [UInt32] {
        let profile: CreativeProfile.Profile
        switch try CreativeProfile.decode(text) {
        case .rgb(let t): profile = CreativeProfile.Profile(key: "t\(text.hashValue)/\(amount)", rgb: t, look: nil, rgbAmount: amount, lookAmount: 0, grayscale: false)
        case .look(let t): profile = CreativeProfile.Profile(key: "l\(text.hashValue)/\(amount)", rgb: nil, look: t, rgbAmount: 0, lookAmount: amount, grayscale: false)
        }
        return CreativeProfile.apply(PixelImage(width: Self.input.count, height: 1, pixels: Self.input), profile).pixels
    }

    func testBase85RoundTripAndTablesMatchAndroid() throws {
        #if canImport(Compression)
        for (text, expected) in [(Self.swap, Self.swapOut), (Self.adobeId, Self.adobeIdOut), (Self.warm, Self.warmOut), (Self.gray, Self.grayOut), (Self.rot, Self.rotOut)] {
            let out = try run(text, 1)
            for i in out.indices { XCTAssertTrue(near(out[i], expected[i]), "pixel \(i): \(String(out[i], radix: 16)) ≠ \(String(expected[i], radix: 16))") }
        }
        let half = try run(Self.swap, 0.5)
        for i in half.indices { XCTAssertTrue(near(half[i], Self.swapHalf[i]), "intensidade 50% pixel \(i)") }
        XCTAssertEqual(try run(Self.swap, 0), Self.input)
        #endif
        XCTAssertThrowsError(try CreativeProfile.decode("@@@@@"))
    }

    func testStoreResolveAndXMPLook() throws {
        #if canImport(Compression)
        let md5 = "0123456789ABCDEF0123456789ABCDEF"
        try CreativeProfile.store(md5, Self.swap)
        XCTAssertEqual(CreativeProfile.load(md5.lowercased()), Self.swap)
        let ref = CreativeLook(name: "Filme", rgbTable: md5, rgbAmount: 0.5)
        XCTAssertEqual(CreativeProfile.resolve(ref, amount: 100)?.rgbAmount ?? -1, 0.5, accuracy: 1e-9)
        XCTAssertEqual(CreativeProfile.resolve(ref, amount: 200)?.rgbAmount ?? -1, 1, accuracy: 1e-9)
        XCTAssertEqual(CreativeProfile.resolve(ref, amount: 0)?.active, false)
        XCTAssertNil(CreativeProfile.resolve(CreativeLook(name: "x", rgbTable: String(repeating: "F", count: 32)), amount: 100))
        XCTAssertThrowsError(try CreativeProfile.store("nada", Self.swap))

        // Preset com crs:Look: a curva e a exposição do perfil não vazam para os ajustes.
        let preset = """
        <x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/" crs:Exposure2012="+0.30" crs:Table_\(md5)="\(Self.swap)"><crs:Look><rdf:Description crs:Name="Filme 01" crs:Amount="0.8"><crs:Parameters><rdf:Description crs:RGBTable="\(md5)" crs:RGBTableAmount="0.6" crs:Exposure2012="+2"><crs:ToneCurvePV2012><rdf:Seq><rdf:li>0, 40</rdf:li><rdf:li>255, 200</rdf:li></rdf:Seq></crs:ToneCurvePV2012></rdf:Description></crs:Parameters></rdf:Description></crs:Look><crs:Contrast2012>+15</crs:Contrast2012></rdf:Description></rdf:RDF></x:xmpmeta>
        """
        let parsed = try XMPPreset.parseWithLook(Data(preset.utf8))
        XCTAssertEqual(parsed.values["Exposure2012"], 0.3)
        XCTAssertEqual(parsed.values["Contrast2012"], 15)
        XCTAssertNil(parsed.values["ToneCurvePV2012.0"])
        XCTAssertEqual(parsed.values["LookAmount"] ?? 0, 80, accuracy: 1e-9)
        XCTAssertEqual(parsed.look["RGBTable"], md5)
        XCTAssertEqual(parsed.look["RGBTableAmount"], "0.6")
        XCTAssertEqual(parsed.look["Name"], "Filme 01")
        XCTAssertEqual(parsed.look["Table_" + md5], Self.swap)

        // .xmp de perfil: tabela no topo, junto dos ajustes.
        let profile = """
        <x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/" crs:PresetType="Look" crs:RGBTable="\(md5)" crs:RGBTableAmount="0.75" crs:Table_\(md5)="\(Self.swap)" crs:Saturation="-10"/></rdf:RDF></x:xmpmeta>
        """
        let p2 = try XMPPreset.parseWithLook(Data(profile.utf8))
        XCTAssertEqual(p2.values["Saturation"], -10)
        XCTAssertEqual(p2.values["LookAmount"], 100)
        XCTAssertEqual(p2.look["RGBTableAmount"], "0.75")

        var o = EditOptions()
        o.look = CreativeLook(name: "Filme", rgbTable: md5)
        let img = PixelImage(width: Self.input.count, height: 1, pixels: Self.input)
        XCTAssertTrue(near(PhotoEditor.prepare(img, options: o).pixels[0], Self.swapOut[0]))
        o.extra = ["LookAmount": 0]
        XCTAssertEqual(PhotoEditor.prepare(img, options: o).pixels, Self.input)
        #endif
    }

    func testDefaultsAndCurveMerge() {
        XCTAssertEqual(Develop.value("LookAmount", in: [:]), 100)
        XCTAssertEqual(Develop.value("ColorGradeBlending", in: [:]), 100)
        XCTAssertEqual(Develop.value("ColorGradeBlending", in: ["ColorGradeMidtoneHue": 20]), 50)
        let preset: [String: Double] = ["ToneCurvePV2012.0": 0, "ToneCurvePV2012.1": 30, "ToneCurvePV2012.2": 128, "ToneCurvePV2012.3": 128,
                                        "ToneCurvePV2012.4": 255, "ToneCurvePV2012.5": 230, "Exposure2012": 0.5]
        let m = PhotoEditor.merged(preset, ["ToneCurvePV2012.0": 0, "ToneCurvePV2012.1": 0, "ToneCurvePV2012.2": 255, "ToneCurvePV2012.3": 255])!
        XCTAssertNil(m["ToneCurvePV2012.4"], "curva do extra substitui a do preset inteira")
        XCTAssertEqual(m["ToneCurvePV2012.3"], 255)
        XCTAssertEqual(m["Exposure2012"], 0.5)
        XCTAssertTrue(PhotoEditor.isCurveKey("ToneCurvePV2012Red.4"))
        XCTAssertFalse(PhotoEditor.isCurveKey("ToneCurvePV2012Red"))
        XCTAssertFalse(PhotoEditor.isCurveKey("Exposure2012.1"))
    }

    func testCropAndStraighten() {
        let w = 60, h = 40
        var img = PixelImage(width: w, height: h)
        for y in 0..<h { for x in 0..<w { img[x, y] = 0xFF00_0000 | UInt32(x * 4) << 16 | UInt32(y * 6) << 8 | 77 } }
        XCTAssertEqual(Crop.none.apply(img), img)
        let half = Crop(left: 0.5, top: 0, right: 1, bottom: 0.5).apply(img)
        XCTAssertEqual(half.width, 30); XCTAssertEqual(half.height, 20)
        XCTAssertEqual(half[0, 0], img[30, 0]); XCTAssertEqual(half[29, 19], img[59, 19])
        for a in [-30.0, -7.5, 3, 12, 45] {
            let c = Crop.largest(w, h, angle: a, aspect: 0)
            XCTAssertTrue(Crop.fits(c.left, c.top, c.right, c.bottom, w, h, a), "maior corte cabe \(a)")
            let big = Crop(angle: a).constrained(w, h)
            XCTAssertTrue(Crop.fits(big.left, big.top, big.right, big.bottom, w, h, a), "corte encolhido cabe \(a)")
            let out = c.apply(img)
            XCTAssertEqual(out.pixels.count, out.width * out.height)
        }
        let sq = Crop.largest(w, h, angle: 0, aspect: 1).size(w, h)
        XCTAssertLessThanOrEqual(abs(sq.0 - sq.1), 1); XCTAssertEqual(sq.1, h)
        let solid = PixelImage(width: w, height: h, fill: 0xFF0A_C85A)
        XCTAssertTrue(Crop.largest(w, h, angle: 20, aspect: 0).apply(solid).pixels.allSatisfy { $0 == 0xFF0A_C85A }, "girado sem borda preta")
        let canvas = Crop.rotated(solid, angle: 30, fill: 0xFF00_0000)
        XCTAssertEqual(canvas[0, 0], 0xFF00_0000); XCTAssertEqual(canvas[w / 2, h / 2], 0xFF0A_C85A)
        let q = Crop.toSource(Double(w) / 2, Double(h) / 2, w, h, 33)
        XCTAssertEqual(q.0, Double(w) / 2, accuracy: 1e-9); XCTAssertEqual(q.1, Double(h) / 2, accuracy: 1e-9)
        // O corte da foto é gravado e lido de volta.
        let data = try? JSONEncoder().encode(Crop(angle: 5, left: 0.1, top: 0.2, right: 0.9, bottom: 0.8))
        XCTAssertEqual(data.flatMap { try? JSONDecoder().decode(Crop.self, from: $0) }, Crop(angle: 5, left: 0.1, top: 0.2, right: 0.9, bottom: 0.8))
    }
}
