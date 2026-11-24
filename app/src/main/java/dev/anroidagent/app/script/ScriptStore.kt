package dev.anroidagent.app.script

import java.io.File

/**
 * 待审脚本的落盘存储。
 *
 * 目录：`filesDir/scripts/`
 * - `index.json`     元数据（id / 时间 / 拦截原因 / 来源）
 * - `<id>.sh`        脚本文本
 *
 * 与 `TaskStore` 一样的原则：写入用临时文件 + rename，读取失败降级为空，
 * 坏文件不能让 App 起不来。
 */
class ScriptStore(dir: File) {

    private val base: File = dir.apply { mkdirs() }
    private val indexFile = File(base, "index.json")
    private val lock = Any()

    fun list(): List<PendingScript> = ScriptCodec.decode(read(indexFile))

    fun count(): Int = list().size

    /**
     * 落一份草稿。达到上限返回 null（不静默顶掉旧脚本——那等于偷偷丢证据）。
     */
    fun add(script: PendingScript): PendingScript? = synchronized(lock) {
        val all = list()
        if (all.size >= MAX) return null
        if (!ScriptCodec.isSafeId(script.id)) return null

        runCatching { File(base, script.fileName).writeText(script.command) }
        write(indexFile, ScriptCodec.encode(all + script))
        return script
    }

    fun get(id: String): PendingScript? = list().firstOrNull { it.id == id }

    /** 优先读文件，文件丢了就退回索引里存的副本 */
    fun content(script: PendingScript): String =
        runCatching { File(base, script.fileName).readText() }.getOrDefault(script.command)

    fun remove(id: String): Boolean = synchronized(lock) {
        val all = list()
        val target = all.firstOrNull { it.id == id } ?: return false
        runCatching { File(base, target.fileName).delete() }
        write(indexFile, ScriptCodec.encode(all - target))
        return true
    }

    fun clear(): Int = synchronized(lock) {
        val all = list()
        all.forEach { runCatching { File(base, it.fileName).delete() } }
        write(indexFile, "[]")
        all.size
    }

    private fun read(file: File): String? = runCatching {
        if (file.exists()) file.readText() else null
    }.getOrNull()

    private fun write(file: File, text: String) {
        runCatching {
            val tmp = File(base, file.name + ".tmp")
            tmp.writeText(text)
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }
    }

    private companion object {
        const val MAX = 20
    }
}
