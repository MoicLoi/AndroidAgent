package dev.anroidagent.app.media

import dev.anroidagent.core.media.TrashEntry
import dev.anroidagent.core.media.TrashVerifier
import dev.anroidagent.driver.shell.ShellProvider
import dev.anroidagent.driver.shell.ShellQuoting

/**
 * 带校验的暂存区移动器（走 shell）。
 *
 * 顺序刻意如此，**每一步失败都不会丢源文件**：
 * ```
 * mkdir -p   →   sha256sum 源  →   cp 到暂存区   →   sha256sum + stat 副本
 *            →   三项校验通过   →   才删源
 * ```
 *
 * 为什么不用 `mv`：`mv` 在同一文件系统上是原子的 rename，
 * 看起来更省事，但**没法校验**——rename 之后源已经没了，复制坏了你也发现不了。
 * 多花一次 IO 换来"可以验证"，值。
 */
class TrashMover(
    private val shell: ShellProvider,
    private val trashDir: String,
) {

    data class Outcome(
        val moved: List<TrashEntry>,
        val failed: List<Pair<TrashEntry, String>>,
    ) {
        val movedBytes: Long get() = moved.sumOf { it.sizeBytes }
    }

    fun moveAll(
        entries: List<TrashEntry>,
        onProgress: (done: Int, total: Int, label: String) -> Unit = { _, _, _ -> },
    ): Outcome {
        shell.exec("mkdir -p ${q(trashDir)}")

        val moved = mutableListOf<TrashEntry>()
        val failed = mutableListOf<Pair<TrashEntry, String>>()

        entries.forEachIndexed { index, entry ->
            onProgress(index, entries.size, entry.srcPath.substringAfterLast('/'))
            runCatching { moveOne(entry) }
                .onSuccess { moved += it }
                .onFailure { failed += entry to (it.message ?: "unknown") }
        }

        onProgress(entries.size, entries.size, "")
        return Outcome(moved, failed)
    }

    private fun moveOne(entry: TrashEntry): TrashEntry {
        val src = entry.srcPath
        val dst = "$trashDir/${entry.trashName}"

        // 1. 源摘要与大小（大小以 shell 为准，不信 MediaStore 的缓存值）
        val srcDigest = digest(src)
        val srcSize = size(src)
        if (srcSize <= 0) throw IllegalStateException("源文件大小异常：$srcSize")

        // 2. 复制
        val cp = shell.exec("cp -p ${q(src)} ${q(dst)}")
        if (!cp.ok) throw IllegalStateException("复制失败：${cp.error}")

        // 3. 副本摘要与大小
        val dstDigest = digest(dst)
        val dstSize = size(dst)

        // 4. 三项校验；不通过就删副本、保留源
        val verdict = TrashVerifier.check(srcSize, srcDigest, dstSize, dstDigest)
        if (verdict is TrashVerifier.Result.Reject) {
            shell.exec("rm -f ${q(dst)}")
            throw IllegalStateException("校验失败，已保留源文件：${verdict.why}")
        }

        // 5. 只有校验通过才删源
        val rm = shell.exec("rm -f ${q(src)}")
        if (!rm.ok) throw IllegalStateException("副本已就绪但删除源失败：${rm.error}")

        return entry.copy(sha256 = srcDigest, sizeBytes = srcSize)
    }

    /** 回滚：从暂存区搬回原路径，同样校验 */
    fun restore(entry: TrashEntry): Boolean {
        val src = "$trashDir/${entry.trashName}"
        val dst = entry.srcPath

        val srcDigest = runCatching { digest(src) }.getOrNull() ?: return false
        if (entry.sha256 != null && !entry.sha256.equals(srcDigest, ignoreCase = true)) {
            // 暂存区里的内容被改过，不能盲目还原
            return false
        }

        shell.exec("mkdir -p ${q(dst.substringBeforeLast('/'))}")
        val cp = shell.exec("cp -p ${q(src)} ${q(dst)}")
        if (!cp.ok) return false

        val backDigest = runCatching { digest(dst) }.getOrNull() ?: return false
        if (!backDigest.equals(srcDigest, ignoreCase = true)) {
            shell.exec("rm -f ${q(dst)}")
            return false
        }

        shell.exec("rm -f ${q(src)}")
        return true
    }

    /** 清空暂存区（真正不可恢复的一步，只在用户明确要求时调用） */
    fun emptyTrash(): Boolean =
        shell.exec("rm -rf ${q(trashDir)}").ok

    // ---------------- 内部 ----------------

    private fun digest(path: String): String {
        val r = shell.exec("sha256sum ${q(path)}")
        if (!r.ok) throw IllegalStateException("摘要失败：${r.error}")
        val hash = r.output.trim().substringBefore(' ')
        if (hash.length != 64) throw IllegalStateException("摘要格式异常：${r.output.take(40)}")
        return hash
    }

    private fun size(path: String): Long {
        val r = shell.exec("stat -c %s ${q(path)}")
        if (!r.ok) throw IllegalStateException("读取大小失败：${r.error}")
        return r.output.trim().toLongOrNull() ?: throw IllegalStateException("大小格式异常")
    }

    private fun q(value: String): String = ShellQuoting.quote(value)
}
