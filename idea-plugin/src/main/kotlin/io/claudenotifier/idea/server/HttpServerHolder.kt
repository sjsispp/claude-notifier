package io.claudenotifier.idea.server

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors

object HttpServerHolder {
    private val log = Logger.getInstance(HttpServerHolder::class.java)
    private var server: HttpServer? = null
    private var disposable: Disposable? = null

    /**
     * 启动 HTTP server。端口冲突 → 抛弃 (不再 fallback 到其他端口，
     * 因为 App 端硬编码 6790)。如果 6790 被占，说明有 zombie，需要 IDE 重启。
     *
     * 同时把 stop() 注册到 IDE 的 Disposer，plugin unload / IDE 退出时
     * 自动 stop server，避免 zombie。
     */
    @Synchronized
    fun start() {
        if (server != null) return

        try {
            val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 6790), 0)
            srv.executor = Executors.newFixedThreadPool(4)
            srv.createContext("/healthz", HealthzHandler())
            srv.createContext("/sendText", SendTextHandler())
            srv.createContext("/focusTab", FocusTabHandler())
            srv.start()
            server = srv

            // 注册 disposable，plugin unload / IDE 退出时调 stop()，避免 zombie 占住 :6790
            val d = Disposable { stop() }
            disposable = d
            Disposer.register(ApplicationManager.getApplication(), d)

            log.info("[ClaudeNotifier] HTTP server listening on :6790")
        } catch (e: Exception) {
            log.warn("[ClaudeNotifier] FAILED to bind :6790: ${e.message}. " +
                "Likely a zombie HTTP server from a previous plugin reload. " +
                "Please FULLY RESTART IDE (⌘Q + reopen, not disable/enable plugin).")
        }
    }

    @Synchronized
    fun stop() {
        server?.stop(0)
        server = null
        disposable?.let {
            try { Disposer.dispose(it) } catch (_: Throwable) {}
        }
        disposable = null
        log.info("[ClaudeNotifier] HTTP server stopped")
    }

    fun isRunning(): Boolean = server != null
}
