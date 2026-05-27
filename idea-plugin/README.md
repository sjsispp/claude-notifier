# claude-notifier-idea (IntelliJ Platform plugin)

让 ClaudeNotifier.app 能精准 send-text / focus-tab 到 IDEA 终端的具体 tab，**消除焦点依赖**。

## 工作原理

1. 安装并启用本 plugin 后，**每个新建的 IDEA 终端 tab** 都会被注入环境变量：
   `CLAUDE_IDEA_TAB_ID=<uuid>`
2. plugin 在首个 project 打开时启动一个本地 HTTP server（默认 :6790，被占用时 6791..6799）
3. CC 在该终端运行时，通过 hook 把 tabId 传给 ClaudeNotifier.app
4. App 浮窗里点"同意" → POST http://127.0.0.1:6790/sendText → plugin 直接调
   `TerminalWidget.sendCommandToExecute("1")` → 100% 命中

## 开发与构建

需要 JDK 17+。开发机若没装独立 JDK，可用 IntelliJ IDEA 自带的 JBR：

```bash
export JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"
cd idea-plugin
./gradlew buildPlugin
# 产物：build/distributions/claude-notifier-idea-0.1.0.zip
```

## 本地安装

**方式 A：通过 sandbox 调试**
```bash
./gradlew runIde
# 起一个独立的 IntelliJ sandbox，本 plugin 已加载
```

**方式 B：装到你自己的 IDEA**
1. 关闭 IDEA
2. Preferences → Plugins → ⚙️ → Install plugin from disk... → 选 `build/distributions/claude-notifier-idea-0.1.0.zip`
3. 重启 IDEA
4. 新开一个终端 tab，`echo $CLAUDE_IDEA_TAB_ID` 应有 UUID 输出

## 端点

| 路径 | 方法 | 说明 |
|------|------|------|
| /healthz | GET | 健康检查，返回 plugin 版本 |
| /sendText | POST | body: `{tabId, text}`，把 text 送到 tabId 对应的 PTY |
| /focusTab | POST | body: `{tabId}`，提升 IDE 窗口、显示 Terminal tool window、选中 tab |

错误码：200 成功 / 400 参数错 / 410 tabId 找不到或 project 关闭 / 405 method 不对

## 限制

- 必须重启 IDE 才能让 plugin 生效（IntelliJ 限制）
- 已经打开的终端 tab 不会有 CLAUDE_IDEA_TAB_ID（plugin 安装前的）；需要新开一个
- 暂未支持远程开发（JetBrains Gateway）；本 plugin 只跑在本地 IDE 进程

## 排错

- `runIde` 失败：检查 JDK 17+，或导出 IDEA JBR 路径到 `JAVA_HOME`
- plugin 装上后没 server：查 `~/Library/Logs/JetBrains/<IDE>/idea.log` 搜 `ClaudeNotifier`
- `/healthz` 拒连：plugin 没启动或端口冲突，看上面日志中 `port X unavailable` 行
