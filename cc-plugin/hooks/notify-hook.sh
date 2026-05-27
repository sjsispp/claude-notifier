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
