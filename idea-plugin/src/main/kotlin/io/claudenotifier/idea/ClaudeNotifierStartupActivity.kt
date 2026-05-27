package io.claudenotifier.idea

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import io.claudenotifier.idea.server.HttpServerHolder

class ClaudeNotifierStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        // 全 App 第一次打开任意 project 时启动 server（之后所有 project 共用）
        HttpServerHolder.start()
    }
}
