package dev.anroidagent.core.media

/**
 * 分组的默认勾选策略。
 *
 * 抽出来是因为这是一个**安全默认值**，不该埋在界面代码里：
 * - `EXACT`（字节级重复）默认选中：删掉副本不可能丢内容
 * - `NEAR` / `BURST`（启发式相似）默认不选：可能删掉一张构图更好的
 *
 * 用户当然可以手动勾上后两类，但那必须是他的主动选择，不是我们的默认。
 */
object GroupSelection {

    fun defaultChecked(groups: List<DuplicateGroup>): BooleanArray =
        BooleanArray(groups.size) { groups[it].kind == GroupKind.EXACT }

    /**
     * 把勾选状态映射成分组 id。
     *
     * 长度不一致时以较短者为准——UI 与数据错位时宁可少选，不可错选。
     */
    fun selectedIds(groups: List<DuplicateGroup>, checked: BooleanArray): Set<String> {
        val n = minOf(groups.size, checked.size)
        val out = LinkedHashSet<String>(n)
        for (i in 0 until n) {
            if (checked[i]) out += groups[i].id
        }
        return out
    }

    /** 勾选后预计可回收的字节数，用于二次确认时显示 */
    fun reclaimable(groups: List<DuplicateGroup>, selected: Set<String>): Long =
        groups.filter { it.id in selected }.sumOf { it.reclaimableBytes }

    /** 勾选后涉及的文件数（不含每组保留的那一张） */
    fun fileCount(groups: List<DuplicateGroup>, selected: Set<String>): Int =
        groups.filter { it.id in selected }.sumOf { it.drop.size }
}
