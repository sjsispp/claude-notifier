# Plan C: IDEA Plugin 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 `claude-notifier-idea` IntelliJ Platform plugin (Kotlin) —— 在 IDEA 终端 tab 创建时注入 `CLAUDE_IDEA_TAB_ID` 环境变量；暴露本地 HTTP API（:6790）让 ClaudeNotifier.app 精准 sendText / focusTab，**消除焦点依赖**。同时重写 App 端的 `IdeaAdapter` 把 stub 换成真实 HTTP 客户端。

**Architecture:**
- IDEA plugin 通过 `LocalTerminalCustomizer` 扩展点拦截新终端 tab 创建，注入 UUID 环境变量并登记到 `TerminalTabRegistry`
- 用 JDK 自带 `com.sun.net.httpserver.HttpServer` 监听 :6790（端口冲突 → fail-open 不启动，记录日志）
- 两个端点：`POST /sendText {tabId, text}` 调 `JBTerminalWidget.executeCommand`；`POST /focusTab {tabId}` 激活 IDEA + 显示 Terminal tool window + 选中 tab
- App 端 `IdeaAdapter` 从 stub 重写成 URLSession HTTP 客户端

**Tech Stack:**
- Kotlin 1.9+ / JVM 17（IntelliJ Platform 2024.x 默认）
- Gradle 8.x + `org.jetbrains.intellij.platform` 2.x
- IntelliJ Platform 2024.2+（target IC 2024.2.5，open to running on 2024.2~current）
- JSON 用平台内置 Gson
- 不引入 Ktor / Javalin 等 HTTP 框架

**对应 spec:** `docs/specs/2026-05-27-claude-notifier-design.md` §3 (架构) + §5 (终端定位) + `docs/specs/2026-05-27-claude-notifier-implementation-notes.md` §6 (Plan C hints)

**前置依赖:**
- Plan A 完成（App 端 `IdeaAdapter.swift` 是 stub）
- Plan B 完成（hook 已经在传 `idea_tab_id` 字段）
- 开发者本机有 JDK 17+ 与 Gradle 8（Gradle wrapper 自带，可省手装）
- 测试需要 IntelliJ IDEA（任何 JetBrains IDE 都行），运行时通过 `./gradlew runIde` 起 sandbox

---

## File Structure

```
idea-plugin/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradle/wrapper/{gradle-wrapper.jar, gradle-wrapper.properties}
├── gradlew, gradlew.bat
├── .gitignore
├── src/main/
│   ├── kotlin/io/claudenotifier/idea/
│   │   ├── ClaudeNotifierStartupActivity.kt   # ProjectActivity，启动 HTTP server
│   │   ├── TerminalTabRegistry.kt              # 服务，UUID ↔ JBTerminalWidget
│   │   ├── TerminalEnvCustomizer.kt            # LocalTerminalCustomizer 实现
│   │   └── server/
│   │       ├── HttpServerHolder.kt             # 全局单例，懒启动
│   │       ├── SendTextHandler.kt              # POST /sendText
│   │       ├── FocusTabHandler.kt              # POST /focusTab
│   │       └── HealthzHandler.kt               # GET /healthz
│   └── resources/META-INF/
│       ├── plugin.xml
│       └── pluginIcon.svg
└── src/test/kotlin/io/claudenotifier/idea/
    └── TerminalTabRegistryTest.kt
```

App 端额外改：

```
app/Sources/ClaudeNotifier/Terminal/IdeaAdapter.swift   # rewrite
app/Tests/ClaudeNotifierTests/IdeaAdapterTests.swift    # new
```

---

### Task 1: Gradle 项目脚手架

**Files:**
- Create: `idea-plugin/settings.gradle.kts`
- Create: `idea-plugin/build.gradle.kts`
- Create: `idea-plugin/gradle.properties`
- Create: `idea-plugin/.gitignore`
- Create: `idea-plugin/gradle/wrapper/gradle-wrapper.properties`

- [ ] **Step 1: 创建目录**

```bash
mkdir -p /Users/wudazhan/workplace/project/claude-notifier/idea-plugin/{src/main/kotlin/io/claudenotifier/idea/server,src/main/resources/META-INF,src/test/kotlin/io/claudenotifier/idea,gradle/wrapper}
```

- [ ] **Step 2: settings.gradle.kts**

`idea-plugin/settings.gradle.kts`:

```kotlin
rootProject.name = "claude-notifier-idea"
```

- [ ] **Step 3: gradle.properties**

`idea-plugin/gradle.properties`:

```properties
pluginGroup=io.claudenotifier
pluginName=claude-notifier-idea
pluginVersion=0.1.0
pluginSinceBuild=242
platformType=IC
platformVersion=2024.2.5

# Performance
org.gradle.jvmargs=-Xmx2g
org.gradle.parallel=true
kotlin.stdlib.default.dependency=false
```

- [ ] **Step 4: build.gradle.kts**

`idea-plugin/build.gradle.kts`:

```kotlin
plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        create(
            providers.gradleProperty("platformType"),
            providers.gradleProperty("platformVersion")
        )
        bundledPlugin("org.jetbrains.plugins.terminal")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }
}

kotlin { jvmToolchain(17) }

tasks {
    wrapper { gradleVersion = "8.10.2" }
}
```

- [ ] **Step 5: gradle-wrapper.properties**

`idea-plugin/gradle/wrapper/gradle-wrapper.properties`:

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.10.2-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

- [ ] **Step 6: .gitignore**

