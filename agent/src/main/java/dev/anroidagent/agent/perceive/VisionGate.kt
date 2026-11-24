package dev.anroidagent.agent.perceive

import dev.anroidagent.core.model.UiNode

/**
 * 视觉兜底的门槛。
 *
 * TUI 映射在自绘界面上会失效：Flutter / Unity / 部分 WebView 的无障碍树
 * 要么是空，要么只有一层空容器。这种情况下继续发 TUI 等于给模型看空屏，
 * 必须回落到截图 + VLM。
 *
 * 判定按"信息量不足"而不是"识别出某个框架"——后者永远追不上壳的变种。
 */
object VisionGate {

    data class Stats(
        val nodes: Int,
        val clickables: Int,
        val texts: Int,
        val depth: Int,
    )

    fun stats(root: UiNode?): Stats {
        if (root == null) return Stats(0, 0, 0, 0)

        var nodes = 0
        var clickables = 0
        var texts = 0
        var depth = 0

        fun walk(n: UiNode, d: Int) {
            if (!n.visibleToUser) return
            nodes++
            if (n.clickable || n.editable) clickables++
            if (n.hasText) texts++
            if (d > depth) depth = d
            n.children.forEach { walk(it, d + 1) }
        }

        walk(root, 0)
        return Stats(nodes, clickables, texts, depth)
    }

    /**
     * 信息量不足就要求视觉输入。
     *
     * 三个阈值取"与"，任何一个不满足就走视觉：
     * - 节点 < 5：基本是空树（自绘容器通常只有 1~3 个节点）
     * - 可交互 < 3：只有背景容器
     * - 有文本 < 2：全是图标，TUI 渲染出来是一堆 ◻
     *
     * 刻意不看层深：扁平的多按钮页面（6 个按钮都在 root 下）深度只有 1，
     * 但信息量完全够用，按深度判会白白多付截图的钱。
     */
    fun needsVision(root: UiNode?): Boolean {
        if (root == null) return true
        val s = stats(root)
        return s.nodes < 5 || s.clickables < 3 || s.texts < 2
    }
}
