package dev.anroidagent.core.media

/**
 * dHash（difference hash）。
 *
 * 把图缩到 9x8 灰度，逐行比较相邻像素：左边比右边亮就记 1。
 * 8 行 × 8 次比较 = 64 位，对缩放、亮度微调、轻微裁剪都不敏感，
 * 但能抓住"同一张照片的两次导出"。
 *
 * 纯函数：输入是已经缩放好的灰度数组，不依赖 Bitmap，
 * 所以能在 JVM 单测里验证（Bitmap 在单测里不可用）。
 */
object DHash {

    const val WIDTH = 9
    const val HEIGHT = 8
    const val PIXELS = WIDTH * HEIGHT

    /** [gray] 必须正好是 9x8 个灰度值（0..255，顺序为行优先） */
    fun hash(gray: IntArray): Long {
        require(gray.size == PIXELS) { "expected $PIXELS gray pixels, got ${gray.size}" }

        var bits = 0L
        var bit = 0
        for (y in 0 until HEIGHT) {
            val row = y * WIDTH
            for (x in 0 until WIDTH - 1) {
                if (gray[row + x] > gray[row + x + 1]) {
                    bits = bits or (1L shl bit)
                }
                bit++
            }
        }
        return bits
    }

    fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    /**
     * 灰度化 + 最近邻缩放的取样规则。
     *
     * 放在这里是为了让 Android 层只做"读像素"这一件事，
     * 缩放和灰度算法本身可以单测。
     */
    fun sampleGray(rgba: IntArray, srcWidth: Int, srcHeight: Int): IntArray {
        require(srcWidth > 0 && srcHeight > 0)
        require(rgba.size >= srcWidth * srcHeight)

        val out = IntArray(PIXELS)
        for (y in 0 until HEIGHT) {
            val sy = (y.toLong() * srcHeight / HEIGHT).toInt().coerceIn(0, srcHeight - 1)
            for (x in 0 until WIDTH) {
                val sx = (x.toLong() * srcWidth / WIDTH).toInt().coerceIn(0, srcWidth - 1)
                val c = rgba[sy * srcWidth + sx]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                // 整数近似亮度（0.299/0.587/0.114）
                out[y * WIDTH + x] = (r * 299 + g * 587 + b * 114) / 1000
            }
        }
        return out
    }
}
