import XCTest
@testable import LumoCore

final class MockTransport: PTPTransport {
    var calls: [(UInt16, [UInt32])] = []
    var handler: (UInt16, [UInt32]) throws -> Data

    init(handler: @escaping (UInt16, [UInt32]) throws -> Data) { self.handler = handler }

    func execute(_ code: UInt16, _ params: [UInt32]) async throws -> Data {
        calls.append((code, params))
        return try handler(code, params)
    }
}

func u32List(_ values: [UInt32]) -> Data {
    var d = Data()
    d.appendLE(UInt32(values.count))
    for v in values { d.appendLE(v) }
    return d
}

func objectInfo(name: String, date: String, size: UInt32, format: UInt16 = 0x3801) -> Data {
    var d = Data()
    d.appendLE(UInt32(0x0001_0001)); d.appendLE(format); d.appendLE(UInt16(0)); d.appendLE(size)
    d.append(Data(count: 52 - d.count))
    for s in [name, date] {
        let units = Array((s + "\0").utf16)
        d.append(UInt8(units.count))
        for u in units { d.appendLE(u) }
    }
    return d
}

final class PTPTests: XCTestCase {
    func testCommandContainer() {
        let c = PTPContainer.command(0x1007, transaction: 5, params: [0x10001, 0x3801, 0])
        XCTAssertEqual(c.count, 24)
        XCTAssertEqual(Array(c.prefix(12)), [24, 0, 0, 0, 1, 0, 0x07, 0x10, 5, 0, 0, 0])
        XCTAssertEqual(try PTPContainer.header(c), PTPContainer.Header(length: 24, type: 1, code: 0x1007, transaction: 5))
    }

    func testObjectInfoParsing() throws {
        let info = try PTPObjectInfo.parse(objectInfo(name: "IMG_0001.JPG", date: "20261013T183000.0", size: 123_456))
        XCTAssertEqual(info.name, "IMG_0001.JPG")
        XCTAssertEqual(info.size, 123_456)
        XCTAssertTrue(info.isJPEG)
        XCTAssertNotNil(PTPDate.parse(info.captureDate))
        XCTAssertThrowsError(try PTPObjectInfo.parse(Data(count: 10)))
    }

    func testArrayRejectsInvalidCount() {
        XCTAssertEqual(try PTPArray.u32(u32List([7, 9])), [7, 9])
        var bad = Data(); bad.appendLE(UInt32(5)); bad.appendLE(UInt32(1))
        XCTAssertThrowsError(try PTPArray.u32(bad))
    }

    func testCanonCaptureModeRetriesDeviceBusy() async throws {
        var busy = 2
        let t = MockTransport { code, _ in
            if code == PTPCode.canonSetRemoteMode && busy > 0 { busy -= 1; throw PTPError.response(PTPCode.deviceBusy) }
            return Data()
        }
        let session = CanonPTPSession(transport: t, sleep: { _ in })
        try await session.enableCaptureMode()
        XCTAssertEqual(t.calls.map { $0.0 }, [0x9114, 0x9114, 0x9114, 0x9115, 0x9116])
        XCTAssertTrue(session.remote && session.events)
    }

    func testListsAndDownloadsJPEGs() async throws {
        let jpeg = Data(repeating: 0xAB, count: 1000)
        let t = MockTransport { code, params in
            switch code {
            case PTPCode.getStorageIDs: return u32List([0x10001])
            case PTPCode.getObjectHandles: return u32List([11, 12])
            case PTPCode.getObjectInfo: return objectInfo(name: "IMG_\(params[0]).JPG", date: "20261013T120000", size: 1000)
            case PTPCode.getObject: return jpeg
            default: throw PTPError.response(0x2005)
            }
        }
        let s = CanonPTPSession(transport: t, sleep: { _ in })
        let objects = try await s.jpegObjects()
        XCTAssertEqual(objects, [.init(storage: 0x10001, handle: 11), .init(storage: 0x10001, handle: 12)])
        let info = try await s.info(12)
        XCTAssertEqual(info.name, "IMG_12.JPG")
        let data = try await s.download(12, expectedSize: info.size)
        XCTAssertEqual(data, jpeg)
        do { _ = try await s.download(12, expectedSize: 999); XCTFail("deveria falhar") } catch {}
    }

    func testPTPIPAssembler() throws {
        XCTAssertEqual(PTPIP.packet(14).count, 8)
        let request = PTPIP.operationRequest(0x1009, transaction: 7, params: [42])
        XCTAssertEqual(try PTPIP.header(request).type, 6)
        var a = PTPIP.ResponseAssembler(transaction: 7)
        var start = Data(); start.appendLE(UInt32(7)); start.appendLE(UInt64(6))
        XCTAssertEqual(try a.consume(type: 9, body: start), .needMore)
        var d1 = Data(); d1.appendLE(UInt32(7)); d1.append(contentsOf: [1, 2, 3])
        XCTAssertEqual(try a.consume(type: 10, body: d1), .needMore)
        XCTAssertEqual(try a.consume(type: 13, body: Data()), .replyProbe)
        var d2 = Data(); d2.appendLE(UInt32(7)); d2.append(contentsOf: [4, 5, 6])
        XCTAssertEqual(try a.consume(type: 12, body: d2), .needMore)
        var resp = Data(); resp.appendLE(UInt16(0x2001)); resp.appendLE(UInt32(7))
        XCTAssertEqual(try a.consume(type: 7, body: resp), .done(code: 0x2001, data: Data([1, 2, 3, 4, 5, 6])))
    }

