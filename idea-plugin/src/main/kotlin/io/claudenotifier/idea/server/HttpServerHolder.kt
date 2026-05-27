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
