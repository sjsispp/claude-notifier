import XCTest
@testable import ClaudeNotifier

final class MockAdapter: TerminalAdapter {
    let host: HookPayload.Host
    var jumpCalled = 0
    var approveCalled = 0
    var jumpError: Error?
    var approveError: Error?
    init(host: HookPayload.Host) { self.host = host }
    func jump(item: NotificationItem) async throws {
        jumpCalled += 1
        if let e = jumpError { throw e }
    }
    func approve(item: NotificationItem) async throws {
        approveCalled += 1
        if let e = approveError { throw e }
    }
}

final class TerminalRouterTests: XCTestCase {
    private func item(host: HookPayload.Host) -> NotificationItem {
        let json = #"{"schema":1,"event":"notification","session_id":"s","cwd":"/p","project_name":"p","terminal":{"host":"\#(host.rawValue)"},"ts":1}"#.data(using: .utf8)!
        return NotificationItem(payload: try! JSONDecoder().decode(HookPayload.self, from: json))
    }

    func test_routes_jump_byHost() async throws {
        let iterm = MockAdapter(host: .iterm)
        let idea = MockAdapter(host: .idea)
        let router = TerminalRouter(adapters: [iterm, idea])
        try await router.jump(item: item(host: .iterm))
        XCTAssertEqual(iterm.jumpCalled, 1)
        XCTAssertEqual(idea.jumpCalled, 0)
    }

    func test_routes_approve_byHost() async throws {
        let iterm = MockAdapter(host: .iterm)
        let router = TerminalRouter(adapters: [iterm])
        try await router.approve(item: item(host: .iterm))
        XCTAssertEqual(iterm.approveCalled, 1)
    }

    func test_throwsUnsupported_whenNoAdapter() async {
        let router = TerminalRouter(adapters: [])
        do {
            try await router.jump(item: item(host: .unknown))
            XCTFail()
        } catch let e as AdapterError {
            XCTAssertEqual(e, .unsupported)
        } catch { XCTFail() }
    }
}
