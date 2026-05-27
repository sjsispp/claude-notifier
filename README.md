# Claude Notifier

> 让 Claude Code 不再被你错过：**常驻浮窗**接管所有权限确认与任务完成提示，一键同意、一键跳回终端。

适配 macOS（13+），同时支持 iTerm2、IntelliJ 全家桶、VS Code 终端。

---

## 为什么写这个

Claude Code 时不时会 **暂停等你拍板**：

- 跑 Bash 前问"是否允许"
- 单轮回复结束等下一步指示

你切去看文档/写别的代码/开会，回来一看——CC 在终端里已经傻等了 20 分钟。

macOS 原生通知会自动消失到通知中心；没法"同意"，只能切回去手动按 `1`；多窗口/多 tab 时还得肉眼找是哪个 CC 在等你。

**Claude Notifier 解决这三件事**：

1. **持久浮窗**：屏幕右上常驻直到你处理掉，不自动消失
2. **一键操作**：同意按钮直接发 `1\n` 给 CC 那个终端会话
3. **精准定位**：点跳转切到具体那个 iTerm tab / IDEA 终端 tab，不靠肉眼找

---

## 它长这样

```
┌─────────────────────────────────────┐
│ Claude 通知 · 2              🗑️ ⚙️ │
├─────────────────────────────────────┤
│ 🔐 等待权限              2 分钟前    │
│ workplace/foo  [iTerm]              │
│ Claude 想要执行 Bash 命令            │
│ ┌─────────────────────┐             │
│ │ npm run test:integ  │             │
│ └─────────────────────┘             │
│ [ ✓ 同意 ]  [ ↗ 跳转 ]  [ × ]       │
├─────────────────────────────────────┤
│ ✓ 任务完成                 刚刚      │
│ project/redapi [IDEA]               │
│ 把 spec 写到 docs/specs/...         │
│ [ ↗ 跳转 ]              [ 关闭 ]    │
└─────────────────────────────────────┘
```

浮窗常驻屏幕右上、不抢焦点、半透明 vibrancy 背景。

---

## 三件套

```
┌──────────────────────┐  POST :6789  ┌──────────────────────┐
│ CC plugin (bash)     │ ───────────► │ ClaudeNotifier.app   │ ◄─┐
│ hooks + slash cmds   │              │ macOS 菜单栏代理       │   │
└──────────────────────┘              │ NSPanel 浮窗          │   │
                                      │ TerminalRouter        │   │ POST :6790
                                      └──────────────────────┘   │
                                                                 │
┌──────────────────────────────────────────────────────────────┐ │
│ IDEA plugin (Kotlin)                                          │ │
│ TerminalEnvCustomizer 注入 CLAUDE_IDEA_TAB_ID                 │─┘
│ HTTP server: /sendText /focusTab /healthz                     │
└──────────────────────────────────────────────────────────────┘
```

| 组件 | 角色 | 必须装？ |
|------|------|---------|
| **macOS App** (`app/`) | 通知中枢、浮窗 UI、操作派发 | ✅ 必须 |
| **CC plugin** (`cc-plugin/`) | 把 CC 事件 POST 到 App | ✅ 必须 |
| **IDEA plugin** (`idea-plugin/`) | 让 IDEA 终端 100% 可靠 sendText / focusTab | ⚠️ 只有用 IDEA 终端才需要 |

不装 IDEA plugin 也能用 —— 只是 IDEA 行点"同意"会提示"未检测到 IDEA plugin"。iTerm 路径完全不需要 IDEA plugin。

---

## 终端宿主能力矩阵

| 终端 | 跳转 | 同意 | 实现 |
|------|------|------|------|
| **iTerm2** | ✅ 100% | ✅ 100% | AppleScript `write text` 直送 PTY，无焦点依赖 |
| **IDEA 全家桶**（IntelliJ / GoLand / PyCharm / RustRover / WebStorm…） | ✅ 100% | ✅ 100% | IDEA plugin 暴露 HTTP API，调 `JBTerminalWidget.sendCommandToExecute` |
| **VS Code** | ⚠️ 仅激活窗口 | ❌ 禁用 | `open vscode://file/<cwd>`，集成终端无脚本接口 |
| **其他/unknown** | ❌ | ❌ | 仅显示通知 |

> 为什么 IDEA 不能像 VS Code 一样退化为"激活窗口"就够了？因为 IDEA 用户大量在 IDE 内嵌终端跑 CC，那里弹权限确认特别多。装个原生 plugin 把可靠性从"靠焦点猜"提到 100% 是值得的。

---

## 5 分钟安装

完整指南：[**INSTALL.md**](INSTALL.md)

### 速通版

