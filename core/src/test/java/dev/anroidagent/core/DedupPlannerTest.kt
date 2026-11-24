package dev.anroidagent.core

import dev.anroidagent.core.media.DedupPlanner
import dev.anroidagent.core.media.DedupPolicy
import dev.anroidagent.core.media.GroupKind
import dev.anroidagent.core.media.MediaItem
import dev.anroidagent.core.media.SafePaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 相册去重决策核心。
 *
 * 这个类会直接决定"建议删哪张照片"，所以每条规则都必须有测试钉住，
 * 尤其是**永不建议删光一组**这条——误删照片的后果比误点支付更持久。
 */
class DedupPlannerTest {

    private val day = 24L * 60 * 60 * 1000

    private fun photo(
        id: String,
        size: Long = 1_000_000,
        date: Long = 0L,
        w: Int = 4000,
        h: Int = 3000,
        hash: Long? = 0L,
        path: String? = null,
    ) = MediaItem(
        id = id,
        path = path ?: "/sdcard/DCIM/Camera/$id.jpg",
        sizeBytes = size,
        dateTakenMs = date,
        width = w,
        height = h,
        hash = hash,
    )

    private fun video(id: String, size: Long, durationMs: Long = 600_000) = MediaItem(
        id = id,
        path = "/sdcard/DCIM/Camera/$id.mp4",
        sizeBytes = size,
        dateTakenMs = 0,
        isVideo = true,
        durationMs = durationMs,
    )

    private val planner = DedupPlanner()

    // ---------------- 完全重复 ----------------

    @Test
    fun `exact duplicates group together`() {
        val plan = planner.plan(
            listOf(
                photo("a", size = 1_000_000, hash = 42L, date = 0),
                photo("b", size = 1_000_000, hash = 42L, date = 1000),
            ),
        )

        assertEquals(1, plan.groups.size)
        assertEquals(GroupKind.EXACT, plan.groups[0].kind)
        assertEquals(1, plan.groups[0].drop.size)
    }

    @Test
    fun `same hash different size is not exact`() {
        // 关键边界：EXACT 必须同时满足大小一致，否则会把剪辑版当成副本
        val plan = planner.plan(
            listOf(
                photo("a", size = 1_000_000, hash = 42L, date = 0),
                photo("b", size = 1_200_000, hash = 42L, date = 100_000),
            ),
        )

        assertEquals(1, plan.groups.size)
        assertTrue("大小不同应归为近似而非完全重复", plan.groups[0].kind != GroupKind.EXACT)
    }

    // ---------------- 近似重复 ----------------

    @Test
    fun `near duplicates within threshold group`() {
        val plan = planner.plan(
            listOf(
                photo("a", size = 1_000_000, hash = 0L, date = 0),
                // 汉明距离 3，≤ 默认阈值 6
                photo("b", size = 1_100_000, hash = 0b111L, date = 20 * 60_000),
            ),
        )

        assertEquals(1, plan.groups.size)
        assertEquals(GroupKind.NEAR, plan.groups[0].kind)
    }

    @Test
    fun `dissimilar photos are not grouped`() {
        val plan = planner.plan(
            listOf(
                photo("a", size = 1_000_000, hash = 0L, date = 0),
                // 汉明距离 7 > 6
                photo("b", size = 1_100_000, hash = 0b1111111L, date = 20 * 60_000),
            ),
        )

        assertTrue("超过阈值不应成组", plan.groups.isEmpty())
    }

    @Test
    fun `different days are never grouped`() {
        val plan = planner.plan(
            listOf(
                photo("a", size = 1_000_000, hash = 0L, date = 0),
                photo("b", size = 1_100_000, hash = 0L, date = 3 * day),
            ),
        )

        assertTrue("跨天不参与近似聚类", plan.groups.isEmpty())
    }

    // ---------------- 连拍 ----------------

    @Test
    fun `burst detected inside time window`() {
        val plan = planner.plan(
            listOf(
                photo("a", size = 1_000_000, hash = 0L, date = 0),
                photo("b", size = 1_100_000, hash = 0b11L, date = 2_000),
            ),
        )

        assertEquals(1, plan.groups.size)
        assertEquals(GroupKind.BURST, plan.groups[0].kind)
    }

    @Test
    fun `same scene far apart is near not burst`() {
        val plan = planner.plan(
            listOf(
                photo("a", size = 1_000_000, hash = 0L, date = 0),
                photo("b", size = 1_100_000, hash = 0L, date = 30 * 60_000),
            ),
        )

        assertEquals(GroupKind.NEAR, plan.groups[0].kind)
    }

    // ---------------- 保留策略 ----------------

    @Test
    fun `keeps highest resolution even if smaller bytes`() {
        val plan = planner.plan(
            listOf(
                photo("big", size = 900_000, w = 4032, h = 3024, hash = 0L, date = 0),
                photo("small", size = 2_000_000, w = 1920, h = 1080, hash = 0L, date = 1000),
            ),
        )

        assertEquals("big", plan.groups[0].keep.id)
    }

    @Test
    fun `keeps larger bytes when resolution equal`() {
        val plan = planner.plan(
            listOf(
                photo("a", size = 1_000_000, hash = 0L, date = 0),
                photo("b", size = 1_500_000, hash = 0L, date = 1000),
            ),
        )

        assertEquals("b", plan.groups[0].keep.id)
    }

    @Test
    fun `keeps newest when resolution and size equal`() {
        val plan = planner.plan(
            listOf(
                photo("old", size = 1_000_000, hash = 0L, date = 0),
                photo("new", size = 1_000_000, hash = 1L, date = 5000),
            ),
        )

        assertEquals("new", plan.groups[0].keep.id)
    }

