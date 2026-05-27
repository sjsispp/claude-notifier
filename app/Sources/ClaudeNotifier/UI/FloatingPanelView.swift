import SwiftUI

struct FloatingPanelView: View {
    @ObservedObject var store: NotificationStore
    let onApprove: (NotificationItem) async -> Void
    let onJump: (NotificationItem) async -> Void
    let onDismiss: (UUID) -> Void

    var body: some View {
        VStack(spacing: 0) {
            header
            Divider()
            if store.items.isEmpty {
                emptyState
            } else {
                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(store.items) { item in
                            NotificationRowView(
                                item: item,
                                onApprove: { Task { await onApprove(item) } },
                                onJump: { Task { await onJump(item) } },
                                onDismiss: { onDismiss(item.id) }
                            )
                            Divider()
                        }
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(VisualEffectBlur())
        .clipShape(RoundedRectangle(cornerRadius: 14))
    }

    private var header: some View {
        HStack {
            Text("Claude 通知 · \(store.items.count)")
                .font(.system(size: 12, weight: .semibold))
            Spacer()
            Button(action: { store.clear() }) {
                Image(systemName: "trash")
            }
            .buttonStyle(.plain)
            .help("全部关闭")
        }
        .padding(.horizontal, 14).padding(.vertical, 10)
    }

    private var emptyState: some View {
        VStack {
            Spacer()
            Text("当前没有通知").font(.caption).foregroundColor(.secondary)
            Spacer()
        }
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
