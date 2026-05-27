import Foundation

struct UnknownAdapter: TerminalAdapter {
    let host: HookPayload.Host = .unknown
    func jump(item: NotificationItem) async throws { throw AdapterError.unsupported }
    func approve(item: NotificationItem) async throws { throw AdapterError.unsupported }
}