    // ---------------- 安全边界 ----------------

    @Test
    fun `never suggests dropping the last copy`() {
        val plan = planner.plan(listOf(photo("only")))
        assertTrue("单张不构成一组", plan.groups.isEmpty())
    }

    @Test
    fun `every group keeps at least one`() {
        val plan = planner.plan(
            listOf(
                photo("a", size = 1_000_000, hash = 0L),
                photo("b", size = 1_000_000, hash = 0L),
                photo("c", size = 1_000_000, hash = 0L),
            ),
        )

        plan.groups.forEach { g ->
            assertTrue("一组至少要留一个", g.drop.isNotEmpty())
            assertFalse("keep 不能同时出现在 drop 里", g.drop.any { it.id == g.keep.id })
        }
    }

    @Test
    fun `protected paths are excluded and counted`() {
        val plan = planner.plan(
            listOf(
                photo("a", size = 1_000_000, hash = 0L, path = "/sdcard/Android/data/com.tencent.mm/1.jpg"),
                photo("b", size = 1_000_000, hash = 0L, path = "/sdcard/Tencent/2.jpg"),
                photo("c", size = 1_000_000, hash = 0L),
            ),
        )

        assertEquals(2, plan.skippedProtected)
        assertTrue(plan.groups.isEmpty())
    }

    @Test
    fun `small files are skipped`() {
        val plan = planner.plan(
            listOf(
                photo("a", size = 1024, hash = 0L),
                photo("b", size = 1024, hash = 0L),
            ),
        )

        assertTrue("小于 minBytes 不参与", plan.groups.isEmpty())
    }

    @Test
    fun `items without hash are counted but not grouped`() {
        val plan = planner.plan(
            listOf(
                photo("a", hash = null),
                photo("b", hash = null),
            ),
        )

        assertEquals(2, plan.skippedNoHash)
        assertTrue(plan.groups.isEmpty())
    }

    // ---------------- 大视频 ----------------

    @Test
    fun `large videos listed and sorted`() {
        val plan = planner.plan(
            listOf(
                video("v1", 1_500L * 1024 * 1024),
                video("v2", 200L * 1024 * 1024),
                video("small", 50L * 1024 * 1024),
            ),
        )

        assertEquals(2, plan.largeVideos.size)
        assertEquals("v1", plan.largeVideos[0].item.id)
        assertEquals("v2", plan.largeVideos[1].item.id)
        assertTrue("视频不进入照片分组", plan.groups.isEmpty())
    }

    @Test
    fun `video note shows duration`() {
        val plan = planner.plan(listOf(video("v", 500L * 1024 * 1024, durationMs = 33 * 60_000 + 12_000)))
        assertEquals("33:12", plan.largeVideos[0].note)
    }

    // ---------------- 报告 ----------------

    @Test
    fun `prompt table is compact and indexed`() {
        val items = (1..50).flatMap { i ->
            listOf(
                photo("p${i}a", size = 1_000_000, hash = i.toLong(), date = i.toLong()),
                photo("p${i}b", size = 1_000_000, hash = i.toLong(), date = i.toLong() + 1),
            )
        }
        val plan = planner.plan(items)
        val table = plan.promptTable(limit = 10)

        assertTrue("应有表头", table.startsWith("# 相册扫描"))
        assertTrue("应有分组编号", table.contains("[G"))
        assertTrue("应显示可回收体积", table.contains("可回收"))
        assertTrue("应限制行数", table.contains("还有"))

        // 100 项输入压成十几行：这是 token 优化的实证
        assertTrue("表格不应随输入线性膨胀", table.lines().size < 20)
    }

    @Test
    fun `reclaimable totals add up`() {
        val plan = planner.plan(
            listOf(
                photo("a", size = 1_000_000, hash = 0L),
                photo("b", size = 1_000_000, hash = 0L),
                photo("c", size = 1_000_000, hash = 0L),
            ),
        )

        assertEquals(2_000_000L, plan.reclaimableBytes)
    }

    @Test
    fun `plan is deterministic`() {
        val items = listOf(
            photo("a", size = 1_000_000, hash = 0L, date = 0),
            photo("b", size = 1_000_000, hash = 0L, date = 1),
            photo("c", size = 900_000, hash = 0b1L, date = 2),
        )

        val first = planner.plan(items)
        val second = planner.plan(items)

        assertEquals(first.groups.map { it.id }, second.groups.map { it.id })
        assertEquals(first.groups.map { it.drop.map { d -> d.id } }, second.groups.map { it.drop.map { d -> d.id } })
    }

    // ---------------- 路径保护 ----------------

    @Test
    fun `safe paths blocks im and android dirs`() {
        listOf(
            "/sdcard/Android/data/x/1.jpg",
            "/sdcard/tencent/1.jpg",
            "/sdcard/Tencent/1.jpg",
            "/sdcard/WeiXin/1.jpg",
            "/sdcard/Pictures/.agent_trash/1.jpg",
            "",
        ).forEach { assertTrue("应受保护：$it", SafePaths.isProtected(it)) }

        listOf(
            "/sdcard/DCIM/Camera/1.jpg",
            "/sdcard/Pictures/Screenshots/1.png",
        ).forEach { assertTrue("应可回收：$it", SafePaths.canReclaim(it)) }
    }

    @Test
    fun `custom policy tightens matching`() {
        val strict = DedupPlanner(DedupPolicy(maxHamming = 1))
        val plan = strict.plan(
            listOf(
                photo("a", size = 1_000_000, hash = 0L, date = 0),
                photo("b", size = 1_100_000, hash = 0b11L, date = 100_000),
            ),
        )

        assertTrue("阈值收紧后距离 2 不应成组", plan.groups.isEmpty())
    }
}
