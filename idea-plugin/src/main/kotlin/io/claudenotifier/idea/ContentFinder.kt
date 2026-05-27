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
                    // 先看 PID 本身的 env
                    val directUuid = PidEnvLookup.readClaudeTabId(pid)
                    if (directUuid == uuid) {
                        result = Match(project, content, pid)
                        return@invokeAndWait
                    }
                    // 再看 PID 所有子孙进程的 env（zsh 可能 unset 了，但 CC 子进程还有）
                    val descendantUuids = PidEnvLookup.readClaudeTabIdRecursive(pid)
                    if (uuid in descendantUuids) {
                        log.info("[ClaudeNotifier] matched uuid=$uuid via descendant of pid=$pid")
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

    /** 多条路径找 Content 对应的 PID */
    fun extractPidFromContent(content: Content, twm: TerminalToolWindowManager): Long? {
        diag(content, twm)

        // 路径 A1/A2: findWidgetByContent / getWidgetByContent（reworked 都返回 null，但还是 try）
        for (methodName in listOf("findWidgetByContent", "getWidgetByContent")) {
            runCatching {
                val m = twm.javaClass.methods.firstOrNull {
                    it.name == methodName && it.parameterCount == 1
                }
                val widget = m?.invoke(twm, content)
                if (widget != null) {
                    diagWidget(widget)
                    PidEnvLookup.extractPid(widget)?.let { return it }
                }
            }
        }

        // 路径 B: content.component 是 TerminalToolWindowPanel，递归扫它的整个 swing 子树
        val comp = content.component
        if (comp != null) {
            walkComponentTree(comp, depth = 0)?.let { return it }
        }

        return null
    }

    /** 递归扫 swing 组件树，对每个 component 试 extractPid */
    private fun walkComponentTree(comp: Any, depth: Int): Long? {
        if (depth > 6) return null

        diagTreeNode(comp, depth)

        // 试这个 component 本身
        PidEnvLookup.extractPid(comp)?.let { return it }

        // 关键：如果是内部类（含 $），挖出 this$0 拿外部类实例
        if (comp.javaClass.name.contains("$")) {
            runCatching {
                val outerField = comp.javaClass.declaredFields.firstOrNull { it.name == "this\$0" }
                if (outerField != null) {
                    outerField.isAccessible = true
                    val outer = outerField.get(comp)
                    if (outer != null) {
                        diagOuter(outer, depth)
                        PidEnvLookup.extractPid(outer)?.let { return it }
                        // 也走 outer 的 getter
                        walkObjectGetters(outer, depth + 1)?.let { return it }
                    }
                }
            }
        }

        // 递归子组件
        if (comp is java.awt.Container) {
            try {
                for (child in comp.components) {
                    walkComponentTree(child, depth + 1)?.let { return it }
                }
            } catch (_: Throwable) {}
        }

        walkObjectGetters(comp, depth)?.let { return it }
        return null
    }

    private fun walkObjectGetters(obj: Any, depth: Int): Long? {
        for (m in obj.javaClass.methods) {
            if (m.parameterCount != 0) continue
            val name = m.name
            if (name !in setOf("getTerminalWidget", "getWidget", "getSession",
                    "getTerminalSession", "getModel", "getController", "getPanel",
                    "getTerminalPanel", "getView", "getJBTerminalWidget",
                    "getActiveOutputModel", "getOutputModel", "getBackend",
                    "getTtyConnector", "getProcess", "getShellSession",
                    "getRunner", "getEditor")) continue
            runCatching {
                val v = m.invoke(obj) ?: return@runCatching
                if (v is Process) return v.pid()
                PidEnvLookup.extractPid(v)?.let { return it }
                if (depth < 6) walkObjectGetters(v, depth + 1)?.let { return it }
            }
        }
        return null
    }

    @Volatile private var diagOuterDone: MutableSet<String> = mutableSetOf()

    private fun diagOuter(obj: Any, depth: Int) {
        val cls = obj.javaClass.name
        if (diagOuterDone.add(cls)) {
            val methods = obj.javaClass.methods
                .filter { it.parameterCount == 0 && it.returnType != Void.TYPE }
                .map { "${it.name}:${it.returnType.simpleName}" }
                .sorted().take(50).joinToString(", ")
            log.info("[ClaudeNotifier] OUTER depth=$depth cls=$cls methods=[$methods]")
        }
    }

    @Volatile private var diagTreeDone: MutableSet<String> = mutableSetOf()

    private fun diagTreeNode(obj: Any, depth: Int) {
        val cls = obj.javaClass.name
        // 优先 log terminal 相关的
        if (cls.contains("erminal", ignoreCase = false) || cls.contains("Jedi", ignoreCase = false) || cls.contains("Block", ignoreCase = false)) {
            if (diagTreeDone.add(cls)) {
                val indent = "  ".repeat(depth)
                val methodNames = obj.javaClass.methods
                    .filter { it.parameterCount == 0 && it.returnType != Void.TYPE }
                    .map { "${it.name}:${it.returnType.simpleName}" }
                    .sorted().take(40).joinToString(", ")
                log.info("[ClaudeNotifier] TREE depth=$depth ${indent}cls=$cls methods=[$methodNames]")
            }
        }
    }

    @Volatile private var diagWidgetDone: MutableSet<String> = mutableSetOf()

    private fun diagWidget(widget: Any) {
        val cls = widget.javaClass.name
        if (diagWidgetDone.add(cls)) {
            val methods = widget.javaClass.methods
                .filter { it.parameterCount == 0 && it.returnType != Void.TYPE }
                .map { "${it.name}:${it.returnType.simpleName}" }
                .sorted().take(60).joinToString(", ")
            log.info("[ClaudeNotifier] DIAG TerminalWidget=$cls methods=[$methods]")
        }
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