```bash
git clone <repo-url> ~/claude-notifier
cd ~/claude-notifier

# 1. macOS App（需 Xcode）
cd app && swift run ClaudeNotifier &
cd ..

# 2. CC plugin（需 jq）
ln -sf "$PWD/cc-plugin" ~/.claude/plugins/claude-notifier
# 然后把 cc-plugin/plugin.json 的 hooks 段合并进 ~/.claude/settings.json
# 详见 INSTALL.md 第 2 节

# 3. IDEA plugin（可选）
unzip -o idea-plugin/build/distributions/claude-notifier-idea-0.1.0.zip \
  -d "$HOME/Library/Application Support/JetBrains/IntelliJIdea2026.1/plugins/"
# 重启 IDE
```

验证（任一组件装完都能跑）：

```bash
curl -X POST http://127.0.0.1:6789/hook -d '{
  "schema":1,"event":"notification","session_id":"t","cwd":"/tmp",
  "project_name":"test","message":"hi","terminal":{"host":"iterm"},"ts":1}'
# 浮窗应弹出 + 听到 Glass 提示音
```

---

## 工作流程

以"CC 弹 Bash 权限确认"为例：

```
1. 用户在 iTerm 里跑 claude code，问它"跑下 npm test"
2. CC 准备执行 npm test → 触发 Notification hook
3. CC plugin hook 脚本 (notify-hook.sh) 抓住：
   - session_id, cwd, message
   - 当前终端的 TERM_PROGRAM / ITERM_SESSION_ID / CLAUDE_IDEA_TAB_ID
   - 上一条 prompt（来自 capture-prompt.sh 缓存的 /tmp/claude-prompt-*）
4. curl POST → http://127.0.0.1:6789/hook
5. App 收到，按 session_id 去重，更新 NotificationStore
6. 浮窗自动弹出（如果隐藏），菜单栏徽标 +1，Glass 提示音
7. 用户点"同意"：
   - iTerm 场景：AppleScript 直接 `write text "1"` 到对应 session
   - IDEA 场景：HTTP POST 到 IDEA plugin → JBTerminalWidget.sendCommandToExecute("1")
8. CC 收到 1 + 回车，权限通过，npm test 开始跑
9. 浮窗那行消失
```

权限链路是 **fail-open** —— App 没启动 / hook 出错都不会阻塞 CC，最多没通知而已（带 `terminal-notifier` 兜底通知）。

---

## 项目结构

```
claude-notifier/
├── app/                              # macOS 菜单栏 App
│   ├── Package.swift
│   ├── Sources/ClaudeNotifier/
│   │   ├── ClaudeNotifierApp.swift   # @main + AppDelegate
│   │   ├── Core/                     # HookPayload, NotificationStore, RuntimeConfig
│   │   ├── Server/                   # HookEndpointServer (Swifter)
│   │   ├── Terminal/                 # Router + 4 adapters
│   │   ├── UI/                       # NSPanel + SwiftUI views
│   │   └── Utilities/                # SoundPlayer, LaunchAtLogin
│   └── Tests/                        # 30 单元 + 集成测试
│
├── cc-plugin/                        # Claude Code plugin
│   ├── plugin.json
│   ├── hooks/
│   │   ├── capture-prompt.sh         # UserPromptSubmit
│   │   └── notify-hook.sh            # Notification + Stop
│   └── commands/
│       ├── notifier-install.md       # /notifier-install
│       └── notifier-status.md        # /notifier-status
│
├── idea-plugin/                      # IntelliJ Platform plugin
│   ├── build.gradle.kts
│   ├── src/main/kotlin/io/claudenotifier/idea/
│   │   ├── TerminalTabRegistry.kt
│   │   ├── TerminalEnvCustomizer.kt
│   │   ├── ClaudeNotifierStartupActivity.kt
│   │   └── server/                   # HTTP server + 3 handlers
│   └── src/test/                     # 4 Kotlin 单元测试
│
├── docs/
│   ├── specs/
│   │   ├── 2026-05-27-claude-notifier-design.md          # 整体设计
│   │   └── 2026-05-27-claude-notifier-implementation-notes.md  # 实施备忘
│   ├── plans/                        # 3 个实施 plan
│   └── manual-test-checklist.md
│
├── INSTALL.md                        # 完整安装指南
└── README.md
```

---

## 开发

### macOS App

需要 **Xcode**（完整安装，不是 Command Line Tools——XCTest 不在 CLT 里）。

```bash
cd app
swift build                          # debug 构建
swift build -c release               # release 构建（用于后台常驻）
swift test                           # 跑 30 个测试
swift run ClaudeNotifier             # 前台启动看日志
```

### CC plugin

纯 bash + jq，无构建步骤。手动测试：

