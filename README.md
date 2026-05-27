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

## 当前能力（Plan A 完成后）

- ✅ 接收 CC hook（POST :6789/hook）
- ✅ 菜单栏徽标 + 屏幕右上常驻浮窗
- ✅ iTerm 跳转 + 同意（100% 可靠）
- ✅ VS Code 跳转（同意暂不支持）
- ⏳ IDEA：等 Plan C（当前 stub，会提示"未检测到 IDEA plugin"）
- ⏳ CC plugin：等 Plan B（当前需手动改 ~/.claude/settings.json）

## 设计文档

详见 `docs/specs/2026-05-27-claude-notifier-design.md`
