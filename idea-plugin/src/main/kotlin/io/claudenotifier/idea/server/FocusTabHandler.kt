package io.claudenotifier.idea.server

import com.google.gson.Gson
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.wm.WindowManager
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import io.claudenotifier.idea.ContentFinder
import io.claudenotifier.idea.IdeActivator
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

        // PID-based 全局查找：不依赖 registry，扫所有 open project 的 content
        val match = ContentFinder.findByUuid(tabId)
        if (match == null) {
            respond(exchange, 410, Response(false, "no terminal process has CLAUDE_IDEA_TAB_ID=$tabId (tab closed?)"))
            return
        }

        // 先 osascript 激活 IDE app（绕过 macOS 焦点抢占保护）
        IdeActivator.activateIdeApp()

        ApplicationManager.getApplication().invokeAndWait {
            // 提升对应 project 的窗口
            val frame = WindowManager.getInstance().getFrame(match.project)
            frame?.toFront()
            frame?.requestFocus()

            // 显示 Terminal tool window 并选中目标 content
            val twm = TerminalToolWindowManager.getInstance(match.project)
            twm.toolWindow.show {}
            twm.toolWindow.contentManager.setSelectedContent(match.content, true)
        }

        respond(exchange, 200, Response(true))
    }

    private fun respond(exchange: HttpExchange, code: Int, body: Response) {
        val json = gson.toJson(body).toByteArray()
        exchange.responseHeaders["Content-Type"] = listOf("application/json")
        exchange.sendResponseHeaders(code, json.size.toLong())
        exchange.responseBody.use { it.write(json) }
    }
}
