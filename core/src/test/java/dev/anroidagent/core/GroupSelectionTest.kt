package dev.anroidagent.core

import dev.anroidagent.core.media.DuplicateGroup
import dev.anroidagent.core.media.GroupKind
import dev.anroidagent.core.media.GroupSelection
import dev.anroidagent.core.media.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 勾选策略。
 *
 * 最要紧的一条：**默认只勾确定性重复**。
 * 如果哪天有人把 defaultChecked 改成"全选"，这组测试会红。
 */
class GroupSelectionTest {

    private fun item(id: String, size: Long = 1_000_000) = MediaItem(
        id = id,
        path = "/sdcard/DCIM/$id.jpg",
        sizeBytes = size,
        dateTakenMs = 0,
        width = 4000,
        height = 3000,
        hash = 0L,
    )

    private fun group(id: String, kind: GroupKind, drops: Int = 1): DuplicateGroup {
        val drop = (1..drops).map { item("${id}_d$it", 1_000_000) }
        return DuplicateGroup(
            id = id,
            kind = kind,
            keep = item("${id}_keep", 1_200_000),
            drop = drop,
            reason = "test",
        )
    }

    @Test
    fun `default selects only exact duplicates`() {
        val groups = listOf(
            group("g1", GroupKind.EXACT),
            group("g2", GroupKind.NEAR),
            group("g3", GroupKind.BURST),
        )

        val checked = GroupSelection.defaultChecked(groups)

        assertTrue("EXACT 应默认选中", checked[0])
        assertFalse("NEAR 是启发式，不能默认删", checked[1])
        assertFalse("BURST 是启发式，不能默认删", checked[2])
    }

    @Test
    fun `empty plan yields empty selection`() {
        assertTrue(GroupSelection.defaultChecked(emptyList()).isEmpty())
        assertTrue(GroupSelection.selectedIds(emptyList(), BooleanArray(0)).isEmpty())
    }

    @Test
    fun `selected ids follow checked flags`() {
        val groups = listOf(
            group("g1", GroupKind.EXACT),
            group("g2", GroupKind.NEAR),
            group("g3", GroupKind.EXACT),
        )

        val ids = GroupSelection.selectedIds(groups, booleanArrayOf(false, true, true))
        assertEquals(setOf("g2", "g3"), ids)
    }

    @Test
    fun `mismatched length selects the shorter side`() {
        val groups = listOf(
            group("g1", GroupKind.EXACT),
            group("g2", GroupKind.EXACT),
        )

        // UI 只给了 1 个勾选状态
        val ids = GroupSelection.selectedIds(groups, booleanArrayOf(true))
        assertEquals("错位时宁可少选", setOf("g1"), ids)
    }

    @Test
    fun `reclaimable sums only selected groups`() {
        val groups = listOf(
            group("g1", GroupKind.EXACT, drops = 2),
            group("g2", GroupKind.NEAR, drops = 3),
        )

        assertEquals(2_000_000L, GroupSelection.reclaimable(groups, setOf("g1")))
        assertEquals(5_000_000L, GroupSelection.reclaimable(groups, setOf("g1", "g2")))
        assertEquals(0L, GroupSelection.reclaimable(groups, emptySet()))
    }

    @Test
    fun `file count excludes the kept item`() {
        val groups = listOf(group("g1", GroupKind.BURST, drops = 3))
        assertEquals("3→1 应只算 3 个待移", 3, GroupSelection.fileCount(groups, setOf("g1")))
    }
}
