package io.claudenotifier.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.terminal.LocalTerminalCustomizer

/**
 * 在每个新终端 tab 创建时注入 CLAUDE_IDEA_TAB_ID 环境变量。
 * CC hook 脚本读取此变量识别"我在哪个 IDEA 终端"，回传给 ClaudeNotifier.app。
 *
 * 同时把 UUID 推入 PendingTabUuids 队列，等紧随其后的 contentAdded 事件
 * 把 widget 引用关联进 registry（避开 reworked terminal 反查 env 失败的问题）。
 */
class TerminalEnvCustomizer : LocalTerminalCustomizer() {

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
        return command
    }
}
