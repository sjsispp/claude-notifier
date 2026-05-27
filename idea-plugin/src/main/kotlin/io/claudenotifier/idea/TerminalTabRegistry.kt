package io.claudenotifier.idea

import com.intellij.openapi.components.Service
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class TerminalTabEntry(
    val uuid: String,
    val projectName: String,
    val projectPath: String,
    val createdAt: Long = System.currentTimeMillis(),
    @Volatile var widgetRef: Any? = null,
)

/**
 * 维护 UUID -> 终端 tab 元数据的映射。
 * 通过 LocalTerminalCustomizer 注入到 shell 环境变量 CLAUDE_IDEA_TAB_ID。
 *
 * widgetRef 由 SendTextHandler / FocusTabHandler 在第一次定位到 widget 时回填
 * (或在 customizer 已知 widget 时直接登记)，作为兜底路径。
 */
@Service(Service.Level.APP)
class TerminalTabRegistry {
    private val entries = ConcurrentHashMap<String, TerminalTabEntry>()

    fun register(projectName: String, projectPath: String): String {
        val uuid = UUID.randomUUID().toString()
        entries[uuid] = TerminalTabEntry(uuid, projectName, projectPath)
        return uuid
    }

    fun lookup(uuid: String): TerminalTabEntry? = entries[uuid]

    fun unregister(uuid: String) { entries.remove(uuid) }

    fun snapshot(): List<TerminalTabEntry> = entries.values.toList()

    fun attachWidget(uuid: String, widget: Any?) {
        entries[uuid]?.widgetRef = widget
    }
}
