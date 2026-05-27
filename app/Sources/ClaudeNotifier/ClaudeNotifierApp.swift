import Cocoa

@main
final class ClaudeNotifierApp: NSObject, NSApplicationDelegate {
    static func main() {
        let app = NSApplication.shared
        let delegate = ClaudeNotifierApp()
        app.delegate = delegate
        app.setActivationPolicy(.accessory)  // 不在 Dock 显示
        app.run()
    }

    private var store: NotificationStore!
    private var server: HookEndpointServer!
    private var menuBar: MenuBarController!
    private var runtimeConfig: RuntimeConfig!

    func applicationDidFinishLaunching(_ notification: Notification) {
        store = NotificationStore()
        server = HookEndpointServer(store: store)
        runtimeConfig = RuntimeConfig()

        guard let port = runtimeConfig.findAvailablePort(
            preferred: 6789, attempts: 11,
            isAvailable: HookEndpointServer.isPortAvailable)
        else {
            NSLog("[ClaudeNotifier] no available port; exit")
            NSApp.terminate(nil)
            return
        }

        do {
            try server.start(port: port)
            try runtimeConfig.writeRuntimeInfo(port: port)
            NSLog("[ClaudeNotifier] server listening on :\(port)")
        } catch {
            NSLog("[ClaudeNotifier] server start failed: \(error)")
            NSApp.terminate(nil)
        }

        menuBar = MenuBarController(store: store)
    }
}
