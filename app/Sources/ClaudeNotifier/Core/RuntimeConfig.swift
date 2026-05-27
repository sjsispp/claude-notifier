import Foundation

final class RuntimeConfig {
    let runtimeFileURL: URL

    init(runtimeFileURL: URL = RuntimeConfig.defaultRuntimeFileURL()) {
        self.runtimeFileURL = runtimeFileURL
    }

    static func defaultRuntimeFileURL() -> URL {
        let home = FileManager.default.homeDirectoryForCurrentUser
        return home.appendingPathComponent(".config/claude-notifier/runtime.json")
    }

    /// `isAvailable` 返回该端口是否可绑定；生产中由 HookEndpointServer 试 bind 决定，测试用注入 closure。
    func findAvailablePort(preferred: Int, attempts: Int,
                           isAvailable: (Int) -> Bool) -> Int? {
        for offset in 0..<attempts {
            let candidate = preferred + offset
            if isAvailable(candidate) { return candidate }
        }
        return nil
    }

    func writeRuntimeInfo(port: Int) throws {
        let dir = runtimeFileURL.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let payload: [String: Any] = [
            "port": port,
            "host": "127.0.0.1",
            "pid": ProcessInfo.processInfo.processIdentifier,
            "startedAt": Int(Date().timeIntervalSince1970)
        ]
        let data = try JSONSerialization.data(withJSONObject: payload, options: [.prettyPrinted])
        try data.write(to: runtimeFileURL, options: .atomic)
    }
}
