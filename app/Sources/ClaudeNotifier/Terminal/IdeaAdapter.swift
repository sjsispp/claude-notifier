import Foundation

/// Plan A 阶段为 stub，Plan C 会改写为 HTTP 调用 IDEA plugin。
struct IdeaAdapter: TerminalAdapter {
    let host: HookPayload.Host = .idea
    func jump(item: NotificationItem) async throws { throw AdapterError.pluginNotInstalled }
    func approve(item: NotificationItem) async throws { throw AdapterError.pluginNotInstalled }
}
