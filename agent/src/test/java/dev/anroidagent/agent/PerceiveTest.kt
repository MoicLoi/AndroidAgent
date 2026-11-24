package dev.anroidagent.agent

import dev.anroidagent.agent.perceive.Compressor
import dev.anroidagent.agent.perceive.FrameDiffer
import dev.anroidagent.agent.perceive.TokenEstimate
import dev.anroidagent.agent.perceive.TuiRenderer
import dev.anroidagent.core.model.Bounds
import dev.anroidagent.core.model.PageContext
import dev.anroidagent.core.model.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TUI 映射的量化验证。
 *
 * 断言两件事：
 * 1. 压缩后的 token 数显著低于等价的无障碍 XML（至少 4 倍）
 * 2. 列表折叠生效（不把 200 条商品全塞进上下文）
 *
 * 这两个数字是"省 token"这个卖点的实测依据，不是估算。
 */
class PerceiveTest {

    private val screen = Bounds(0, 0, 1080, 2400)

    private fun n(
        id: String,
        label: String = "",
        hint: String = "",
        desc: String = "",
        l: Int = 0,
        t: Int = 0,
        r: Int = 1080,
        b: Int = 2400,
        clickable: Boolean = false,
        editable: Boolean = false,
        scrollable: Boolean = false,
        cls: String = "android.widget.FrameLayout",
        depth: Int = 0,
        children: List<UiNode> = emptyList(),
    ) = UiNode(
        id = id,
        label = label,
        hint = hint,
        desc = desc,
        bounds = Bounds(l, t, r, b),
        clickable = clickable,
        editable = editable,
        scrollable = scrollable,
        className = cls,
        depth = depth,
        children = children,
    )

    /** 电商商品列表页：搜索框 + 头像 + 3 个 tab + 12 个商品 + 底部 4 个导航 */
    private fun productPage(): UiNode {
        val products = (1..12).map { i ->
            n(
                id = "p$i",
                l = 20, t = 600 + (i - 1) * 140, r = 1060, b = 600 + (i - 1) * 140 + 130,
                cls = "android.widget.LinearLayout",
                children = listOf(
                    n("p${i}t", label = "商品标题$i", l = 40, t = 620, r = 800, b = 680,
                        cls = "android.widget.TextView"),
                    n("p${i}p", label = "¥${99 + i}", l = 40, t = 690, r = 300, b = 730,
                        cls = "android.widget.TextView"),
                ),
            )
        }

        return n(
            id = "root",
            children = listOf(
                n("search", hint = "输入关键词", l = 40, t = 180, r = 860, b = 300,
                    editable = true, cls = "android.widget.EditText"),
                n("avatar", l = 900, t = 180, r = 1040, b = 320,
                    clickable = true, cls = "android.widget.ImageView"),
                n("tabs", l = 0, t = 340, r = 1080, b = 420,
                    children = listOf(
                        n("t1", label = "推荐", l = 40, t = 350, r = 200, b = 410,
                            clickable = true, cls = "android.widget.TextView"),
                        n("t2", label = "关注", l = 220, t = 350, r = 380, b = 410,
                            clickable = true, cls = "android.widget.TextView"),
                        n("t3", label = "附近", l = 400, t = 350, r = 560, b = 410,
                            clickable = true, cls = "android.widget.TextView"),
                    )),
                n("list", l = 0, t = 440, r = 1080, b = 2200,
                    scrollable = true, cls = "androidx.recyclerview.widget.RecyclerView",
                    children = products),
                n("nav", l = 0, t = 2260, r = 1080, b = 2400,
                    children = listOf(
                        n("n1", label = "首页", l = 40, t = 2280, r = 240, b = 2380,
                            clickable = true, cls = "android.widget.TextView"),
                        n("n2", label = "购物车", l = 280, t = 2280, r = 520, b = 2380,
                            clickable = true, cls = "android.widget.TextView"),
                        n("n3", label = "消息", l = 560, t = 2280, r = 800, b = 2380,
                            clickable = true, cls = "android.widget.TextView"),
                        n("n4", label = "我的", l = 840, t = 2280, r = 1040, b = 2380,
                            clickable = true, cls = "android.widget.TextView"),
                    )),
            ),
        )
    }

    /** 等价的无障碍 XML（uiautomator dump 的典型冗余度） */
    private fun rawXml(node: UiNode, sb: StringBuilder = StringBuilder()) {
        sb.append("<node text=\"").append(node.label)
            .append("\" resource-id=\"com.example:id/").append(node.id)
            .append("\" class=\"").append(node.className)
            .append("\" package=\"com.example.shop\" content-desc=\"").append(node.desc)
            .append("\" checkable=\"false\" checked=\"false\" clickable=\"").append(node.clickable)
            .append("\" enabled=\"true\" focusable=\"true\" focused=\"false\" scrollable=\"")
            .append(node.scrollable).append("\" long-clickable=\"false\" password=\"false\" selected=\"false\" bounds=\"[")
            .append(node.bounds.left).append(',').append(node.bounds.top).append("][")
            .append(node.bounds.right).append(',').append(node.bounds.bottom).append("]\">\n")
        node.children.forEach { rawXml(it, sb) }
        sb.append("</node>\n")
    }

    @Test
    fun `tui is at least 4x cheaper than raw accessibility xml`() {
        val root = productPage()
        val ctx = PageContext(
            packageName = "com.example.shop",
            screenWidth = 1080,
            screenHeight = 2400,
            step = 3,
        )

        val items = Compressor().compress(root, screen)
        val tui = TuiRenderer().render(ctx, items)

        val sb = StringBuilder()
        rawXml(root, sb)

        val tuiTokens = TokenEstimate.of(tui)
        val rawTokens = TokenEstimate.of(sb.toString())

        println("=== TUI tokens: $tuiTokens / raw XML tokens: $rawTokens / ratio: ${rawTokens.toFloat() / tuiTokens}")

        assertTrue("TUI 应低于 900 token，实际 $tuiTokens", tuiTokens < 900)
        assertTrue(
            "压缩比应 >= 4，实际 ${rawTokens.toFloat() / tuiTokens}",
            rawTokens >= tuiTokens * 4,
        )
    }

    @Test
    fun `long list is collapsed instead of dumped`() {
        val items = Compressor(maxListItems = 5).compress(productPage(), screen)
        val more = items.filter { it.text.contains("more") }
        assertEquals("12 个商品应折叠成 1 条 more 提示", 1, more.size)
        assertTrue("折叠后的元素总数应远小于原始节点数", items.size < 30)
    }

    @Test
    fun `frame diff stays small on scroll`() {
        val differ = FrameDiffer()
        val a = "# 1080x2400 shop s1\n[1]搜索 [2]◻\n[3]商品1 ¥100\n[4]商品2 ¥101"
        val b = "# 1080x2400 shop s2\n[1]搜索 [2]◻\n[3]商品2 ¥101\n[4]商品3 ¥102"

        val delta = differ.diff(a, b)
        assertTrue(delta.isSmall)
        assertTrue(differ.render(delta)!!.contains("Δ"))
    }
}
