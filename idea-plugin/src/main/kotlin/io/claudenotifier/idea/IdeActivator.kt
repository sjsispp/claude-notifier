package io.claudenotifier.idea

import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.diagnostic.Logger

/**
 * macOS 上 Java JVM 后台进程调 `frame.toFront()` 会被系统拒绝（焦点抢占保护）。
 * 但 `osascript -e 'tell application "X" to activate'` 是高层用户动作，macOS 允许。
 *
 * 我们 fork 一个 osascript 子进程让它代我们激活当前 IDE 应用。
 */
object IdeActivator {
    private val log = Logger.getInstance(IdeActivator::class.java)

    @Volatile private var cachedAppName: String? = null

    fun activateIdeApp() {
        val appName = currentIdeAppName()
        try {
            val cmd = listOf("osascript", "-e", "tell application \"$appName\" to activate")
            ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()
            // 不 waitFor —— 不阻塞调用方
        } catch (e: Throwable) {
            log.warn("[ClaudeNotifier] osascript activate failed: ${e.message}")
        }
    }

    private fun currentIdeAppName(): String {
        cachedAppName?.let { return it }
        // ApplicationNamesInfo.fullProductName e.g. "IntelliJ IDEA", "GoLand", "PyCharm Community Edition"
        // AppleScript "tell application" 接受 .app bundle 名（也通常等于 fullProductName）
        val name = ApplicationNamesInfo.getInstance().fullProductName
        cachedAppName = name
        return name
    }
}
