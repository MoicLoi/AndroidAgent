package dev.anroidagent.driver

import dev.anroidagent.core.model.Bounds
import dev.anroidagent.core.model.UiNode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 研究模式（只记录不执行）。
 *
 * 两条不变量：
 * 1. 变更动作**绝不**透传到内层（这是"不会碰设备"的全部意义）
 * 2. 读操作必须透传，否则无从记录、也判不了红线
 */
class DryRunDriverTest {

    private class Counting : Driver {
        var taps = 0
        var swipes = 0
        var texts = 0
        var launches = 0
        var backs = 0
        var shots = 0
        var trees = 0

        override suspend fun tap(x: Int, y: Int) { taps++ }
        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) { swipes++ }
        override suspend fun inputText(text: String) { texts++ }
        override suspend fun launch(pkg: String) { launches++ }
        override suspend fun back() { backs++ }
        override suspend fun shot(): ByteArray { shots++; return "img".toByteArray() }
        override suspend fun tree(): UiNode {
            trees++
            return UiNode(id = "root", bounds = Bounds(0, 0, 10, 10))
        }
    }

    @Before
    fun reset() {
        InputChannelProbe.note(InputChannelProbe.NONE)
    }

    @Test
    fun `mutation actions are recorded but never executed`() = runBlocking {
        val inner = Counting()
        val records = mutableListOf<String>()
        val driver = DryRunDriver(inner) { records += it }

        driver.tap(10, 20)
        driver.swipe(1, 2, 3, 4, 300)
        driver.inputText("hello")
        driver.launch("com.a.b")
        driver.back()

        assertEquals("点击不应执行", 0, inner.taps)
        assertEquals(0, inner.swipes)
        assertEquals(0, inner.texts)
        assertEquals(0, inner.launches)
        assertEquals(0, inner.backs)

        assertEquals(5, records.size)
        assertTrue(records[0].startsWith("tap("))
        assertTrue(records.any { it.startsWith("type(") })
        assertTrue(records.any { it.startsWith("launch(") })
    }

    @Test
    fun `read operations pass through`() = runBlocking {
        val inner = Counting()
        val driver = DryRunDriver(inner)

        driver.tree()
        val shot = driver.shot()

        assertEquals("读树必须透传", 1, inner.trees)
        assertEquals("截图必须透传", 1, inner.shots)
        assertEquals("img", String(shot!!))
    }

    @Test
    fun `probe reports dry run`() = runBlocking {
        val driver = DryRunDriver(Counting())
        driver.tap(1, 1)

        assertEquals(InputChannelProbe.DRY, InputChannelProbe.last)
        assertEquals("研究模式（未执行）", InputChannelProbe.label())
    }

    @Test
    fun `record callback failure does not break the driver`() = runBlocking {
        val inner = Counting()
        val driver = DryRunDriver(inner) { throw IllegalStateException("boom") }

        driver.tap(1, 1)   // 不应抛

        assertEquals(0, inner.taps)
        assertEquals(InputChannelProbe.DRY, InputChannelProbe.last)
    }
}
