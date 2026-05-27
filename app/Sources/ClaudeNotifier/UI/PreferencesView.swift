import SwiftUI

struct PreferencesView: View {
    @AppStorage("soundEnabled") private var soundEnabled = true
    @AppStorage("autoShowEnabled") private var autoShowEnabled = true
    @State private var launchAtLogin = LaunchAtLogin.isEnabled
    let currentPort: Int

    var body: some View {
        Form {
            Toggle("新事件提示音", isOn: $soundEnabled)
            Toggle("新事件自动弹出浮窗", isOn: $autoShowEnabled)
            Toggle("开机自启", isOn: $launchAtLogin)
                .onChange(of: launchAtLogin) { newVal in
                    if newVal { LaunchAtLogin.register() }
                    else { LaunchAtLogin.unregister() }
                }
            HStack {
                Text("HTTP 端口")
                Spacer()
                Text("\(currentPort)").foregroundColor(.secondary)
            }
        }
        .padding(20)
        .frame(width: 360)
    }
}
