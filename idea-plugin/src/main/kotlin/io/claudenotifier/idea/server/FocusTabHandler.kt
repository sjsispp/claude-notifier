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
