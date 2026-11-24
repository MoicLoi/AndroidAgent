package dev.anroidagent.app.overlay

import org.json.JSONObject

/**
 * 悬浮窗配置。
 *
 * 设计要点：
 * - `x/y < 0` 表示"未设置"，由首次显示时按屏幕宽度放到右侧默认位
 * - 所有尺寸用 dp，位置用 px（WindowManager 坐标是 px）
 * - 提供 `toJson/fromJson`：换机、复现 bug、灰度对比都需要可导出的配置
 */
data class OverlayConfig(
    val widthDp: Int = 168,
    val heightDp: Int = 84,
    val x: Int = -1,
    val y: Int = -1,
    val bgColor: Int = 0xE6202124.toInt(),
    val textColor: Int = 0xFFFFFFFF.toInt(),
    val alpha: Float = 0.96f,
    val cornerDp: Int = 12,
    /** 执行时把悬浮窗整个移除，避免目标 App 收到 FLAG_WINDOW_IS_OBSCURED */
    val hideWhileRunning: Boolean = true,
) {

    fun toJson(): String = JSONObject().apply {
        put("widthDp", widthDp)
        put("heightDp", heightDp)
        put("x", x)
        put("y", y)
        put("bgColor", bgColor)
        put("textColor", textColor)
        put("alpha", alpha.toDouble())
        put("cornerDp", cornerDp)
        put("hideWhileRunning", hideWhileRunning)
    }.toString()

    /** 位置钳制：防止用户把窗口拖出屏幕后再也拖不回来 */
    fun clamp(screenW: Int, screenH: Int, widthPx: Int, heightPx: Int): OverlayConfig {
        val maxX = (screenW - widthPx).coerceAtLeast(0)
        val maxY = (screenH - heightPx).coerceAtLeast(0)
        return copy(
            x = x.coerceIn(0, maxX),
            y = y.coerceIn(0, maxY),
        )
    }

    companion object {

        fun fromJson(text: String?): OverlayConfig {
            if (text.isNullOrBlank()) return OverlayConfig()
            return runCatching {
                val o = JSONObject(text)
                OverlayConfig(
                    widthDp = o.optInt("widthDp", 168),
                    heightDp = o.optInt("heightDp", 84),
                    x = o.optInt("x", -1),
                    y = o.optInt("y", -1),
                    bgColor = o.optInt("bgColor", 0xE6202124.toInt()),
                    textColor = o.optInt("textColor", 0xFFFFFFFF.toInt()),
                    alpha = o.optDouble("alpha", 0.96).toFloat(),
                    cornerDp = o.optInt("cornerDp", 12),
                    hideWhileRunning = o.optBoolean("hideWhileRunning", true),
                )
            }.getOrDefault(OverlayConfig())
        }
    }
}
