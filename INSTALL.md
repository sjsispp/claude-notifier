# Claude Notifier 安装指南

> 把 Claude Code 的权限确认 / 任务完成事件，实时推送到 macOS 屏幕右上角的常驻浮窗。点"同意"自动按 `1`，点"跳转"切到对应终端 tab。

**架构**：3 个组件，按顺序装。每装完一个都能独立验证，全装完才能享受完整体验。

```
┌──────────────────┐  POST  ┌──────────────────────┐
│ CC plugin (bash) │ ─────► │ ClaudeNotifier.app   │ ◄────┐
└──────────────────┘        │ (macOS 菜单栏，:6789) │      │
                            └──────────────────────┘      │ POST :6790
                                                          │
┌──────────────────────────────────────────────────────┐  │
│ IDEA plugin (Kotlin) — 让 IDEA 终端能精准被定位         │ ─┘
└──────────────────────────────────────────────────────┘
```

---

## 0. 系统要求

| 项 | 要求 |
|----|------|
| 操作系统 | macOS 13 (Ventura) 或更高 |
| Claude Code | 已安装（用过 `/plugin install` 或 `~/.claude/settings.json`） |
| 终端 | iTerm2 推荐；IDEA 终端、VS Code 终端都支持 |
| 命令行工具 | `bash`, `curl`, `jq`（macOS 一般自带；缺 jq 就 `brew install jq`） |

**强烈建议** 但非必须：
- Xcode（不是 Command Line Tools）—— 只有你想自己改代码、跑测试才需要
- IntelliJ IDEA 2024.2+（或 GoLand / PyCharm / RustRover / WebStorm 任一）—— 想用 IDEA 终端的同意/跳转才需要

---

## 1. 安装 macOS App

App 是核心 —— **它必须运行，浮窗才会出现**。

### 方式 A：从源码运行（推荐，开发期）

需要：Xcode 完整安装（不是 CLT；`xcode-select -p` 应输出 `/Applications/Xcode.app/...`）。

```bash
# 1. 拿到源码
git clone <repo-url> ~/claude-notifier
cd ~/claude-notifier/app

# 2. 跑起来
swift run ClaudeNotifier
```

成功标志：
- 终端输出 `[ClaudeNotifier] server listening on :6789`
- 屏幕右上菜单栏出现一个 🔔 图标
- `~/.config/claude-notifier/runtime.json` 文件已生成

按 `Ctrl+C` 退出。

### 方式 B：后台常驻

终端关了 App 也跟着关。要让 App 一直在后台跑：

```bash
cd ~/claude-notifier/app
swift build -c release
# 后台启动：
nohup .build/release/ClaudeNotifier > /tmp/claude-notifier.log 2>&1 &
disown
```

或者写个 LaunchAgent（开机自启）：

```bash
mkdir -p ~/Library/LaunchAgents
cat > ~/Library/LaunchAgents/io.claudenotifier.app.plist <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>io.claudenotifier.app</string>
    <key>ProgramArguments</key>
    <array>
        <string>$HOME/claude-notifier/app/.build/release/ClaudeNotifier</string>
    </array>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
    <key>StandardOutPath</key>
    <string>/tmp/claude-notifier.log</string>
    <key>StandardErrorPath</key>
    <string>/tmp/claude-notifier.err.log</string>
</dict>
</plist>
EOF
launchctl load ~/Library/LaunchAgents/io.claudenotifier.app.plist
```

以后开机自动启动，菜单栏永远有 🔔。

### 验证 App 装好了

```bash
# 1. 进程在跑
lsof -nP -iTCP:6789 -sTCP:LISTEN
# 应看到 ClaudeN... 监听 6789（如果端口被占了会自动 +1，看 runtime.json）

# 2. runtime.json 存在
cat ~/.config/claude-notifier/runtime.json
# {"port":6789,"host":"127.0.0.1","pid":..,"startedAt":..}

# 3. HTTP 接口工作
curl -X POST http://127.0.0.1:6789/hook -H 'Content-Type: application/json' -d '{
  "schema":1,"event":"notification","session_id":"test","cwd":"/tmp",
  "project_name":"test","message":"装好啦","terminal":{"host":"iterm"},"ts":'$(date +%s)'}'
# 期望：{"accepted":true,"event_id":"..."}
# 同时：菜单栏 🔔 旁出现 "1"，浮窗自动弹出右上角，听到 Glass 提示音
```

看到浮窗 = App 安装成功 ✅

---

## 2. 安装 CC Plugin

CC plugin 让你**在 Claude Code 里跑命令时自动推送事件给 App**。不装的话，App 是空闲状态（除非别的进程往 :6789 推）。

