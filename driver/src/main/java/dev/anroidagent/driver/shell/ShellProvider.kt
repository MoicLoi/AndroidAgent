package dev.anroidagent.driver.shell

import android.app.Activity
import dev.anroidagent.driver.Driver

/**
 * shell 能力的提供方，由追加包实现，基础包通过反射加载。
 *
 * 放在 `:driver` 而不是 `:core`，因为它依赖 [Driver]；两个 flavor 都包含该模块，
 * 所以基础包也能引用这个接口，但**没有任何实现**（追加包不在依赖里）。
 */
interface ShellProvider {

    /** 实现标识，例如 "shizuku" */
    val id: String

    /** Shizuku 服务已连接且已授权 */
    val isReady: Boolean

    /** 触发授权请求；结果回到 Activity.onRequestPermissionsResult */
    fun requestPermission(activity: Activity, requestCode: Int)

    /** 返回 true 表示该 requestCode 由本提供方处理 */
    fun onRequestPermissionsResult(requestCode: Int, granted: Boolean): Boolean

    /** 已经就用时返回驱动，否则 null */
    fun driver(): Driver?

    /** `dumpsys` 精确判定 FLAG_SECURE；不可用时返回 null，由调用方回退到全黑帧推断 */
    fun secureWindow(): Boolean

    /** 启动自检：ping + 执行一条命令。失败时不应启用本提供方 */
    fun selfTest(): Boolean

    /** 高级操作集合；未实现或未就绪时返回 null */
    fun ops(context: android.content.Context): ShellOps? = null

    /**
     * 执行一条**已经过分类闸门**的 shell 命令。
     *
     * 实现方只负责跑，不再做安全判定——分类在 `ShellCommandPolicy`，
     * 执行前由策略层（`ActionGuard`）拦截。
     */
    fun exec(cmd: String): ShellResult = ShellResult.fail("unsupported")
}
