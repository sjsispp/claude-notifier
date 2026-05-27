import ServiceManagement

struct LaunchAtLogin {
    static func register() {
        if #available(macOS 13.0, *) {
            do {
                if SMAppService.mainApp.status != .enabled {
                    try SMAppService.mainApp.register()
                }
            } catch {
                NSLog("[ClaudeNotifier] register login item failed: \(error)")
            }
        }
    }

    static func unregister() {
        if #available(macOS 13.0, *) {
            try? SMAppService.mainApp.unregister()
        }
    }

    static var isEnabled: Bool {
        if #available(macOS 13.0, *) {
            return SMAppService.mainApp.status == .enabled
        }
        return false
    }
}
