package dev.anroidagent.core.media

/**
 * 一条待移入暂存区的记录。
 *
 * [sha256] 在**移动时**计算并写入（扫描阶段算不起：要读整份文件），
 * 它是回滚与校验的唯一凭据。
 */
data class TrashEntry(
    val mediaId: String,
    val srcPath: String,
    val trashName: String,
    val sizeBytes: Long,
    val movedAtMs: Long,
    val sha256: String? = null,
)

data class TrashPlan(
    val trashDir: String,
    val entries: List<TrashEntry>,
    val skippedTooNew: Int = 0,
    val cappedOut: Int = 0,
) {
    val totalBytes: Long get() = entries.sumOf { it.sizeBytes }
    val isEmpty: Boolean get() = entries.isEmpty()
}

/**
 * 暂存区策略。
 *
 * [minAgeMs] 存在的理由很实际：相机 App 正在写、云同步正在拉、
 * 用户刚拍完还没看——最近一小时的媒体一律不动。
 */
data class TrashPolicy(
    val minAgeMs: Long = 60L * 60 * 1000,
    val undoWindowMs: Long = 7L * 24 * 60 * 60 * 1000,
    val maxPerRun: Int = 500,
)

/**
 * 暂存区命名。
 *
 * 不用原文件名直接搬：不同目录下同名文件极多（每台手机都有一堆 `IMG_0001.jpg`），
 * 直接搬会互相覆盖——覆盖就意味着"备份"变成了"丢数据"。
 */
object TrashNaming {

    fun trashName(srcPath: String, seq: Int): String {
        val name = srcPath.substringAfterLast('/')
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) name.substringAfterLast('.') else ""

        val safeBase = base
            .take(60)
            .map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }
            .joinToString("")
            .ifBlank { "media" }

        val prefix = seq.toString().padStart(4, '0')
        return if (ext.isNotEmpty() && ext.length <= 5) "$prefix-$safeBase.$ext" else "$prefix-$safeBase"
    }
}

/**
 * 复制后的校验。
 *
 * **这是"备份"这个词唯一的实质**：不比字节数和摘要的复制，
 * 只是把文件挪了个地方，用户以为安全了，其实可能只剩半张图。
 */
object TrashVerifier {

    sealed interface Result {
        data object Ok : Result
        data class Reject(val why: String) : Result
    }

    fun check(
        expectedSize: Long,
        expectedDigest: String?,
        actualSize: Long,
        actualDigest: String?,
    ): Result {
        if (expectedSize <= 0) return Result.Reject("源文件大小为 0")
        if (actualSize != expectedSize) {
            return Result.Reject("大小不一致：期望 $expectedSize，实际 $actualSize")
        }
        if (expectedDigest.isNullOrBlank()) return Result.Reject("源文件没有摘要")
        if (actualDigest.isNullOrBlank()) return Result.Reject("副本没有摘要")
        if (!expectedDigest.equals(actualDigest, ignoreCase = true)) {
            return Result.Reject("摘要不一致")
        }
        return Result.Ok
    }
}

/**
 * 把去重建议转成暂存区计划。
 *
 * 只接受**用户明确选中**的分组（[selectedGroupIds]），不做"全选式"默认，
 * 避免一次误操作把几百个文件全搬走。
 */
class TrashPlanner(private val policy: TrashPolicy = TrashPolicy()) {

    fun plan(
        groups: List<DuplicateGroup>,
        selectedGroupIds: Set<String>,
        nowMs: Long,
        trashDir: String,
    ): TrashPlan {
        val picked = groups.filter { it.id in selectedGroupIds }

        var tooNew = 0
        val candidates = mutableListOf<MediaItem>()

        picked.forEach { g ->
            g.drop.forEach { item ->
                if (!SafePaths.canReclaim(item.path)) return@forEach
                if (nowMs - item.dateTakenMs < policy.minAgeMs) {
                    tooNew++
                } else {
                    candidates += item
                }
            }
        }

        val accepted = candidates.take(policy.maxPerRun)
        val cappedOut = candidates.size - accepted.size

        val entries = accepted.mapIndexed { index, item ->
            TrashEntry(
                mediaId = item.id,
                srcPath = item.path,
                trashName = TrashNaming.trashName(item.path, index + 1),
                sizeBytes = item.sizeBytes,
                movedAtMs = nowMs,
            )
        }

        return TrashPlan(
            trashDir = trashDir,
            entries = entries,
            skippedTooNew = tooNew,
            cappedOut = cappedOut,
        )
    }
}
