package dev.anroidagent.app.script

import dev.anroidagent.driver.shell.ShellProvider
import dev.anroidagent.driver.shell.ShellResult
import java.util.Base64

/**
 * 待审脚本的执行器。
 *
 * 只做三件事：把脚本内容送进 `/data/local/tmp`、`sh` 执行、删除临时文件。
 *
 * **这里刻意不做命令分类**——走到这一步意味着用户已经在界面上看过内容并点了执行。
 * 换句话说：本类的安全边界是"人点了一次"，不是字符串检查。
 * 所以它**不能**被模型直接调用，只能由 UI 触发。
 */
object ScriptRunner {

    private const val REMOTE_DIR = "/data/local/tmp"

    fun remotePath(script: PendingScript): String {
        require(ScriptCodec.isSafeId(script.id)) { "unsafe script id" }
        return "$REMOTE_DIR/agent_${script.id}.sh"
    }

    /**
     * 写入远端的命令。
     *
     * 用 base64 而不是 heredoc：脚本内容里可能出现任意字符（引号、`$`、反引号、
     * 换行），heredoc 定界符一旦撞上内容就会被截断。
     */
    fun buildWriteCommand(script: PendingScript): String {
        // java.util.Base64（API 26+）：JVM 单测也能跑，不必引 android.util
        val b64 = Base64.getEncoder().encodeToString(script.command.toByteArray(Charsets.UTF_8))
        return "echo $b64 | base64 -d > ${remotePath(script)}"
    }

    fun buildRunCommand(script: PendingScript): String = "sh ${remotePath(script)}"

    fun buildCleanupCommand(script: PendingScript): String = "rm -f ${remotePath(script)}"

    /**
     * 执行。任一阶段失败都会尽量清理临时文件。
     */
    fun run(provider: ShellProvider, script: PendingScript): ShellResult {
        if (script.command.isBlank()) return ShellResult.fail("脚本内容为空")

        val write = provider.exec(buildWriteCommand(script))
        if (!write.ok) return ShellResult.fail("写入失败：${write.error}")

        return try {
            provider.exec(buildRunCommand(script))
        } finally {
            runCatching { provider.exec(buildCleanupCommand(script)) }
        }
    }
}
