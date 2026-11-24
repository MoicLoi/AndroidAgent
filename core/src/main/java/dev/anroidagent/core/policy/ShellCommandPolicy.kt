package dev.anroidagent.core.policy

/**
 * shell 命令分类闸门。
 *
 * 模型可以主动发 shell 动作，但**能不能执行不归模型决定**：
 * - [ShellClass.READ_ONLY]：直接执行，失败由调用方处理
 * - [ShellClass.MUTATING]：转人工确认（策略层判为硬升级）
 * - [ShellClass.FORBIDDEN]：直接拒绝，不解释也不重试
 *
 * 分类只看命令前缀，宁可误判为危险——shell 的破坏力不可逆，
 * 而"多问一次"的成本只是慢一步。
 */
enum class ShellClass { READ_ONLY, MUTATING, FORBIDDEN }

object ShellCommandPolicy {

    /** 直接执行：只读/无害 */
    private val READ_ONLY = listOf(
        "dumpsys",
        "getprop",
        "settings get",
        "pm list",
        "pm path",
        "pm dump",
        "cmd package list",
        "cmd -l",
        "service list",
        "wm size",
        "wm density",
        "screencap",
        "id",
        "whoami",
        "date",
        "uptime",
        "df",
        "ls",
        "stat",
        "cat /sys/class/net",
        "cat /proc/",
        "logcat -d",
        "ps",
        "top -n 1",
    )

    /** 管道里可以出现的过滤器（只在管道段生效） */
    private val PIPELINE_FILTERS = listOf(
        "grep", "head", "tail", "wc", "sort", "uniq", "echo",
    )

    /** 变更类：可以执行，但必须人工确认 */
    private val MUTATING = listOf(
        "settings put",
        "settings delete",
        "pm grant",
        "pm revoke",
        "pm clear",
        "pm suspend",
        "pm unsuspend",
        "pm disable",
        "pm enable",
        "pm install",
        "pm uninstall",
        "pm hide",
        "am force-stop",
        "am start",
        "am broadcast",
        "am kill",
        "am set-debug-app",
        "input ",
        "cmd appops",
        "cmd notification",
        "cmd uimode",
        "cmd statusbar",
        "cmd deviceidle",
        "cmd jobscheduler",
        "svc ",
        "ime ",
        "monkey",
        "uiautomator",
        "setprop",
    )

    /** 危险：一律拒绝 */
    private val FORBIDDEN = listOf(
        "rm", "rmdir", "su", "sudo", "reboot", "shutdown", "halt",
        "dd", "mkfs", "mount", "umount", "chmod", "chown", "chcon",
        "kill", "killall", "pkill", "setenforce", "restorecon",
        "insmod", "rmmod", "flash", "fastboot", "recovery",
        "svc power", "pm disable-user --user 0 android",
    )

    /** 命令链/子命令逃逸的写法，一律拒绝 */
    private val CHAIN_TOKENS = listOf(";", "&&", "||", "$(", "`", ">", "<", "\n", "\r")

    fun classify(cmd: String): ShellClass {
        val trimmed = cmd.trim()
        if (trimmed.isEmpty()) return ShellClass.FORBIDDEN

        // 1. 拒绝命令链与重定向（管道单独处理）
        if (CHAIN_TOKENS.any { trimmed.contains(it) }) return ShellClass.FORBIDDEN

        val segments = trimmed.split('|').map { it.trim() }.filter { it.isNotEmpty() }
        if (segments.isEmpty() || segments.size > 5) return ShellClass.FORBIDDEN

        var worst = ShellClass.READ_ONLY

        segments.forEachIndexed { index, seg ->
            val cls = classifySingle(seg, isPipelineStage = index > 0)
            if (cls == ShellClass.FORBIDDEN) return ShellClass.FORBIDDEN
            if (cls == ShellClass.MUTATING) worst = ShellClass.MUTATING
        }

        return worst
    }

    private fun classifySingle(seg: String, isPipelineStage: Boolean): ShellClass {
        val lower = seg.lowercase()

        // wm 的读写只差一个参数，必须显式区分：
        //   wm size / wm density       → 只读
        //   wm size 1080x1920 / wm density 420 → 变更
        if (lower.startsWith("wm size ") || lower.startsWith("wm density ")) {
            return ShellClass.MUTATING
        }

        if (FORBIDDEN.any { lower == it || lower.startsWith("$it ") }) {
            return ShellClass.FORBIDDEN
        }

        if (MUTATING.any { lower.startsWith(it) || lower == it.trim() }) {
            return ShellClass.MUTATING
        }

        if (READ_ONLY.any { lower.startsWith(it) || lower == it.trim() }) {
            return ShellClass.READ_ONLY
        }

        if (isPipelineStage && PIPELINE_FILTERS.any { lower == it || lower.startsWith("$it ") }) {
            return ShellClass.READ_ONLY
        }

        // 未知命令：按危险处理
        return ShellClass.FORBIDDEN
    }

    /** 给提示词用的示例清单，和分类表保持同源 */
    fun promptExamples(): String = buildString {
        appendLine("可直接执行（只读）：")
        appendLine("  dumpsys window windows / dumpsys activity activities / dumpsys package <pkg>")
        appendLine("  getprop ro.build.version.sdk / getprop ro.product.model")
        appendLine("  settings get global policy_control")
        appendLine("  pm list packages | grep <kw> / pm path <pkg>")
        appendLine("  wm size / wm density / id / date / ps")
        appendLine("需要人工确认（会被拦截）：")
        append("  settings put … / pm clear / pm grant / pm suspend / am force-stop / am start / input … / cmd appops set …")
    }
}
