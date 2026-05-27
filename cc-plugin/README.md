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
