import XCTest
@testable import ClaudeNotifier

final class AdapterTests: XCTestCase {
    private func item(host: HookPayload.Host) -> NotificationItem {
        let json = #"{"schema":1,"event":"notification","session_id":"s","cwd":"/p","project_name":"p","terminal":{"host":"\#(host.rawValue)"},"ts":1}"#
            .data(using: .utf8)!
        return NotificationItem(payload: try! JSONDecoder().decode(HookPayload.self, from: json))
    }

    func test_unknownAdapter_alwaysUnsupported() async {
        let a = UnknownAdapter()
        await XCTAssertThrowsErrorAsync(try await a.jump(item: item(host: .unknown)))
        await XCTAssertThrowsErrorAsync(try await a.approve(item: item(host: .unknown)))
    }

    func test_ideaAdapter_returnsPluginNotInstalledByDefault() async {
        let a = IdeaAdapter()
        do {
            try await a.jump(item: item(host: .idea))
            XCTFail("expected throw")
        } catch let e as AdapterError {
            XCTAssertEqual(e, .pluginNotInstalled)
        } catch {
            XCTFail("wrong error type: \(error)")
        }
    }

    func test_vscodeAdapter_jump_invokesOpenWithVSCodeURI() async throws {
        var captured: [String] = []
        let a = VSCodeAdapter(processRunner: { args in
            captured = args
            return 0
        })
        try await a.jump(item: item(host: .vscode))
        XCTAssertEqual(captured.first, "open")
        XCTAssertTrue(captured.contains(where: { $0.hasPrefix("vscode://file") }))
    }

    func test_vscodeAdapter_approve_unsupported() async {
        let a = VSCodeAdapter(processRunner: { _ in 0 })
        await XCTAssertThrowsErrorAsync(try await a.approve(item: item(host: .vscode)))
    }
}

// helper
func XCTAssertThrowsErrorAsync<T>(_ expr: @autoclosure () async throws -> T,
                                  file: StaticString = #file, line: UInt = #line) async {
    do { _ = try await expr(); XCTFail("expected throw", file: file, line: line) }
    catch {}
}
