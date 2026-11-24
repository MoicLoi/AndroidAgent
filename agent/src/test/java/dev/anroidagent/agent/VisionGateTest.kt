package dev.anroidagent.agent

import dev.anroidagent.agent.perceive.VisionGate
import dev.anroidagent.core.model.Bounds
import dev.anroidagent.core.model.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视觉兜底门槛。
 *
 * 这些断言直接决定"什么时候多花 1500 token 发一张图"，
 * 阈值调错要么白烧钱，要么让模型看空屏瞎猜。
 */
class VisionGateTest {

    private val screen = Bounds(0, 0, 1080, 2400)

    private fun leaf(id: String, text: String = "", clickable: Boolean = false) = UiNode(
        id = id,
        label = text,
        bounds = Bounds(0, 0, 400, 120),
        clickable = clickable,
        className = "android.widget.TextView",
    )

    private fun container(children: List<UiNode>) = UiNode(
        id = "root",
        bounds = screen,
        className = "android.widget.FrameLayout",
        children = children,
    )

    @Test
    fun `missing tree needs vision`() {
        assertTrue(VisionGate.needsVision(null))
    }

    @Test
    fun `empty root needs vision`() {
        assertTrue(VisionGate.needsVision(container(emptyList())))
    }

    @Test
    fun `single self drawn container needs vision`() {
        // 典型 Flutter / Unity 页面：一个容器 + 一个自绘 view，没有任何文本
        val tree = container(listOf(leaf("canvas", clickable = true)))
        assertTrue(VisionGate.needsVision(tree))
    }

    @Test
    fun `icon only page needs vision`() {
        val icons = (1..6).map { leaf("i$it", text = "", clickable = true) }
        val tree = container(icons)
        assertEquals(0, VisionGate.stats(tree).texts)
        assertTrue("全是图标时 TUI 只剩 ◻，应走视觉", VisionGate.needsVision(tree))
    }

    @Test
    fun `rich flat page does not need vision`() {
        // 6 个带文本的按钮都在 root 下（深度 1），信息量完全够用
        val buttons = (1..6).map { leaf("b$it", text = "按钮$it", clickable = true) }
        val tree = container(buttons)

        assertEquals(7, VisionGate.stats(tree).nodes)
        assertFalse(VisionGate.needsVision(tree))
    }

    @Test
    fun `few clickables still needs vision`() {
        val tree = container(
            listOf(
                leaf("b1", "确定", clickable = true),
                leaf("b2", "取消", clickable = true),
                leaf("t3", "说明文字"),
                leaf("t4", "更多说明"),
                leaf("t5", "补充"),
            ),
        )
        assertTrue("可交互 < 3 时定位不可靠", VisionGate.needsVision(tree))
    }

    @Test
    fun `stats ignores invisible nodes`() {
        val invisible = leaf("hidden", "不可见").copy(bounds = Bounds(0, 0, 0, 0))
        val tree = container(listOf(invisible))
        assertEquals(1, VisionGate.stats(tree).nodes)
    }

    @Test
    fun `stats reports depth`() {
        // root(0) → 中间容器(1) → 3 个叶子(2)，最大深度 = 2
        val deep = container(
            listOf(
                container(
                    listOf(
                        leaf("a", "x", clickable = true),
                        leaf("b", "y", clickable = true),
                        leaf("c", "z", clickable = true),
                    ),
                ),
            ),
        )
        assertEquals(2, VisionGate.stats(deep).depth)
    }
}