    func testPTPIPRejectsWrongTransactionAndIncompleteData() {
        var a = PTPIP.ResponseAssembler(transaction: 3)
        var start = Data(); start.appendLE(UInt32(4)); start.appendLE(UInt64(1))
        XCTAssertThrowsError(try a.consume(type: 9, body: start))
        var b = PTPIP.ResponseAssembler(transaction: 3)
        var ok = Data(); ok.appendLE(UInt32(3)); ok.appendLE(UInt64(2))
        XCTAssertNoThrow(try b.consume(type: 9, body: ok))
        var resp = Data(); resp.appendLE(UInt16(0x2001)); resp.appendLE(UInt32(3))
        XCTAssertThrowsError(try b.consume(type: 7, body: resp))
    }

    func testInitCommandRequestLayout() throws {
        let p = PTPIP.initCommandRequest(guid: UUID(), name: "LUMO")
        let h = try PTPIP.header(p)
        XCTAssertEqual(h.type, 1)
        XCTAssertEqual(h.bodyLength, 16 + 10 + 4)
    }
}

final class FottoParsingTests: XCTestCase {
    func json(_ s: String) -> Any { try! FottoParsing.json(Data(s.utf8)) }

    func testGalleryArrayFormats() {
        XCTAssertEqual(FottoParsing.galleryArray(json(#"[{"id":1}]"#))?.count, 1)
        XCTAssertEqual(FottoParsing.galleryArray(json(#"{"galleries":[{"id":"a"},{"id":"b"}]}"#))?.count, 2)
        XCTAssertEqual(FottoParsing.galleryArray(json(#"{"data":{"items":[{"id":"a"}]}}"#))?.count, 1)
        let g = FottoParsing.galleries(from: FottoParsing.galleryArray(json(#"{"data":[{"id":12,"name":""},{"title":"x"}]}"#))!)
        XCTAssertEqual(g, [FottoGallery(id: "12", title: "Evento 12")])
    }

    func testNextGalleryPath() {
        XCTAssertEqual(FottoParsing.nextGalleryPath(json(#"{"meta":{"nextPage":2}}"#)), "/me/galleries?page=2")
        XCTAssertEqual(FottoParsing.nextGalleryPath(json(#"{"links":{"next":"https://api.fotto.com.br/api/me/galleries?page=3"}}"#)), "/me/galleries?page=3")
        XCTAssertNil(FottoParsing.nextGalleryPath(json(#"{"next":"https://evil.example/me/galleries?page=3"}"#)))
        XCTAssertNil(FottoParsing.nextGalleryPath(json(#"{"next":null}"#)))
        XCTAssertNil(FottoParsing.nextGalleryPath(json(#"{"next":false}"#)))
        XCTAssertEqual(FottoParsing.nextGalleryPath(json(#"{"nextCursor":"abc=="}"#)), "/me/galleries?cursor=abc%3D%3D")
    }

    func testProcessedMediaAndSignedURL() throws {
        let media = FottoParsing.processedMedia(json(#"{"data":{"medias":[{"id":5,"processed":true,"originalFileName":"A.jpg"},{"id":6,"processed":false}]}}"#))
        XCTAssertEqual(media, ["5": "A.jpg"])
        let signed = try FottoParsing.signedUpload(json(#"[{"id":"m1","signedUrl":"https://s3.example/x?sig=1"}]"#))
        XCTAssertEqual(signed.mediaID, "m1")
        XCTAssertEqual(signed.url.host, "s3.example")
        let body = try JSONSerialization.jsonObject(with: try FottoParsing.createMediaBody(name: "A.jpg", size: 10)) as? [[String: Any]]
        XCTAssertEqual(body?.first?["originalFileName"] as? String, "A.jpg")
        XCTAssertEqual(body?.first?["mediaSize"] as? Int, 10)
    }

    func testGalleryIDInputAndTokens() {
        XCTAssertEqual(FottoParsing.galleryID(fromInput: " https://fotto.com.br/painel/galleries/AbC-123/fotos "), "AbC-123")
        XCTAssertEqual(FottoParsing.galleryID(fromInput: "998877"), "998877")
        XCTAssertNil(FottoParsing.galleryID(fromInput: "a b"))
        XCTAssertEqual(FottoParsing.stripBearer("Bearer   xyz"), "xyz")
        XCTAssertTrue(FottoParsing.isTokenCandidate("abcdefghijklmnopq"))
        XCTAssertFalse(FottoParsing.isTokenCandidate("undefined"))
        XCTAssertFalse(FottoParsing.isTokenCandidate("https://fotto.com.br/x/y/z/w"))
    }
}

final class MockHTTP: FottoHTTP {
    var responses: [String: (Int, String)] = [:]
    var requested: [String] = []

    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let url = request.url!.absoluteString
        requested.append(url)
        let (code, body) = responses[url] ?? (404, "{}")
        return (Data(body.utf8), HTTPURLResponse(url: request.url!, statusCode: code, httpVersion: nil, headerFields: nil)!)
    }

    func upload(_ request: URLRequest, fromFile file: URL) async throws -> (Data, HTTPURLResponse) {
        requested.append("PUT " + request.url!.absoluteString)
        return (Data(), HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!)
    }
}

final class FottoClientTests: XCTestCase {
    let base = FottoClient.base

    func testFollowsExplicitPagination() async throws {
        let http = MockHTTP()
        http.responses[base + "/me"] = (200, "{}")
        http.responses[base + "/me/galleries"] = (200, #"{"data":[{"id":1,"title":"A"}],"meta":{"page":1,"totalPages":2}}"#)
        http.responses[base + "/me/galleries?page=2"] = (200, #"{"data":[{"id":2,"title":"B"}],"meta":{"page":2,"totalPages":2}}"#)
        let client = FottoClient(credential: FottoCredential(token: "k", bearer: false), http: http, sleep: { _ in })
        let listing = try await client.galleries()
        XCTAssertEqual(listing.items.map(\.id), ["1", "2"])
        XCTAssertTrue(listing.complete)
    }

    func testImplicitPaginationStopsWithWarning() async throws {
        let http = MockHTTP()
        http.responses[base + "/me"] = (200, "{}")
        let page = #"[{"id":1,"title":"A"}]"#
        http.responses[base + "/me/galleries"] = (200, page)
        http.responses[base + "/me/galleries?page=1"] = (200, page)
        http.responses[base + "/me/galleries?page=2"] = (200, page)
        let client = FottoClient(credential: FottoCredential(token: "k", bearer: true), http: http, sleep: { _ in })
        let listing = try await client.galleries()
        XCTAssertEqual(listing.items.count, 1)
        XCTAssertFalse(listing.complete)
        XCTAssertFalse(listing.warning.isEmpty)
    }

    func testUnauthorizedIsSessionExpired() async {
        let http = MockHTTP()
        http.responses[base + "/me"] = (401, "{}")
        let client = FottoClient(credential: FottoCredential(token: "k", bearer: false), http: http, sleep: { _ in })
        do { _ = try await client.galleries(); XCTFail("deveria falhar") } catch {
            XCTAssertEqual(error as? FottoError, .sessionExpired)
        }
        let validated = await client.validate(token: "k123456789012345678")
        XCTAssertNil(validated)
    }

    func testUploadCreatesMediaThenPuts() async throws {
        let http = MockHTTP()
        http.responses[base + "/me/galleries/77/medias"] = (200, #"[{"id":"m9","signedUrl":"https://s3.example/put"}]"#)
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("lumo-test-\(UUID().uuidString).jpg")
        try Data([1, 2, 3]).write(to: file)
        defer { try? FileManager.default.removeItem(at: file) }
        let client = FottoClient(credential: FottoCredential(token: "k", bearer: false), http: http, sleep: { _ in })
        let id = try await client.upload(file: file, name: "A_Editada.jpg", galleryID: "77")
        XCTAssertEqual(id, "m9")
        XCTAssertEqual(http.requested.last, "PUT https://s3.example/put")
    }
}

final class EventLibraryTests: XCTestCase {
    func testEventsPhotosAndDuplicates() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("lumo-lib-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: root) }
        let lib = EventLibrary(root: root)
        let event = try lib.createEvent(named: "Casamento Ana & João")
        XCTAssertTrue(event.folderName.hasPrefix("Casamento_Ana___Jo"))
        XCTAssertTrue(FileManager.default.fileExists(atPath: lib.folder(event, EventLibrary.review).path))
        XCTAssertThrowsError(try lib.createEvent(named: "   "))

        let id = EventLibrary.photoID(eventID: event.id, sourceKey: "usb:1:IMG_1.JPG:20261013:100")
        var p = PhotoRecord(id: id, eventID: event.id, source: .usb, originalName: "IMG_1.JPG",
                            baseName: EventLibrary.baseName(originalName: "IMG_1.JPG", id: id), capturedAt: Date(), size: 100, sha256: "abc")
        try lib.save(p, in: event)
        XCTAssertNotNil(lib.duplicate(sha256: "abc", in: event))
        p.stage = .approved
        try lib.save(p, in: event)
        XCTAssertEqual(lib.photos(in: event).count, 1)
        XCTAssertEqual(lib.pendingUploads(in: event, galleryID: "g").count, 1)
        p.upload = .confirmed; p.fottoGalleryID = "g"
        try lib.save(p, in: event)
        XCTAssertEqual(lib.pendingUploads(in: event, galleryID: "g").count, 0)
        XCTAssertEqual(lib.pendingUploads(in: event, galleryID: "outro").count, 1)

        // Releitura do disco.
        let reopened = EventLibrary(root: root)
        XCTAssertEqual(reopened.events().map(\.id), [event.id])
        XCTAssertEqual(reopened.photos(in: event).first?.upload, .confirmed)
    }
}
