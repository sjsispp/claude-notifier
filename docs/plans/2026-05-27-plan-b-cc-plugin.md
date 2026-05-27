# Plan B: CC Plugin 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 `claude-notifier` Claude Code plugin —— 3 个 hook 脚本（UserPromptSubmit/Notification/Stop）+ 2 个 slash command（install/status）。Hook 接收 CC 事件后 POST 到 Plan A 的 macOS App（http://127.0.0.1:6789/hook）。

**Architecture:**
- `claude-notifier` plugin 通过 plugin.json 注册 3 个 hook
- Hook 脚本用 bash + curl + jq，端口动态读取 `~/.config/claude-notifier/runtime.json`
- App 不可达时 fail-open（exit 0），并可选 fallback 到 `terminal-notifier`
- 2 个 slash command 提供安装自检和运行状态查询

**Tech Stack:** bash 4+, jq（已是 macOS 标配），curl，可选 `terminal-notifier`（brew 装）

**对应 spec:** `docs/specs/2026-05-27-claude-notifier-design.md` §4 (Hook 协议) + §7 (安装)

**前置依赖:**
- Plan A 已完成（App 监听 :6789）
- `~/.config/claude-notifier/runtime.json` 存在（App 启动时自动写）

---

## File Structure

```
cc-plugin/
├── plugin.json
├── hooks/
│   ├── capture-prompt.sh        # UserPromptSubmit hook
│   └── notify-hook.sh           # Notification + Stop hook（事件类型在脚本内分支）
├── commands/
│   ├── notifier-install.md      # /notifier-install
│   └── notifier-status.md       # /notifier-status
├── tests/
│   └── manual-test-checklist.md
└── README.md
```

---

### Task 1: Plugin 脚手架 + plugin.json

**Files:**
- Create: `cc-plugin/plugin.json`
- Create: `cc-plugin/README.md`（最简）

- [ ] **Step 1: 创建目录与 plugin.json**

```bash
mkdir -p /Users/wudazhan/workplace/project/claude-notifier/cc-plugin/{hooks,commands,tests}
```

`cc-plugin/plugin.json`:

```json
{
  "name": "claude-notifier",
  "version": "0.1.0",
  "description": "把 Claude Code 的 Notification/Stop 事件推送到 ClaudeNotifier.app 浮窗",
  "author": "wudazhan",
  "hooks": {
    "UserPromptSubmit": [
      {
        "matcher": "",
        "hooks": [
          {
            "type": "command",
            "command": "bash ${CLAUDE_PLUGIN_ROOT}/hooks/capture-prompt.sh",
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
            "command": "bash ${CLAUDE_PLUGIN_ROOT}/hooks/notify-hook.sh",
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
            "command": "bash ${CLAUDE_PLUGIN_ROOT}/hooks/notify-hook.sh",
            "timeout": 5000
          }
        ]
      }
    ]
  },
  "commands": [
    "commands/notifier-install.md",
    "commands/notifier-status.md"
  ]
}
```

注：`${CLAUDE_PLUGIN_ROOT}` 是 Claude Code 在执行 plugin hook 时注入的环境变量，指向 plugin 安装根目录。

- [ ] **Step 2: 创建最简 README**

`cc-plugin/README.md`:

```markdown
# claude-notifier (Claude Code plugin)

把 Claude Code 的 Notification/Stop 事件推送到 ClaudeNotifier.app 浮窗。

## 安装

ClaudeNotifier.app 必须先安装并运行（见 项目根 README）。然后：

\`\`\`
/plugin install claude-notifier
\`\`\`

或手动复制到 `~/.claude/plugins/claude-notifier/`。

## 自检

\`\`\`
/notifier-install
\`\`\`
```

- [ ] **Step 3: Commit**

```bash
cd /Users/wudazhan/workplace/project/claude-notifier
git add cc-plugin/plugin.json cc-plugin/README.md
git -c commit.gpgsign=false commit -m "scaffold(cc-plugin): plugin.json + README"
```

---

### Task 2: capture-prompt.sh（UserPromptSubmit）

**Files:**
- Create: `cc-plugin/hooks/capture-prompt.sh`

用户每次发 prompt 时把它缓存到 `/tmp/claude-prompt-${session_id}`，供 notify-hook.sh 读取。

- [ ] **Step 1: 实现脚本**

`cc-plugin/hooks/capture-prompt.sh`:

