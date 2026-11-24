package dev.anroidagent.app.script

import org.json.JSONArray
import org.json.JSONObject

/**
 * 待审脚本。
 *
 * 模型被策略拦截的 shell 命令会落成这个物件——**只是数据，不会自动执行**。
 * 唯一能触发执行的路径是用户在界面上点"执行"。
 */
data class PendingScript(
    val id: String,
    val createdAt: Long,
    val reason: String,
    val command: String,
    val source: String = "shell-blocked",
) {
    val fileName: String get() = "$id.sh"

    /** 列表里显示的一行摘要 */
    fun summary(): String = command.lineSequence().firstOrNull().orEmpty().take(60)
}

object ScriptCodec {

    fun encode(list: List<PendingScript>): String = JSONArray().apply {
        list.forEach { s ->
            put(
                JSONObject().apply {
                    put("id", s.id)
                    put("createdAt", s.createdAt)
                    put("reason", s.reason)
                    put("command", s.command)
                    put("source", s.source)
                },
            )
        }
    }.toString()

    fun decode(text: String?): List<PendingScript> {
        if (text.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").takeIf { isSafeId(it) } ?: return@mapNotNull null
                PendingScript(
                    id = id,
                    createdAt = o.optLong("createdAt", 0L),
                    reason = o.optString("reason"),
                    command = o.optString("command"),
                    source = o.optString("source", "shell-blocked"),
                )
            }
        }.getOrDefault(emptyList())
    }

    /**
     * id 会拼进文件路径与远端路径，必须严格限制字符集。
     * 这是本模块唯一需要防注入的地方——脚本**内容**是用户确认过的，不由我们校验。
     */
    fun isSafeId(id: String): Boolean =
        id.isNotEmpty() && id.length <= 40 && id.all { it.isLetterOrDigit() || it == '-' }
}
