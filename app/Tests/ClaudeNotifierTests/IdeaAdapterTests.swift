import XCTest
@testable import ClaudeNotifier

private final class MockURLProtocol: URLProtocol {
    static var handler: ((URLRequest) -> (HTTPURLResponse, Data?))?
    static var error: URLError?

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        if let err = MockURLProtocol.error {
            client?.urlProtocol(self, didFailWithError: err)
            return
        }
        guard let handler = MockURLProtocol.handler else {
            client?.urlProtocol(self, didFailWithError: URLError(.unknown)); return
        }
        let (resp, body) = handler(request)
        client?.urlProtocol(self, didReceive: resp, cacheStoragePolicy: .notAllowed)
        if let body { client?.urlProtocol(self, didLoad: body) }
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

final class IdeaAdapterTests: XCTestCase {
    override func setUp() {
        super.setUp()
        MockURLProtocol.handler = nil
        MockURLProtocol.error = nil
    }

    private func makeSession() -> URLSession {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [MockURLProtocol.self]
        return URLSession(configuration: config)
    }

    private func item(tabId: String?) -> NotificationItem {
        let id = tabId.map { #""\#($0)""# } ?? "null"
        let json = #"{"schema":1,"event":"notification","session_id":"s","cwd":"/p","project_name":"p","terminal":{"host":"idea","idea_tab_id":\#(id)},"ts":1}"#
            .data(using: .utf8)!
        return NotificationItem(payload: try! JSONDecoder().decode(HookPayload.self, from: json))
    }

    func test_jump_postsFocusTabWithTabId() async throws {
        var captured: URLRequest?
        MockURLProtocol.handler = { req in
            captured = req
            let resp = HTTPURLResponse(url: req.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (resp, #"{"ok":true}"#.data(using: .utf8))
        }
        let a = IdeaAdapter(session: makeSession())
        try await a.jump(item: item(tabId: "uuid-1"))

        XCTAssertEqual(captured?.url?.path, "/focusTab")
        XCTAssertEqual(captured?.httpMethod, "POST")
        let body = String(data: captured?.bodySteamCollected() ?? Data(), encoding: .utf8) ?? ""
        XCTAssertTrue(body.contains("uuid-1"))
    }

    func test_approve_postsSendTextWith1Newline() async throws {
        var captured: URLRequest?
        MockURLProtocol.handler = { req in
            captured = req
            let resp = HTTPURLResponse(url: req.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (resp, #"{"ok":true}"#.data(using: .utf8))
        }
        let a = IdeaAdapter(session: makeSession())
        try await a.approve(item: item(tabId: "uuid-2"))

        XCTAssertEqual(captured?.url?.path, "/sendText")
        let body = String(data: captured?.bodySteamCollected() ?? Data(), encoding: .utf8) ?? ""
        XCTAssertTrue(body.contains("uuid-2"))
        XCTAssertTrue(body.contains(#""text""#))
    }

    func test_jump_throwsTargetNotFound_whenTabIdMissing() async {
        let a = IdeaAdapter(session: makeSession())
        do { try await a.jump(item: item(tabId: nil)); XCTFail() }
        catch let e as AdapterError { XCTAssertEqual(e, .targetNotFound) }
        catch { XCTFail() }
    }

    func test_jump_maps410ToTargetNotFound() async {
        MockURLProtocol.handler = { req in
            let resp = HTTPURLResponse(url: req.url!, statusCode: 410, httpVersion: nil, headerFields: nil)!
            return (resp, nil)
        }
        let a = IdeaAdapter(session: makeSession())
        do { try await a.jump(item: item(tabId: "x")); XCTFail() }
        catch let e as AdapterError { XCTAssertEqual(e, .targetNotFound) }
        catch { XCTFail() }
    }

    func test_jump_maps500ToScriptError() async {
        MockURLProtocol.handler = { req in
            let resp = HTTPURLResponse(url: req.url!, statusCode: 500, httpVersion: nil, headerFields: nil)!
            return (resp, nil)
        }
        let a = IdeaAdapter(session: makeSession())
        do { try await a.jump(item: item(tabId: "x")); XCTFail() }
        catch let e as AdapterError {
            if case .scriptError = e { /* ok */ } else { XCTFail("expected scriptError, got \(e)") }
        }
        catch { XCTFail() }
    }

    func test_jump_mapsConnectionRefusedToPluginNotInstalled() async {
        MockURLProtocol.error = URLError(.cannotConnectToHost)
        let a = IdeaAdapter(session: makeSession())
        do { try await a.jump(item: item(tabId: "x")); XCTFail() }
        catch let e as AdapterError { XCTAssertEqual(e, .pluginNotInstalled) }
        catch { XCTFail("wrong error type: \(error)") }
    }
}

// 辅助：从 URLRequest 取出 httpBody。
// URLSession 把 httpBody 转成 httpBodyStream 后传给 URLProtocol，所以两边都得试。
private extension URLRequest {
    func bodySteamCollected() -> Data {
        if let body = self.httpBody { return body }
        guard let stream = self.httpBodyStream else { return Data() }
        var data = Data()
        stream.open()
        defer { stream.close() }
        let bufferSize = 4096
        let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: bufferSize)
        defer { buffer.deallocate() }
        while stream.hasBytesAvailable {
            let read = stream.read(buffer, maxLength: bufferSize)
            if read <= 0 { break }
            data.append(buffer, count: read)
        }
        return data
    }
}