```bash
echo '{"hook_event_name":"Notification","session_id":"t","cwd":"/tmp","message":"x"}' \
  | TERM_PROGRAM=iTerm.app ITERM_SESSION_ID=fake \
  bash cc-plugin/hooks/notify-hook.sh
```

### IDEA plugin

需要 **JDK 17+**（或用 IntelliJ 自带的 JBR：`export JAVA_HOME=/Applications/IntelliJ\ IDEA.app/Contents/jbr/Contents/Home`）。

```bash
cd idea-plugin
./gradlew test                       # 跑 Kotlin 单元测试
./gradlew buildPlugin                # 产物：build/distributions/*.zip
./gradlew runIde                     # 起 IntelliJ sandbox 调试（GUI）
```

---

## 测试

```
Swift (app/)      : 30 tests passing
Kotlin (idea-plugin/) : 4 tests passing
```

完整手动 E2E 清单：
- `docs/manual-test-checklist.md` —— App 层
- `cc-plugin/tests/manual-test-checklist.md` —— 全链路
- `idea-plugin/tests/manual-test-checklist.md` —— IDEA plugin

---

## 端口与运行时

| 端口 | 用途 | 占用时自增 |
|------|------|-----------|
| 6789 | App HTTP server（接收 hook） | 6790..6799 |
| 6790 | IDEA plugin HTTP server | 6791..6799 |

App 启动后写 `~/.config/claude-notifier/runtime.json` 暴露当前端口；CC plugin hook 读这个文件，**不要在脚本里硬编码端口**。

---

## 偏好设置

菜单栏 🔔 右键 → 偏好设置：

- **新事件提示音**：开关 Glass 音效
- **新事件自动弹出浮窗**：关掉后只更新菜单栏徽标，浮窗保持收起
- **开机自启**：macOS 13+ 用 `SMAppService` 注册登录项
- **HTTP 端口**：只读展示当前端口（端口冲突时会显示自增后的）

---

## Roadmap

- [ ] 历史记录视图（看过去一天所有事件）
- [ ] Brew Cask 发布（`brew install --cask claude-notifier`）
- [ ] 远程通知转发（手机收到通知，可点确认）
- [ ] VS Code 集成终端 100% 可靠的同意（需要 VS Code 插件，与 IDEA plugin 类似思路）
- [ ] 自定义事件规则（什么关键词触发更醒目的提示）
- [ ] 跨 Mac 同步（一个账号在多台 Mac）

---

## 兼容性

| 项 | 要求 | 备注 |
|----|------|------|
| macOS | 13 Ventura+ | NSPanel.floatingPanel / SMAppService 需要 |
| Xcode | 完整安装 | swift test 依赖 XCTest |
| IntelliJ Platform | 2024.2+ | plugin sinceBuild=242 |
| JDK（构建 IDEA plugin） | 17+ | 或用 IntelliJ 自带 JBR |
| Claude Code | 任意支持 plugin hooks 的版本 | UserPromptSubmit / Notification / Stop |

不支持 Windows / Linux —— 浮窗依赖 macOS NSPanel。

---

## 设计文档

- [设计 spec](docs/specs/2026-05-27-claude-notifier-design.md) — 11 节完整设计（背景 → 架构 → 协议 → UI → 错误处理 → 测试）
- [实施备忘](docs/specs/2026-05-27-claude-notifier-implementation-notes.md) — 实施过程中的偏差与决策记录
- [Plan A](docs/plans/2026-05-27-plan-a-macos-app.md) — App 16-task 实施计划
- [Plan B](docs/plans/2026-05-27-plan-b-cc-plugin.md) — CC plugin 7-task 实施计划
- [Plan C](docs/plans/2026-05-27-plan-c-idea-plugin.md) — IDEA plugin 9-task 实施计划

---

## 卸载

```bash
# 关 App
pkill -f 'ClaudeNotifier$'

# 移除 CC plugin
rm ~/.claude/plugins/claude-notifier
# 然后从 ~/.claude/settings.json 删 claude-notifier 那几段 hook（INSTALL.md 第 5 节有备份回滚）

# IDEA plugin: Preferences → Plugins → 卸载 → 重启

# 清运行时
rm -rf ~/.config/claude-notifier
rm /tmp/claude-prompt-* 2>/dev/null
```

---

## License

MIT —— 见 [LICENSE](LICENSE)。

## 贡献

欢迎 issue / PR。开发约定见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 致谢

- [Swifter](https://github.com/httpswift/swifter) — 极轻量 Swift HTTP server
- [IntelliJ Platform SDK](https://plugins.jetbrains.com/docs/intellij/welcome.html) — JetBrains 平台 API
- [terminal-notifier](https://github.com/julienXX/terminal-notifier) — macOS 通知 fallback
