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
