import Cocoa
import Combine

final class MenuBarController {
    private let store: NotificationStore
    private let statusItem: NSStatusItem
    private var cancellables: Set<AnyCancellable> = []

    var onClick: (() -> Void)?
    var onShowPreferences: (() -> Void)?

    init(store: NotificationStore) {
        self.store = store
        self.statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        setupButton()
        subscribe()
    }

    private func setupButton() {
        guard let btn = statusItem.button else { return }
        btn.image = NSImage(systemSymbolName: "bell", accessibilityDescription: "Claude Notifier")
        btn.image?.isTemplate = true
        btn.target = self
        btn.action = #selector(handleClick(_:))
        btn.sendAction(on: [.leftMouseUp, .rightMouseUp])
        btn.title = ""
    }

    private func subscribe() {
        store.$items
            .receive(on: DispatchQueue.main)
            .sink { [weak self] items in
                self?.updateBadge(count: items.count)
            }
            .store(in: &cancellables)
    }

    private func updateBadge(count: Int) {
        guard let btn = statusItem.button else { return }
        btn.title = count > 0 ? " \(count)" : ""
    }

    @objc private func handleClick(_ sender: NSStatusBarButton) {
        let event = NSApp.currentEvent
        if event?.type == .rightMouseUp {
            showContextMenu()
        } else {
            onClick?()
        }
    }

    private func showContextMenu() {
        let menu = NSMenu()
        menu.addItem(withTitle: "偏好设置…", action: #selector(handlePreferences), keyEquivalent: ",").target = self
        menu.addItem(withTitle: "全部清空", action: #selector(handleClearAll), keyEquivalent: "").target = self
        menu.addItem(.separator())
        menu.addItem(withTitle: "退出 ClaudeNotifier", action: #selector(handleQuit), keyEquivalent: "q").target = self
        statusItem.menu = menu
        statusItem.button?.performClick(nil)
        statusItem.menu = nil
    }

    @objc private func handlePreferences() { onShowPreferences?() }
    @objc private func handleClearAll() { store.clear() }
    @objc private func handleQuit() { NSApp.terminate(nil) }
}
