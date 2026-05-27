import Foundation

struct VSCodeAdapter: TerminalAdapter {
    let host: HookPayload.Host = .vscode
    let processRunner: ([String]) -> Int32

    init(processRunner: @escaping ([String]) -> Int32 = VSCodeAdapter.defaultRunner) {
        self.processRunner = processRunner
    }

    static let defaultRunner: ([String]) -> Int32 = { args in
        let p = Process()
        p.launchPath = "/usr/bin/env"
        p.arguments = args
        do { try p.run(); p.waitUntilExit(); return p.terminationStatus }
        catch { return -1 }
    }

    func jump(item: NotificationItem) async throws {
        let uri = "vscode://file\(item.cwd)"
        let code = processRunner(["open", uri])
        guard code == 0 else { throw AdapterError.scriptError("open failed: \(code)") }
    }

    func approve(item: NotificationItem) async throws {
        throw AdapterError.unsupported
    }
}
