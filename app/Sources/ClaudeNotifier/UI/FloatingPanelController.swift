import Cocoa
import SwiftUI

final class FloatingPanelController {
    private let panel: NSPanel
    private let store: NotificationStore
    private weak var router: TerminalRouter?

    init(store: NotificationStore, router: TerminalRouter) {
        self.store = store
        self.router = router

        let frame = NSRect(x: 0, y: 0, width: 360, height: 480)
        panel = NSPanel(
            contentRect: frame,
            styleMask: [.titled, .closable, .nonactivatingPanel, .fullSizeContentView],
            backing: .buffered, defer: false)
        panel.title = "Claude 通知"
        panel.titlebarAppearsTransparent = true
        panel.titleVisibility = .hidden
        panel.isFloatingPanel = true
        panel.level = .floating
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        panel.hidesOnDeactivate = false
        panel.isReleasedWhenClosed = false
        panel.backgroundColor = .clear
        panel.isOpaque = false

        let view = FloatingPanelView(store: store, onApprove: { [weak self] in await self?.handleApprove($0) },
                                                  onJump: { [weak self] in await self?.handleJump($0) },
                                                  onDismiss: { [weak self] in self?.store.remove(id: $0) })
        panel.contentView = NSHostingView(rootView: view)
    }

    func show() {
        positionTopRight()
        panel.orderFrontRegardless()
    }

    func hide() { panel.orderOut(nil) }

    func toggle() {
        if panel.isVisible { hide() } else { show() }
    }

    var isVisible: Bool { panel.isVisible }

    private func positionTopRight() {
        guard let screen = NSScreen.main else { return }
        let v = screen.visibleFrame
        let size = panel.frame.size
        let x = v.maxX - size.width - 12
        let y = v.maxY - size.height - 12
        panel.setFrameOrigin(NSPoint(x: x, y: y))
    }

    private func handleApprove(_ item: NotificationItem) async {
        do {
            try await router?.approve(item: item)
            await MainActor.run { store.remove(id: item.id) }
        } catch {
            NSLog("[ClaudeNotifier] approve failed: \(error)")
        }
    }

    private func handleJump(_ item: NotificationItem) async {
        do { try await router?.jump(item: item) }
        catch { NSLog("[ClaudeNotifier] jump failed: \(error)") }
    }
}