```bash
#!/bin/bash
# UserPromptSubmit hook: 缓存最后一条 prompt 到 /tmp
# CC 通过 stdin 传 JSON {session_id, hook_event_name, prompt, ...}
# 失败永不阻塞 CC（exit 0）

set +e
input=$(cat)
session_id=$(echo "$input" | jq -r '.session_id // empty')
prompt=$(echo "$input" | jq -r '.prompt // empty' | head -c 200)

[ -z "$session_id" ] && exit 0

echo "$prompt" > "/tmp/claude-prompt-${session_id}"
exit 0
```

加可执行权限：

```bash
chmod +x /Users/wudazhan/workplace/project/claude-notifier/cc-plugin/hooks/capture-prompt.sh
```

- [ ] **Step 2: 手动验证**

```bash
echo '{"session_id":"test-123","hook_event_name":"UserPromptSubmit","prompt":"hello world"}' \
  | bash /Users/wudazhan/workplace/project/claude-notifier/cc-plugin/hooks/capture-prompt.sh

cat /tmp/claude-prompt-test-123
# Expected: hello world

rm /tmp/claude-prompt-test-123
```

- [ ] **Step 3: 验证 fail-open**

```bash
echo 'not json at all' \
  | bash /Users/wudazhan/workplace/project/claude-notifier/cc-plugin/hooks/capture-prompt.sh
echo "exit code: $?"
# Expected: exit code: 0（即使输入是垃圾也要 exit 0）
```

- [ ] **Step 4: Commit**

```bash
cd /Users/wudazhan/workplace/project/claude-notifier
git add cc-plugin/hooks/capture-prompt.sh
git -c commit.gpgsign=false commit -m "feat(cc-plugin): capture-prompt.sh caches latest prompt"
```

---

### Task 3: notify-hook.sh（Notification + Stop）

**Files:**
- Create: `cc-plugin/hooks/notify-hook.sh`

读 CC stdin，构造 HookPayload JSON，POST 到 App。App 不可达时 fallback。

- [ ] **Step 1: 实现脚本**

`cc-plugin/hooks/notify-hook.sh`:

```bash
#!/bin/bash
# Notification + Stop hook: 构造 HookPayload 并 POST 到 ClaudeNotifier.app
# 失败永不阻塞 CC（exit 0）

set +e

input=$(cat)
event_raw=$(echo "$input" | jq -r '.hook_event_name // empty')
session_id=$(echo "$input" | jq -r '.session_id // empty')
cwd=$(echo "$input" | jq -r '.cwd // empty')
message=$(echo "$input" | jq -r '.message // empty')

[ -z "$session_id" ] && exit 0

# 映射事件类型
case "$event_raw" in
  Notification) event="notification" ;;
  Stop)         event="stop" ;;
  *)            exit 0 ;;
esac

# 项目名 = cwd 末段
project_name=$(basename "${cwd:-/unknown}")

# 最后一条 prompt（capture-prompt.sh 缓存的）
last_prompt=""
prompt_file="/tmp/claude-prompt-${session_id}"
if [ -f "$prompt_file" ]; then
  last_prompt=$(cat "$prompt_file" | head -c 200)
fi

# 终端宿主识别
host="unknown"
iterm_session_id=""
idea_tab_id=""
if [ "$TERM_PROGRAM" = "iTerm.app" ]; then
  host="iterm"
  iterm_session_id="${ITERM_SESSION_ID:-}"
elif [ -n "$CLAUDE_IDEA_TAB_ID" ] || [ "$TERMINAL_EMULATOR" = "JetBrains-JediTerm" ]; then
  host="idea"
  idea_tab_id="${CLAUDE_IDEA_TAB_ID:-}"
elif [ "$TERM_PROGRAM" = "vscode" ]; then
  host="vscode"
fi

# 解析 App 端口（runtime.json）
runtime_file="$HOME/.config/claude-notifier/runtime.json"
port=6789
if [ -f "$runtime_file" ]; then
  port=$(jq -r '.port // 6789' "$runtime_file" 2>/dev/null)
fi

ts=$(date +%s)

# Notification 事件尝试提取 command_preview（CC 在 message 里通常包含具体动作；这里保守起见暂时留空）
command_preview=""

# 构造 payload
payload=$(jq -n \
  --arg event "$event" \
  --arg session_id "$session_id" \
  --arg cwd "${cwd:-/unknown}" \
  --arg project_name "$project_name" \
  --arg message "$message" \
  --arg command_preview "$command_preview" \
  --arg last_prompt "$last_prompt" \
  --arg host "$host" \
  --arg iterm_session_id "$iterm_session_id" \
  --arg idea_tab_id "$idea_tab_id" \
  --arg tty "$(tty 2>/dev/null || echo '')" \
  --argjson ppid "${PPID:-0}" \
  --argjson ts "$ts" \
  '{
    schema: 1,
    event: $event,
    session_id: $session_id,
    cwd: $cwd,
    project_name: $project_name,
    message: (if $message == "" then null else $message end),
    command_preview: (if $command_preview == "" then null else $command_preview end),
    last_prompt: (if $last_prompt == "" then null else $last_prompt end),
    terminal: {
      host: $host,
      iterm_session_id: (if $iterm_session_id == "" then null else $iterm_session_id end),
      idea_tab_id: (if $idea_tab_id == "" then null else $idea_tab_id end),
      tty: (if $tty == "" then null else $tty end),
      ppid: $ppid
    },
    ts: $ts
  }')

# POST 到 App，max-time 2 秒
http_code=$(curl -sS -o /dev/null -w "%{http_code}" \
  --max-time 2 \
  -X POST "http://127.0.0.1:${port}/hook" \
  -H "Content-Type: application/json" \
  -d "$payload" 2>/dev/null)

# Fallback：App 不可达 + 有 terminal-notifier → 用原生通知
if [ "$http_code" != "200" ] && command -v terminal-notifier >/dev/null 2>&1; then
  title="Claude $event"
  body="${message:-$last_prompt}"
  [ -z "$body" ] && body="task ${event}"
  terminal-notifier -title "$title" -message "$body" -activate "com.googlecode.iterm2" 2>/dev/null
fi

exit 0
```