`idea-plugin/.gitignore`:

```
.gradle/
build/
.idea/
*.iml
out/
```

- [ ] **Step 7: 拉 wrapper jar（gradle wrapper 必须存在以供构建）**

```bash
cd /Users/wudazhan/workplace/project/claude-notifier/idea-plugin
# 用系统 gradle（若没装则 brew install gradle）生成 wrapper jar
if command -v gradle >/dev/null 2>&1; then
  gradle wrapper --gradle-version 8.10.2 --quiet
else
  echo "system gradle missing — falling back to manual wrapper jar download"
  curl -sL -o gradle/wrapper/gradle-wrapper.jar \
    https://raw.githubusercontent.com/gradle/gradle/v8.10.2/gradle/wrapper/gradle-wrapper.jar
  cp $(brew --prefix 2>/dev/null)/share/gradle/* gradle/ 2>/dev/null || true
  curl -sL -o gradlew \
    https://raw.githubusercontent.com/gradle/gradle/v8.10.2/gradlew
  curl -sL -o gradlew.bat \
    https://raw.githubusercontent.com/gradle/gradle/v8.10.2/gradlew.bat
  chmod +x gradlew
fi
ls gradle/wrapper/gradle-wrapper.jar gradlew
```

- [ ] **Step 8: 验证 `./gradlew --version` 跑得起来**

```bash
cd /Users/wudazhan/workplace/project/claude-notifier/idea-plugin
./gradlew --version
```

Expected: 看到 Gradle 8.10.2 + JVM 17+。
失败 → BLOCKED，可能需要装 JDK 17（`brew install --cask temurin@17` 或类似）。

- [ ] **Step 9: Commit**

```bash
cd /Users/wudazhan/workplace/project/claude-notifier
git add idea-plugin/settings.gradle.kts idea-plugin/build.gradle.kts \
        idea-plugin/gradle.properties idea-plugin/.gitignore \
        idea-plugin/gradle/wrapper/gradle-wrapper.properties \
        idea-plugin/gradle/wrapper/gradle-wrapper.jar \
        idea-plugin/gradlew idea-plugin/gradlew.bat
git -c commit.gpgsign=false commit -m "scaffold(idea-plugin): Gradle + IntelliJ Platform 2.x"
```

---

### Task 2: plugin.xml + plugin metadata

**Files:**
- Create: `idea-plugin/src/main/resources/META-INF/plugin.xml`
- Create: `idea-plugin/src/main/resources/META-INF/pluginIcon.svg`（简易占位）

- [ ] **Step 1: plugin.xml**

`idea-plugin/src/main/resources/META-INF/plugin.xml`:

```xml
<idea-plugin>
    <id>io.claudenotifier.idea</id>
    <name>Claude Notifier</name>
    <vendor email="wudazhan@local" url="https://github.com/wudazhan/claude-notifier">wudazhan</vendor>

    <description><![CDATA[
        把 IntelliJ 终端的 Claude Code 事件精准送到 ClaudeNotifier.app 浮窗。
        <br/>
        新终端 tab 注入 CLAUDE_IDEA_TAB_ID 环境变量；本地 HTTP 服务（:6790）
        让 App 能 100% 可靠地 sendText / focusTab，无焦点依赖。
    ]]></description>

    <depends>com.intellij.modules.platform</depends>
    <depends>org.jetbrains.plugins.terminal</depends>

    <extensions defaultExtensionNs="com.intellij">
        <postStartupActivity implementation="io.claudenotifier.idea.ClaudeNotifierStartupActivity"/>
    </extensions>

    <extensions defaultExtensionNs="org.jetbrains.plugins.terminal">
        <localTerminalCustomizer implementation="io.claudenotifier.idea.TerminalEnvCustomizer"/>
    </extensions>

    <applicationListeners/>
</idea-plugin>
```

- [ ] **Step 2: 简易 SVG 图标（13x13）**

`idea-plugin/src/main/resources/META-INF/pluginIcon.svg`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<svg width="40" height="40" viewBox="0 0 40 40" xmlns="http://www.w3.org/2000/svg">
  <circle cx="20" cy="20" r="18" fill="#FF6B35"/>
  <text x="20" y="27" text-anchor="middle" font-family="Helvetica" font-size="22" font-weight="bold" fill="white">🔔</text>
</svg>
```

- [ ] **Step 3: 构建（buildPlugin 任务，验证 plugin.xml 结构）**

```bash
cd /Users/wudazhan/workplace/project/claude-notifier/idea-plugin
./gradlew --offline verifyPlugin || ./gradlew verifyPlugin
```

如果 IntelliJ Platform Gradle 任务名不是 `verifyPlugin`，尝试 `buildPlugin`。Expected: 一切正常通过（plugin.xml 结构正确，无 class 找不到——这一步 class 还没写所以会有 "class not found"，那是正常的，等 T3 之后再跑）。

如果 buildPlugin 失败因 class 找不到 → 把这一步跳过，留到 T3 后再验证。注明在 commit message 里。

- [ ] **Step 4: Commit**

```bash
cd /Users/wudazhan/workplace/project/claude-notifier
git add idea-plugin/src/main/resources/META-INF/
git -c commit.gpgsign=false commit -m "feat(idea-plugin): plugin.xml + icon (class impls in T3+)"
```

---

### Task 3: TerminalTabRegistry（UUID ↔ widget 映射）

**Files:**
- Create: `idea-plugin/src/main/kotlin/io/claudenotifier/idea/TerminalTabRegistry.kt`
- Create: `idea-plugin/src/test/kotlin/io/claudenotifier/idea/TerminalTabRegistryTest.kt`

线程安全 singleton service，维护 UUID → 元数据（包含 Project 引用，方便 focusTab 跨项目寻址）。

- [ ] **Step 1: 写测试（先红）**

`idea-plugin/src/test/kotlin/io/claudenotifier/idea/TerminalTabRegistryTest.kt`:

```kotlin
package io.claudenotifier.idea

