package io.claudenotifier.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.terminal.LocalTerminalCustomizer
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

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
            // 给 IDE 创建 widget 一点时间
            for (attempt in 1..20) {
                Thread.sleep(150)
                if (tryAttach(project, uuid)) {
                    log.info("[ClaudeNotifier] polling-attached widget for uuid=$uuid (attempt=$attempt)")
                    return@executeOnPooledThread
                }
            }
            log.warn("[ClaudeNotifier] polling failed to attach widget for uuid=$uuid after 20 attempts")
        }
    }

    private fun tryAttach(project: Project, uuid: String): Boolean {
        if (project.isDisposed) return false
        val registry = ApplicationManager.getApplication().getService(TerminalTabRegistry::class.java)
        if (registry.lookup(uuid)?.widgetRef != null) return true  // 已被 ContentManagerListener 抢先关联

        var attached = false
        ApplicationManager.getApplication().invokeAndWait {
            val twm = try {
                TerminalToolWindowManager.getInstance(project)
            } catch (e: Throwable) {
                return@invokeAndWait
            }
            val widgets = try {
                twm.terminalWidgets
            } catch (e: Throwable) {
                return@invokeAndWait
            }
            if (widgets.isEmpty()) return@invokeAndWait

            val attachedRefs = registry.snapshot().mapNotNull { it.widgetRef }.toSet()
            val unattached = widgets.firstOrNull { it !in attachedRefs }
            if (unattached != null) {
                registry.attachWidget(uuid, unattached)
                attached = true
            }
        }
        return attached
    }
}
