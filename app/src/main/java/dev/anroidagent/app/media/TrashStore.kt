package dev.anroidagent.app.media

import dev.anroidagent.core.media.TrashEntry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 暂存区索引的编解码。
 *
 * 这个索引是**还原的唯一依据**：暂存区里的文件名已经和原文件不同
 * （`0001-IMG_0001.jpg`），丢了索引就等于丢了原始路径，文件还在但回不去。
 *
 * 纯函数（String ↔ 对象），可在 JVM 单测里验证。
 */
object TrashCodec {

    fun encode(entries: List<TrashEntry>): String = JSONArray().apply {
        entries.forEach { e ->
            put(
                JSONObject().apply {
                    put("mediaId", e.mediaId)
                    put("srcPath", e.srcPath)
                    put("trashName", e.trashName)
                    put("sizeBytes", e.sizeBytes)
                    put("movedAtMs", e.movedAtMs)
                    put("sha256", e.sha256 ?: "")
                },
            )
        }
    }.toString()

    fun decode(text: String?): List<TrashEntry> {
        if (text.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val src = o.optString("srcPath")
                val name = o.optString("trashName")
                // 缺路径或缺文件名的记录无法还原，直接丢弃
                if (src.isBlank() || name.isBlank()) return@mapNotNull null

                TrashEntry(
                    mediaId = o.optString("mediaId"),
                    srcPath = src,
                    trashName = name,
                    sizeBytes = o.optLong("sizeBytes", 0L),
                    movedAtMs = o.optLong("movedAtMs", 0L),
                    sha256 = o.optString("sha256").takeIf { it.length == 64 },
                )
            }
        }.getOrDefault(emptyList())
    }
}

/**
 * 暂存区索引存储。与 TaskStore / ScriptStore 同一套路：
 * 临时文件 + rename，读取失败降级为空，坏档不能阻止 App 启动。
 */
class TrashStore(dir: File) {

    private val base: File = dir.apply { mkdirs() }
    private val indexFile = File(base, "index.json")
    private val lock = Any()

    fun list(): List<TrashEntry> = TrashCodec.decode(read())

    fun count(): Int = list().size

    fun add(entries: List<TrashEntry>) {
        if (entries.isEmpty()) return
        synchronized(lock) {
            val all = (list() + entries).takeLast(MAX)
            write(TrashCodec.encode(all))
        }
    }

    fun removeAll(trashNames: Set<String>) {
        if (trashNames.isEmpty()) return
        synchronized(lock) {
            write(TrashCodec.encode(list().filterNot { it.trashName in trashNames }))
        }
    }

    fun clear() = synchronized(lock) { write("[]") }

    private fun read(): String? = runCatching {
        if (indexFile.exists()) indexFile.readText() else null
    }.getOrNull()

    private fun write(text: String) {
        runCatching {
            val tmp = File(base, indexFile.name + ".tmp")
            tmp.writeText(text)
            if (indexFile.exists()) indexFile.delete()
            tmp.renameTo(indexFile)
        }
    }

    private companion object {
        const val MAX = 5000
    }
}