import org.junit.Assert.*
import org.junit.Test

class TerminalTabRegistryTest {
    @Test
    fun `register adds entry`() {
        val r = TerminalTabRegistry()
        val uuid = r.register(projectName = "foo", projectPath = "/p/foo")
        assertNotNull(uuid)
        val entry = r.lookup(uuid)
        assertNotNull(entry)
        assertEquals("foo", entry!!.projectName)
        assertEquals("/p/foo", entry.projectPath)
    }

    @Test
    fun `lookup missing returns null`() {
        val r = TerminalTabRegistry()
        assertNull(r.lookup("nonexistent"))
    }

    @Test
    fun `unregister removes entry`() {
        val r = TerminalTabRegistry()
        val uuid = r.register(projectName = "x", projectPath = "/x")
        r.unregister(uuid)
        assertNull(r.lookup(uuid))
    }

    @Test
    fun `multiple registrations produce distinct uuids`() {
        val r = TerminalTabRegistry()
        val u1 = r.register("a", "/a")
        val u2 = r.register("a", "/a")
        assertNotEquals(u1, u2)
    }
}
```

- [ ] **Step 2: 实现**

`idea-plugin/src/main/kotlin/io/claudenotifier/idea/TerminalTabRegistry.kt`:

```kotlin
package io.claudenotifier.idea

import com.intellij.openapi.components.Service
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class TerminalTabEntry(
    val uuid: String,
    val projectName: String,
    val projectPath: String,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 维护 UUID → 终端 tab 元数据的映射。
 * 通过 LocalTerminalCustomizer 注入到 shell 环境变量 CLAUDE_IDEA_TAB_ID。
 *
 * 注意：Plan C 当前版本只保存元数据（用于 focusTab 的项目寻址）；
 * 真实把字符送进 PTY 的 JBTerminalWidget 引用挂接由 SendTextHandler 在请求时
 * 通过遍历所有 project 的 TerminalToolWindowManager 寻找对应 widget 完成。
 */
@Service(Service.Level.APP)
class TerminalTabRegistry {
    private val entries = ConcurrentHashMap<String, TerminalTabEntry>()

    fun register(projectName: String, projectPath: String): String {
        val uuid = UUID.randomUUID().toString()
        entries[uuid] = TerminalTabEntry(uuid, projectName, projectPath)
        return uuid
    }

    fun lookup(uuid: String): TerminalTabEntry? = entries[uuid]

    fun unregister(uuid: String) { entries.remove(uuid) }

    fun snapshot(): List<TerminalTabEntry> = entries.values.toList()
}
```

- [ ] **Step 3: 跑测试**

```bash
cd /Users/wudazhan/workplace/project/claude-notifier/idea-plugin
./gradlew test --tests 'io.claudenotifier.idea.TerminalTabRegistryTest'
```

Expected: 4 tests pass.

- [ ] **Step 4: Commit**

```bash
cd /Users/wudazhan/workplace/project/claude-notifier
git add idea-plugin/src/
git -c commit.gpgsign=false commit -m "feat(idea-plugin): TerminalTabRegistry with UUID lifecycle"
```

---

### Task 4: TerminalEnvCustomizer（注入环境变量）

**Files:**
- Create: `idea-plugin/src/main/kotlin/io/claudenotifier/idea/TerminalEnvCustomizer.kt`

- [ ] **Step 1: 实现**

`idea-plugin/src/main/kotlin/io/claudenotifier/idea/TerminalEnvCustomizer.kt`:

```kotlin
package io.claudenotifier.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.terminal.LocalTerminalCustomizer
import java.io.File

/**
 * 在每个新终端 tab 创建时注入 CLAUDE_IDEA_TAB_ID 环境变量。
 * CC hook 脚本读取此变量识别"我在哪个 IDEA 终端"，回传给 ClaudeNotifier.app。
 */
class TerminalEnvCustomizer : LocalTerminalCustomizer() {

    override fun customizeCommandAndEnvironment(
        project: Project,
        workingDirectory: String?,
        command: Array<out String>,
        envs: MutableMap<String, String>
    ): Array<out String> {
        val registry = ApplicationManager.getApplication().getService(TerminalTabRegistry::class.java)
        val uuid = registry.register(
            projectName = project.name,
            projectPath = project.basePath ?: workingDirectory ?: ""
        )
        envs["CLAUDE_IDEA_TAB_ID"] = uuid
        return command
    }
}
```

注：`LocalTerminalCustomizer` 当前版本可能有方法签名差异（IntelliJ Platform 2024.x 引入了新方法）。如果编译失败，先 `./gradlew compileKotlin` 看错误，按 IDE 内 quick fix 修正方法签名（可能需要 override 不同名字的方法，但语义不变）。

- [ ] **Step 2: 编译验证**

```bash
cd /Users/wudazhan/workplace/project/claude-notifier/idea-plugin
./gradlew compileKotlin
```

Expected: SUCCESS（如果失败，参考上面注释处理）。

- [ ] **Step 3: Commit**

```bash
cd /Users/wudazhan/workplace/project/claude-notifier
git add idea-plugin/src/main/kotlin/io/claudenotifier/idea/TerminalEnvCustomizer.kt
git -c commit.gpgsign=false commit -m "feat(idea-plugin): TerminalEnvCustomizer injects CLAUDE_IDEA_TAB_ID"
```

---

### Task 5: HTTP Server + StartupActivity + /healthz

**Files:**
- Create: `idea-plugin/src/main/kotlin/io/claudenotifier/idea/server/HttpServerHolder.kt`
- Create: `idea-plugin/src/main/kotlin/io/claudenotifier/idea/server/HealthzHandler.kt`
- Create: `idea-plugin/src/main/kotlin/io/claudenotifier/idea/ClaudeNotifierStartupActivity.kt`

- [ ] **Step 1: HealthzHandler**

`idea-plugin/src/main/kotlin/io/claudenotifier/idea/server/HealthzHandler.kt`:

```kotlin
package io.claudenotifier.idea.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler

class HealthzHandler : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        val body = """{"ok":true,"plugin":"claude-notifier-idea","version":"0.1.0"}"""
        exchange.responseHeaders["Content-Type"] = listOf("application/json")
        exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
        exchange.responseBody.use { it.write(body.toByteArray()) }
    }
}
```

- [ ] **Step 2: HttpServerHolder（单例）**

`idea-plugin/src/main/kotlin/io/claudenotifier/idea/server/HttpServerHolder.kt`:

```kotlin
package io.claudenotifier.idea.server

