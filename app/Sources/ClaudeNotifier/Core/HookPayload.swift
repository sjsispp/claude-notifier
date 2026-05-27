import Foundation

struct HookPayload: Codable, Equatable {
    enum Event: String, Codable { case notification, stop }
    enum Host: String, Codable { case iterm, idea, vscode, unknown }

    struct Terminal: Codable, Equatable {
        let host: Host
        let itermSessionId: String?
        let ideaTabId: String?
        let tty: String?
        let ppid: Int?

        enum CodingKeys: String, CodingKey {
            case host
            case itermSessionId = "iterm_session_id"
            case ideaTabId = "idea_tab_id"
            case tty, ppid
        }
    }

    let schema: Int
    let event: Event
    let sessionId: String
    let cwd: String
    let projectName: String
    let message: String?
    let commandPreview: String?
    let lastPrompt: String?
    let terminal: Terminal
    let ts: Int

    enum CodingKeys: String, CodingKey {
        case schema, event
        case sessionId = "session_id"
        case cwd
        case projectName = "project_name"
        case message
        case commandPreview = "command_preview"
        case lastPrompt = "last_prompt"
        case terminal, ts
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let schema = try c.decode(Int.self, forKey: .schema)
        guard schema == 1 else {
            throw DecodingError.dataCorruptedError(
                forKey: .schema, in: c,
                debugDescription: "unsupported schema: \(schema)")
        }
        self.schema = schema
        self.event = try c.decode(Event.self, forKey: .event)
        self.sessionId = try c.decode(String.self, forKey: .sessionId)
        self.cwd = try c.decode(String.self, forKey: .cwd)
        self.projectName = try c.decode(String.self, forKey: .projectName)
        self.message = try c.decodeIfPresent(String.self, forKey: .message)
        self.commandPreview = try c.decodeIfPresent(String.self, forKey: .commandPreview)
        self.lastPrompt = try c.decodeIfPresent(String.self, forKey: .lastPrompt)
        self.terminal = try c.decode(Terminal.self, forKey: .terminal)
        self.ts = try c.decode(Int.self, forKey: .ts)
    }
}
