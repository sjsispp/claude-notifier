import Foundation

struct ITermAdapter: TerminalAdapter {
    let host: HookPayload.Host = .iterm
    let runner: AppleScriptRunning

    init(runner: AppleScriptRunning = AppleScriptRunner()) {
        self.runner = runner
    }

    func jump(item: NotificationItem) async throws {
        guard let sid = item.itermSessionId else { throw AdapterError.targetNotFound }
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
        guard let sid = item.itermSessionId else { throw AdapterError.targetNotFound }
        let escaped = sid.replacingOccurrences(of: "\"", with: "\\\"")
        let script = """
        tell application "iTerm"
          tell session id "\(escaped)" of current tab of current window
            write text "1"
          end tell
        end tell
        """
        _ = try runner.run(script)
    }
}
