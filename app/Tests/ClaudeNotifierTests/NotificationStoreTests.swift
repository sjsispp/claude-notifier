import XCTest
@testable import ClaudeNotifier

final class NotificationStoreTests: XCTestCase {
    private func makePayload(session: String, event: HookPayload.Event = .notification, ts: Int = 1) -> HookPayload {
        let json = #"""
        {
          "schema": 1, "event": "\#(event.rawValue)",
          "session_id": "\#(session)", "cwd": "/p/\#(session)",
          "project_name": "\#(session)",
          "terminal": {"host": "iterm"},
          "ts": \#(ts)
        }
        """#.data(using: .utf8)!
        return try! JSONDecoder().decode(HookPayload.self, from: json)
    }

    func test_addingFirstItem_appearsInList() {
        let store = NotificationStore()
        store.upsert(makePayload(session: "a"))
        XCTAssertEqual(store.items.count, 1)
        XCTAssertEqual(store.items[0].sessionId, "a")
    }

    func test_addingSameSession_replacesInPlace() {
        let store = NotificationStore()
        store.upsert(makePayload(session: "a", ts: 1))
        store.upsert(makePayload(session: "a", ts: 2))
        XCTAssertEqual(store.items.count, 1)
        XCTAssertEqual(store.items[0].timestamp, 2)
    }

    func test_addingDifferentSessions_keepsBoth() {
        let store = NotificationStore()
        store.upsert(makePayload(session: "a"))
        store.upsert(makePayload(session: "b"))
        XCTAssertEqual(store.items.count, 2)
    }

    func test_remove_clearsItem() {
        let store = NotificationStore()
        store.upsert(makePayload(session: "a"))
        let id = store.items[0].id
        store.remove(id: id)
        XCTAssertEqual(store.items.count, 0)
    }

    func test_isEmpty_observable() {
        let store = NotificationStore()
        XCTAssertTrue(store.isEmpty)
        store.upsert(makePayload(session: "a"))
        XCTAssertFalse(store.isEmpty)
    }
}
