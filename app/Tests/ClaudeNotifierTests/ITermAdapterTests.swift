import XCTest
@testable import ClaudeNotifier

final class MockAppleScriptRunner: AppleScriptRunning {
    var captured: [String] = []
    var stub: ((String) -> String?)?
    func run(_ source: String) throws -> String? {
        captured.append(source)
        return stub?(source)
    }
}

final class ITermAdapterTests: XCTestCase {
    private func item(itermId: String?) -> NotificationItem {
        let id = itermId.map { #""\#($0)""# } ?? "null"
        let json = #"""
        {"schema":1,"event":"notification","session_id":"s","cwd":"/p","project_name":"p",
         "terminal":{"host":"iterm","iterm_session_id":\#(id)},"ts":1}
        """#.data(using: .utf8)!
        return NotificationItem(payload: try! JSONDecoder().decode(HookPayload.self, from: json))
    }

    // MARK: - 关键 bugfix: ITERM_SESSION_ID 环境变量是 "wXtYpZ:UUID" 格式，
    // 但 iTerm AppleScript `id of session` 只返回 UUID。必须剥前缀，否则
    // `id of s contains "<env_id>"` 永远 false（短串不可能 contains 长串）。

    func test_jump_stripsItermSessionIdPrefix_andUsesUUID() async throws {
        let mock = MockAppleScriptRunner()
        let a = ITermAdapter(runner: mock)
        try await a.jump(item: item(itermId: "w0t2p0:776C9868-49B3-4DE5-947F-18238A996DE5"))
        XCTAssertEqual(mock.captured.count, 1)
        let script = mock.captured[0]
        XCTAssertTrue(script.contains("776C9868-49B3-4DE5-947F-18238A996DE5"),
                      "Script should embed the raw UUID")
        XCTAssertFalse(script.contains("w0t2p0:"),
                       "Script must strip iTerm window/tab/pane prefix; iTerm's session id is the UUID only")
        XCTAssertTrue(script.contains("iTerm"))
    }

    func test_approve_stripsItermSessionIdPrefix_andUsesUUID() async throws {
        let mock = MockAppleScriptRunner()
        let a = ITermAdapter(runner: mock)
        try await a.approve(item: item(itermId: "w0t2p0:776C9868-49B3-4DE5-947F-18238A996DE5"))
        XCTAssertEqual(mock.captured.count, 1)
        let script = mock.captured[0]
        XCTAssertTrue(script.contains("776C9868-49B3-4DE5-947F-18238A996DE5"))
        XCTAssertFalse(script.contains("w0t2p0:"))
        XCTAssertTrue(script.contains(#"write text "1""#))
    }

    func test_jump_acceptsPlainUUID_withoutPrefix() async throws {
        // 容错：如果 hook 已经传裸 UUID（无前缀），也要 work
        let mock = MockAppleScriptRunner()
        let a = ITermAdapter(runner: mock)
        try await a.jump(item: item(itermId: "PLAIN-UUID-NO-COLON"))
        XCTAssertTrue(mock.captured[0].contains("PLAIN-UUID-NO-COLON"))
    }

    func test_jump_throwsWhenItermSessionIdMissing() async {
        let mock = MockAppleScriptRunner()
        let a = ITermAdapter(runner: mock)
        do {
            try await a.jump(item: item(itermId: nil))
            XCTFail("expected throw")
        } catch let e as AdapterError {
            XCTAssertEqual(e, .targetNotFound)
        } catch {
            XCTFail("wrong type")
        }
    }
}
