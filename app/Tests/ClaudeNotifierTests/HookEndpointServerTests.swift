import XCTest
@testable import ClaudeNotifier

final class HookEndpointServerTests: XCTestCase {
    func test_postValidHook_addsToStore_andReturns200() async throws {
        let store = NotificationStore()
        let server = HookEndpointServer(store: store)
        try server.start(port: 16789)
        defer { server.stop() }

        let url = URL(string: "http://127.0.0.1:16789/hook")!
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = """
        {"schema":1,"event":"notification","session_id":"abc",
         "cwd":"/p/foo","project_name":"foo",
         "message":"Permission","terminal":{"host":"iterm"},"ts":1}
        """.data(using: .utf8)

        let (data, response) = try await URLSession.shared.data(for: req)
        let http = response as! HTTPURLResponse
        XCTAssertEqual(http.statusCode, 200)
        let body = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        XCTAssertEqual(body["accepted"] as? Bool, true)

        // 等一拍让主线程更新 store
        try await Task.sleep(nanoseconds: 100_000_000)
        XCTAssertEqual(store.items.count, 1)
        XCTAssertEqual(store.items[0].sessionId, "abc")
    }

    func test_postInvalidSchema_returns400() async throws {
        let store = NotificationStore()
        let server = HookEndpointServer(store: store)
        try server.start(port: 16790)
        defer { server.stop() }

        let url = URL(string: "http://127.0.0.1:16790/hook")!
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.httpBody = #"{"schema":99,"event":"stop","session_id":"x","cwd":"/","project_name":"x","terminal":{"host":"unknown"},"ts":1}"#.data(using: .utf8)
        let (_, response) = try await URLSession.shared.data(for: req)
        XCTAssertEqual((response as! HTTPURLResponse).statusCode, 400)
        XCTAssertEqual(store.items.count, 0)
    }
}
