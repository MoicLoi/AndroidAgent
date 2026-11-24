package dev.anroidagent.core.model

/**
 * 屏幕矩形。坐标系为屏幕绝对坐标。
 */
data class Bounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    fun intersects(other: Bounds): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    fun contains(x: Int, y: Int): Boolean =
        x in left until right && y in top until bottom
}

/**
 * 无障碍树节点。只保留策略与 TUI 渲染需要的字段。
 *
 * 注意：[label] / [hint] / [desc] 三者用途不同，红线判定依赖 [hint] 与 [label]，
 * 比只看 text 精确得多（输入框的真实用途通常在 hintText 里）。
 */
data class UiNode(
    val id: String,
    val label: String = "",
    val hint: String = "",
    val desc: String = "",
    val bounds: Bounds,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val enabled: Boolean = true,
    val selected: Boolean = false,
    val checked: Boolean = false,
    val className: String = "",
    val depth: Int = 0,
    val children: List<UiNode> = emptyList(),
) {
    val text: String get() = label.ifBlank { desc }.ifBlank { hint }
    val hasText: Boolean get() = text.isNotBlank()
    val visibleToUser: Boolean get() = !bounds.isEmpty
}

/**
 * 单步动作。索引引用（[Step.Tap.index]）而非坐标，是 TUI 方案降低
 * grounding 错误率的关键：模型输出 "tap 7"，由本地映射回坐标。
 */
sealed interface Step {

    data class Tap(
        val index: Int,
        val label: String = "",
    ) : Step

    data class Type(
        val index: Int,
        val text: String,
        val targetLabel: String = "",
        val targetHint: String = "",
    ) : Step

    data class Swipe(
        val fromX: Int,
        val fromY: Int,
        val toX: Int,
        val toY: Int,
        val durationMs: Long = 300L,
    ) : Step

    data class Launch(val pkg: String) : Step

    data object Back : Step

    data class Wait(val ms: Long) : Step

    /** 人工接管：支付前、验证码、登录等场景主动交接 */
    data class Ask(val question: String) : Step

    /**
     * 执行一条 adb shell 命令。
     *
     * 是否真的允许执行由 `ShellCommandPolicy` 分类决定：
     * 只读放行，变更类转人工，危险命令直接拒绝。
     */
    data class Shell(val cmd: String) : Step

    /** 仅滚动，不点击 */
    data object Scroll : Step
}

/**
 * 一帧的上下文。
 */
data class PageContext(
    val packageName: String,
    val activity: String? = null,
    val screenWidth: Int,
    val screenHeight: Int,
    val step: Int = 0,
    val isSecureWindow: Boolean = false,
) {
    val screen: Bounds get() = Bounds(0, 0, screenWidth, screenHeight)
}
