# Claude Notifier 设计文档

- 日期：2026-05-27
- 状态：草案，待 writing-plans 拆解执行
- 项目目录：`/Users/wudazhan/workplace/project/claude-notifier`

## 1. 背景与目标

### 问题陈述

Claude Code（以下简称 CC）在多个常见场景下需要程序员的人工介入：

- 请求工具调用权限（Notification hook 触发，CC 会一直等待用户在终端选 1/2/3）
- 单轮任务完成等待下一步指示（Stop hook 触发）

但程序员经常在切到其他屏幕、写其他代码、看文档时错过这些中断。当前用户已经接入 `terminal-notifier` 作为基础提醒，但存在三个核心痛点：

1. macOS 原生通知会自动消失到通知中心，**不够"持久"**
2. 通知没有"直接同意/继续"的动作按钮，必须切回终端手动操作
3. 多个并发 CC 会话时，通知不能精准定位到具体的终端窗口/tab

### 目标

构建一套常驻、可交互、能跨终端宿主精准定位的通知系统，最大化降低 CC 中断对程序员心流的打扰。

### 非目标

- 不在 Windows / Linux 上支持（用户场景纯 macOS）
- 不承担 CC 会话的完整 UI（仍是"提醒 + 跳转"，主交互在终端里）
- 不替代 CC 的内置权限系统（不试图改写 CC 自身的 permission 流程）

## 2. 关键决策与限制条件

| # | 决策 | 替代方案 | 选定理由 |
|---|------|----------|----------|
| D1 | macOS App + CC plugin + IDEA plugin 三件套 | 纯 CC plugin / 纯独立 App | CC plugin 受 CC 进程生命周期限制无法常驻；纯独立 App 安装麻烦。三件套各司其职：plugin 简化接入，App 承载 UI 与跨会话状态，IDEA plugin 解决 JediTerm 无脚本接口问题 |
| D2 | App 用 SwiftUI 原生开发 | Tauri (Rust) / Electron / Python | 真正持久浮窗依赖 `NSPanel.floatingPanel`，原生体验最优；包体积小（<50MB）；调 AppleScript / Accessibility API 最顺 |
| D3 | 浮窗形态为屏幕右上常驻 `NSPanel` | macOS 通知中心 / 模态对话框 / 菜单栏下拉 | 用户明确要"一直存在直到自己删除"；非激活面板不抢焦点 |
| D4 | 三件套通过本地 HTTP 通信 | UNIX domain socket / mach port / 文件监听 | curl + JSON 最简；调试容易；端口冲突可自动重试 |
| D5 | IDEA 端写专属 plugin，**不**走键盘事件模拟 | CGEventPost + Option+F12 聚焦 | 键盘事件存在焦点依赖，10%+ 失败率不可接受；IDEA plugin 通过 `JBTerminalWidget.executeCommand()` 直接送字符到 PTY，100% 可靠且可寻址到具体 tab |
| D6 | 同 session_id 的事件**替换**而非堆叠 | 累加计数显示 "(3)" | 多次重复请求（如连续 3 个 Bash 权限）聚合为一条"等待中"更易理解 |
| D7 | hook 脚本 fail-open，永不阻塞 CC | hook 失败也提醒用户 | CC 体验优先，通知是辅助；hook 链路时延上限 100ms |

### 重要技术限制

- **TIOCSTI 不可用**：macOS Catalina 起禁用，无法直接向他人 tty 注入输入
- **Accessibility 权限**：iTerm 路径不依赖；仅 IDEA fallback / VS Code 场景需要（可选）
- **IDEA plugin 需要重启 IDE 才生效**（IntelliJ Platform API 的固定限制）

## 3. 整体架构