可执行：

```bash
chmod +x /Users/wudazhan/workplace/project/claude-notifier/cc-plugin/hooks/notify-hook.sh
```

- [ ] **Step 2: 启动 App 后端到端验证**

```bash
cd /Users/wudazhan/workplace/project/claude-notifier/app
swift run ClaudeNotifier > /tmp/cn.log 2>&1 &
APP_PID=$!
sleep 2

# 模拟 CC 的 Notification hook stdin
echo '{
  "hook_event_name":"Notification","session_id":"t1","cwd":"/Users/wudazhan/workplace/foo",
  "message":"Claude needs permission to use Bash"
}' | TERM_PROGRAM=iTerm.app ITERM_SESSION_ID=w0t0p0:FAKE \
  bash /Users/wudazhan/workplace/project/claude-notifier/cc-plugin/hooks/notify-hook.sh
echo "exit: $?"

# 验证：菜单栏徽标应 +1（手动观察，或下一步 status 命令查）
sleep 1
kill $APP_PID 2>/dev/null
wait 2>/dev/null
```

- [ ] **Step 3: 验证 fail-open（App 不可达不报错）**

```bash
# 确认 App 已关
lsof -nP -iTCP:6789 -sTCP:LISTEN 2>/dev/null
# 应该没输出

echo '{"hook_event_name":"Notification","session_id":"t2","cwd":"/foo","message":"x"}' \
  | bash /Users/wudazhan/workplace/project/claude-notifier/cc-plugin/hooks/notify-hook.sh
echo "exit: $?"
# Expected: exit: 0
```

- [ ] **Step 4: Commit**

```bash
cd /Users/wudazhan/workplace/project/claude-notifier
git add cc-plugin/hooks/notify-hook.sh
git -c commit.gpgsign=false commit -m "feat(cc-plugin): notify-hook.sh posts events to App, fail-open"
```

---

### Task 4: notifier-install slash command

**Files:**
- Create: `cc-plugin/commands/notifier-install.md`

Slash command 通过对话 + 检测脚本实现"安装自检"。

- [ ] **Step 1: 实现 command**

`cc-plugin/commands/notifier-install.md`:

```markdown
---
description: 检测 ClaudeNotifier.app 与 IDEA plugin 安装状态，给出修复建议
---

请帮我检测 Claude Notifier 各组件的安装状态。按以下步骤：

1. **检测 macOS App 是否运行**：
   - 检查 `~/.config/claude-notifier/runtime.json` 是否存在
   - 如果存在，读取 port 字段，然后 `curl -sS --max-time 2 http://127.0.0.1:<port>/hook` —— 注意要 POST 一个非法 payload，应该收到 400（说明 server 活着）
   - 如果 runtime.json 不存在或 curl 连不上，告诉用户："App 未运行，请打开 /Applications/ClaudeNotifier.app（或 `cd <repo>/app && swift run ClaudeNotifier`）"
   - 通过则报告："✅ App 运行中，端口 <port>"

2. **检测 IDEA plugin（如果当前在 IDEA 终端运行）**：
   - 检查 `$CLAUDE_IDEA_TAB_ID` 环境变量是否存在
   - 如果不存在但 `$TERMINAL_EMULATOR = JetBrains-JediTerm`，提示："你在 IDEA 终端里，但未检测到 IDEA plugin。请在 IDEA 的 Preferences → Plugins 安装 Claude Notifier，然后重启 IDE。"
   - 如果存在，curl 一下 IDEA plugin 的健康端点 `http://127.0.0.1:6790/healthz`，按结果报告

