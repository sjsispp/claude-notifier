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
