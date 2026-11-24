package dev.anroidagent.core.media

/**
 * 相册扫描得到的单个媒体项。
 *
 * [hash] 是 dHash 64 位（由系统缩略图算出，不解原图）；视频或读不到时为 null。
 * 这个字段是"近似重复"判定的唯一依据，所以拿不到哈希的项不会进入任何分组，
 * 只计入 [DedupPlan.skippedNoHash]。
 */
data class MediaItem(
    val id: String,
    val path: String,
    val sizeBytes: Long,
    val dateTakenMs: Long,
    val width: Int = 0,
    val height: Int = 0,
    val isVideo: Boolean = false,
    val durationMs: Long = 0,
    val hash: Long? = null,
) {
    val megapixels: Int get() = width * height

    fun humanSize(): String = humanBytes(sizeBytes)

    fun resolution(): String = if (width > 0 && height > 0) "${width}x$height" else "?"
}

/** 统一的体积格式化，避免各处写法漂移 */
fun humanBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format("%.1f GB", bytes / 1024.0 / 1024 / 1024)
    bytes >= 1L shl 20 -> String.format("%.1f MB", bytes / 1024.0 / 1024)
    else -> String.format("%.0f KB", bytes / 1024.0)
}

/** 去重策略。所有阈值集中在这里，便于按设备调整。 */
data class DedupPolicy(
    /** dHash 汉明距离上限；≤ 这个值算近似重复 */
    val maxHamming: Int = 6,
    /** 同一场景的连拍时间窗 */
    val burstWindowMs: Long = 3_000,
    /** 小于这个体积不参与（贴图、缩略图、表情） */
    val minBytes: Long = 32 * 1024,
    /** 大视频阈值 */
    val largeVideoBytes: Long = 100L * 1024 * 1024,
    /** 单个分组的项数上限，超过就跳过（防御异常输入） */
    val maxGroupSize: Int = 200,
)