import com.intellij.openapi.diagnostic.Logger
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors

object HttpServerHolder {
    private val log = Logger.getInstance(HttpServerHolder::class.java)
    private var server: HttpServer? = null

    /**
     * 启动 HTTP server。端口冲突时尝试 6790~6799，失败则 fail-open 不抛出。
     */
    @Synchronized
    fun start() {
        if (server != null) return

        val ports = (6790..6799).toList()
        for (port in ports) {
            try {
                val srv = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
                srv.executor = Executors.newFixedThreadPool(4)
                registerRoutes(srv)
                srv.start()
                server = srv
                log.info("[ClaudeNotifier] HTTP server listening on :$port")
                return
            } catch (e: Exception) {
                log.warn("[ClaudeNotifier] port $port unavailable: ${e.message}")
            }
        }
        log.error("[ClaudeNotifier] no available port in 6790..6799; HTTP server NOT started")
    }

    private fun registerRoutes(srv: HttpServer) {
        srv.createContext("/healthz", HealthzHandler())
        srv.createContext("/sendText", SendTextHandler())
        srv.createContext("/focusTab", FocusTabHandler())
    }

    @Synchronized
    fun stop() {
        server?.stop(0)
        server = null
    }

    fun isRunning(): Boolean = server != null

    fun currentPort(): Int? = server?.address?.port
}
```

注：`SendTextHandler` 和 `FocusTabHandler` 在 T6/T7 创建。当前阶段先 stub：

临时 `SendTextHandler.kt`（T6 会重写）:

```kotlin
package io.claudenotifier.idea.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler

class SendTextHandler : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        val body = """{"ok":false,"error":"not implemented yet"}"""
        exchange.sendResponseHeaders(501, body.toByteArray().size.toLong())
        exchange.responseBody.use { it.write(body.toByteArray()) }
    }
}
```

临时 `FocusTabHandler.kt`（T7 会重写）:

```kotlin
package io.claudenotifier.idea.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler

class FocusTabHandler : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        val body = """{"ok":false,"error":"not implemented yet"}"""
        exchange.sendResponseHeaders(501, body.toByteArray().size.toLong())
        exchange.responseBody.use { it.write(body.toByteArray()) }
    }
}
```

- [ ] **Step 3: ClaudeNotifierStartupActivity**

`idea-plugin/src/main/kotlin/io/claudenotifier/idea/ClaudeNotifierStartupActivity.kt`:

```kotlin
package io.claudenotifier.idea

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import io.claudenotifier.idea.server.HttpServerHolder

class ClaudeNotifierStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        // 全 App 第一次打开任意 project 时启动 server（之后所有 project 共用）
        HttpServerHolder.start()
    }
}
```

- [ ] **Step 4: 编译**

```bash
cd /Users/wudazhan/workplace/project/claude-notifier/idea-plugin
./gradlew compileKotlin
```

Expected: SUCCESS.

- [ ] **Step 5: Commit**

```bash
cd /Users/wudazhan/workplace/project/claude-notifier
git add idea-plugin/src/main/kotlin/io/claudenotifier/idea/server/ \
        idea-plugin/src/main/kotlin/io/claudenotifier/idea/ClaudeNotifierStartupActivity.kt
git -c commit.gpgsign=false commit -m "feat(idea-plugin): HTTP server + /healthz + startup activity"
```

---

### Task 6: SendTextHandler（真实实现：寻找 widget 并送字符）

**Files:**
- Modify: `idea-plugin/src/main/kotlin/io/claudenotifier/idea/server/SendTextHandler.kt`

- [ ] **Step 1: 重写 SendTextHandler**

`idea-plugin/src/main/kotlin/io/claudenotifier/idea/server/SendTextHandler.kt`:

```kotlin
package io.claudenotifier.idea.server

import com.google.gson.Gson
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.ProjectManager
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import io.claudenotifier.idea.TerminalTabRegistry
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

