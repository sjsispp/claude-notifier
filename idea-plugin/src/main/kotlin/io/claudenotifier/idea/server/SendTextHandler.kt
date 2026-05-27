package io.claudenotifier.idea.server

import com.google.gson.Gson
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.ProjectManager
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import io.claudenotifier.idea.TerminalTabRegistry
import org.jetbrains.plugins.terminal.ShellTerminalWidget
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
        val req = try {
            gson.fromJson(bodyStr, Request::class.java)
        } catch (e: Exception) {
            respond(exchange, 400, Response(false, "invalid json")); return
        }

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
                val shell = runCatching { ShellTerminalWidget.asShellJediTermWidget(widget) }.getOrNull()
                val env = runCatching { shell?.startupOptions?.envVariables }.getOrNull() ?: continue
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