```
┌──────────────────────┐   ┌──────────────────────┐
│  iTerm2 / VS Code    │   │  IntelliJ IDEA       │
│   ↓ CC hook          │   │  ┌───────────────┐   │
└──────────┬───────────┘   │  │ 终端 tab (CC) │   │
           │               │  │  ↓ CC hook    │   │
           │               │  └───────┬───────┘   │
           │               │  ┌───────▼────────┐  │
           │               │  │ IDEA Plugin    │  │
           │               │  │ HTTP :6790     │◀─┼─┐
           │               │  └────────────────┘  │ │
           │               └──────────────────────┘ │
           ▼                                        │
┌─────────────────────────────────────────────────┐ │
│  ClaudeNotifier.app  (menubar, SwiftUI)         │ │
│                                                  │ │
│  HTTP :6789  ──▶ NotificationStore              │ │
│                       │                          │ │
│                       ▼                          │ │
│              NSPanel.floatingPanel              │ │
│                  + Menubar item                  │ │
│                       │                          │ │
│                       ▼ 用户点击                  │ │
│              TerminalRouter                      │ │
│                  ├ iTermAdapter (AppleScript)   │ │
│                  └ IdeaAdapter (HTTP :6790) ────┴─┘
└─────────────────────────────────────────────────┘
           ▲
           │ POST hook event
┌──────────┴───────────────┐
│ CC plugin (claude-notifier) │
│ hooks/notify-hook.sh        │
└─────────────────────────────┘
```

### 三组件职责

**ClaudeNotifier.app**（菜单栏代理，`LSUIElement=true`）
- 内嵌 HTTP server 接收 hook POST
- 维护 NotificationStore（内存中，不落盘）
- 渲染浮窗与菜单栏徽标
- 通过 TerminalRouter 派发跳转/同意操作

**claude-notifier CC plugin**
- `hooks/notify-hook.sh`：Notification + Stop 事件统一 POST
- `hooks/capture-prompt.sh`：UserPromptSubmit 时缓存最后一条 prompt
- `commands/notifier-install.md`：自检 App 与 IDEA plugin 安装状态
- `commands/notifier-status.md`：查看当前 queue 与最近事件（调试用）

**claude-notifier IDEA plugin**（IntelliJ Platform, Kotlin）
- `TerminalEnvCustomizer`：新建终端 tab 时分配 UUID 并注入 `CLAUDE_IDEA_TAB_ID` 环境变量
- `TerminalTabRegistry`：维护 UUID → `JBTerminalWidget` 映射
- `HttpServer`：监听 :6790，提供 `/sendText` 和 `/focusTab`

## 4. Hook → Server 协议

### Hook 脚本行为

CC 调用 hook 时 stdin 已提供 `hook_event_name`、`session_id`、`cwd` 等字段。脚本在此基础上补充终端环境信息：

```bash
TERM_PROGRAM           # iTerm.app / vscode / 空
ITERM_SESSION_ID       # iTerm 专属
TERMINAL_EMULATOR      # JetBrains-JediTerm (IDEA)
CLAUDE_IDEA_TAB_ID     # 由 IDEA plugin 注入
TTY=$(tty)
PPID
```

最后一条 prompt 缓存沿用文件方案：UserPromptSubmit hook 写入 `/tmp/claude-prompt-${session_id}`，Notification/Stop hook 读取。

### POST 包结构

```json
POST http://127.0.0.1:6789/hook
{
  "schema": 1,
  "event": "notification",
  "session_id": "550e8400-e29b-41d4-a716-446655440000",
  "cwd": "/Users/wudazhan/workplace/foo",
  "project_name": "foo",
  "message": "Claude needs your permission to use Bash",
  "command_preview": "npm run test:integration",
  "last_prompt": "实现登录接口...",
  "terminal": {
    "host": "iterm",
    "iterm_session_id": "w0t1p0:UUID",
    "idea_tab_id": null,
    "tty": "/dev/ttys001",
    "ppid": 12345
  },
  "ts": 1779874936
}
```

- `event` 取值：`notification` | `stop`
- `host` 取值：`iterm` | `idea` | `vscode` | `unknown`
- Notification 事件包含 `message` 和 `command_preview`；Stop 事件可省略

### 终端宿主识别规则

