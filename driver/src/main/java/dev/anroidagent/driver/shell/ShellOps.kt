package dev.anroidagent.driver.shell

/**
 * shell 能力操作。
 *
 * 分两类，行为完全不同：
 * - **只读**（[TOP_ACTIVITY] / [DEVICE_IDS]）：可直接自动调用
 * - **变更**（其余）：必须显式触发、写审计日志，全局副作用还要能恢复
 */
enum class ShellOp {
    /** 读前台 Activity 的包名（dumpsys activity activities） */
    TOP_ACTIVITY,

    /** 读设备标识（android_id / wlan0 MAC / serial / model） */
    DEVICE_IDS,

    /** 隐藏系统栏（policy_control immersive.full） */
    HIDE_SYSTEM_BARS,

    /** 恢复系统栏（写回改动前的值） */
    RESTORE_SYSTEM_BARS,

    /** 关闭动画（三个 scale = 0） */
    ANIMATION_OFF,

    /** 恢复动画（写回改动前的值） */
    ANIMATION_RESTORE,

    /** 启用本应用的无障碍服务（不覆盖用户已有的其它服务） */
    ENABLE_A11Y,

    /** 移除本应用的无障碍服务 */
    DISABLE_A11Y,

    /** pm clear */
    CLEAR_APP_DATA,

    /** am force-stop */
    FORCE_STOP,

    /** pm suspend */
    SUSPEND_APP,

    /** pm unsuspend */
    UNSUSPEND_APP,

    /** pm grant */
    GRANT_PERMISSION,

    /** pm revoke */
    REVOKE_PERMISSION,

    /** cmd appops set */
    SET_APPOP,
}

data class ShellResult(
    val ok: Boolean,
    val output: String = "",
    val error: String? = null,
) {
    companion object {
        fun ok(output: String = "") = ShellResult(true, output)
        fun fail(error: String) = ShellResult(false, error = error)
    }
}

/**
 * shell 操作集合。由追加包实现；基础包通过 [ShellProvider.ops] 拿到 null。
 */
interface ShellOps {

    /** 执行一个操作。[arg] 的含义按操作而定：
     * - 应用类操作：包名
     * - [ShellOp.GRANT_PERMISSION] / [ShellOp.REVOKE_PERMISSION]：`pkg:permission`
     * - [ShellOp.SET_APPOP]：`pkg:op:mode`
     */
    fun run(op: ShellOp, arg: String? = null): ShellResult

    /** 恢复所有被本应用改过的全局设置（启动时与退出时各调一次） */
    fun restoreAll(): ShellResult

    /** 是否有未恢复的全局改动（UI 用来提示） */
    fun hasPendingRestore(): Boolean
}
