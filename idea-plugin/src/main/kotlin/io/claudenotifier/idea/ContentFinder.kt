package io.claudenotifier.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.content.Content
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

/**
 * 不依赖 registry，直接全局扫描所有 open project 的所有 terminal content，
 * 反射拿每个 widget 的 PID，ps eww 读 CLAUDE_IDEA_TAB_ID，匹配。
 *
 * 多条路径找 PID：从 Content 直接走，从 resolveWidget 走，从 component 走。
 */
object ContentFinder {
    private val log = Logger.getInstance(ContentFinder::class.java)
    @Volatile private var diagDone: MutableSet<String> = mutableSetOf()

    data class Match(val project: Project, val content: Content, val pid: Long)

    fun findByUuid(uuid: String): Match? {
        var result: Match? = null
        ApplicationManager.getApplication().invokeAndWait {
            for (project in ProjectManager.getInstance().openProjects) {
                if (project.isDisposed) continue
                val twm = runCatching { TerminalToolWindowManager.getInstance(project) }.getOrNull() ?: continue
                val contents = runCatching { twm.toolWindow.contentManager.contents.toList() }.getOrNull() ?: continue
                for (content in contents) {
                    val pid = extractPidFromContent(content, twm) ?: continue
                    val foundUuid = PidEnvLookup.readClaudeTabId(pid) ?: continue
                    if (foundUuid == uuid) {
                        result = Match(project, content, pid)
                        return@invokeAndWait
                    }
                }
            }
        }
        if (result == null) {
            log.info("[ClaudeNotifier] findByUuid($uuid): not found")
        } else {
            log.info("[ClaudeNotifier] findByUuid($uuid): matched pid=${result!!.pid}")
        }
        return result
    }

    /** 三条路径找 Content 对应的 PID */
    fun extractPidFromContent(content: Content, twm: TerminalToolWindowManager): Long? {
        // 一次性诊断 log：content 类、component 类、widget 类
        diag(content, twm)

        // 路径 A: 经 resolveWidget（twm.getWidgetByContent）
        val widget = runCatching {
            val m = twm.javaClass.methods.firstOrNull {
                it.name == "getWidgetByContent" && it.parameterCount == 1
            }
            m?.invoke(twm, content)
        }.getOrNull()
        if (widget != null) {
            PidEnvLookup.extractPid(widget)?.let { return it }
        }

        // 路径 B: content.component（Swing 组件本身可能就是 widget）
        val comp = content.component
        if (comp != null) {
            PidEnvLookup.extractPid(comp)?.let { return it }
        }

        // 路径 C: content.preferredFocusableComponent
        val pfc = runCatching { content.preferredFocusableComponent }.getOrNull()
        if (pfc != null && pfc !== comp) {
            PidEnvLookup.extractPid(pfc)?.let { return it }
        }

        return null
    }

    private fun diag(content: Content, twm: TerminalToolWindowManager) {
        val contentCls = content.javaClass.name
        val compCls = content.component?.javaClass?.name ?: "null"
        val widgetCls = runCatching {
            val m = twm.javaClass.methods.firstOrNull {
                it.name == "getWidgetByContent" && it.parameterCount == 1
            }
            m?.invoke(twm, content)?.javaClass?.name
        }.getOrNull() ?: "null"
        val key = "$contentCls|$compCls|$widgetCls"
        if (diagDone.add(key)) {
            log.info("[ClaudeNotifier] DIAG content=$contentCls component=$compCls widget=$widgetCls")
            // 也 dump twm 方法名
            val twmMethods = twm.javaClass.methods
                .filter { it.parameterCount == 1 && it.parameterTypes[0] == Content::class.java }
                .map { "${it.name}():${it.returnType.simpleName}" }
                .sorted().take(15).joinToString(", ")
            log.info("[ClaudeNotifier] DIAG twm methods taking Content: [$twmMethods]")
        }
    }
}
