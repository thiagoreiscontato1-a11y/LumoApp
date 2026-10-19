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
        let android: [UInt32] = [0xFF376BA6, 0xFF3A6DA9, 0xFF3E72AD, 0xFF4378B3, 0xFF457AB8, 0xFF487EBC, 0xFF27703C, 0xFF2D7A43, 0xFF2E7C44,
                                 0xFFD3AA8E, 0xFFC69E82, 0xFFD6AD90, 0xFF474A4E, 0xFF515559, 0xFF54575A, 0xFF595C60, 0xFF5A5D60, 0xFF55585B]
        for (n, i) in stride(from: 0, to: out.count, by: 97).enumerated() {
            for shift: UInt32 in [16, 8, 0] {
                let a = Int((out[i] >> shift) & 255), b = Int((android[n] >> shift) & 255)
                XCTAssertLessThanOrEqual(abs(a - b), 1, "pixel \(i) canal \(shift)")
            }
        }
        let sum = out.reduce(0) { $0 + Int(($1 >> 16) & 255) + Int(($1 >> 8) & 255) + Int($1 & 255) }
        XCTAssertEqual(Double(sum), 700_788, accuracy: 700_788 * 0.001)
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
