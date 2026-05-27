import Foundation

final class TerminalRouter {
    private let adapters: [HookPayload.Host: TerminalAdapter]

    init(adapters: [TerminalAdapter]) {
        var map: [HookPayload.Host: TerminalAdapter] = [:]
        for a in adapters { map[a.host] = a }
        self.adapters = map
    }

    static func defaultRouter() -> TerminalRouter {
        TerminalRouter(adapters: [
            ITermAdapter(),
            VSCodeAdapter(),
            IdeaAdapter(),
            UnknownAdapter()
        ])
    }

    func jump(item: NotificationItem) async throws {
        guard let a = adapters[item.host] else { throw AdapterError.unsupported }
        try await a.jump(item: item)
    }

    func approve(item: NotificationItem) async throws {
        guard let a = adapters[item.host] else { throw AdapterError.unsupported }
        try await a.approve(item: item)
    }
}
