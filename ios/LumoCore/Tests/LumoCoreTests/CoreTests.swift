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

    func testManualNeutralIsNoOp() {
        var img = gradient()
        let before = img
        PhotoEditor.applyManual(&img.pixels, .zero)
        XCTAssertEqual(img, before)
    }

    func testManualExposureBrightens() {
        var img = gradient()
        let before = meanLuminance(img)
        var c = EditControls(); c.exposure = 1
        PhotoEditor.applyManual(&img.pixels, c)
        XCTAssertGreaterThan(meanLuminance(img), before * 1.4)
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

    func testAutoStrengthZeroKeepsImage() {
        var img = gradient()
        let before = img
        PhotoEditor.auto(&img.pixels, strength: 0)
        XCTAssertEqual(img, before)
    }

    func testPresetExposureBrightensAndKeepsSize() {
        var img = gradient(40, 30)
        let before = meanLuminance(img)
        PhotoEditor.applyPreset(&img, ["Exposure2012": 0.7, "Clarity2012": 20, "PostCropVignetteAmount": -10])
        XCTAssertEqual(img.pixels.count, 40 * 30)
        XCTAssertGreaterThan(meanLuminance(img), before)
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