| `TERM_PROGRAM` | `TERMINAL_EMULATOR` | `CLAUDE_IDEA_TAB_ID` | 判定 host |
|----------------|---------------------|---------------------|-----------|
| `iTerm.app` | — | — | `iterm` |
| 任意 | `JetBrains-JediTerm` | 非空 | `idea` |
| `vscode` | — | — | `vscode` |
| 其它 | 其它 | — | `unknown` |

### Server 响应与去重规则

- 200 OK，返回 `{event_id, accepted: true}`
- 同 `session_id` 已有未处理事件 → **替换**该条目（不堆叠），UI 上边框闪一下
- 用户点"同意"/"关闭" → 从队列移除
- 用户点"跳转" → 保留条目，仅激活窗口

### 性能要求

- Hook 链端到端 < 100ms
- curl `--max-time 2`，超时后静默 + fallback 到 `terminal-notifier`
- Hook 脚本永不返回非 0（不阻塞 CC 主流程）

## 5. 终端定位（跳转 + 同意）

### iTerm2（黄金路径）

跳转：

```applescript
tell application "iTerm"
  activate
  repeat with w in windows
    repeat with t in tabs of w
      repeat with s in sessions of t
        if id of s as text contains "<iterm_session_id>" then
          select s
          tell w to select t
          select w
          return
        end if
      end repeat
    end repeat
  end repeat
end tell
```

同意：

```applescript
tell application "iTerm"
  tell session id "<iterm_session_id>" of current tab of current window
    write text "1"
  end tell
end tell
```

`write text` 直接送字符到该 session 的 stdin，不依赖窗口焦点，100% 可靠。

### IDEA（通过 IDEA plugin）

跳转：

```
POST http://127.0.0.1:6790/focusTab
{ "tabId": "<CLAUDE_IDEA_TAB_ID>" }
```

IDEA plugin 内部：

```kotlin
1. ProjectManager.getOpenProjects() 找到 tab 所在 project
2. WindowManager.getFrame(project).toFront()
3. TerminalToolWindowManager.getInstance(project).toolWindow.show()
4. registry[tabId].select()
```

同意：

```
POST http://127.0.0.1:6790/sendText
{ "tabId": "<CLAUDE_IDEA_TAB_ID>", "text": "1\n" }
```

IDEA plugin 内部调 `JBTerminalWidget.executeCommand("1")`，直接送到 PTY，无焦点依赖。

### VS Code（最小支持）

- 跳转：`open "vscode://file/<cwd>"`（若已开则激活窗口）
- 同意：按钮置灰，UI 提示"VS Code 不支持自动同意，请跳转后手动操作"

### unknown 宿主

- 跳转、同意按钮均置灰
- 仅显示通知，关闭按钮可用

### 能力总览

| 宿主 | 跳转 | 同意 | 实现 |
|------|------|------|------|
| iTerm2 | ✅ 100% | ✅ 100% | AppleScript `write text` |
| IDEA / JetBrains 全家桶 | ✅ 100% | ✅ 100% | IDEA plugin HTTP API |
| VS Code | ⚠️ 仅激活窗口 | ❌ | `open vscode://` |
| unknown | ❌ | ❌ | 仅通知 |

## 6. UI 与交互

### 浮窗形态

- `NSPanel` 子类，启用 `.nonactivatingPanel + .floatingPanel` 风格
- 尺寸：宽 360pt，高度按内容自适应（最大 500pt 后内容滚动）
- 位置：默认屏幕右上、菜单栏下方 12pt（可记忆用户拖动位置）
- 不抢焦点（用户在任何 App 打字、点击都不受影响）
- 半透明 vibrancy 背景（`NSVisualEffectView` material `.popover`）

### 通知行结构

```
[图标] [事件标签]                    [相对时间]
[项目名] [宿主徽标]
[消息描述]
[命令/上下文预览 (monospace, 灰底)]
[同意/关闭] [跳转] [×]
```

