package dev.anroidagent.driver.capture

import android.graphics.Bitmap
import android.media.ImageReader
import java.io.ByteArrayOutputStream

/**
 * 屏幕帧的进程内共享点。
 *
 * 由 [ScreenCaptureService] 绑定 `ImageReader`，任何 `Driver` 实现（含追加包）
 * 都能直接取帧，不必各自持有 VirtualDisplay。
 */
object ScreenCapture {

    private val lock = Any()

    private var reader: ImageReader? = null
    private var width: Int = 0
    private var height: Int = 0

    /**
     * 上一帧是否"几乎全黑"。
     *
     * `FLAG_SECURE` 窗口在投屏里就是全黑，这是基础版唯一能拿到的 secure 信号，
     * 交给 `SecureWindowWatch.viaBlackFrame` 判定。
     */
    @Volatile
    var lastFrameUniformlyBlack: Boolean = false
        private set

    val isActive: Boolean
        get() = synchronized(lock) { reader != null }

    fun bind(r: ImageReader, w: Int, h: Int) = synchronized(lock) {
        reader = r
        width = w
        height = h
    }

    fun release() = synchronized(lock) {
        runCatching { reader?.close() }
        reader = null
        width = 0
        height = 0
        lastFrameUniformlyBlack = false
    }

    /**
     * 取最近一帧并编码为 JPEG。返回 null 表示暂无新帧或投屏未就绪。
     *
     * 用 `acquireLatestImage` 而不是 `acquireNextImage`：慢一帧但有帧率上限时
     * 不会堆积，且不会因为处理慢而丢帧阻塞 producer。
     */
    fun latestJpeg(quality: Int = 70): ByteArray? = synchronized(lock) {
        val r = reader ?: return null
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return null

        val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return null

        try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * w
            val paddedWidth = w + rowPadding / pixelStride

            val padded = Bitmap.createBitmap(paddedWidth, h, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)

            val cropped = if (paddedWidth == w) {
                padded
            } else {
                Bitmap.createBitmap(padded, 0, 0, w, h)
            }

            lastFrameUniformlyBlack = isUniformlyBlack(cropped)

            val out = ByteArrayOutputStream(256 * 1024)
            cropped.compress(Bitmap.CompressFormat.JPEG, quality, out)

            if (cropped !== padded) cropped.recycle()
            padded.recycle()

            return out.toByteArray()
        } finally {
            runCatching { image.close() }
        }
    }

    /** 16×16 采样，亮度跨度极小即认为全黑 */
    private fun isUniformlyBlack(bmp: Bitmap): Boolean {
        var max = 0
        val stepX = (bmp.width / 16).coerceAtLeast(1)
        val stepY = (bmp.height / 16).coerceAtLeast(1)

        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                val p = bmp.getPixel(x, y)
                val lum = ((p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF)) / 3
                if (lum > max) max = lum
                x += stepX
            }
            y += stepY
        }
        return max < 8
    }
}
