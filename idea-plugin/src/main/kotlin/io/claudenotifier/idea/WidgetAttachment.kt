package io.claudenotifier.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.content.Content
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

/**
 * 把 registry 里的 UUID 关联到正确的 Content。
 *
 * 算法优先级：
 *   1. PID-based matching：iterate Contents → 反射拿 widget.ttyConnector.process.pid() →
 *      ps eww 读 CLAUDE_IDEA_TAB_ID env → 100% 准确
 *   2. fallback: 第一个未关联的 Content（处理 PID 读不到的边角情况）
 */
object WidgetAttachment {
    private val log = Logger.getInstance(WidgetAttachment::class.java)

    /**
     * 在 EDT 上调；返回是否新关联了一个 widgetRef。
     * 优先用 PID 精确匹配。
     */
    fun tryAttachByContent(project: Project, uuid: String): Boolean {
        val registry = ApplicationManager.getApplication().getService(TerminalTabRegistry::class.java)
        if (registry.lookup(uuid)?.widgetRef != null) return true

        val twm = runCatching { TerminalToolWindowManager.getInstance(project) }.getOrNull() ?: return false
        val contents = runCatching { twm.toolWindow.contentManager.contents.toList() }.getOrNull() ?: return false
        if (contents.isEmpty()) return false

        // 算法 1：PID-based 精确匹配
        val pidToUuid = PidEnvLookup.snapshotAllClaudeTabs()
        if (pidToUuid.isNotEmpty()) {
            for (content in contents) {
                val widget = resolveWidget(content, twm) ?: continue
                val pid = PidEnvLookup.extractPid(widget) ?: continue
                val foundUuid = pidToUuid[pid] ?: continue
                if (foundUuid == uuid) {
                    // 清掉任何其他错位关联到这个 content 的 UUID
                    registry.snapshot().forEach { entry ->
                        if (entry.uuid != uuid &&
                            (entry.widgetRef === content || entry.widgetRef === widget)) {
                            registry.attachWidget(entry.uuid, null as Any?)
                        }
                    }
                    registry.attachWidget(uuid, content)
                    log.info("[ClaudeNotifier] PID-matched: uuid=$uuid → pid=$pid (Content)")
                    return true
                }
            }
        }

        // 算法 2：fallback —— 第一个未关联的 content
        val attachedRefs = registry.snapshot().mapNotNull { it.widgetRef }.toSet()
        val unattached = contents.firstOrNull { it !in attachedRefs }
        if (unattached != null) {
            registry.attachWidget(uuid, unattached)
            log.info("[ClaudeNotifier] fallback-attached (no PID match): uuid=$uuid → ${unattached.javaClass.simpleName}")
            return true
        }

        log.info("[ClaudeNotifier] tryAttachByContent: no match (uuid=$uuid, contents=${contents.size}, pid_snapshot=${pidToUuid.size})")
        return false
    }

    fun resolveWidget(widgetRef: Any?, twm: TerminalToolWindowManager): Any? {
        if (widgetRef == null) return null
        if (widgetRef !is Content) return widgetRef
        return runCatching {
            val m = twm.javaClass.methods.firstOrNull {
                it.name == "getWidgetByContent" && it.parameterCount == 1
            }
            m?.invoke(twm, widgetRef)
        }.getOrNull()
    }
}
