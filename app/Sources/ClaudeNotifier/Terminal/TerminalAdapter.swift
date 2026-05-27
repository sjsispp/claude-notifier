import Foundation

enum AdapterError: Error, Equatable {
    case unsupported
    case targetNotFound
    case pluginNotInstalled
    case scriptError(String)
}

protocol TerminalAdapter {
    var host: HookPayload.Host { get }
    func jump(item: NotificationItem) async throws
    func approve(item: NotificationItem) async throws
}
