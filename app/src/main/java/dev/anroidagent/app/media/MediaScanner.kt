package dev.anroidagent.app.media

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import dev.anroidagent.core.media.DHash
import dev.anroidagent.core.media.MediaItem

/**
 * 相册扫描器。
 *
 * 两条关键取舍：
 * 1. **走 MediaStore，不走文件遍历**——系统已经索引好了，一次 query 拿全
 * 2. **哈希用系统缩略图**，不解原图（`loadThumbnail` 命中系统缓存，
 *    比 `BitmapFactory.decodeFile` 快一个数量级）
 *
 * 需要 `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO`（API 33+）或
 * `READ_EXTERNAL_STORAGE`（更低版本）。也可以用高级操作里的 `pm grant` 授权。
 */
class MediaScanner(private val ctx: Context) {

    fun scan(limit: Int = 20_000): List<MediaItem> {
        val out = ArrayList<MediaItem>(minOf(limit, 4096))
        out += queryImages(limit)
        out += queryVideos(limit)
        return out
    }

    /**
     * 给照片补 dHash。视频跳过（缩略图代表的是首帧，去重意义不大）。
     *
     * 单张失败不影响整批：读不到缩略图的项保持 hash = null，
     * 后续 planner 会把它计入 skippedNoHash，而不是当成"独一无二"。
     *
     * [shouldStop] 每张检查一次，支持中途取消——大相册跑几分钟没有中断入口是不能接受的。
     */
    fun hashPhotos(
        items: List<MediaItem>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        shouldStop: () -> Boolean = { false },
    ): List<MediaItem> {
        val photos = items.filter { !it.isVideo }
        val hashes = HashMap<String, Long>(photos.size)
        var done = 0

        onProgress(0, photos.size)

        for (item in photos) {
            if (shouldStop()) break

            val uri = imageUri(item.id)
            if (uri != null) {
                runCatching { hashOf(uri) }.getOrNull()?.let { hashes[item.id] = it }
            }

            done++
            if (done % 25 == 0) onProgress(done, photos.size)
        }

        onProgress(done, photos.size)

        return items.map { item ->
            if (item.isVideo) item else item.copy(hash = hashes[item.id])
        }
    }

    // ---------------- 内部 ----------------

    private fun imageUri(id: String): Uri? = id.toLongOrNull()?.let {
        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it)
    }

    private fun hashOf(uri: Uri): Long {
        val bmp: Bitmap = ctx.contentResolver.loadThumbnail(
            uri,
            Size(DHash.WIDTH, DHash.HEIGHT),
            null,
        )

        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        bmp.recycle()

        // 缩略图可能不是正好 9x8，用 sampleGray 再归一到 9x8
        return DHash.hash(DHash.sampleGray(pixels, w, h))
    }

    private fun queryImages(limit: Int): List<MediaItem> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.DATA,
        )

        return runCatching {
            ctx.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.MediaColumns.SIZE} DESC",
            )?.use { c ->
                buildList {
                    while (c.moveToNext() && size < limit) {
                        val id = c.getString(0) ?: continue
                        val path = c.getString(6).orEmpty()
                        if (path.isBlank()) continue
                        add(
                            MediaItem(
                                id = id,
                                path = path,
                                sizeBytes = c.getLong(1),
                                dateTakenMs = takenAt(c.getLong(2), c.getLong(3)),
                                width = c.getInt(4),
                                height = c.getInt(5),
                            ),
                        )
                    }
                }
            }
        }.getOrNull() ?: emptyList()
    }

    private fun queryVideos(limit: Int): List<MediaItem> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.DATA,
        )

        return runCatching {
            ctx.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.MediaColumns.SIZE} DESC",
            )?.use { c ->
                buildList {
                    while (c.moveToNext() && size < limit) {
                        val id = c.getString(0) ?: continue
                        val path = c.getString(5).orEmpty()
                        if (path.isBlank()) continue
                        add(
                            MediaItem(
                                id = id,
                                path = path,
                                sizeBytes = c.getLong(1),
                                dateTakenMs = takenAt(c.getLong(2), c.getLong(3)),
                                isVideo = true,
                                durationMs = c.getLong(4),
                            ),
                        )
                    }
                }
            }
        }.getOrNull() ?: emptyList()
    }

    /** DATE_TAKEN 常为空，退回 DATE_MODIFIED（秒 → 毫秒） */
    private fun takenAt(taken: Long, modifiedSec: Long): Long =
        if (taken > 0) taken else modifiedSec * 1000
}
