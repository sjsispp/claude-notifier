package io.claudenotifier.idea

import com.intellij.openapi.diagnostic.Logger

/**
 * 通过 PID 读进程 env，找哪个终端进程的 CLAUDE_IDEA_TAB_ID 等于目标 UUID。
 *
 * 比"第一个未关联的 Content"靠谱 N 倍——env 是注入到 PTY 子进程的，
 * 用 `ps eww` 能精确读到，不会错配。
 */
object PidEnvLookup {
    private val log = Logger.getInstance(PidEnvLookup::class.java)

    /** 反射尝试从 widget 拿 ttyConnector.process.pid() */
    fun extractPid(widget: Any?): Long? {
        if (widget == null) return null
        return runCatching {
            // 1. widget.getTtyConnector()
            val getTty = widget.javaClass.methods.firstOrNull {
                it.name == "getTtyConnector" && it.parameterCount == 0
            } ?: return@runCatching null
            val ttyConn = getTty.invoke(widget) ?: return@runCatching null

            // 2. ttyConnector.getProcess() —— PtyProcessTtyConnector 有
            val getProc = ttyConn.javaClass.methods.firstOrNull {
                it.name == "getProcess" && it.parameterCount == 0
            } ?: return@runCatching null
            val process = getProc.invoke(ttyConn) as? Process ?: return@runCatching null

            process.pid()
        }.getOrNull()
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
            // 在所有空格切分后找 CLAUDE_IDEA_TAB_ID=<value>
            val token = output.split(Regex("\\s+")).firstOrNull {
                it.startsWith("CLAUDE_IDEA_TAB_ID=")
            } ?: return@runCatching null
            token.substringAfter("=")
        }.getOrNull()
    }

    /** 把所有 PID -> CLAUDE_IDEA_TAB_ID 一次性读出（一次 ps 调用，更快） */
    fun snapshotAllClaudeTabs(): Map<Long, String> {
        return runCatching {
            val pb = ProcessBuilder("ps", "-A", "-E", "-o", "pid=,command=")
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val finished = proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return@runCatching emptyMap<Long, String>()
            }
            val output = proc.inputStream.bufferedReader().readText()
            val result = mutableMapOf<Long, String>()
            for (line in output.lineSequence()) {
                val trimmed = line.trimStart()
                if (trimmed.isEmpty()) continue
                val pidStr = trimmed.takeWhile { it.isDigit() }
                if (pidStr.isEmpty()) continue
                val pid = pidStr.toLongOrNull() ?: continue
                if (!line.contains("CLAUDE_IDEA_TAB_ID=")) continue
                val token = line.split(Regex("\\s+")).firstOrNull {
                    it.startsWith("CLAUDE_IDEA_TAB_ID=")
                } ?: continue
                result[pid] = token.substringAfter("=")
            }
            result
        }.getOrNull() ?: emptyMap()
    }
}
