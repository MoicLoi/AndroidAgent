package dev.anroidagent.app

import dev.anroidagent.app.overlay.OverlayConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮窗配置的不变式。
 *
 * 重点是钳制：用户把窗口拖到屏幕外之后，如果位置被原样保存，
 * 下次启动就再也拖不回来——这是这类工具最经典的差评来源。
 */
class OverlayConfigTest {

    @Test
    fun `json round trip keeps every field`() {
        val cfg = OverlayConfig(
            widthDp = 200,
            heightDp = 96,
            x = 120,
            y = 340,
            bgColor = 0xFF102030.toInt(),
            textColor = 0xFF00FF00.toInt(),
            alpha = 0.5f,
            cornerDp = 20,
            hideWhileRunning = false,
        )

        val back = OverlayConfig.fromJson(cfg.toJson())
        assertEquals(cfg, back)
    }

    @Test
    fun `missing or broken json falls back to defaults`() {
        assertEquals(OverlayConfig(), OverlayConfig.fromJson(null))
        assertEquals(OverlayConfig(), OverlayConfig.fromJson(""))
        assertEquals(OverlayConfig(), OverlayConfig.fromJson("{not json"))
    }

    @Test
    fun `clamp keeps window on screen`() {
        val offscreen = OverlayConfig(x = 5000, y = 5000)

        val clamped = offscreen.clamp(
            screenW = 1080,
            screenH = 2400,
            widthPx = 300,
            heightPx = 200,
        )

        assertEquals(780, clamped.x) // 1080 - 300
        assertEquals(2200, clamped.y) // 2400 - 200
    }

    @Test
    fun `clamp never goes negative`() {
        val negative = OverlayConfig(x = -500, y = -500)

        val clamped = negative.clamp(
            screenW = 1080,
            screenH = 2400,
            widthPx = 300,
            heightPx = 200,
        )

        assertEquals(0, clamped.x)
        assertEquals(0, clamped.y)
    }

    @Test
    fun `window larger than screen collapses to origin`() {
        val huge = OverlayConfig(x = 40, y = 40)

        val clamped = huge.clamp(
            screenW = 200,
            screenH = 200,
            widthPx = 400,
            heightPx = 400,
        )

        assertTrue(clamped.x >= 0)
        assertTrue(clamped.y >= 0)
    }

    @Test
    fun `default position is unset so service can auto place`() {
        val default = OverlayConfig()
        assertTrue("x < 0 表示未设置", default.x < 0)
        assertTrue("y < 0 表示未设置", default.y < 0)
    }
}
