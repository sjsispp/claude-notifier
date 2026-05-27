package io.claudenotifier.idea.server

import com.google.gson.Gson
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.content.Content
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import io.claudenotifier.idea.IdeActivator
import io.claudenotifier.idea.TerminalTabRegistry
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

class FocusTabHandler : HttpHandler {
    private val log = Logger.getInstance(FocusTabHandler::class.java)
    private val gson = Gson()

    data class Request(val tabId: String?)
    data class Response(val ok: Boolean, val error: String? = null)

    override fun handle(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") {
            respond(exchange, 405, Response(false, "method not allowed")); return
        }
        val bodyStr = exchange.requestBody.bufferedReader().use { it.readText() }
        val req = try {
            gson.fromJson(bodyStr, Request::class.java)
        } catch (e: Exception) {
            respond(exchange, 400, Response(false, "invalid json")); return
        }

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

        // 关键：先 fork osascript activate IDE app —— Java JVM 后台进程的
        // frame.toFront() 在 macOS 上被焦点抢占保护拦截。osascript 是高层
        // 用户动作，可以绕过限制把 IDE 窗口拉到前台。
        IdeActivator.activateIdeApp()

        var ok = false
        ApplicationManager.getApplication().invokeAndWait {
            // 1. 提升 IDE 窗口（osascript 主力，这里是兜底）
            val frame = WindowManager.getInstance().getFrame(project)
            frame?.toFront()
            frame?.requestFocus()

            // 2. 显示 Terminal tool window
            val twm = TerminalToolWindowManager.getInstance(project)
            twm.toolWindow.show {}

            // 3. 选中匹配的 tab —— 通过 registry 拿 widgetRef，反射查找它对应的 Content
            val widgetRef = entry.widgetRef
            val targetContent: Content? = findContent(widgetRef, twm)

            if (targetContent != null && twm.toolWindow.contentManager.contents.contains(targetContent)) {
                twm.toolWindow.contentManager.setSelectedContent(targetContent, true)
                ok = true
            } else {
                // 兜底：只激活 tool window，无法精准选中具体 tab
                log.info("[ClaudeNotifier] focusTab: widget not yet attached (uuid=$tabId), tool window activated only")
                ok = true
            }
        }

        if (ok) respond(exchange, 200, Response(true))
        else respond(exchange, 500, Response(false, "focus failed"))
    }

    /** 反射方式查找 widgetRef 对应的 Content（避免直接 import 不稳定的 widget 类型） */
    private fun findContent(widgetRef: Any?, twm: TerminalToolWindowManager): Content? {
        if (widgetRef == null) return null
        if (widgetRef is Content) return widgetRef
        // 反射尝试 twm.getContainer(widget).getContent()
        return runCatching {
            val getContainer = twm.javaClass.methods.firstOrNull {
                it.name == "getContainer" && it.parameterCount == 1
            } ?: return@runCatching null
            val container = getContainer.invoke(twm, widgetRef) ?: return@runCatching null
            val getContent = container.javaClass.methods.firstOrNull {
                it.name == "getContent" && it.parameterCount == 0
            } ?: return@runCatching null
            getContent.invoke(container) as? Content
        }.getOrNull()
    }

    private fun respond(exchange: HttpExchange, code: Int, body: Response) {
        val json = gson.toJson(body).toByteArray()
        exchange.responseHeaders["Content-Type"] = listOf("application/json")
        exchange.sendResponseHeaders(code, json.size.toLong())
        exchange.responseBody.use { it.write(json) }
    }
}
