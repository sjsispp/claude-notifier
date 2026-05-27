package io.claudenotifier.idea.server

import com.google.gson.Gson
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.WindowManager
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import io.claudenotifier.idea.TerminalTabRegistry
import org.jetbrains.plugins.terminal.ShellTerminalWidget
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
                val shell = runCatching { ShellTerminalWidget.asShellJediTermWidget(widget) }.getOrNull()
                val env = runCatching { shell?.startupOptions?.envVariables }.getOrNull() ?: continue
                if (env["CLAUDE_IDEA_TAB_ID"] == tabId) {
                    val container = runCatching { twm.getContainer(widget) }.getOrNull()
                    val content = container?.content
                    if (content != null) {
                        twm.toolWindow.contentManager.setSelectedContent(content, true)
                        widget.requestFocus()
                        ok = true
                    } else {
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
