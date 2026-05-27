package io.claudenotifier.idea

import com.intellij.openapi.diagnostic.Logger

/**
 * 反射拿 widget 对应 PTY 进程的 PID。Reworked terminal 不暴露 ttyConnector，
 * 所以走多条反射路径，并在所有都失败时落到"进程树扫描"兜底。
 */
object PidEnvLookup {
    private val log = Logger.getInstance(PidEnvLookup::class.java)

    @Volatile private var loggedTypes: MutableSet<String> = mutableSetOf()

    fun extractPid(widget: Any?): Long? {
        if (widget == null) return null
        val clsName = widget.javaClass.name

        // 诊断：每个新类只 log 一次方法列表
        if (loggedTypes.add(clsName)) {
            val methodNames = widget.javaClass.methods
                .filter { it.parameterCount == 0 && it.returnType != Void.TYPE }
                .map { "${it.name}():${it.returnType.simpleName}" }
                .sorted()
                .take(40)
                .joinToString(", ")
            log.info("[ClaudeNotifier] widget type=$clsName methods=[$methodNames]")
        }

        // 路径 1: 经典 getTtyConnector，含 BackendTtyConnector unwrap
        runCatching {
            val getTty = widget.javaClass.methods.firstOrNull {
                it.name == "getTtyConnector" && it.parameterCount == 0
            } ?: return@runCatching
            var ttyConn = getTty.invoke(widget) ?: return@runCatching

            // IntelliJ 2026.1 即使在 classic 下也用 Remote Dev 架构，
            // ttyConnector 是 BackendTtyConnector 代理，含 getConnector() 包装真正的 connector
            // 最多剥 3 层避免死循环
            for (unwrapDepth in 0..3) {
                val proc = runCatching {
                    val getProc = ttyConn.javaClass.methods.firstOrNull {
                        it.name == "getProcess" && it.parameterCount == 0
                    }
                    getProc?.invoke(ttyConn) as? Process
                }.getOrNull()
                if (proc != null) {
                    log.info("[ClaudeNotifier] extractPid via getTtyConnector (unwrap depth=$unwrapDepth, class=${ttyConn.javaClass.simpleName}): pid=${proc.pid()}")
                    return proc.pid()
                }
                // 没有 getProcess，try unwrap one layer
                val inner = runCatching {
                    ttyConn.javaClass.methods.firstOrNull {
                        it.name == "getConnector" && it.parameterCount == 0
                    }?.invoke(ttyConn)
                }.getOrNull() ?: break
                if (inner === ttyConn) break
                ttyConn = inner
            }
        }

        // 路径 2: reworked terminal 可能用 getSession / getModel / getController
        for (fieldName in listOf("getSession", "getModel", "getController", "getView", "getTerminalSession")) {
            runCatching {
                val m = widget.javaClass.methods.firstOrNull {
                    it.name == fieldName && it.parameterCount == 0
                } ?: return@runCatching
                val inner = m.invoke(widget) ?: return@runCatching
                val pid = walkForPid(inner, depth = 0)
                if (pid != null) return pid
            }
        }

        // 路径 3: 在 widget 上递归走方法找
        val pid = walkForPid(widget, depth = 0)
        if (pid != null) return pid

        return null
    }

    /** 在对象上递归找 Process 类型；最多 2 层深 */
    private fun walkForPid(obj: Any?, depth: Int): Long? {
        if (obj == null || depth > 2) return null

        // 直接是 Process
        if (obj is Process) {
            return runCatching { obj.pid() }.getOrNull()
        }

        for (m in obj.javaClass.methods) {
            if (m.parameterCount != 0) continue
            val name = m.name
            if (!name.startsWith("get")) continue
            // 只对感兴趣的方法递归
            if (name !in setOf("getTtyConnector", "getProcess", "getSession",
                    "getModel", "getController", "getController",
                    "getJBTerminalWidget", "getTerminalWidget", "getDelegate")) continue
            runCatching {
                val v = m.invoke(obj) ?: return@runCatching
                if (v is Process) {
                    val pid = v.pid()
                    log.info("[ClaudeNotifier] PID via depth=$depth path=${name}: $pid")
                    return pid
                }
                walkForPid(v, depth + 1)?.let { return it }
            }
        }
        return null
    }

