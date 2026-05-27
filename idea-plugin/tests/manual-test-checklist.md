# IDEA Plugin 手动测试清单

## 前置

- [ ] JDK 17+ 在 PATH（或导出 IDEA JBR 路径到 `JAVA_HOME`）
- [ ] ClaudeNotifier.app 在跑（菜单栏 🔔）
- [ ] CC plugin（Plan B）已装并触发过事件成功
- [ ] 本 plugin 已 buildPlugin 通过

## Sandbox 启动验证

- [ ] `./gradlew runIde` 起的 sandbox IDE 启动正常
- [ ] sandbox 里打开任一 project 后，控制台日志（Help → Show Log）含
      `[ClaudeNotifier] HTTP server listening on :6790`
- [ ] sandbox 里新开终端 tab，`echo $CLAUDE_IDEA_TAB_ID` 输出非空 UUID

## HTTP 端点

- [ ] `curl http://127.0.0.1:6790/healthz` → 200 `{"ok":true,...}`
- [ ] `curl -X POST http://127.0.0.1:6790/sendText \
        -H 'Content-Type: application/json' \
        -d '{"tabId":"$UUID","text":"echo HI"}'`
      → 200，sandbox 那个 terminal tab 出现 `echo HI` 并执行
- [ ] `curl -X POST http://127.0.0.1:6790/focusTab \
        -H 'Content-Type: application/json' \
        -d '{"tabId":"$UUID"}'`
      → IDE 窗口前置、Terminal tool window 弹出、对应 tab 被选中
- [ ] 错误 tabId → 410

## App 集成端到端

- [ ] 在 sandbox 终端 tab 跑一段会让 CC 弹权限的命令（先得在 CC 里）
- [ ] App 浮窗出现 IDEA 黄色行
- [ ] 点"同意" → IDE terminal 自动按 1 + 回车
- [ ] 点"跳转" → 切到对应 IDE + 对应 tab

## 装到真 IDEA（非 sandbox）

- [ ] 在自己 IDEA Preferences → Plugins → Install plugin from disk → 选 zip
- [ ] 重启 IDEA
- [ ] 同上 E2E 流程