### Step 1：把 plugin 链接到 Claude Code 插件目录

```bash
mkdir -p ~/.claude/plugins
ln -sf ~/claude-notifier/cc-plugin ~/.claude/plugins/claude-notifier
# 或者 cp -r 也行，symlink 方便后续 git pull 拿更新
```

### Step 2：注册 hook 到 settings.json

`~/.claude/settings.json` 加 `hooks` 段（如果已有 hooks 段就合并进去，**别覆盖**）：

```json
{
  "hooks": {
    "UserPromptSubmit": [
      {
        "matcher": "",
        "hooks": [
          {
            "type": "command",
            "command": "bash ${HOME}/.claude/plugins/claude-notifier/hooks/capture-prompt.sh",
            "timeout": 5000
          }
        ]
      }
    ],
    "Notification": [
      {
        "matcher": "",
        "hooks": [
          {
            "type": "command",
            "command": "bash ${HOME}/.claude/plugins/claude-notifier/hooks/notify-hook.sh",
            "timeout": 5000
          }
        ]
      }
    ],
    "Stop": [
      {
        "matcher": "",
        "hooks": [
          {
            "type": "command",
            "command": "bash ${HOME}/.claude/plugins/claude-notifier/hooks/notify-hook.sh",
            "timeout": 5000
          }
        ]
      }
    ]
  }
}
```

⚠️ 已经有一个老的 `notify-on-stop.sh` 在 hook 上？请先把它注释或删掉，避免双重通知。

### Step 3：可选 —— 装 terminal-notifier（fallback 兜底）

```bash
brew install terminal-notifier
```

不装也能用，只是 App 没启动时不会有任何提示（hook 会静默 fail-open）。

### 验证 CC plugin 装好了

```bash
# 1. 手动调一次 hook 看是不是能 POST 到 App
echo '{
  "hook_event_name":"Notification","session_id":"manual-test",
  "cwd":"'$PWD'","message":"测试 hook"
}' | TERM_PROGRAM=iTerm.app ITERM_SESSION_ID=fake \
  bash ~/.claude/plugins/claude-notifier/hooks/notify-hook.sh
echo "exit: $?"
# 期望：exit: 0；浮窗多一条 "manual-test" 黄色权限提醒

# 2. 真实场景：在 CC 里跑一条需要权限的命令
# 比如 claude code 里说"运行 ls /"，CC 会弹权限确认
# → 这时浮窗应该自动弹出
```

看到 CC 真实触发浮窗 = plugin 装成功 ✅

---

## 3. 安装 IDEA Plugin（可选）

**只有你在 IDEA / GoLand / PyCharm 等 JetBrains IDE 里跑 Claude Code，才需要这一步。** 不用 IDEA 终端的话，跳过即可（浮窗 IDEA 行点同意会显示"未检测到 IDEA plugin"，仅此而已）。

### Step 1：构建 plugin zip

预构建产物已在仓库里：

```bash
ls ~/claude-notifier/idea-plugin/build/distributions/
# claude-notifier-idea-0.1.0.zip
```

如果文件不存在（首次 clone），重新构建：

```bash
cd ~/claude-notifier/idea-plugin
# 需要 JDK 17+；如果系统没有，可以用 IntelliJ 自带的：
export JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"
./gradlew buildPlugin
# 产物：build/distributions/claude-notifier-idea-0.1.0.zip
```

### Step 2：装到 IDE

1. 打开 IntelliJ IDEA（或任意 JetBrains IDE）
2. `⌘,` 打开 Preferences → **Plugins**
3. 点齿轮 ⚙️ → **Install Plugin from Disk...**
4. 选 `~/claude-notifier/idea-plugin/build/distributions/claude-notifier-idea-0.1.0.zip`
5. 点提示的 **Restart IDE** 重启

### Step 3：验证

重启 IDE 后：

```bash
# 在 IDE 里新开一个终端 tab（Option+F12 或 View → Tool Windows → Terminal）
echo $CLAUDE_IDEA_TAB_ID
# 应输出一个 UUID，类似：a3f2e1c0-...
```

输出 UUID = plugin 装成功 ✅。**注意：plugin 装好之前打开的终端 tab 不会有这个变量**，要新开一个。

进一步验证（在 IDE 终端里跑）：

```bash
# 健康检查
curl http://127.0.0.1:6790/healthz
# {"ok":true,"plugin":"claude-notifier-idea","version":"0.1.0"}

# 模拟一次 send：把 "echo HI" 送进当前 tab
curl -X POST http://127.0.0.1:6790/sendText \
  -H 'Content-Type: application/json' \
  -d "{\"tabId\":\"$CLAUDE_IDEA_TAB_ID\",\"text\":\"echo HI\"}"
# 该 tab 自动出现 echo HI 并执行
```

