package io.claudenotifier.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.content.Content
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

/**
 * 把 registry 里的 UUID 关联到正确的 Content。
 *
 * 算法：
 *   1. 遍历 toolWindow.contentManager.contents
 *   2. 对每个 content，反射拿 widget.ttyConnector.process.pid()
 *   3. `ps eww -p <pid>` 读 CLAUDE_IDEA_TAB_ID env
 *   4. 匹配 target UUID 即关联
 *   5. fallback：所有 PID 都读不出时，回退"第一个未关联的 content"
 */
object WidgetAttachment {
    private val log = Logger.getInstance(WidgetAttachment::class.java)

    fun tryAttachByContent(project: Project, uuid: String): Boolean {
        val registry = ApplicationManager.getApplication().getService(TerminalTabRegistry::class.java)
        if (registry.lookup(uuid)?.widgetRef != null) return true

        val twm = runCatching { TerminalToolWindowManager.getInstance(project) }.getOrNull() ?: return false
        val contents = runCatching { twm.toolWindow.contentManager.contents.toList() }.getOrNull() ?: return false
        if (contents.isEmpty()) return false

        // 清理 stale entry：registry 里有些 widgetRef 指向已关闭的 Content
        // 这些 entry 会让 fallback "first unattached" 算法误判全占
        val currentContentSet: Set<Any> = contents.toSet()
        registry.snapshot().forEach { entry ->
            val ref = entry.widgetRef ?: return@forEach
            if (ref is Content && ref !in currentContentSet) {
                registry.attachWidget(entry.uuid, null as Any?)
            }
        }

        // 算法 1：逐 content 反射 PID → ps eww 读 env → 精确匹配
        var anyPidReadable = false
        for (content in contents) {
            val widget = resolveWidget(content, twm) ?: continue
            val pid = PidEnvLookup.extractPid(widget) ?: continue
            anyPidReadable = true
            val foundUuid = PidEnvLookup.readClaudeTabId(pid) ?: continue
            if (foundUuid == uuid) {
                // 清掉其他 UUID 错位指向这个 content/widget
                registry.snapshot().forEach { entry ->
                    if (entry.uuid != uuid &&
                        (entry.widgetRef === content || entry.widgetRef === widget)) {
                        registry.attachWidget(entry.uuid, null as Any?)
                    }
                }
                registry.attachWidget(uuid, content)
                log.info("[ClaudeNotifier] PID-matched: uuid=$uuid → pid=$pid")
                return true
            }
        }

        // 算法 2：fallback —— 第一个未关联的 content
        if (!anyPidReadable) {
            val attachedRefs = registry.snapshot().mapNotNull { it.widgetRef }.toSet()
            val unattached = contents.firstOrNull { it !in attachedRefs }
            if (unattached != null) {
                registry.attachWidget(uuid, unattached)
                log.info("[ClaudeNotifier] fallback-attached (PID extraction failed for all widgets): uuid=$uuid")
                return true
            }
        }

        log.info("[ClaudeNotifier] tryAttachByContent: no match for uuid=$uuid (contents=${contents.size}, anyPidReadable=$anyPidReadable)")
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
