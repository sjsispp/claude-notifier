import SwiftUI

struct NotificationRowView: View {
    let item: NotificationItem
    let onApprove: () -> Void
    let onJump: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Text(eventIcon)
                Text(eventLabel).font(.caption).fontWeight(.semibold)
                    .foregroundColor(eventColor)
                Spacer()
                Text(relativeTime).font(.caption).foregroundColor(.secondary)
            }
            HStack(spacing: 5) {
                Text(item.projectName).font(.subheadline).fontWeight(.semibold)
                hostBadge
            }
            if let msg = item.message ?? item.lastPrompt {
                Text(msg).font(.caption).foregroundColor(.secondary)
                    .lineLimit(2)
            }
            if let cmd = item.commandPreview {
                Text(cmd).font(.system(.caption, design: .monospaced))
                    .padding(.horizontal, 6).padding(.vertical, 3)
                    .background(Color.black.opacity(0.05)).cornerRadius(3)
            }
            HStack(spacing: 6) {
                if item.event == .notification && canApprove {
                    Button("✓ 同意") { onApprove() }
                        .buttonStyle(.borderedProminent).controlSize(.small)
                }
                if canJump {
                    Button("↗ 跳转") { onJump() }
                        .buttonStyle(.bordered).controlSize(.small)
                }
                Button("×") { onDismiss() }
                    .buttonStyle(.bordered).controlSize(.small)
            }
        }
        .padding(12)
        .background(rowBackground)
    }

    private var eventIcon: String {
        item.event == .notification ? "🔐" : "✓"
    }
    private var eventLabel: String {
        item.event == .notification ? "等待权限" : "任务完成"
    }
    private var eventColor: Color {
        item.event == .notification ? .orange : .green
    }
    private var rowBackground: Color {
        item.event == .notification ? Color.yellow.opacity(0.15) : Color.green.opacity(0.10)
    }
    private var relativeTime: String {
        let delta = Int(Date().timeIntervalSince1970) - item.timestamp
        if delta < 60 { return "刚刚" }
        if delta < 3600 { return "\(delta/60) 分钟前" }
        return "\(delta/3600) 小时前"
    }
    private var hostBadge: some View {
        Text(hostLabel)
            .font(.system(size: 9, weight: .semibold))
            .padding(.horizontal, 5).padding(.vertical, 1)
            .background(hostColor)
            .foregroundColor(.white).cornerRadius(3)
    }
    private var hostLabel: String {
        switch item.host {
        case .iterm: return "iTerm"
        case .idea: return "IDEA"
        case .vscode: return "VS Code"
        case .unknown: return "?"
        }
    }
    private var hostColor: Color {
        switch item.host {
        case .iterm: return .black
        case .idea: return .orange
        case .vscode: return .blue
        case .unknown: return .gray
        }
    }
    private var canApprove: Bool {
        item.host == .iterm || item.host == .idea
    }
    private var canJump: Bool {
        item.host != .unknown
    }
}
