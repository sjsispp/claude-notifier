package io.claudenotifier.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.content.Content
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

/**
 * 把 registry 里某个 UUID 关联到一个尚未被任何 UUID 占用的 Content。
 *
 * 为啥用 Content 不用 widget：reworked terminal (IntelliJ 2024.2+) 的
 * `twm.terminalWidgets` 经常返回空集，但 `toolWindow.contentManager.contents`
 * 总有所有 tab。Content 是 tool window 里 tab 的通用抽象。
 *
 * 选 "第一个未关联的" 是启发式：用户串行开 tab 时基本对；
 * 极端并发下可能错位（但仍能 send/focus，只是发到错的 tab）。
 */
object WidgetAttachment {
    private val log = Logger.getInstance(WidgetAttachment::class.java)

    /** 在 EDT 上调；返回是否新关联了一个 widgetRef */
    fun tryAttachByContent(project: Project, uuid: String): Boolean {
        val registry = ApplicationManager.getApplication().getService(TerminalTabRegistry::class.java)
        if (registry.lookup(uuid)?.widgetRef != null) return true  // 已被关联，不动

        val twm = runCatching { TerminalToolWindowManager.getInstance(project) }.getOrNull() ?: return false
        val contents = runCatching { twm.toolWindow.contentManager.contents.toList() }.getOrNull() ?: return false
        if (contents.isEmpty()) return false

        val attachedRefs = registry.snapshot().mapNotNull { it.widgetRef }.toSet()
        val unattached = contents.firstOrNull { it !in attachedRefs }
        if (unattached != null) {
            registry.attachWidget(uuid, unattached)
            log.info("[ClaudeNotifier] lazy-attached content for uuid=$uuid (${unattached.javaClass.simpleName})")
            return true
        }
        log.info("[ClaudeNotifier] tryAttachByContent: no unattached content (uuid=$uuid, total=${contents.size}, attached=${attachedRefs.size})")
        return false
    }

    /**
     * Content -> Widget 转换（用于 SendText）。
     * 如果 widgetRef 本身已是 widget，直接返回；如果是 Content，通过 getWidgetByContent 转。
     */
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
