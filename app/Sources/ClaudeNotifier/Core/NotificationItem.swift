import Foundation

struct NotificationItem: Identifiable, Equatable {
    let id: UUID
    let sessionId: String
    let event: HookPayload.Event
    let host: HookPayload.Host
    let projectName: String
    let cwd: String
    let message: String?
    let commandPreview: String?
    let lastPrompt: String?
    let itermSessionId: String?
    let ideaTabId: String?
    let timestamp: Int

    init(payload: HookPayload, id: UUID = UUID()) {
        self.id = id
        self.sessionId = payload.sessionId
        self.event = payload.event
        self.host = payload.terminal.host
        self.projectName = payload.projectName
        self.cwd = payload.cwd
        self.message = payload.message
        self.commandPreview = payload.commandPreview
        self.lastPrompt = payload.lastPrompt
        self.itermSessionId = payload.terminal.itermSessionId
        self.ideaTabId = payload.terminal.ideaTabId
        self.timestamp = payload.ts
    }
}
