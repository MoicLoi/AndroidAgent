package dev.anroidagent.core

import dev.anroidagent.core.media.DedupPlanner
import dev.anroidagent.core.media.DuplicateGroup
import dev.anroidagent.core.media.GroupKind
import dev.anroidagent.core.media.MediaItem
import dev.anroidagent.core.media.TrashNaming
import dev.anroidagent.core.media.TrashPlan
import dev.anroidagent.core.media.TrashPlanner
import dev.anroidagent.core.media.TrashPolicy
import dev.anroidagent.core.media.TrashVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 暂存区：命名、校验、规划。
 *
 * 这里的每一条都直接对应"会不会丢照片"，所以负例比正例更重要。
 */
class TrashTest {

    private val hour = 60L * 60 * 1000
    private val day = 24 * hour

    private fun photo(
        id: String,
        date: Long = 0L,
        size: Long = 1_000_000,
        path: String? = null,
        hash: Long = 0L,
    ) = MediaItem(
        id = id,
        path = path ?: "/sdcard/DCIM/Camera/$id.jpg",
        sizeBytes = size,
        dateTakenMs = date,
        width = 4000,
        height = 3000,
        hash = hash,
    )

    private fun groupOf(vararg items: MediaItem): dev.anroidagent.core.media.DuplicateGroup {
        val plan = DedupPlanner().plan(items.toList())
        return plan.groups.first()
    }

    // ---------------- 命名 ----------------

    @Test
    fun `trash name keeps extension and pads sequence`() {
        assertEquals("0001-IMG_0001.jpg", TrashNaming.trashName("/sdcard/DCIM/Camera/IMG_0001.jpg", 1))
        assertEquals("0042-b.jpg", TrashNaming.trashName("/sdcard/Pictures/b.jpg", 42))
    }

    @Test
    fun `trash name sanitizes unsafe characters`() {
        val n = TrashNaming.trashName("/sdcard/DCIM/a b;c\$d.jpg", 1)
        assertFalse(n.contains(' '))
        assertFalse(n.contains(';'))
        assertFalse(n.contains('$'))
        assertTrue(n.endsWith(".jpg"))
    }

    @Test
    fun `trash name handles files without extension`() {
        assertEquals("0001-rawfile", TrashNaming.trashName("/sdcard/DCIM/rawfile", 1))
    }

    @Test
    fun `same basename from different dirs gets different names`() {
        val a = TrashNaming.trashName("/sdcard/DCIM/Camera/IMG_1.jpg", 1)
        val b = TrashNaming.trashName("/sdcard/Pictures/IMG_1.jpg", 2)
        assertTrue("序号必须保证唯一，否则会互相覆盖", a != b)
    }

    @Test
    fun `blank basename falls back`() {
        assertEquals("0001-media.jpg", TrashNaming.trashName("/sdcard/DCIM/.jpg", 1))
    }

    // ---------------- 校验 ----------------

    @Test
    fun `verify accepts matching size and digest`() {
        val r = TrashVerifier.check(1000, "ABC123", 1000, "abc123")
        assertEquals(TrashVerifier.Result.Ok, r)
    }

    @Test
    fun `verify rejects size mismatch`() {
        val r = TrashVerifier.check(1000, "abc", 999, "abc")
        assertTrue(r is TrashVerifier.Result.Reject)
    }

    @Test
    fun `verify rejects missing digest`() {
        assertTrue(TrashVerifier.check(1000, null, 1000, "abc") is TrashVerifier.Result.Reject)
        assertTrue(TrashVerifier.check(1000, "abc", 1000, null) is TrashVerifier.Result.Reject)
        assertTrue(TrashVerifier.check(1000, "  ", 1000, "  ") is TrashVerifier.Result.Reject)
    }

    @Test
    fun `verify rejects digest mismatch`() {
        assertTrue(TrashVerifier.check(1000, "abc", 1000, "def") is TrashVerifier.Result.Reject)
    }

    @Test
    fun `verify rejects zero size source`() {
        assertTrue(TrashVerifier.check(0, "abc", 0, "abc") is TrashVerifier.Result.Reject)
    }

    // ---------------- 规划 ----------------

