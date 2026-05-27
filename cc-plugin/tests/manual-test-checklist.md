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
