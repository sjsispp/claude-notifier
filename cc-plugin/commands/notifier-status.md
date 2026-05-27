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