---

## 4. 完整 E2E 自检

三件套都装完后，跑一次真实场景：

1. 在 iTerm（或 IDEA 终端）启动 `claude code`
2. 让 CC 跑一个需要权限的命令，比如：
   ```
   帮我执行 npm test
   ```
3. CC 应该弹"是否允许使用 Bash?"
4. **同时** —— ClaudeNotifier 浮窗自动弹出，含项目名、命令预览、同意/跳转按钮
5. 点"**同意**" → 终端那边的 CC 自动收到 `1` + 回车，命令开始执行，浮窗那行消失
6. 单轮跑完后 CC 会触发 Stop hook → 浮窗多一条绿色"任务完成"
7. 点"**跳转**" → 切回那个终端 tab

---

## 5. 卸载

```bash
# 1. 停 App
lsof -nP -iTCP:6789 -sTCP:LISTEN | awk 'NR>1 {print $2}' | xargs kill 2>/dev/null

# 2. 取消开机自启（如果装了）
launchctl unload ~/Library/LaunchAgents/io.claudenotifier.app.plist 2>/dev/null
rm ~/Library/LaunchAgents/io.claudenotifier.app.plist 2>/dev/null

# 3. 移除 CC plugin
rm ~/.claude/plugins/claude-notifier
# 然后从 ~/.claude/settings.json 删掉 claude-notifier 那几段 hook

# 4. IDE plugin：Preferences → Plugins → Claude Notifier → ⚙️ → Uninstall → 重启 IDE

# 5. 清理运行时文件
rm -rf ~/.config/claude-notifier
rm /tmp/claude-prompt-* 2>/dev/null
```

---

## 6. 排错

### 浮窗不出现

按顺序排查：

1. **App 没启动？** `lsof -nP -iTCP:6789 -sTCP:LISTEN` 应该有输出
2. **端口换了？** 看 `cat ~/.config/claude-notifier/runtime.json` 的 `port` 字段
3. **Hook 没生效？** `bash -x ~/.claude/plugins/claude-notifier/hooks/notify-hook.sh < /dev/null` 看哪一步出错
4. **CC 没读到 hook？** `cat ~/.claude/settings.json | jq .hooks` 验证 hook 确实注册了
5. **手动 curl 能成功但 CC 不行？** 看 CC 的 hook 调用日志（CC 通常会把 hook 错误打到屏幕上）

### iTerm "跳转" 失败

- 检查 `ITERM_SESSION_ID` 是不是真的传过来了：
  ```bash
  echo $ITERM_SESSION_ID  # 应该非空
  ```
- iTerm 版本太老？AppleScript 接口需要 iTerm2 3.0+

### IDEA "同意" 失败

- `$CLAUDE_IDEA_TAB_ID` 必须非空（plugin 装好且**重新开过 tab**）
- `curl http://127.0.0.1:6790/healthz` 应该返回 200
- 看 IDE 日志：`Help → Show Log in Finder`，搜 `ClaudeNotifier`

### 同意按钮按了没反应（iTerm）

最常见：传给 App 的 `iterm_session_id` 和实际 session 不匹配。手动验证：

```bash
# 在那个 iTerm tab 里：
osascript -e 'tell application "iTerm" to id of current session of current tab of current window'
# 拿到的 id 应该和 hook 抓到的一致
```

如果不一致，是 hook 脚本 `ITERM_SESSION_ID` 环境变量没传到位 —— 重启 iTerm tab 一般能修复。

### 多个 CC 同时跑会乱吗

不会。每个 CC 会话有独立 `session_id`，App 按 session 去重，浮窗每条独立显示。

### App 突然不响应

```bash
# 杀掉重启
lsof -nP -iTCP:6789 -sTCP:LISTEN | awk 'NR>1 {print $2}' | xargs kill -9
cd ~/claude-notifier/app && swift run ClaudeNotifier &
```

---

## 7. 进一步

- **手动测试清单**：`docs/manual-test-checklist.md`、`cc-plugin/tests/manual-test-checklist.md`、`idea-plugin/tests/manual-test-checklist.md`
- **设计文档**：`docs/specs/2026-05-27-claude-notifier-design.md`
- **实施备忘**：`docs/specs/2026-05-27-claude-notifier-implementation-notes.md`（包含 HTTP API、端口策略、Adapter 矩阵等 ground truth）

有问题去仓库 issue 区，或者直接在群里 @ 我。
