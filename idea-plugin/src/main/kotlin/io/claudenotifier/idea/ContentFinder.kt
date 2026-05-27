package io.claudenotifier.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.content.Content
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

/**
 * 不依赖 registry，直接全局扫描所有 open project 的所有 terminal content，
 * 反射拿 widget.ttyConnector.process.pid()，ps eww 读 CLAUDE_IDEA_TAB_ID，
 * 跟目标 UUID 匹配。
 *
 * 适用场景：浮窗里有跨 IDE 重启的老通知，registry 不认这些 UUID，
 * 但只要终端进程还活着，PID 反查照样能找到对应 content。
 */
object ContentFinder {
    private val log = Logger.getInstance(ContentFinder::class.java)

    data class Match(val project: Project, val content: Content, val pid: Long)

    fun findByUuid(uuid: String): Match? {
        var result: Match? = null
        ApplicationManager.getApplication().invokeAndWait {
            for (project in ProjectManager.getInstance().openProjects) {
                if (project.isDisposed) continue
                val twm = runCatching { TerminalToolWindowManager.getInstance(project) }.getOrNull() ?: continue
                val contents = runCatching { twm.toolWindow.contentManager.contents.toList() }.getOrNull() ?: continue
                for (content in contents) {
                    val widget = WidgetAttachment.resolveWidget(content, twm) ?: continue
                    val pid = PidEnvLookup.extractPid(widget) ?: continue
                    val foundUuid = PidEnvLookup.readClaudeTabId(pid) ?: continue
                    if (foundUuid == uuid) {
                        result = Match(project, content, pid)
                        return@invokeAndWait
                    }
                }
            }
        }
        if (result == null) {
            log.info("[ClaudeNotifier] findByUuid: no terminal process has CLAUDE_IDEA_TAB_ID=$uuid")
        } else {
            log.info("[ClaudeNotifier] findByUuid: matched uuid=$uuid → pid=${result!!.pid}")
        }
        return result
    }
}
