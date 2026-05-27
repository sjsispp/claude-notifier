import XCTest
@testable import ClaudeNotifier

final class HookPayloadTests: XCTestCase {
    func test_decodesNotificationEvent() throws {
        let json = """
        {
          "schema": 1,
          "event": "notification",
          "session_id": "abc-123",
          "cwd": "/Users/me/foo",
          "project_name": "foo",
          "message": "Claude needs permission",
          "command_preview": "npm test",
          "last_prompt": "run tests",
          "terminal": {
            "host": "iterm",
            "iterm_session_id": "w0t1p0:UUID",
            "idea_tab_id": null,
            "tty": "/dev/ttys001",
            "ppid": 12345
          },
          "ts": 1779874936
        }
        """.data(using: .utf8)!
        let payload = try JSONDecoder().decode(HookPayload.self, from: json)
        XCTAssertEqual(payload.event, .notification)
        XCTAssertEqual(payload.sessionId, "abc-123")
        XCTAssertEqual(payload.terminal.host, .iterm)
        XCTAssertEqual(payload.terminal.itermSessionId, "w0t1p0:UUID")
        XCTAssertEqual(payload.commandPreview, "npm test")
    }

    func test_decodesStopEvent_withoutOptionalFields() throws {
        let json = """
        {
          "schema": 1, "event": "stop",
          "session_id": "x", "cwd": "/tmp", "project_name": "tmp",
          "terminal": {"host": "unknown"},
          "ts": 1
        }
        """.data(using: .utf8)!
        let payload = try JSONDecoder().decode(HookPayload.self, from: json)
        XCTAssertEqual(payload.event, .stop)
        XCTAssertNil(payload.message)
        XCTAssertNil(payload.commandPreview)
        XCTAssertEqual(payload.terminal.host, .unknown)
    }

    func test_rejectsUnsupportedSchema() throws {
        let json = #"{"schema": 99, "event": "stop", "session_id":"x", "cwd":"/","project_name":"x","terminal":{"host":"unknown"},"ts":1}"#
            .data(using: .utf8)!
        XCTAssertThrowsError(try JSONDecoder().decode(HookPayload.self, from: json))
    }
}