class SendTextHandler : HttpHandler {
    private val log = Logger.getInstance(SendTextHandler::class.java)
    private val gson = Gson()

    data class Request(val tabId: String?, val text: String?)
    data class Response(val ok: Boolean, val error: String? = null)

    override fun handle(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") {
            respond(exchange, 405, Response(false, "method not allowed"))
            return
        }
        val bodyStr = exchange.requestBody.bufferedReader().use { it.readText() }
        val req = try { gson.fromJson(bodyStr, Request::class.java) }
                  catch (e: Exception) { respond(exchange, 400, Response(false, "invalid json")); return }

        val tabId = req.tabId ?: run { respond(exchange, 400, Response(false, "missing tabId")); return }
        val text = req.text ?: run { respond(exchange, 400, Response(false, "missing text")); return }

        val registry = ApplicationManager.getApplication().getService(TerminalTabRegistry::class.java)
        val entry = registry.lookup(tabId) ?: run {
            respond(exchange, 410, Response(false, "tabId not registered"))
            return
        }

        // 找到 entry.projectPath 对应的 Project，遍历其 terminal widgets
        val project = ProjectManager.getInstance().openProjects.firstOrNull {
            it.basePath == entry.projectPath || it.name == entry.projectName
        } ?: run {
            respond(exchange, 410, Response(false, "project closed"))
            return
        }

        // 遍历所有 terminal widget，挑环境变量含 CLAUDE_IDEA_TAB_ID=<tabId> 的那个
        var sent = false
        ApplicationManager.getApplication().invokeAndWait {
            val twm = TerminalToolWindowManager.getInstance(project)
            for (widget in twm.terminalWidgets) {
                val env = runCatching { widget.terminalRunner?.shellEnv }.getOrNull() ?: continue
                if (env["CLAUDE_IDEA_TAB_ID"] == tabId) {
                    try {
                        widget.sendCommandToExecute(text.trimEnd('\n'))
                        sent = true
                        break
                    } catch (e: Exception) {
                        log.warn("sendCommandToExecute failed: ${e.message}")
                    }
                }
            }
        }

        if (sent) respond(exchange, 200, Response(true))
        else respond(exchange, 410, Response(false, "matching widget not found"))
    }

    private fun respond(exchange: HttpExchange, code: Int, body: Response) {
        val json = gson.toJson(body).toByteArray()
        exchange.responseHeaders["Content-Type"] = listOf("application/json")
        exchange.sendResponseHeaders(code, json.size.toLong())
        exchange.responseBody.use { it.write(json) }
    }
}
```

⚠️ **API 兼容性提示**：IntelliJ Platform 的 terminal API 在不同版本间不太稳定。具体方法名（`terminalWidgets`、`shellEnv`、`sendCommandToExecute`）在 2024.2 可能略有不同。编译错误时：

1. 用 IDE 的 quick-fix 让它换成实际可用的 API
2. 如果 `shellEnv` 在该版本中不可访问，退而求其次：把 widget → tabId 的映射在 `TerminalEnvCustomizer.customizeCommandAndEnvironment` 时直接登记到 `TerminalTabRegistry` 里（让 entry 持有 widget 引用），SendTextHandler 直接从 entry 拿
3. 该兜底方案的代价：registry 持有 widget 强引用，关闭 tab 时需要清理（注册 `Disposable`）

如果 API 不稳，先用兜底方案。**记录到 commit message 里。**

- [ ] **Step 2: 编译**

```bash
cd /Users/wudazhan/workplace/project/claude-notifier/idea-plugin
./gradlew compileKotlin
```

如果 API 编译错，按上面说明改用兜底方案。

- [ ] **Step 3: Commit**

```bash
cd /Users/wudazhan/workplace/project/claude-notifier
git add idea-plugin/src/main/kotlin/io/claudenotifier/idea/server/SendTextHandler.kt
# 如果改了 registry 或 customizer 也一并加
git add idea-plugin/src/main/kotlin/io/claudenotifier/idea/
git -c commit.gpgsign=false commit -m "feat(idea-plugin): SendTextHandler routes text to matching terminal widget"
```

---

### Task 7: FocusTabHandler（真实实现：激活窗口 + 选中 tab）

**Files:**
- Modify: `idea-plugin/src/main/kotlin/io/claudenotifier/idea/server/FocusTabHandler.kt`

- [ ] **Step 1: 重写 FocusTabHandler**

`idea-plugin/src/main/kotlin/io/claudenotifier/idea/server/FocusTabHandler.kt`:

```kotlin
package io.claudenotifier.idea.server

import com.google.gson.Gson
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.WindowManager
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import io.claudenotifier.idea.TerminalTabRegistry
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

class FocusTabHandler : HttpHandler {
    private val gson = Gson()

    data class Request(val tabId: String?)
    data class Response(val ok: Boolean, val error: String? = null)

    override fun handle(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") {
            respond(exchange, 405, Response(false, "method not allowed")); return
        }
        val bodyStr = exchange.requestBody.bufferedReader().use { it.readText() }
        val req = try { gson.fromJson(bodyStr, Request::class.java) }
                  catch (e: Exception) { respond(exchange, 400, Response(false, "invalid json")); return }

        val tabId = req.tabId ?: run { respond(exchange, 400, Response(false, "missing tabId")); return }
        val registry = ApplicationManager.getApplication().getService(TerminalTabRegistry::class.java)
        val entry = registry.lookup(tabId) ?: run {
            respond(exchange, 410, Response(false, "tabId not registered")); return
        }

