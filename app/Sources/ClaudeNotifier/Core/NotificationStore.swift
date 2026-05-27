import Foundation
import Combine

final class NotificationStore: ObservableObject {
    @Published private(set) var items: [NotificationItem] = []

    var isEmpty: Bool { items.isEmpty }

    func upsert(_ payload: HookPayload) {
        let new = NotificationItem(payload: payload)
        if let idx = items.firstIndex(where: { $0.sessionId == new.sessionId }) {
            items[idx] = new
        } else {
            items.append(new)
        }
    }

    func remove(id: UUID) {
        items.removeAll { $0.id == id }
    }

    func setError(id: UUID, message: String?) {
        if let idx = items.firstIndex(where: { $0.id == id }) {
            items[idx].lastError = message
        }
    }

    func clear() {
        items.removeAll()
    }
}