- 事件标签：🔐 等待权限 / ✓ 任务完成 / ⚠️ 操作失败
- 宿主徽标：iTerm（黑）/ IDEA（橙）/ VS Code（蓝）/ unknown（灰）
- 背景色：黄色（权限）/ 绿色（完成）/ 红色（失败重试）

### 显示/隐藏规则

| 触发 | 行为 |
|------|------|
| 队列从空变非空 | 浮窗自动弹出（可在设置中关闭） |
| 队列变空 | 浮窗自动收起，菜单栏徽标消失 |
| 顶栏"－"按钮 | 手动收起，菜单栏图标点击重新打开 |
| 同 session_id 收到新事件 | 替换原行 + 边框闪烁 200ms |
| 新事件到达 | 播放 macOS Glass 提示音（可关） |

### 按钮规则

- **同意**：仅 Notification 事件显示；点击 = 路由到 TerminalRouter 发 "1\n"
- **关闭**：仅 Stop 事件显示；点击 = 从队列移除
- **跳转**：所有事件都有；点击不关闭通知（用户主动管理生命周期）
- **×**：所有事件都有，等价于"关闭"

### 菜单栏图标

- 默认 SF Symbol：`bell.badge` 或 `terminal`
- 徽标显示待处理事件数（≥1 时红色圆点）
- 左键：toggle 浮窗
- 右键菜单：偏好设置 / 全部清空 / 关于 / 退出

## 7. 安装与分发

### 用户视角的三步安装

```
1. macOS App
   brew install --cask claude-notifier   (后续提交到 brew-cask)
   首次启动：可选请求 Accessibility 权限（VS Code 等 fallback 场景用）
   自动注册"登录时打开"

2. CC plugin
   /plugin install claude-notifier
   自动写 ~/.claude/settings.json，挂载三个 hook
   提示运行 /notifier-install 做安装自检

3. IDEA plugin
   Preferences → Plugins → 搜 "Claude Notifier" → Install
   重启 IDE（IntelliJ 限制）
   自检：新开终端 tab，echo $CLAUDE_IDEA_TAB_ID 应有值
```

### 端口与运行时发现

| 端口 | 监听方 | 配置 |
|------|--------|------|
| 6789 | macOS App | `~/Library/Application Support/ClaudeNotifier/config.json` |
| 6790 | IDEA Plugin | `~/.config/JetBrains/<IDE>/options/claude-notifier.xml` |

- 启动时检测端口占用，自动 +1 重试至空闲
- App 启动后写入 `~/.config/claude-notifier/runtime.json` 暴露当前端口
- Hook 脚本读取 `runtime.json` 而非硬编码端口
- IDEA plugin 同理通过 `runtime.json` 或服务发现协调

### 版本兼容

- 三组件各自语义化版本
- hook payload 携带 `schema` 字段
- App 收到不兼容 schema → 400 响应；浮窗顶部弹"请升级 plugin"提示

## 8. 项目结构