    /** ps eww -p <pid>，从输出里抠 CLAUDE_IDEA_TAB_ID 的值 */
    fun readClaudeTabId(pid: Long): String? {
        return runCatching {
            val pb = ProcessBuilder("ps", "eww", "-p", pid.toString())
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val finished = proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return@runCatching null
            }
            val output = proc.inputStream.bufferedReader().readText()
            val token = output.split(Regex("\\s+")).firstOrNull {
                it.startsWith("CLAUDE_IDEA_TAB_ID=")
            } ?: return@runCatching null
            token.substringAfter("=")
        }.getOrNull()
    }

    /**
     * 读 PID 和它所有递归子孙进程的 CLAUDE_IDEA_TAB_ID。
     * 用于：IDE spawn 的 zsh 自己可能被 unset 了 env，但 CC 子进程有。
     */
    fun readClaudeTabIdRecursive(rootPid: Long): Set<String> {
        val result = mutableSetOf<String>()
        val toVisit = mutableListOf(rootPid)
        val visited = mutableSetOf<Long>()

        // 先建 ppid → children 映射（一次 ps，避免每个 PID 都调）
        val children = runCatching {
            val pb = ProcessBuilder("ps", "-Ao", "pid=,ppid=")
            pb.redirectErrorStream(true)
            val proc = pb.start()
            proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            val map = mutableMapOf<Long, MutableList<Long>>()
            proc.inputStream.bufferedReader().lineSequence().forEach { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size < 2) return@forEach
                val pid = parts[0].toLongOrNull() ?: return@forEach
                val ppid = parts[1].toLongOrNull() ?: return@forEach
                map.getOrPut(ppid) { mutableListOf() }.add(pid)
            }
            map
        }.getOrNull() ?: emptyMap()

        while (toVisit.isNotEmpty()) {
            val pid = toVisit.removeAt(0)
            if (!visited.add(pid)) continue
            if (visited.size > 50) break  // 防爆
            readClaudeTabId(pid)?.let { result.add(it) }
            children[pid]?.let { toVisit.addAll(it) }
        }
        return result
    }

    /**
     * 反向查找：ps 找所有有 CLAUDE_IDEA_TAB_ID=$uuid 的进程，沿 ppid 向上走，
     * 直到找到某 zsh/bash 进程（IDEA 直接 spawn 的 terminal shell）。
     * 返回该 shell PID。
     */
    fun findShellPidByUuid(uuid: String): Long? {
        return runCatching {
            // ps -A 找所有进程 + env
            val pb = ProcessBuilder("ps", "-Ao", "pid=,ppid=,command=")
            pb.redirectErrorStream(true)
            val proc = pb.start()
            proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            val all = proc.inputStream.bufferedReader().readText()
            val procMap = mutableMapOf<Long, Pair<Long, String>>()  // pid → (ppid, comm)
            for (line in all.lines()) {
                val parts = line.trim().split(Regex("\\s+"), limit = 3)
                if (parts.size < 3) continue
                val pid = parts[0].toLongOrNull() ?: continue
                val ppid = parts[1].toLongOrNull() ?: continue
                procMap[pid] = ppid to parts[2]
            }
            // 找所有匹配 uuid 的 PID
            val matchingPids = mutableListOf<Long>()
            for (pid in procMap.keys) {
                val tabId = readClaudeTabId(pid)
                if (tabId == uuid) matchingPids.add(pid)
            }
            // 沿 ppid 走，找到 /bin/zsh 或 /bin/bash
            for (startPid in matchingPids) {
                var cur = startPid
                for (hop in 0..10) {
                    val (ppid, _) = procMap[cur] ?: break
                    val (_, comm) = procMap[ppid] ?: break
                    if (comm.contains("/bin/zsh") || comm.contains("/bin/bash")) {
                        return ppid  // 这是 IDEA spawn 的 shell
                    }
                    cur = ppid
                    if (ppid <= 1L) break
                }
            }
            null
        }.getOrNull()
    }
}