3. **检测 hook 是否注册**：
   - 检查 `~/.claude/settings.json` 中是否包含本 plugin 的 hook 配置
   - 通常 `/plugin install claude-notifier` 已经自动处理；如果用户手动复制，需要手动加 hook
   - 如果未注册，给出加入示例（指向 plugin.json 的 hooks 字段）

4. **总结**：把三项检测结果汇总成一个表格输出给用户。
```

- [ ] **Step 2: 验证 markdown 格式**

```bash
head -5 /Users/wudazhan/workplace/project/claude-notifier/cc-plugin/commands/notifier-install.md
# 应该看到 YAML frontmatter
```

- [ ] **Step 3: Commit**

```bash
cd /Users/wudazhan/workplace/project/claude-notifier
git add cc-plugin/commands/notifier-install.md
git -c commit.gpgsign=false commit -m "feat(cc-plugin): /notifier-install slash command"
```

---

### Task 5: notifier-status slash command

**Files:**
- Create: `cc-plugin/commands/notifier-status.md`

- [ ] **Step 1: 实现 command**

`cc-plugin/commands/notifier-status.md`:

```markdown
---
description: 查看 ClaudeNotifier 当前运行状态与队列
---

请告诉我 Claude Notifier 的当前状态：

1. **App 进程**：
   - 读取 `~/.config/claude-notifier/runtime.json`，输出 host/port/pid/startedAt
   - 用 `kill -0 <pid>` 验证进程是否存活
   - 输出 uptime（now - startedAt）

2. **端口监听**：
   - `lsof -nP -iTCP:<port> -sTCP:LISTEN` 验证

3. **最近一次 hook 触发**：
   - 列出 `/tmp/claude-prompt-*` 文件，按 mtime 排序，最近 5 个
   - 每个显示：session_id（从文件名提取）、mtime、内容预览

4. **如果 App 没运行**：
   - 提示运行 `/notifier-install` 排查

格式：表格 + 简短说明。
```

- [ ] **Step 2: Commit**

```bash
cd /Users/wudazhan/workplace/project/claude-notifier
git add cc-plugin/commands/notifier-status.md
git -c commit.gpgsign=false commit -m "feat(cc-plugin): /notifier-status slash command"
```

---

### Task 6: 手动 E2E 集成测试清单

**Files:**
- Create: `cc-plugin/tests/manual-test-checklist.md`

- [ ] **Step 1: 写清单**

`cc-plugin/tests/manual-test-checklist.md`:

```markdown
# CC Plugin 手动测试清单

## 前置

- [ ] ClaudeNotifier.app 已运行（菜单栏出现 🔔，`runtime.json` 存在）
- [ ] 本 plugin 已安装（`~/.claude/plugins/claude-notifier/` 存在 或 已用 /plugin install）

## 单元行为

- [ ] capture-prompt.sh：注入 `{session_id:"a", prompt:"x"}` → `/tmp/claude-prompt-a` 内容为 "x"
- [ ] notify-hook.sh + App 运行：注入 Notification 事件 → 浮窗弹出新条目
- [ ] notify-hook.sh + App 关闭：注入事件 → exit 0 + 触发 terminal-notifier（如装）
- [ ] 端口动态：手动改 runtime.json 的 port，再触发 hook，POST 走新端口

## 端到端（真 CC）

- [ ] 在 CC 里随便发个 prompt → 浮窗不弹（UserPromptSubmit 只缓存不通知）
- [ ] CC 执行 Bash 命令需要权限确认 → Notification 触发 → 浮窗弹出黄色"等待权限"
- [ ] 点浮窗"同意" → iTerm 那个 session 自动按下 "1"，权限通过
- [ ] CC 完成单轮回复（Stop 事件） → 浮窗弹出绿色"任务完成"，包含最后一条 prompt
- [ ] 同 session 多次 hook → 浮窗只保留最新一条

## Slash commands

- [ ] /notifier-install 在 App 运行时报 "✅ 端口 6789"
- [ ] /notifier-install 在 App 关闭时报 "App 未运行"
- [ ] /notifier-status 输出 pid/uptime/最近 prompts
```

- [ ] **Step 2: Commit**

```bash
cd /Users/wudazhan/workplace/project/claude-notifier
git add cc-plugin/tests/manual-test-checklist.md
git -c commit.gpgsign=false commit -m "docs(cc-plugin): manual E2E test checklist"
```

---

### Task 7: README + 真实 CC 安装路径

**Files:**
- Modify: `cc-plugin/README.md`（完善）
- Modify: 项目根 `README.md`（更新 Plan B 状态）

- [ ] **Step 1: 完善 cc-plugin/README.md**

```markdown
# claude-notifier (Claude Code plugin)

