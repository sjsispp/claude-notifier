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

    func test_jump_buildsScriptContainingSessionId() async throws {
        let mock = MockAppleScriptRunner()
        let a = ITermAdapter(runner: mock)
        try await a.jump(item: item(itermId: "w0t1p0:UUID"))
        XCTAssertEqual(mock.captured.count, 1)
        XCTAssertTrue(mock.captured[0].contains("w0t1p0:UUID"))
        XCTAssertTrue(mock.captured[0].contains("iTerm"))
    }

    func test_approve_writesText1ToSession() async throws {
        let mock = MockAppleScriptRunner()
        let a = ITermAdapter(runner: mock)
        try await a.approve(item: item(itermId: "w0t1p0:UUID"))
        XCTAssertEqual(mock.captured.count, 1)
        XCTAssertTrue(mock.captured[0].contains("w0t1p0:UUID"))
        XCTAssertTrue(mock.captured[0].contains(#"write text "1""#))
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
