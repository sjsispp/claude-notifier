import Foundation

struct ITermAdapter: TerminalAdapter {
    let host: HookPayload.Host = .iterm
    let runner: AppleScriptRunning

    init(runner: AppleScriptRunning = AppleScriptRunner()) {
        self.runner = runner
    }

    /// iTerm 的 `ITERM_SESSION_ID` 环境变量格式是 `wXtYpZ:UUID`，
    /// 但 AppleScript `id of session` 只返回 UUID。
    /// 必须剥前缀，否则 `id of s contains "<env_id>"` 永远 false。
    static func normalizeSessionId(_ raw: String) -> String {
        if let lastColon = raw.lastIndex(of: ":") {
            return String(raw[raw.index(after: lastColon)...])
        }
        return raw
    }

    func jump(item: NotificationItem) async throws {
        guard let rawSid = item.itermSessionId else { throw AdapterError.targetNotFound }
        let sid = ITermAdapter.normalizeSessionId(rawSid)
        let escaped = sid.replacingOccurrences(of: "\"", with: "\\\"")
        let script = """
        tell application "iTerm"
          activate
          repeat with w in windows
            repeat with t in tabs of w
              repeat with s in sessions of t
                if id of s as text contains "\(escaped)" then
                  select s
                  tell w to select t
                  select w
                  return
                end if
              end repeat
            end repeat
          end repeat
        end tell
        """
        _ = try runner.run(script)
    }

    func approve(item: NotificationItem) async throws {
        guard let rawSid = item.itermSessionId else { throw AdapterError.targetNotFound }
        let sid = ITermAdapter.normalizeSessionId(rawSid)
        let escaped = sid.replacingOccurrences(of: "\"", with: "\\\"")
        // 用 jump 同样的全局遍历方式定位 session，避免依赖 "current tab of current window"
        // —— 之前 approve 在 CC 不在前台 tab 时同样会失败。
        let script = """
        tell application "iTerm"
          repeat with w in windows
            repeat with t in tabs of w
              repeat with s in sessions of t
                if id of s as text contains "\(escaped)" then
                  tell s to write text "1"
                  return
                end if
              end repeat
            end repeat
          end repeat
        end tell
        """
        _ = try runner.run(script)
    }
}
