package dev.anroidagent.premium.shizuku

/**
 * shell 命令的**纯构造层**：只拼接字符串与校验参数，不执行。
 *
 * 单独抽出来有两个原因：
 * 1. 可以 JVM 单测（不需要真机）
 * 2. **命令注入防护集中在这里** —— 我们把这些字符串拼进 `sh -c`，
 *    任何进入命令的参数必须先通过白名单校验，否则一个带 `;` 的包名
 *    就能执行任意命令。
 */
object ShellCommands {

    /** Android 包名：字母数字、下划线、点 */
    private val PACKAGE = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$")

    /** 运行时权限名：android.permission.XXX */
    private val PERMISSION = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$")

    /** appops 操作名：如 CAMERA / READ_CLIPBOARD */
    private val APPOP = Regex("^[A-Z][A-Z0-9_]*$")

    private val APPOP_MODE = Regex("^(allow|deny|ignore|default)$")

    val ANIMATION_KEYS = listOf(
        "window_animation_scale",
        "transition_animation_scale",
        "animator_duration_scale",
    )

    fun isSafePackage(pkg: String): Boolean = PACKAGE.matches(pkg) && pkg.length <= 200

    fun isSafePermission(perm: String): Boolean = PERMISSION.matches(perm) && perm.length <= 200

    fun isSafeAppOp(op: String): Boolean = APPOP.matches(op) && op.length <= 64

    fun isSafeAppOpMode(mode: String): Boolean = APPOP_MODE.matches(mode)

    // ---------------- 只读 ----------------

    fun topActivity(): String = "dumpsys activity activities"

    /**
     * 从 `dumpsys activity activities` 里解析前台包名。
     *
     * 不同版本输出差异大，所以按"先找 mResumedActivity，再找 topResumedActivity，
     * 最后退到 ResumedActivity"的顺序尝试；都失败返回 null。
     */
    fun parseTopActivity(dump: String): String? {
        val ordered = listOf("mResumedActivity", "topResumedActivity", "ResumedActivity")
        for (key in ordered) {
            val line = dump.lineSequence().firstOrNull { it.contains(key) } ?: continue
            // 形如： mResumedActivity: ActivityRecord{ab12 u0 com.foo/.Bar t42}
            val m = Regex("([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+)/").find(line)
            if (m != null) return m.groupValues[1]
        }
        return null
    }

    fun androidId(): String = "settings get secure android_id"

    fun wifiMac(): String = "cat /sys/class/net/wlan0/address"

    fun serial(): String = "getprop ro.serialno"

    fun model(): String = "getprop ro.product.model"

    // ---------------- 全局设置 ----------------

    fun getGlobal(key: String): String = "settings get global $key"

    fun putGlobal(key: String, value: String): String =
        "settings put global $key ${quote(value)}"

    fun getSecure(key: String): String = "settings get secure $key"

    fun putSecure(key: String, value: String): String =
        "settings put secure $key ${quote(value)}"

    fun hideSystemBars(): String = "settings put global policy_control immersive.full=*"

    fun restoreSystemBars(previous: String?): String =
        if (previous.isNullOrBlank() || previous == "null") {
            "settings put global policy_control null*"
        } else {
            "settings put global policy_control ${quote(previous)}"
        }

    fun animationOff(): List<String> =
        ANIMATION_KEYS.map { "settings put global $it 0" }

    fun animationRestore(previous: Map<String, String>): List<String> =
        ANIMATION_KEYS.map { key ->
            val v = previous[key]?.takeIf { it.isNotBlank() && it != "null" } ?: "1.0"
            "settings put global $key $v"
        }

    // ---------------- 应用管理 ----------------

    fun clearAppData(pkg: String): String = "pm clear ${check(pkg)}"

    fun forceStop(pkg: String): String = "am force-stop ${check(pkg)}"

    fun suspend(pkg: String): String = "pm suspend ${check(pkg)}"

    fun unsuspend(pkg: String): String = "pm unsuspend ${check(pkg)}"

    fun grant(pkg: String, perm: String): String {
        require(isSafePermission(perm)) { "unsafe permission" }
        return "pm grant ${check(pkg)} $perm"
    }

    fun revoke(pkg: String, perm: String): String {
        require(isSafePermission(perm)) { "unsafe permission" }
        return "pm revoke ${check(pkg)} $perm"
    }

    fun setAppOp(pkg: String, op: String, mode: String): String {
        require(isSafeAppOp(op)) { "unsafe appop" }
        require(isSafeAppOpMode(mode)) { "unsafe mode" }
        return "cmd appops set ${check(pkg)} $op $mode"
    }

    // ---------------- 无障碍 ----------------

    fun getEnabledA11y(): String = "settings get secure enabled_accessibility_services"

    fun putEnabledA11y(value: String): String =
        "settings put secure enabled_accessibility_services ${quote(value)}"

    fun setA11yEnabled(on: Boolean): String =
        "settings put secure accessibility_enabled ${if (on) 1 else 0}"

    /**
     * 把 [component] 并入用户已有的服务列表，而不是覆盖。
     * 覆盖会把用户其它无障碍服务关掉——这是最容易被投诉的破坏性行为。
     */
    fun mergeA11y(current: String?, component: String): String {
        val parts = current.orEmpty()
            .split(':')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (parts.contains(component)) return parts.joinToString(":")
        return (parts + component).joinToString(":")
    }

    fun removeA11y(current: String?, component: String): String =
        current.orEmpty()
            .split(':')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != component }
            .joinToString(":")

    // ---------------- 工具 ----------------

    private fun check(pkg: String): String {
        require(isSafePackage(pkg)) { "unsafe package: $pkg" }
        return pkg
    }

    /** 单引号转义，防止 value 里带 `;` 或 `$` 破坏命令结构 */
    fun quote(value: String): String = dev.anroidagent.driver.shell.ShellQuoting.quote(value)
}
