package dev.anroidagent.driver

import dev.anroidagent.core.model.UiNode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * 通道选择语义。
 *
 * 这三条是产品约束，必须由测试钉住：
 * 1. 没配 shell → 只走无障碍
 * 2. 配了 shell → 走 shell
 * 3. shell 失败 → 自动回退无障碍（不能整个动作失败）
 *
 * 外加一条容易写错但很关键的：**取帧不受注入开关影响**。
 */
class DriverRouterTest {

    private class Fake(
        private val label: String,
        private val fail: Boolean = false,
    ) : Driver {
        var taps = 0
        var texts = 0

        override suspend fun tap(x: Int, y: Int) {
            taps++
            if (fail) throw DegradedException("$label tap failed")
        }

        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) {
            if (fail) throw DegradedException("$label swipe failed")
        }

        override suspend fun inputText(text: String) {
            texts++
            if (fail) throw DegradedException("$label input failed")
        }

        override suspend fun launch(pkg: String) {
            if (fail) throw DegradedException("$label launch failed")
        }

        override suspend fun back() {
            if (fail) throw DegradedException("$label back failed")
        }

        override suspend fun shot(): ByteArray = label.toByteArray()

        override suspend fun tree(): UiNode? = null
    }

    @Before
    fun reset() {
        InputChannelProbe.note(InputChannelProbe.NONE)
    }

    @Test
    fun `without shell only a11y is used`() = runBlocking {
        val a11y = Fake("a11y")
        val router = DriverRouter(shell = null, a11y = a11y)

        router.tap(10, 20)

        assertEquals(1, a11y.taps)
        assertEquals(InputChannelProbe.A11Y, InputChannelProbe.last)
    }

    @Test
    fun `with shell the shell channel is preferred`() = runBlocking {
        val shell = Fake("shell")
        val a11y = Fake("a11y")
        val router = DriverRouter(shell = shell, a11y = a11y)

        router.tap(10, 20)

        assertEquals(1, shell.taps)
        assertEquals("无障碍不应被调用", 0, a11y.taps)
        assertEquals(InputChannelProbe.SHELL, InputChannelProbe.last)
    }

    @Test
    fun `shell failure falls back to a11y`() = runBlocking {
        val shell = Fake("shell", fail = true)
        val a11y = Fake("a11y")
        val router = DriverRouter(shell = shell, a11y = a11y)

        router.tap(10, 20)

        assertEquals(1, shell.taps)
        assertEquals("必须回退无障碍", 1, a11y.taps)
        assertEquals(InputChannelProbe.A11Y, InputChannelProbe.last)
    }

    @Test
    fun `text input also falls back`() = runBlocking {
        val shell = Fake("shell", fail = true)
        val a11y = Fake("a11y")
        val router = DriverRouter(shell = shell, a11y = a11y)

        router.inputText("hello")

        assertEquals(1, a11y.texts)
        assertEquals(InputChannelProbe.A11Y, InputChannelProbe.last)
    }

    @Test
    fun `shot uses shot source even when injection is a11y only`() = runBlocking {
        val shell = Fake("shell")
        val a11y = Fake("a11y")
        // 注入关（shell=null），但取帧仍走 shell 的 screencap
        val router = DriverRouter(shell = null, a11y = a11y, shotSource = shell)

        val bytes = router.shot()

        assertEquals("shell", String(bytes!!))
    }

    @Test
    fun `shot returns null when no source`() = runBlocking {
        val router = DriverRouter(shell = null, a11y = null)
        assertNull(router.shot())
    }

    @Test
    fun `no driver throws`() {
        val router = DriverRouter(shell = null, a11y = null)
        assertThrows(DegradedException::class.java) {
            runBlocking { router.tap(1, 2) }
        }
    }

    @Test
    fun `probe label is human readable`() {
        InputChannelProbe.note(InputChannelProbe.SHELL)
        assertEquals("shell 注入", InputChannelProbe.label())
        InputChannelProbe.note(InputChannelProbe.A11Y)
        assertEquals("无障碍点击", InputChannelProbe.label())
    }
}
