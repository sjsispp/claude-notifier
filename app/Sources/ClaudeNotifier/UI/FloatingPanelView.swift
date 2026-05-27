import SwiftUI

struct FloatingPanelView: View {
    @ObservedObject var store: NotificationStore
    let onApprove: (NotificationItem) async -> Void
    let onJump: (NotificationItem) async -> Void
    let onDismiss: (UUID) -> Void

    var body: some View {
        VStack(spacing: 0) {
            Text("Claude 通知 (\(store.items.count))")
                .font(.headline)
                .padding()
            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(VisualEffectBlur())
    }
}

struct VisualEffectBlur: NSViewRepresentable {
    func makeNSView(context: Context) -> NSVisualEffectView {
        let v = NSVisualEffectView()
        v.material = .popover
        v.blendingMode = .behindWindow
        v.state = .active
        return v
    }
    func updateNSView(_ nsView: NSVisualEffectView, context: Context) {}
}