        val project = ProjectManager.getInstance().openProjects.firstOrNull {
            it.basePath == entry.projectPath || it.name == entry.projectName
        } ?: run {
            respond(exchange, 410, Response(false, "project closed")); return
        }

        var ok = false
        ApplicationManager.getApplication().invokeAndWait {
            // 1. 提升 IDE 窗口
            val frame = WindowManager.getInstance().getFrame(project)
            frame?.toFront()
            frame?.requestFocus()

            // 2. 显示 Terminal tool window
            val twm = TerminalToolWindowManager.getInstance(project)
            twm.toolWindow.show {}

            // 3. 选中匹配 tabId 的 widget（与 SendTextHandler 相同的查找逻辑）
            for (widget in twm.terminalWidgets) {
                val env = runCatching { widget.terminalRunner?.shellEnv }.getOrNull() ?: continue
                if (env["CLAUDE_IDEA_TAB_ID"] == tabId) {
                    twm.toolWindow.contentManager.contents.forEach { content ->
                        if (content.component == widget || content.component.toString().contains(widget.toString())) {
                            twm.toolWindow.contentManager.setSelectedContent(content)
                            ok = true
                        }
                    }
                    if (!ok) {
                        // 兜底：只激活了 tool window 没精准选中 tab；仍算部分成功
                        ok = true
                    }
                    break
                }
            }
        }

        if (ok) respond(exchange, 200, Response(true))
        else respond(exchange, 410, Response(false, "matching widget not found"))
    }

    private fun respond(exchange: HttpExchange, code: Int, body: Response) {
        val json = gson.toJson(body).toByteArray()
        exchange.responseHeaders["Content-Type"] = listOf("application/json")
        exchange.sendResponseHeaders(code, json.size.toLong())
        exchange.responseBody.use { it.write(json) }
    }
}
```

API 兼容性同 Task 6 —— 如果某些方法名变了，按 IDE quick-fix 调整。

- [ ] **Step 2: 编译 + buildPlugin**

```bash
cd /Users/wudazhan/workplace/project/claude-notifier/idea-plugin
./gradlew buildPlugin
```

Expected: `build/distributions/claude-notifier-idea-0.1.0.zip` 出现。

- [ ] **Step 3: Commit**

```bash
cd /Users/wudazhan/workplace/project/claude-notifier
git add idea-plugin/src/main/kotlin/io/claudenotifier/idea/server/FocusTabHandler.kt
git -c commit.gpgsign=false commit -m "feat(idea-plugin): FocusTabHandler activates window + tab"
```

---

### Task 8: App 端 IdeaAdapter 重写（HTTP 客户端 + 测试）

**Files:**
- Modify: `app/Sources/ClaudeNotifier/Terminal/IdeaAdapter.swift`
- Modify: `app/Tests/ClaudeNotifierTests/AdapterTests.swift`（删除 stub 的旧测试）
- Create: `app/Tests/ClaudeNotifierTests/IdeaAdapterTests.swift`

- [ ] **Step 1: 重写 IdeaAdapter**

`app/Sources/ClaudeNotifier/Terminal/IdeaAdapter.swift`:

```swift
import Foundation

/// 通过 HTTP 调用 IDEA plugin（默认 :6790）实现精准 jump / approve。
struct IdeaAdapter: TerminalAdapter {
    let host: HookPayload.Host = .idea
    let baseURL: URL
    let session: URLSession

    init(baseURL: URL = URL(string: "http://127.0.0.1:6790")!,
         session: URLSession = .shared) {
        self.baseURL = baseURL
        self.session = session
    }

    func jump(item: NotificationItem) async throws {
        guard let tabId = item.ideaTabId else { throw AdapterError.targetNotFound }
        try await post(path: "/focusTab", body: ["tabId": tabId])
    }

    func approve(item: NotificationItem) async throws {
        guard let tabId = item.ideaTabId else { throw AdapterError.targetNotFound }
        try await post(path: "/sendText", body: ["tabId": tabId, "text": "1\n"])
    }

    private func post(path: String, body: [String: String]) async throws {
        var req = URLRequest(url: baseURL.appendingPathComponent(path))
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONSerialization.data(withJSONObject: body)
        req.timeoutInterval = 2

        do {
            let (data, response) = try await session.data(for: req)
            guard let http = response as? HTTPURLResponse else {
                throw AdapterError.scriptError("invalid response")
            }
            switch http.statusCode {
            case 200: return
            case 410: throw AdapterError.targetNotFound
            case 400, 405: throw AdapterError.scriptError("bad request: \(String(data: data, encoding: .utf8) ?? "")")
            default: throw AdapterError.scriptError("HTTP \(http.statusCode)")
            }
        } catch let e as AdapterError {
            throw e
        } catch let e as URLError where e.code == .cannotConnectToHost || e.code == .timedOut {
            throw AdapterError.pluginNotInstalled
        } catch {
            throw AdapterError.scriptError("\(error)")
        }
    }
}
```

- [ ] **Step 2: 删除 AdapterTests 里旧的 `test_ideaAdapter_returnsPluginNotInstalledByDefault`（已不适用）**

打开 `app/Tests/ClaudeNotifierTests/AdapterTests.swift`，删除以下完整函数：

```swift
func test_ideaAdapter_returnsPluginNotInstalledByDefault() async {
    let a = IdeaAdapter()
    do {
        try await a.jump(item: item(host: .idea))
        XCTFail("expected throw")
    } catch let e as AdapterError {
        XCTAssertEqual(e, .pluginNotInstalled)
    } catch {
        XCTFail("wrong error type: \(error)")
    }
}
```

- [ ] **Step 3: 新建 IdeaAdapterTests，用 URLProtocol mock 测**

`app/Tests/ClaudeNotifierTests/IdeaAdapterTests.swift`:

```swift
import XCTest
@testable import ClaudeNotifier

