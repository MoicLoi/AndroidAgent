package dev.anroidagent.app

import dev.anroidagent.app.media.ProgressText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扫描进度文案。
 *
 * 边界比看起来多：done=0 不能算 ETA（除零）、done>total 不能超 100%、
 * 秒/分进位、长耗时不能出现负数。
 */
class ProgressTextTest {

    @Test
    fun `formats seconds`() {
        assertEquals("0s", ProgressText.fmtDuration(0))
        assertEquals("1s", ProgressText.fmtDuration(1_000))
        assertEquals("59s", ProgressText.fmtDuration(59_000))
    }

    @Test
    fun `formats minutes and seconds`() {
        assertEquals("1m0s", ProgressText.fmtDuration(60_000))
        assertEquals("1m1s", ProgressText.fmtDuration(61_000))
        assertEquals("12m34s", ProgressText.fmtDuration(12 * 60_000L + 34_000))
    }

    @Test
    fun `negative duration clamps to zero`() {
        assertEquals("0s", ProgressText.fmtDuration(-5000))
    }

    @Test
    fun `unknown total shows phase only`() {
        val text = ProgressText.scan(0, 0, 3_000, "读取媒体库")
        assertTrue(text.contains("读取媒体库"))
        assertTrue(text.contains("已用 3s"))
        assertFalse("拿不到总数时不能编造百分比", text.contains("%"))
    }

    @Test
    fun `zero done does not compute eta`() {
        val text = ProgressText.scan(0, 100, 2_000)
        assertTrue(text.contains("0/100（0%）"))
        assertTrue("第一个还没算完时不能给 ETA", text.contains("估算中"))
        assertFalse(text.contains("约剩"))
    }

    @Test
    fun `computes remaining time`() {
        // 50/100 用了 10s → 每张 200ms → 还剩 50 张 = 10s
        val text = ProgressText.scan(50, 100, 10_000)
        assertTrue(text.contains("50/100（50%）"))
        assertTrue("应给出约剩 10s，实际：$text", text.contains("约剩 10s"))
    }

    @Test
    fun `completion hides eta`() {
        val text = ProgressText.scan(100, 100, 20_000)
        assertTrue(text.contains("100%"))
        assertFalse(text.contains("约剩"))
    }

    @Test
    fun `done beyond total is clamped`() {
        // 扫描期间新增文件会让 done 短暂超过 total
        val text = ProgressText.scan(120, 100, 5_000)
        assertTrue(text.contains("100%"))
        assertFalse(text.contains("120%"))
    }

    @Test
    fun `phase label is included`() {
        assertTrue(ProgressText.scan(10, 100, 1_000, "分组").contains("分组"))
    }
}