把 Claude Code 的 Notification/Stop 事件推送到 ClaudeNotifier.app 浮窗。

## 依赖

- ClaudeNotifier.app 已运行（监听 :6789，端口自适配）
- macOS 系统命令：bash、curl、jq、basename、date
- 可选：`terminal-notifier`（`brew install terminal-notifier`）—— App 不可达时的兜底通知

## 安装方式

### 方式 A：通过 Claude Code marketplace

```
/plugin install claude-notifier
```

会自动把 plugin.json 中的 hooks 段合并进 `~/.claude/settings.json`。

### 方式 B：手动安装（开发者本地）

```bash
ln -s /Users/wudazhan/workplace/project/claude-notifier/cc-plugin \
      ~/.claude/plugins/claude-notifier
```

然后手动把 plugin.json 中的 hooks 复制到 `~/.claude/settings.json` 的 hooks 字段。

## 工作机制

1. 用户在 CC 里发 prompt → UserPromptSubmit hook 触发 → `capture-prompt.sh` 把 prompt 缓存到 `/tmp/claude-prompt-${session_id}`
2. CC 弹权限确认 / 单轮结束 → Notification/Stop hook 触发 → `notify-hook.sh` 构造 JSON POST 到 `http://127.0.0.1:6789/hook`
3. App 收到事件 → 浮窗显示，包含项目名、最后 prompt、命令预览等
4. 用户点"同意" → App 通过 AppleScript 让 iTerm 那个 session 输入 "1"
5. 用户点"跳转" → App 切换到那个 iTerm tab

## 调试

- 查看 hook 执行日志：暂未持久化，可临时改脚本在 trap 里加 `echo "$@" >> /tmp/notify-hook.log`
- 查看 App 收到的请求：`tail -f /tmp/cn.log` 当 App 用 `swift run` 启动时
- 直接发请求：见 manual-test-checklist.md

## 限制

- IDEA 终端：当前 App 端 IdeaAdapter 仍是 stub（Plan C 解决）；点同意会显示"未检测到 IDEA plugin"
- VS Code：同意按钮置灰，只能跳转
- 多 CC 实例并发：通过 session_id 去重，逻辑健壮
```

- [ ] **Step 2: 更新项目根 README.md**

把根 `README.md` 中 "当前能力" 段落改为：

```markdown
## 当前能力

- ✅ Plan A：菜单栏 App + iTerm 完整闭环
- ✅ Plan B：CC plugin（hook 脚本 + slash command）—— CC 事件自动推送到 App
- ⏳ Plan C：IDEA plugin —— IDEA 终端的同意/跳转 100% 可靠（当前 stub）

## 三件套安装

1. **macOS App**：`cd app && swift run ClaudeNotifier`（或后续打包成 .app）
2. **CC plugin**：`/plugin install claude-notifier` 或手动 symlink
3. **IDEA plugin**：等 Plan C

详见：
- App 开发：`app/README.md`（如有）或 `docs/manual-test-checklist.md`
- CC plugin：`cc-plugin/README.md`
```

- [ ] **Step 3: Commit**

```bash
cd /Users/wudazhan/workplace/project/claude-notifier
git add cc-plugin/README.md README.md
git -c commit.gpgsign=false commit -m "docs: Plan B README + status update"
```

---

## Self-Review 备忘

### Spec 覆盖
- §4 Hook 协议 → T2 (UserPromptSubmit) + T3 (Notification/Stop POST)
- §7 安装链路 → T1 (plugin.json) + T7 (README install)
- §9 错误矩阵：fail-open + fallback → T3 (curl --max-time + terminal-notifier)
- §10 测试：手动 E2E → T6

### 范围外
- bats 单元测试：bash 脚本逻辑可单独测，但当前 plan 走"手动 + curl 验证"
- 真实 `/plugin install` 发布到 marketplace：本地 symlink 已够用
- App 那边的 `/api/notifications` GET：未实现，notifier-status 改为读 runtime.json + /tmp prompt 文件

### Type 一致性
- Hook payload schema 与 App 端 HookPayload.swift §4 一致：schema=1，事件 enum 为 notification/stop
- terminal.host 取值与 App 端 HookPayload.Host enum 一致：iterm/idea/vscode/unknown
