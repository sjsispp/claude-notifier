# Claude Notifier

CC（Claude Code）通知与快速操作助手：菜单栏 App + CC plugin + IDEA plugin 三件套。

## 项目结构

- `app/` — macOS 菜单栏 App（SwiftUI + SPM）
- `cc-plugin/` — Claude Code plugin（待 Plan B）
- `idea-plugin/` — IntelliJ Platform plugin（待 Plan C）
- `docs/specs/` — 设计文档
- `docs/plans/` — 实施计划

## App 开发

```bash
cd app
swift build       # 编译
swift test        # 跑测试
swift run ClaudeNotifier  # 启动（菜单栏出现 🔔）
```

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

## 设计文档

详见 `docs/specs/2026-05-27-claude-notifier-design.md`
