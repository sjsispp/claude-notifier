package io.claudenotifier.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.terminal.LocalTerminalCustomizer

/**
 * 在每个新终端 tab 创建时注入 CLAUDE_IDEA_TAB_ID 环境变量。
 *
 * Widget 关联走两条路：
 * 1. PendingTabUuids + ContentManagerListener（老式 terminal 通用）
 * 2. 后台 polling 扫描 widget 集合，找新增的 widget 关联（reworked terminal 兜底）
 */
class TerminalEnvCustomizer : LocalTerminalCustomizer() {
    private val log = Logger.getInstance(TerminalEnvCustomizer::class.java)

    override fun customizeCommandAndEnvironment(
        project: Project,
        workingDirectory: String?,
        command: Array<out String>,
        envs: MutableMap<String, String>
    ): Array<out String> {
        val registry = ApplicationManager.getApplication().getService(TerminalTabRegistry::class.java)
        val uuid = registry.register(
            projectName = project.name,
            projectPath = project.basePath ?: workingDirectory ?: ""
        )
        envs["CLAUDE_IDEA_TAB_ID"] = uuid
        PendingTabUuids.enqueue(uuid)

        // 后台扫描兜底：reworked terminal 不触发 ContentManagerListener，
        // 而是通过 LocalBlockTerminalRunner 直接管理。我们扫 terminalWidgets，
        // 把"集合里没人关联过"的新 widget 关联给本次 UUID。
        scheduleWidgetAttachment(project, uuid)

        return command
    }

    private fun scheduleWidgetAttachment(project: Project, uuid: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            // Reworked terminal 的 contentAdded 实测延迟 5-15 秒，所以扫 30 秒
            for (attempt in 1..150) {
                Thread.sleep(200)
                if (project.isDisposed) return@executeOnPooledThread
                var attached = false
                ApplicationManager.getApplication().invokeAndWait {
                    attached = WidgetAttachment.tryAttachByContent(project, uuid)
                }
                if (attached) {
                    if (attempt > 1) {
                        log.info("[ClaudeNotifier] polling-attached for uuid=$uuid at attempt=$attempt")
                    }
                    return@executeOnPooledThread
                }
            }
            log.warn("[ClaudeNotifier] polling gave up for uuid=$uuid after 30s; handler will lazy-attach on demand")
        }
    }
}
