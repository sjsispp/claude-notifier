# Contributing

欢迎 issue / PR！项目由 3 个相对独立的子项目组成，按你想动哪部分往下看。

## 项目布局

| 子项目 | 语言 | 构建 |
|--------|------|------|
| `app/` | Swift 5.9 / macOS 13+ | `swift build` / `swift test` |
| `cc-plugin/` | Bash + jq | 无（脚本即代码） |
| `idea-plugin/` | Kotlin 1.9+ / JDK 17+ | `./gradlew build` |

## 开发约定

### 提交信息

参考已有 commit，使用 conventional commits 风格：

```
feat(scope): ...      新功能
fix(scope): ...       bug 修复
docs: ...             文档
chore: ...            杂项（构建、依赖、配置）
scaffold(scope): ...  脚手架
plan: ...             实施计划文档
spec: ...             设计 spec 文档
```

scope 用：`core`, `server`, `terminal`, `ui`, `util`, `cc-plugin`, `idea-plugin`。

### 分支与 PR

- `main` 始终可发布
- feature 分支命名：`feat/<short-desc>`、`fix/<issue-id>`
- PR 描述讲清楚：动机、改动范围、如何测试
- 单 PR 单关注点，别混

### 代码风格

- **Swift**：默认 Xcode formatter；async/await 优先；强类型避免 `Any`；adapter 用 protocol
- **Kotlin**：JetBrains IDE 默认 formatter；IntelliJ Platform API 不稳定时优先 `runCatching` 包起来
- **Bash**：`set +e` + 显式 `exit 0`（hook 永远 fail-open）；用 `jq` 处理 JSON；不写 `eval`
- **路径**：文档里别写 `/Users/<你>/...`，用 `<repo-root>` 或 `~/claude-notifier` 占位

## 写测试

### App (Swift)

TDD 流程：先写测试 → `swift test` 看红 → 实现 → `swift test` 看绿 → commit。

- 单元测试：放 `app/Tests/ClaudeNotifierTests/`
- 测试涉及外部依赖（AppleScript、HTTP）必须用 mock 或 URLProtocol 拦截
- 命名 `test_<行为>_<条件>()`

### IDEA plugin (Kotlin)

- 单元测试：`idea-plugin/src/test/kotlin/`
- 涉及 IntelliJ Platform API 的代码用 `BasePlatformTestCase`（heavy，慎用）；纯逻辑用 JUnit 4 即可
- 跑：`./gradlew test`

### CC plugin (Bash)

目前没单测框架（bats 可选）。手动验证：

```bash
echo '<样例 JSON>' | bash cc-plugin/hooks/notify-hook.sh
echo "exit: $?"     # 必须永远 0
```

完整 E2E 清单：`cc-plugin/tests/manual-test-checklist.md`。

## 改 spec / plan

仓库里的 `docs/specs/` 和 `docs/plans/` 是历史快照，**不要直接改**。如果实施过程中发现 spec 不准，新增一个 `docs/specs/YYYY-MM-DD-<topic>-implementation-notes.md` 补丁文档，标明覆盖了原 spec 的哪几节。

## 开新功能前

打 issue 讨论一下设计，避免做完发现方向不对。涉及多组件改动（比如新增一种终端宿主）需要：

1. spec 文档补一节（架构层面的决策）
2. 各组件分别提 PR，按依赖顺序合并
3. INSTALL.md 同步更新

## 报 bug

带上：

- macOS 版本、Swift 版本、IDE 版本
- 复现步骤
- App 日志：`/tmp/claude-notifier.log`（如果用 `nohup` 启动）
- IDE 日志：`Help → Show Log in Finder`，搜 `ClaudeNotifier`
- hook 调用情况：`bash -x cc-plugin/hooks/notify-hook.sh < /dev/null` 看输出
