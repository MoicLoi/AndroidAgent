package dev.anroidagent.core.media

/** 分组类型，决定"为什么这几张算重复" */
enum class GroupKind {
    /** 字节级重复：大小 + 哈希一致 */
    EXACT,

    /** 内容近似：dHash 汉明距离 ≤ 阈值 */
    NEAR,

    /** 同一场景连拍：近似 + 时间窗内 */
    BURST,
}

/**
 * 一组重复项。
 *
 * [keep] 是建议保留的那一个，[drop] 是建议清理的其余项。
 * 注意：**这只是建议**。真正删除必须经过人工确认与暂存区，见 README。
 */
data class DuplicateGroup(
    val id: String,
    val kind: GroupKind,
    val keep: MediaItem,
    val drop: List<MediaItem>,
    val reason: String,
) {
    val reclaimableBytes: Long get() = drop.sumOf { it.sizeBytes }
    val size: Int get() = drop.size + 1
}

data class LargeVideo(val id: String, val item: MediaItem, val note: String)

data class DedupPlan(
    val groups: List<DuplicateGroup>,
    val largeVideos: List<LargeVideo>,
    val scanned: Int,
    val skippedNoHash: Int,
    val skippedProtected: Int,
) {
    val reclaimableBytes: Long get() = groups.sumOf { it.reclaimableBytes }
    val videoBytes: Long get() = largeVideos.sumOf { it.item.sizeBytes }

    /**
     * 给模型的紧凑表格。
     *
     * 这是"减少 token 输入"的核心：一万张照片也只输出几十行文本，
     * 模型按编号决策，永远不看图。按 1500 token/张图算，这是 5 个数量级的差距。
     */
    fun promptTable(limit: Int = 40): String = buildString {
        appendLine(
            "# 相册扫描 $scanned 项 · 重复可回收 ${humanBytes(reclaimableBytes)}" +
                " · 大视频 ${humanBytes(videoBytes)}" +
                " · 跳过(保护区) $skippedProtected · 无哈希 $skippedNoHash",
        )

        groups.take(limit).forEach { g ->
            append("[${g.id}] ${g.kind} ${g.size}→1 保留 ${g.keep.path.substringAfterLast('/')} ")
            append("${g.keep.resolution()} 可回收 ${humanBytes(g.reclaimableBytes)}")
            appendLine()
        }
        if (groups.size > limit) appendLine("…还有 ${groups.size - limit} 组")

        largeVideos.take(limit).forEach { v ->
            append("[${v.id}] VIDEO ${v.item.humanSize()} ${v.note} ${v.item.path}")
            appendLine()
        }
    }
}

/**
 * 相位扫描的决策核心：**纯函数、零 Android 依赖、零模型调用**。
 *
 * 设计取舍：
 * - 完全重复（大小+哈希一致）是确定性的，不需要任何智能
 * - 近似重复靠 dHash 汉明距离，也是确定性的
 * - 保留哪一个用可解释的启发式（分辨率 → 体积 → 时间），不用模型
 *
 * 模型只在"要不要按这批建议执行"这一层介入，不参与逐张判断。
 */
class DedupPlanner(private val policy: DedupPolicy = DedupPolicy()) {

    fun plan(items: List<MediaItem>): DedupPlan {
        val skippedProtected = items.count { !SafePaths.canReclaim(it.path) }

        val photos = items.filter {
            !it.isVideo &&
                it.sizeBytes >= policy.minBytes &&
                SafePaths.canReclaim(it.path)
        }
        val skippedNoHash = photos.count { it.hash == null }
        val hashed = photos.filter { it.hash != null }

        // 1. 完全重复
        val exact = exactClusters(hashed)
        val claimed = exact.flatMap { it.members }.toSet()
        val rest = hashed.filterNot { it in claimed }

        // 2. 近似重复（按天分桶，避免全量 O(n²)）
        val near = nearClusters(rest)

        val groups = (exact + near)
            .mapNotNull { toGroup(it) }
            .sortedWith(compareByDescending<DuplicateGroup> { it.reclaimableBytes }.thenBy { it.id })

        val videos = items
            .filter { it.isVideo && it.sizeBytes >= policy.largeVideoBytes && SafePaths.canReclaim(it.path) }
            .sortedByDescending { it.sizeBytes }
            .map { LargeVideo(id = "V${it.id}", item = it, note = videoNote(it)) }

        return DedupPlan(
            groups = groups,
            largeVideos = videos,
            scanned = items.size,
            skippedNoHash = skippedNoHash,
            skippedProtected = skippedProtected,
        )
    }

