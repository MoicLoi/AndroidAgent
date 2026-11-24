package dev.anroidagent.premium.shizuku

import android.app.Activity
import android.content.Context
import dev.anroidagent.core.policy.SecureWindowWatch
import dev.anroidagent.core.policy.ShellExec
import dev.anroidagent.driver.Driver
import dev.anroidagent.driver.shell.ShellOps
import dev.anroidagent.driver.shell.ShellProvider
import dev.anroidagent.driver.shell.ShellResult
import rikka.shizuku.Shizuku

/**
 * 追加包的入口。由基础包通过反射加载：
 * `dev.anroidagent.premium.shizuku.ShizukuShellProvider`。
 *
 * 基础包的 APK 里**没有**这个类，所以它在基础包上永远是 null——
 * 这也是"基础包不含 shell 代码"的可验证证据。
 */
class ShizukuShellProvider : ShellProvider {

    override val id: String = "shizuku"

    private val shell = ShizukuShell()
    private val shellExec = ShellExec { cmd -> shell.exec(cmd) }

    @Volatile
    private var verified: Boolean = false

    @Volatile
    private var opsInstance: ShizukuShellOps? = null

    override val isReady: Boolean
        get() = shell.isUsable() && verified

    override fun requestPermission(activity: Activity, requestCode: Int) {
        if (!shell.ping()) return
        if (shell.granted()) {
            verified = selfTest()
            return
        }
        runCatching { Shizuku.requestPermission(requestCode) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, granted: Boolean): Boolean {
        if (granted) verified = selfTest()
        return true
    }

    override fun driver(): Driver? = if (isReady) ShizukuShellDriver(shell) else null

    override fun ops(context: Context): ShellOps? {
        if (!isReady) return null
        return opsInstance ?: ShizukuShellOps(context.applicationContext, shell)
            .also { opsInstance = it }
    }

    override fun secureWindow(): Boolean = runCatching {
        SecureWindowWatch.viaDumpsys(shellExec)
    }.getOrDefault(false)

    override fun exec(cmd: String): ShellResult = runCatching {
        if (!isReady) return ShellResult.fail("shizuku 未就绪")
        ShellResult.ok(shell.exec(cmd).trim())
    }.getOrElse { ShellResult.fail(it.message ?: "exec failed") }

    override fun selfTest(): Boolean = runCatching {
        if (!shell.isUsable()) return false
        if (!SecureWindowWatch.selfTest(shellExec)) return false
        shell.exec("echo ok").trim() == "ok"
    }.getOrDefault(false)
}
