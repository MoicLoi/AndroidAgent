package dev.anroidagent.core.capability

import android.os.Build

/**
 * 能力位。基础版只允许使用非 shell 能力；shell 系能力由追加包提供。
 */
enum class Cap {
    /** 无障碍：读界面树 */
    A11Y_TREE,

    /** 无障碍：手势注入（dispatchGesture） */
    A11Y_GESTURE,

    /** 无障碍：设置文本 */
    A11Y_SET_TEXT,

    /** MediaProjection 截图（每次会话需用户确认） */
    MEDIA_PROJ,

    /** 悬浮窗 */
    OVERLAY,

    /** 使用情况访问（前台 App 检测） */
    USAGE_STATS,

    // —— 以下需要追加包（shell / root）——

    /** shell 注入：screencap 静默截图 */
    SHELL_SHOT,

    /** shell 注入：injectInputEvent 免无障碍点击 */
    SHELL_INPUT,

    /** shell：pm clear / install / appops */
    SHELL_PM,

    /** shell：dumpsys，用于 FLAG_SECURE 精确判定 */
    SHELL_DUMPSYS,

    /** root：uinput 虚拟输入设备 */
    UINPUT,

    /** 外部 HID 通道 */
    EXTERNAL_HID,
}

/**
 * 能力探测结果。驱动路由与降级决策都基于它，任何分支不得假设能力存在。
 */
data class CapReport(
    val granted: Set<Cap>,
    val androidSdk: Int = Build.VERSION.SDK_INT,
    val notes: List<String> = emptyList(),
) {
    fun has(cap: Cap): Boolean = cap in granted
    val hasShell: Boolean get() = granted.any { it == Cap.SHELL_SHOT || it == Cap.SHELL_INPUT }
    val canInput: Boolean get() = has(Cap.A11Y_GESTURE) || has(Cap.SHELL_INPUT) || has(Cap.UINPUT)
    val canSee: Boolean get() = has(Cap.A11Y_TREE)

    companion object {
        val EMPTY = CapReport(emptySet(), notes = listOf("no capability probed"))
    }
}

interface CapabilityProbe {
    fun probe(): CapReport
}