    // ---------------- 聚类 ----------------

    private data class Cluster(val kind: GroupKind, val members: List<MediaItem>)

    private fun exactClusters(items: List<MediaItem>): List<Cluster> =
        items.groupBy { it.sizeBytes to it.hash }
            .filter { it.value.size > 1 && it.value.size <= policy.maxGroupSize }
            .values
            .map { Cluster(GroupKind.EXACT, it) }

    private fun nearClusters(items: List<MediaItem>): List<Cluster> {
        val out = mutableListOf<Cluster>()

        // 同一天拍摄的照片才可能互为近似；跨天的"像素级巧合"不应成组
        val byDay = items.groupBy { it.dateTakenMs / DAY_MS }

        byDay.values.forEach { day ->
            val visited = mutableSetOf<String>()
            day.forEach { seed ->
                if (seed.id in visited) return@forEach
                val seedHash = seed.hash ?: return@forEach

                val mates = day.filter { other ->
                    if (other.id == seed.id || other.id in visited) return@filter false
                    val h = other.hash ?: return@filter false
                    java.lang.Long.bitCount(h xor seedHash) <= policy.maxHamming
                }

                if (mates.isEmpty()) {
                    visited += seed.id
                    return@forEach
                }

                val members = (listOf(seed) + mates).take(policy.maxGroupSize)
                visited += members.map { it.id }

                val span = members.maxOf { it.dateTakenMs } - members.minOf { it.dateTakenMs }
                val kind = if (span <= policy.burstWindowMs) GroupKind.BURST else GroupKind.NEAR
                out += Cluster(kind, members)
            }
        }
        return out
    }

    // ---------------- 成型 ----------------

    private fun toGroup(cluster: Cluster): DuplicateGroup? {
        val keep = best(cluster.members)
        val drop = cluster.members.filter { it.id != keep.id && SafePaths.canReclaim(it.path) }

        // 永不产出"整组删光"的建议：至少要留一个
        if (drop.isEmpty()) return null

        val reason = when (cluster.kind) {
            GroupKind.EXACT -> "大小与哈希完全一致"
            GroupKind.NEAR -> "内容近似（汉明距离 ≤ ${policy.maxHamming}）"
            GroupKind.BURST -> "连拍（${policy.burstWindowMs / 1000}s 内同场景）"
        }

        return DuplicateGroup(
            id = "G" + keep.id,
            kind = cluster.kind,
            keep = keep,
            drop = drop.sortedByDescending { it.sizeBytes },
            reason = reason,
        )
    }

    /**
     * 保留哪一个：分辨率 → 体积 → 时间（取新）。
     *
     * 这是启发式，不是事实。写死在这里是因为它必须可解释、可预测；
     * 需要更聪明（模糊度、人脸、构图）时再引入模型，但那是另一个功能。
     */
    private fun best(members: List<MediaItem>): MediaItem =
        members.sortedWith(
            compareByDescending<MediaItem> { it.megapixels }
                .thenByDescending { it.sizeBytes }
                .thenByDescending { it.dateTakenMs },
        ).first()

    private fun videoNote(item: MediaItem): String {
        val minutes = item.durationMs / 60_000
        val seconds = (item.durationMs / 1000) % 60
        return "%02d:%02d".format(minutes, seconds)
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