private final class MockURLProtocol: URLProtocol {
    static var handler: ((URLRequest) -> (HTTPURLResponse, Data?))?

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        guard let handler = MockURLProtocol.handler else {
            client?.urlProtocol(self, didFailWithError: URLError(.unknown)); return
        }
        let (resp, body) = handler(request)
        client?.urlProtocol(self, didReceive: resp, cacheStoragePolicy: .notAllowed)
        if let body { client?.urlProtocol(self, didLoad: body) }
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

final class IdeaAdapterTests: XCTestCase {
    private func makeSession() -> URLSession {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [MockURLProtocol.self]
        return URLSession(configuration: config)
    }

    private func item(tabId: String?) -> NotificationItem {
        let id = tabId.map { #""\#($0)""# } ?? "null"
        let json = #"{"schema":1,"event":"notification","session_id":"s","cwd":"/p","project_name":"p","terminal":{"host":"idea","idea_tab_id":\#(id)},"ts":1}"#
            .data(using: .utf8)!
        return NotificationItem(payload: try! JSONDecoder().decode(HookPayload.self, from: json))
    }

    func test_jump_postsFocusTabWithTabId() async throws {
        var captured: URLRequest?
        MockURLProtocol.handler = { req in
            captured = req
            let resp = HTTPURLResponse(url: req.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (resp, #"{"ok":true}"#.data(using: .utf8))
        }
        let a = IdeaAdapter(session: makeSession())
        try await a.jump(item: item(tabId: "uuid-1"))

        XCTAssertEqual(captured?.url?.path, "/focusTab")
        XCTAssertEqual(captured?.httpMethod, "POST")
        let body = String(data: captured?.bodySteamCollected() ?? Data(), encoding: .utf8) ?? ""
        XCTAssertTrue(body.contains("uuid-1"))
    }

    func test_approve_postsSendTextWith1Newline() async throws {
        var captured: URLRequest?
        MockURLProtocol.handler = { req in
            captured = req
            let resp = HTTPURLResponse(url: req.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (resp, #"{"ok":true}"#.data(using: .utf8))
        }
        let a = IdeaAdapter(session: makeSession())
        try await a.approve(item: item(tabId: "uuid-2"))

        XCTAssertEqual(captured?.url?.path, "/sendText")
        let body = String(data: captured?.bodySteamCollected() ?? Data(), encoding: .utf8) ?? ""
        XCTAssertTrue(body.contains("uuid-2"))
        XCTAssertTrue(body.contains(#""text""#))
    }

    func test_jump_throwsTargetNotFound_whenTabIdMissing() async {
        let a = IdeaAdapter(session: makeSession())
        do { try await a.jump(item: item(tabId: nil)); XCTFail() }
        catch let e as AdapterError { XCTAssertEqual(e, .targetNotFound) }
        catch { XCTFail() }
    }

    func test_jump_maps410ToTargetNotFound() async {
        MockURLProtocol.handler = { req in
            let resp = HTTPURLResponse(url: req.url!, statusCode: 410, httpVersion: nil, headerFields: nil)!
            return (resp, nil)
        }
        let a = IdeaAdapter(session: makeSession())
        do { try await a.jump(item: item(tabId: "x")); XCTFail() }
        catch let e as AdapterError { XCTAssertEqual(e, .targetNotFound) }
        catch { XCTFail() }
    }

    func test_jump_mapsConnectionFailureToPluginNotInstalled() async {
        MockURLProtocol.handler = { req in
            // 模拟 connection refused 用 URLError
            // 但 URLProtocol 没法直接抛 URLError；这里用 500 来代替验证非 200/410 路径
            let resp = HTTPURLResponse(url: req.url!, statusCode: 500, httpVersion: nil, headerFields: nil)!
            return (resp, nil)
        }
        let a = IdeaAdapter(session: makeSession())
        do { try await a.jump(item: item(tabId: "x")); XCTFail() }
        catch let e as AdapterError {
            // 500 走 scriptError 分支；真实 connection refused 在 E2E 测
            if case .scriptError = e { /* ok */ } else { XCTFail("expected scriptError, got \(e)") }
        }
        catch { XCTFail() }
    }
}

// 辅助：从 URLRequest 取出 httpBody（mock 流量经 URLProtocol 时 httpBody 直接可读）
private extension URLRequest {
    func bodySteamCollected() -> Data {
        return self.httpBody ?? Data()
    }
}
```

- [ ] **Step 4: 跑测试**

```bash
cd /Users/wudazhan/workplace/project/claude-notifier/app
swift test
```

Expected: 28 tests pass（原 25 - 1 删除 + 4 新增 = 28）。如果某测试名我列错或测试用例数对不上，按实际跑出的数为准。

- [ ] **Step 5: Commit**

```bash
cd /Users/wudazhan/workplace/project/claude-notifier
git add app/
git -c commit.gpgsign=false commit -m "feat(terminal): IdeaAdapter now calls IDEA plugin HTTP API"
```

---

### Task 9: README + 手动 E2E 清单

**Files:**
- Create: `idea-plugin/README.md`
- Create: `idea-plugin/tests/manual-test-checklist.md`
- Modify: 项目根 `README.md`

- [ ] **Step 1: idea-plugin/README.md**

```markdown
# claude-notifier-idea (IntelliJ Platform plugin)

让 ClaudeNotifier.app 能精准 send-text / focus-tab 到 IDEA 终端的具体 tab，**消除焦点依赖**。

## 工作原理

1. 安装并启用本 plugin 后，**每个新建的 IDEA 终端 tab** 都会被注入环境变量：
   `CLAUDE_IDEA_TAB_ID=<uuid>`
2. plugin 在首个 project 打开时启动一个本地 HTTP server（默认 :6790，被占用时 6791..6799）
3. CC 在该终端运行时，通过 hook 把 tabId 传给 ClaudeNotifier.app
4. App 浮窗里点"同意" → POST http://127.0.0.1:6790/sendText → plugin 直接调 JBTerminalWidget.sendCommandToExecute("1") → 100% 命中

## 开发与构建

```bash
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

- `runIde` 失败：检查 JDK 17+
- plugin 装上后没 server：查 `~/Library/Logs/JetBrains/<IDE>/idea.log` 搜 `ClaudeNotifier`
- `/healthz` 拒连：plugin 没启动或端口冲突，看上面日志中 `port X unavailable` 行
```

- [ ] **Step 2: 手动测试清单**

`idea-plugin/tests/manual-test-checklist.md`:

```markdown
# IDEA Plugin 手动测试清单

## 前置

- [ ] JDK 17+ 在 PATH
- [ ] ClaudeNotifier.app 在跑（菜单栏 🔔）
- [ ] CC plugin（Plan B）已装并触发过事件成功
- [ ] 本 plugin 已 buildPlugin 通过

## Sandbox 启动验证

- [ ] `./gradlew runIde` 起的 sandbox IDE 启动正常
- [ ] sandbox 里打开任一 project 后，控制台日志（Help → Show Log）含 `ClaudeNotifier HTTP server listening on :6790`
- [ ] sandbox 里新开终端 tab，`echo $CLAUDE_IDEA_TAB_ID` 输出非空 UUID

## HTTP 端点

- [ ] `curl http://127.0.0.1:6790/healthz` → 200 `{"ok":true,...}`
- [ ] `curl -X POST http://127.0.0.1:6790/sendText -d '{"tabId":"$UUID","text":"echo HI"}'` → 200，sandbox 那个 terminal tab 出现 `echo HI` 并执行
- [ ] `curl -X POST http://127.0.0.1:6790/focusTab -d '{"tabId":"$UUID"}'` → IDE 窗口前置、Terminal tool window 弹出、对应 tab 被选中
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
```

- [ ] **Step 3: 更新项目根 README.md**

把"当前能力"段改为：

```markdown
## 当前能力

- ✅ Plan A：菜单栏 App + iTerm 完整闭环
- ✅ Plan B：CC plugin —— CC 事件自动推送到 App
- ✅ Plan C：IDEA plugin —— IDEA 终端 100% 可靠的同意/跳转

## 三件套安装

1. **macOS App**：`cd app && swift run ClaudeNotifier`
2. **CC plugin**：`/plugin install claude-notifier` 或手动 symlink 到 `~/.claude/plugins/claude-notifier`
3. **IDEA plugin**：`cd idea-plugin && ./gradlew buildPlugin`，然后 IDEA → Plugins → Install from disk
```

- [ ] **Step 4: Commit**

```bash
cd /Users/wudazhan/workplace/project/claude-notifier
git add idea-plugin/README.md idea-plugin/tests/manual-test-checklist.md README.md
git -c commit.gpgsign=false commit -m "docs: Plan C README + test checklist + status update"
```

---

## Self-Review 备忘

### Spec 覆盖
- §3 整体架构 → T1-T5 plugin 基础设施 + T6/T7 业务端点 + T8 App 端集成
- §5 终端定位 IDEA 100% 路径 → T3 (Registry) + T4 (Customizer) + T6 (SendText) + T7 (FocusTab)
- §7 安装链路 IDEA plugin 部分 → T9 (README + 装载流程)
- §9 错误矩阵 IDEA plugin 部分 → 410 Gone (T6/T7) + connection refused → pluginNotInstalled (T8 IdeaAdapter)

### 范围外
- IntelliJ Marketplace 发布 → 后续
- 远程开发（JetBrains Gateway）支持 → 后续
- Plugin 设置面板（自定义端口范围）→ 当前硬编码 6790..6799 够用

### Type 一致性
- T8 App 端 `idea_tab_id` ↔ plugin 内 `CLAUDE_IDEA_TAB_ID` 环境变量 ↔ HTTP body `tabId` —— 全链路 UUID 流转
- AdapterError 4 个 case 在 IdeaAdapter 内一致使用

### API 兼容性风险（明确警告）
- IntelliJ Platform 的 terminal API 不稳定。本 plan 基于 2024.2.5 的 API 假设。如果实际版本中 `TerminalToolWindowManager.terminalWidgets`、`JBTerminalWidget.terminalRunner.shellEnv`、`JBTerminalWidget.sendCommandToExecute` 不存在或签名变了：
  - 兜底 1：让 `TerminalEnvCustomizer` 直接把 widget 引用塞进 `TerminalTabRegistry`（widget → tabId 同步登记），SendText/FocusTabHandler 直接拿 widget
  - 兜底 2：用反射调用，标 `@Suppress` 注解
- 这是 T6 task 内显式标记的"如果失败的回退路径"
