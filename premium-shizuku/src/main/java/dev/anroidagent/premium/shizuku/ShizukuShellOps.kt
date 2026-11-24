package dev.anroidagent.premium.shizuku

import android.content.Context
import dev.anroidagent.driver.shell.ShellOp
import dev.anroidagent.driver.shell.ShellOps
import dev.anroidagent.driver.shell.ShellResult

/**
 * [ShellOps] 的 Shizuku 实现。
 *
 * 全局副作用（`policy_control` / 动画 scale / 无障碍服务列表）在改动**之前**
 * 先备份原值到 SharedPreferences，退出或下次启动时写回。
 * 不备份的后果很实际：进程被杀后设置残留，用户以为手机坏了。
 */
class ShizukuShellOps(
    private val ctx: Context,
    private val shell: ShizukuShell,
) : ShellOps {

    private val prefs = ctx.getSharedPreferences("shell_restore", Context.MODE_PRIVATE)

    /** 本应用的无障碍组件名（与 :app 的 AgentService 对应） */
    private val a11yComponent: String
        get() = "${ctx.packageName}/dev.anroidagent.app.AgentService"

    override fun run(op: ShellOp, arg: String?): ShellResult = try {
        when (op) {
            ShellOp.TOP_ACTIVITY -> ShellResult.ok(
                ShellCommands.parseTopActivity(shell.exec(ShellCommands.topActivity())).orEmpty(),
            )

            ShellOp.DEVICE_IDS -> ShellResult.ok(deviceIds())

            ShellOp.HIDE_SYSTEM_BARS -> {
                backupGlobal("policy_control")
                exec(ShellCommands.hideSystemBars())
            }

            ShellOp.RESTORE_SYSTEM_BARS -> {
                exec(ShellCommands.restoreSystemBars(prefs.getString(KEY_POLICY, null)))
                prefs.edit().remove(KEY_POLICY).apply()
                ShellResult.ok()
            }

            ShellOp.ANIMATION_OFF -> {
                backupAnimation()
                ShellCommands.animationOff().forEach { shell.exec(it) }
                ShellResult.ok()
            }

            ShellOp.ANIMATION_RESTORE -> {
                val previous = ShellCommands.ANIMATION_KEYS.associateWith {
                    prefs.getString(KEY_ANIM_PREFIX + it, null).orEmpty()
                }
                ShellCommands.animationRestore(previous).forEach { shell.exec(it) }
                ShellCommands.ANIMATION_KEYS.forEach {
                    prefs.edit().remove(KEY_ANIM_PREFIX + it).apply()
                }
                ShellResult.ok()
            }

            ShellOp.ENABLE_A11Y -> {
                val current = shell.exec(ShellCommands.getEnabledA11y()).trim()
                if (!prefs.contains(KEY_A11Y)) {
                    prefs.edit().putString(KEY_A11Y, current).apply()
                }
                shell.exec(ShellCommands.putEnabledA11y(ShellCommands.mergeA11y(current, a11yComponent)))
                shell.exec(ShellCommands.setA11yEnabled(true))
                ShellResult.ok("已启用 $a11yComponent")
            }

            ShellOp.DISABLE_A11Y -> {
                val current = shell.exec(ShellCommands.getEnabledA11y()).trim()
                val next = ShellCommands.removeA11y(current, a11yComponent)
                shell.exec(ShellCommands.putEnabledA11y(next))
                if (next.isBlank()) shell.exec(ShellCommands.setA11yEnabled(false))
                prefs.edit().remove(KEY_A11Y).apply()
                ShellResult.ok("已移除 $a11yComponent")
            }

            ShellOp.CLEAR_APP_DATA -> exec(ShellCommands.clearAppData(requireArg(arg)))

            ShellOp.FORCE_STOP -> exec(ShellCommands.forceStop(requireArg(arg)))

            ShellOp.SUSPEND_APP -> exec(ShellCommands.suspend(requireArg(arg)))

            ShellOp.UNSUSPEND_APP -> exec(ShellCommands.unsuspend(requireArg(arg)))

            ShellOp.GRANT_PERMISSION -> {
                val (pkg, perm) = split2(requireArg(arg))
                exec(ShellCommands.grant(pkg, perm))
            }

            ShellOp.REVOKE_PERMISSION -> {
                val (pkg, perm) = split2(requireArg(arg))
                exec(ShellCommands.revoke(pkg, perm))
            }

            ShellOp.SET_APPOP -> {
                val parts = requireArg(arg).split(':')
                if (parts.size != 3) return ShellResult.fail("格式应为 pkg:OP:mode")
                exec(ShellCommands.setAppOp(parts[0], parts[1], parts[2]))
            }
        }
    } catch (e: IllegalArgumentException) {
        ShellResult.fail("参数非法：${e.message}")
    } catch (e: Exception) {
        ShellResult.fail(e.message ?: e.javaClass.simpleName)
    }

    override fun restoreAll(): ShellResult {
        val restored = buildList {
            if (prefs.contains(KEY_POLICY)) {
                add(ShellOp.RESTORE_SYSTEM_BARS)
            }
            if (ShellCommands.ANIMATION_KEYS.any { prefs.contains(KEY_ANIM_PREFIX + it) }) {
                add(ShellOp.ANIMATION_RESTORE)
            }
        }
        if (restored.isEmpty()) return ShellResult.ok("没有需要恢复的设置")

        restored.forEach { run(it) }
        return ShellResult.ok("已恢复：" + restored.joinToString(", "))
    }

    override fun hasPendingRestore(): Boolean =
        prefs.contains(KEY_POLICY) ||
            ShellCommands.ANIMATION_KEYS.any { prefs.contains(KEY_ANIM_PREFIX + it) }

    // ---------------- 内部 ----------------

    private fun deviceIds(): String {
        fun q(cmd: String) = runCatching { shell.exec(cmd).trim() }.getOrDefault("?")

        return buildString {
            appendLine("android_id = " + q(ShellCommands.androidId()))
            appendLine("wlan0_mac  = " + q(ShellCommands.wifiMac()))
            appendLine("serial     = " + q(ShellCommands.serial()))
            append("model      = " + q(ShellCommands.model()))
        }
    }

    private fun backupGlobal(key: String) {
        if (prefs.contains(KEY_POLICY)) return
        val current = runCatching { shell.exec(ShellCommands.getGlobal(key)).trim() }.getOrNull()
        prefs.edit().putString(KEY_POLICY, current ?: "null").apply()
    }

    private fun backupAnimation() {
        ShellCommands.ANIMATION_KEYS.forEach { key ->
            val k = KEY_ANIM_PREFIX + key
            if (prefs.contains(k)) return@forEach
            val current = runCatching { shell.exec(ShellCommands.getGlobal(key)).trim() }.getOrNull()
            prefs.edit().putString(k, current ?: "1.0").apply()
        }
    }

    private fun exec(cmd: String): ShellResult =
        runCatching { ShellResult.ok(shell.exec(cmd).trim()) }
            .getOrElse { ShellResult.fail(it.message ?: "exec failed") }

    private fun requireArg(arg: String?): String {
        require(!arg.isNullOrBlank()) { "缺少参数" }
        return arg
    }

    private fun split2(arg: String): Pair<String, String> {
        val idx = arg.indexOf(':')
        require(idx > 0 && idx < arg.length - 1) { "格式应为 pkg:permission" }
        return arg.substring(0, idx) to arg.substring(idx + 1)
    }

    private companion object {
        const val KEY_POLICY = "policy_control"
        const val KEY_A11Y = "a11y_services"
        const val KEY_ANIM_PREFIX = "anim_"
    }
}