```
claude-notifier/
├── app/                                 # SwiftUI menubar app
│   ├── ClaudeNotifier.xcodeproj
│   ├── Sources/
│   │   ├── App/ClaudeNotifierApp.swift
│   │   ├── UI/
│   │   │   ├── MenuBarView.swift
│   │   │   ├── FloatingPanelView.swift
│   │   │   ├── NotificationRow.swift
│   │   │   └── PreferencesView.swift
│   │   ├── Core/
│   │   │   ├── NotificationStore.swift
│   │   │   ├── NotificationItem.swift
│   │   │   └── RuntimeConfig.swift
│   │   ├── Services/
│   │   │   ├── HookEndpointServer.swift
│   │   │   ├── TerminalRouter.swift
│   │   │   ├── ITermAdapter.swift
│   │   │   ├── IdeaAdapter.swift
│   │   │   ├── VSCodeAdapter.swift
│   │   │   └── AppleScriptRunner.swift
│   │   └── Resources/{Sounds, Assets.xcassets, Info.plist}
│   └── Tests/
│       ├── NotificationStoreTests.swift
│       ├── TerminalRouterTests.swift
│       └── HookEndpointServerTests.swift
│
├── cc-plugin/                           # Claude Code plugin
│   ├── plugin.json
│   ├── hooks/
│   │   ├── notify-hook.sh
│   │   └── capture-prompt.sh
│   └── commands/
│       ├── notifier-install.md
│       └── notifier-status.md
│
├── idea-plugin/                         # IntelliJ Platform plugin
│   ├── build.gradle.kts
│   ├── src/main/kotlin/io/claudenotifier/idea/
│   │   ├── TerminalTabRegistry.kt
│   │   ├── TerminalEnvCustomizer.kt
│   │   ├── HttpServer.kt
│   │   ├── handlers/{SendTextHandler, FocusTabHandler}.kt
│   │   └── ClaudeNotifierStartupActivity.kt
│   ├── src/main/resources/META-INF/plugin.xml
│   └── src/test/kotlin/
│
├── Brewfile
├── Makefile                             # 一键 build 三件套
├── docs/
│   ├── specs/2026-05-27-claude-notifier-design.md
│   └── manual-test-checklist.md
└── README.md
```

## 9. 错误处理矩阵

| 故障 | 检测 | 处理 |
|------|------|------|
| App 未启动，hook 触发 | curl `--max-time 2` 失败 | 静默 + fallback `terminal-notifier` |
| App 端口冲突 | bind EADDRINUSE | 自增端口至 6799；写入 runtime.json |
| IDEA plugin 未装但宿主是 IDEA | 浮窗操作时 HTTP refused | 行变红 + "未检测到 IDEA plugin"，提供文档链接 |
| `CLAUDE_IDEA_TAB_ID` 失效（tab 已关） | plugin 返回 410 Gone | 退化为激活 IDEA 窗口 + 提示 |
| iTerm session_id 失效 | AppleScript 返回 0 个匹配 | 退化为激活 iTerm + 提示 |
| Accessibility 权限被撤销 | CGEvent 调用失败 | 浮窗顶栏挂红条："键盘事件不可用，点此修复" |
| 同 session 1 秒内重复 hook | NotificationStore 检测到已有 | 替换不堆叠 |
| `last_prompt` 文件不存在 | 静默 | 显示"任务推进中…" |
| hook 脚本任何报错 | bash 退出 0 | CC 不受影响 |

**核心原则**：所有失败路径都不能让 CC 卡住或崩溃。Hook 永远 fail-open。

## 10. 测试策略

### 单元测试

- Swift：`TerminalRouter`（按 host 路由）、`NotificationStore`（dedupe）、`HookEndpointServer`（schema 校验）
- Kotlin：`TerminalTabRegistry`（UUID 注入、tab 关闭清理）、HTTP handlers

### 集成测试

- macOS CI 起 App → POST 样例 payload → 查询 `/api/notifications` 验证
- iTerm 适配：起空 session → `write text "echo X"` → 用 iTerm Python API 读 buffer 验证
- IDEA plugin：`BasePlatformTestCase` 起 headless IDE 跑测试

### 手动 E2E（首次发布前过一遍）

8 个核心路径：

```
{Notification, Stop} × {iTerm, IDEA} × {同意, 跳转} = 8
```

边角 3 项：App 没启动 / IDEA plugin 没装 / session 失效。

清单写入 `docs/manual-test-checklist.md`，每次发版手动跑。

### 性能验收

- Hook 链 < 100ms
- 浮窗冷启动 < 300ms
- 待机内存 < 50MB
- 并发：5 个 CC 会话同时 hook 不丢事件

## 11. 后续可演进项（非本次范围）

- 历史记录视图（看过去一天的所有事件）
- 远程通知转发（手机收到通知，可点确认）
- 自定义事件规则（什么 prompt 关键词触发更醒目的提示音）
- 跨 Mac 同步（多台 Mac 共用一个 Claude 账号场景）
- VS Code plugin（补齐 VS Code 端的精准定位与同意能力）
