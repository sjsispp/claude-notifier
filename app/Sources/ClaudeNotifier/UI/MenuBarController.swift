import Cocoa
import Combine

final class MenuBarController {
    private let store: NotificationStore
    private let statusItem: NSStatusItem
    private var cancellables: Set<AnyCancellable> = []

    var onClick: (() -> Void)?

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
        btn.action = #selector(handleClick)
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

    @objc private func handleClick() {
        onClick?()
    }
}
