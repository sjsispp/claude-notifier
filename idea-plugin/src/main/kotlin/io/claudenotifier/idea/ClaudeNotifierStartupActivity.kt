package io.claudenotifier.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import io.claudenotifier.idea.server.HttpServerHolder
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

class ClaudeNotifierStartupActivity : ProjectActivity {
    private val log = Logger.getInstance(ClaudeNotifierStartupActivity::class.java)

    override suspend fun execute(project: Project) {
        // 全 App 第一次打开任意 project 时启动 server（之后所有 project 共用）
        HttpServerHolder.start()

        // 为该 project 的 Terminal tool window 挂 ContentManagerListener，
        // 在新 tab 创建时把 widget 引用关联进 registry（避开反查 env 的 reworked terminal 问题）。
        ApplicationManager.getApplication().invokeLater {
            try {
                val twm = TerminalToolWindowManager.getInstance(project)
                val contentManager = twm.toolWindow.contentManager
                contentManager.addContentManagerListener(object : ContentManagerListener {
                    override fun contentAdded(event: ContentManagerEvent) {
                        val uuid = PendingTabUuids.pollNext()
                        if (uuid == null) {
                            log.info("[ClaudeNotifier] contentAdded but no pending UUID")
                            return
                        }
                        // 反射拿 widget：TerminalToolWindowManager.getWidgetByContent(content)
                        val widget = runCatching {
                            val m = twm.javaClass.methods.firstOrNull {
                                it.name == "getWidgetByContent" && it.parameterCount == 1
                            }
                            m?.invoke(twm, event.content)
                        }.getOrNull()
                        val ref: Any = widget ?: event.content
                        val registry = ApplicationManager.getApplication()
                            .getService(TerminalTabRegistry::class.java)
                        registry.attachWidget(uuid, ref)
                        log.info("[ClaudeNotifier] attached widget for uuid=$uuid (ref=${ref.javaClass.simpleName})")
                    }
                })
                log.info("[ClaudeNotifier] ContentManagerListener registered for project=${project.name}")
            } catch (e: Throwable) {
                log.warn("[ClaudeNotifier] failed to register ContentManagerListener: ${e.message}")
            }
        }
    }
}
