package io.claudenotifier.idea.server

import com.google.gson.Gson
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import io.claudenotifier.idea.ContentFinder
import io.claudenotifier.idea.WidgetAttachment
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

        // PID-based 全局查找
        val match = ContentFinder.findByUuid(tabId)
        if (match == null) {
            respond(exchange, 410, Response(false, "no terminal process has CLAUDE_IDEA_TAB_ID=$tabId (tab closed?)"))
            return
        }

        var sent = false
        var lastError: String? = null
        ApplicationManager.getApplication().invokeAndWait {
            val twm = TerminalToolWindowManager.getInstance(match.project)
            val widget = WidgetAttachment.resolveWidget(match.content, twm) ?: run {
                lastError = "cannot resolve widget from content"
                return@invokeAndWait
            }
            sent = trySendText(widget, text) { lastError = it }
        }

        if (sent) respond(exchange, 200, Response(true))
        else respond(exchange, 500, Response(false, lastError ?: "send failed"))
    }

    /** 多种 API 尝试发字符到 widget；返回是否成功 */
    private fun trySendText(widgetRef: Any, text: String, errorSink: (String) -> Unit): Boolean {
        val cleanText = text.trimEnd('\n')

        runCatching {
            val m = widgetRef.javaClass.methods.firstOrNull {
                it.name == "sendCommandToExecute" && it.parameterCount == 1 &&
                    it.parameterTypes[0] == String::class.java
            }
            if (m != null) {
                m.invoke(widgetRef, cleanText)
                return true
            }
        }.onFailure { log.warn("sendCommandToExecute failed: ${it.message}") }

        runCatching {
            val ttyMethod = widgetRef.javaClass.methods.firstOrNull {
                it.name == "getTtyConnector" && it.parameterCount == 0
            }
            val ttyConnector = ttyMethod?.invoke(widgetRef) ?: return@runCatching
            ttyConnector.javaClass.methods.firstOrNull {
                it.name == "write" && it.parameterCount == 1 && it.parameterTypes[0] == String::class.java
            }?.let {
                it.invoke(ttyConnector, cleanText + "\n")
                return true
            }
            ttyConnector.javaClass.methods.firstOrNull {
                it.name == "write" && it.parameterCount == 1 && it.parameterTypes[0] == ByteArray::class.java
            }?.let {
                it.invoke(ttyConnector, (cleanText + "\n").toByteArray(Charsets.UTF_8))
                return true
            }
        }.onFailure { log.warn("ttyConnector write failed: ${it.message}") }

        errorSink("no send-text API works on widget type ${widgetRef.javaClass.name}")
        return false
    }

    private fun respond(exchange: HttpExchange, code: Int, body: Response) {
        val json = gson.toJson(body).toByteArray()
        exchange.responseHeaders["Content-Type"] = listOf("application/json")
        exchange.sendResponseHeaders(code, json.size.toLong())
        exchange.responseBody.use { it.write(json) }
    }
}
