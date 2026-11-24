package dev.anroidagent.app.media

/**
 * 扫描进度文案。
 *
 * 抽成纯函数是因为它有几个容易出错的边界：done = 0 时不能算 ETA（除零）、
 * done > total（扫描期间新增文件）不能超过 100%、秒/分进位。
 */
object ProgressText {

    fun fmtDuration(ms: Long): String {
        if (ms < 0) return "0s"
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return if (min > 0) "${min}m${sec}s" else "${sec}s"
    }

    /**
     * @param done 已处理数
     * @param total 总数（可为 0：表示还没拿到列表）
     * @param elapsedMs 已耗时
     * @param phase 阶段名（"读取媒体库" / "哈希" / "分组"）
     */
    fun scan(done: Int, total: Int, elapsedMs: Long, phase: String = "哈希"): String {
        if (total <= 0) {
            return "$phase 中… 已用 ${fmtDuration(elapsedMs)}"
        }

        val safeDone = done.coerceIn(0, total)
        val percent = safeDone * 100 / total
        val head = "$phase $safeDone/$total（$percent%）· 已用 ${fmtDuration(elapsedMs)}"

        return when {
            safeDone >= total -> head
            safeDone == 0 -> "$head · 估算中…"
            else -> {
                val perItem = elapsedMs.toDouble() / safeDone
                val remain = (perItem * (total - safeDone)).toLong()
                "$head · 约剩 ${fmtDuration(remain)}"
            }
        }
    }
}