    @Test
    fun `plans only selected groups`() {
        val g1 = groupOf(photo("a", hash = 0L), photo("b", hash = 0L))
        val g2 = groupOf(photo("c", hash = 0xFFFFL, date = 0), photo("d", hash = 0xFFFFL, date = 1000))

        val plan = TrashPlanner().plan(listOf(g1, g2), setOf(g1.id), day, "/sdcard/Pictures/.agent_trash")

        assertEquals(1, plan.entries.size)
        assertTrue(plan.entries[0].srcPath.contains("b"))
    }

    @Test
    fun `skips media newer than min age`() {
        val now = 10 * day
        val fresh = groupOf(
            photo("keep", date = now - 100, hash = 0L),
            photo("fresh", date = now - 5 * 60 * 1000, hash = 0L),
        )

        val plan = TrashPlanner().plan(
            listOf(fresh), setOf(fresh.id), now, "/sdcard/Pictures/.agent_trash",
        )

        assertTrue("刚拍的不能动", plan.isEmpty)
        assertEquals(1, plan.skippedTooNew)
    }

    @Test
    fun `caps number of entries per run`() {
        val now = 30 * day
        val items = (1..10).map { photo("p$it", date = now - 2 * day, size = 1_000_000, hash = 0L) }
        val g = groupOf(*items.toTypedArray())

        val plan = TrashPlanner(TrashPolicy(maxPerRun = 3))
            .plan(listOf(g), setOf(g.id), now, "/sdcard/Pictures/.agent_trash")

        assertEquals(3, plan.entries.size)
        assertEquals(6, plan.cappedOut) // 9 个候选 - 3 个上限
    }

    @Test
    fun `never plans protected paths`() {
        val now = 30 * day
        // 直接构造分组：DedupPlanner 在扫描阶段就会过滤保护区，
        // 这里要验证的是 TrashPlanner 自己的第二道防线。
        val keep = photo("keep", date = now - 2 * day)
        val wx = photo("wx", date = now - 2 * day, path = "/sdcard/Tencent/wx.jpg")
        val g = DuplicateGroup(
            id = "G1",
            kind = GroupKind.EXACT,
            keep = keep,
            drop = listOf(wx),
            reason = "test",
        )

        val plan = TrashPlanner().plan(listOf(g), setOf(g.id), now, "/sdcard/Pictures/.agent_trash")

        assertTrue("受保护路径绝不能进暂存区", plan.isEmpty)
    }

    @Test
    fun `total bytes add up`() {
        val now = 30 * day
        val g = groupOf(
            photo("keep", date = now - 2 * day, size = 1_000_000, hash = 0L),
            photo("x", date = now - 2 * day, size = 700_000, hash = 0L),
            photo("y", date = now - 2 * day, size = 500_000, hash = 0L),
        )

        val plan = TrashPlanner().plan(listOf(g), setOf(g.id), now, "/sdcard/Pictures/.agent_trash")
        assertEquals(1_200_000L, plan.totalBytes)
    }

    @Test
    fun `plan carries sha256 slot empty until move`() {
        val now = 30 * day
        val g = groupOf(
            photo("keep", date = now - 2 * day, hash = 0L),
            photo("x", date = now - 2 * day, hash = 0L),
        )

        val plan = TrashPlanner().plan(listOf(g), setOf(g.id), now, "/sdcard/Pictures/.agent_trash")
        assertTrue("摘要必须在移动时计算，规划阶段为空", plan.entries.all { it.sha256 == null })
    }

    @Test
    fun `empty selection yields empty plan`() {
        val now = 30 * day
        val g = groupOf(photo("a", date = now - 2 * day, hash = 0L), photo("b", date = now - 2 * day, hash = 0L))

        val plan: TrashPlan = TrashPlanner().plan(listOf(g), emptySet(), now, "/x")
        assertTrue(plan.isEmpty)
        assertEquals(0L, plan.totalBytes)
    }

    @Test
    fun `group kind is preserved in plan path`() {
        val now = 30 * day
        val g = groupOf(
            photo("a", date = now - 2 * day, hash = 0L),
            photo("b", date = now - 2 * day, hash = 0L),
        )
        assertEquals(GroupKind.EXACT, g.kind)
    }
}
