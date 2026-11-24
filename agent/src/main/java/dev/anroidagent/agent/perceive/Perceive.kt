package dev.anroidagent.agent.perceive

import dev.anroidagent.core.model.Bounds
import dev.anroidagent.core.model.PageContext
import dev.anroidagent.core.model.UiNode

/**
 * 一个可寻址的 TUI 单元。[index] 是模型唯一可引用的动作句柄，
 * 每帧重算（不做跨帧稳定 id，否则一定错位）。
 */
data class TuiItem(
    val index: Int,
    val text: String,
    val bounds: Bounds,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
)

/**
 * GUI → TUI 的压缩流水线。
 *
 * 目标：把无障碍树压到 400~900 token 的文本，同时保留空间关系。
 * 相比截图（1.4k~3.5k token 且必须用 VLM），成本降约一个数量级。
 */
class Compressor(
    private val maxListItems: Int = 5,
    private val maxLabelChars: Int = 10,
) {

    fun compress(root: UiNode, screen: Bounds): List<TuiItem> {
        val out = ArrayList<TuiItem>(64)
        walk(root, screen, out)
        return out
    }

    private fun walk(node: UiNode, screen: Bounds, out: MutableList<TuiItem>) {
        // 1. 不可见 / 完全出屏：丢弃
        if (!node.visibleToUser) return
        if (!node.bounds.intersects(screen)) return

        // 2. 只保留可交互或有文本的节点（容器压缩的关键）
        if (node.clickable || node.editable || node.hasText) {
            val text = renderText(node)
            if (text.isNotEmpty()) {
                out += TuiItem(
                    index = out.size + 1,
                    text = text,
                    bounds = node.bounds,
                    clickable = node.clickable,
                    editable = node.editable,
                    scrollable = node.scrollable,
                )
            }
        }

        // 3. 列表折叠：同构兄弟只留前 N 项
        val kids = node.children
        if (kids.size > maxListItems && isHomogeneous(kids)) {
            kids.take(maxListItems - 1).forEach { walk(it, screen, out) }
            val last = kids.last()
            out += TuiItem(
                index = out.size + 1,
                text = "⋮ ${kids.size - maxListItems + 1} more",
                bounds = last.bounds,
                scrollable = node.scrollable,
            )
        } else {
            kids.forEach { walk(it, screen, out) }
        }
    }

    private fun isHomogeneous(kids: List<UiNode>): Boolean {
        val cls = kids.first().className
        return cls.isNotBlank() && kids.all { it.className == cls }
    }

    private fun renderText(n: UiNode): String {
        val raw = when {
            n.editable && n.hint.isNotBlank() -> n.hint
            n.hasText -> n.text
            n.clickable -> "◻"
            else -> ""
        }
        if (raw.isBlank()) return ""

        val trimmed = if (raw.length > maxLabelChars) {
            raw.take(maxLabelChars - 1) + "…"
        } else {
            raw
        }

        val flag = when {
            !n.enabled -> "✗"
            n.checked -> "✓"
            n.editable -> "□"
            else -> ""
        }
        return flag + trimmed
    }
}

/**
 * 把 [TuiItem] 列表渲染成字符网格。
 *
 * 渲染顺序按视觉位置（y 分桶、x 排序），**不是**无障碍树的 DFS 顺序——
 * 两者在弹窗/悬浮层场景下经常不一致。
 */
class TuiRenderer(
    private val rows: Int = 22,
) {

    fun render(ctx: PageContext, items: List<TuiItem>): String {
        val header = buildString {
            append("# ").append(ctx.screenWidth).append('x').append(ctx.screenHeight)
            append(' ').append(shortPkg(ctx.packageName))
            append(" s").append(ctx.step)
            if (ctx.isSecureWindow) append(" [SECURE]")
        }
        if (items.isEmpty()) return "$header\n(empty)"

        val byRow = items.groupBy { rowOf(it.bounds.centerY, ctx.screen) }
        return buildString {
            append(header).append('\n')
            for (r in 0 until rows) {
                val row = byRow[r] ?: continue
                append(row.sortedBy { it.bounds.left }.joinToString(" ") { "[${it.index}]${it.text}" })
                append('\n')
            }
        }.trimEnd()
    }

    private fun rowOf(y: Int, screen: Bounds): Int {
        val h = screen.height.coerceAtLeast(1)
        return ((y - screen.top).toLong() * rows / h).toInt().coerceIn(0, rows - 1)
    }

    private fun shortPkg(pkg: String): String =
        pkg.substringAfterLast('.').ifBlank { pkg }
}

/**
 * 相邻帧增量。稳态（同一列表滚动）每帧只有 2~4 行变化，
 * 配合 prompt caching 可把成本压到接近只付输出。
 */
data class Delta(
    val changed: List<String>,
    val unchanged: Int,
) {
    val isSmall: Boolean get() = changed.size <= 5
}

class FrameDiffer {

    fun diff(prev: String, cur: String): Delta {
        val a = prev.lines()
        val b = cur.lines()
        val changed = b.filterIndexed { i, line -> a.getOrNull(i) != line }
        return Delta(changed, b.size - changed.size)
    }

    /** 只有小变化才值得发增量；变化大就整帧重发 */
    fun render(delta: Delta): String? = if (delta.isSmall && delta.changed.isNotEmpty()) {
        buildString {
            append("Δ\n")
            append(delta.changed.joinToString("\n"))
            append("\n(其余 ").append(delta.unchanged).append(" 行不变)")
        }
    } else {
        null
    }
}

/**
 * 粗略 token 估算（中文按 1.5 token/字，其余按 4 字符/token）。
 * 仅用于本地预算控制与测试断言，不作为计费依据。
 */
object TokenEstimate {

    fun of(text: String): Int {
        var cjk = 0
        var other = 0
        for (ch in text) {
            if (ch.code > 0x2E80) cjk++ else other++
        }
        return kotlin.math.ceil(cjk * 1.5 + other / 4.0).toInt()
    }
}
