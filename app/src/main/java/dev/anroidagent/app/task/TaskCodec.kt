package dev.anroidagent.app.task

import dev.anroidagent.agent.task.ActiveTask
import dev.anroidagent.agent.task.StepLog
import dev.anroidagent.agent.task.TaskResult
import dev.anroidagent.agent.task.TaskSpec
import dev.anroidagent.agent.task.TaskStatus
import org.json.JSONArray
import org.json.JSONObject

/**
 * 任务持久化的编解码。
 *
 * 纯函数（String ↔ 对象），因此可以在 JVM 单测里验证，
 * 不需要真机。文件 IO 在 [FileTaskStore] 里，这里只管格式。
 *
 * 存的东西**只有**：任务描述、状态、步数摘要。
 * 不存截图、不存屏幕文本、不存任何账号或凭证。
 */
object TaskCodec {

    private const val RAW_LIMIT = 200
    private const val MAX_RESULTS = 10

    // ---------------- 队列 ----------------

    fun encodeQueue(specs: List<TaskSpec>): String =
        JSONArray().apply { specs.forEach { put(specToJson(it)) } }.toString()

    fun decodeQueue(text: String?): List<TaskSpec> {
        if (text.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let(::specFromJson)
            }
        }.getOrDefault(emptyList())
    }

    // ---------------- 运行中快照 ----------------

    fun encodeActive(active: ActiveTask): String = JSONObject().apply {
        put("spec", specToJson(active.spec))
        put("startedAt", active.startedAt)
        put("stepIndex", active.stepIndex)
    }.toString()

    fun decodeActive(text: String?): ActiveTask? {
        if (text.isNullOrBlank()) return null
        return runCatching {
            val o = JSONObject(text)
            val spec = o.optJSONObject("spec")?.let(::specFromJson) ?: return null
            ActiveTask(
                spec = spec,
                startedAt = o.optLong("startedAt", 0L),
                stepIndex = o.optInt("stepIndex", 0),
            )
        }.getOrNull()
    }

    // ---------------- 结果 ----------------

    fun encodeResults(results: List<TaskResult>): String =
        JSONArray().apply {
            results.takeLast(MAX_RESULTS).forEach { put(resultToJson(it)) }
        }.toString()

    fun decodeResults(text: String?): List<TaskResult> {
        if (text.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let(::resultFromJson)
            }
        }.getOrDefault(emptyList())
    }

    // ---------------- 内部 ----------------

    private fun specToJson(spec: TaskSpec) = JSONObject().apply {
        put("id", spec.id)
        put("instruction", spec.instruction)
        put("maxSteps", spec.maxSteps)
    }

    private fun specFromJson(o: JSONObject) = TaskSpec(
        instruction = o.optString("instruction"),
        maxSteps = o.optInt("maxSteps", 20),
        id = o.optString("id").ifBlank { "restored" },
    )

    private fun resultToJson(r: TaskResult) = JSONObject().apply {
        put("spec", specToJson(r.spec))
        put("status", r.status.name)
        put("message", r.message)
        put("promptTokens", r.promptTokens)
        put("elapsedMs", r.elapsedMs)
        put(
            "steps",
            JSONArray().apply {
                r.steps.forEach { s ->
                    put(
                        JSONObject().apply {
                            put("index", s.index)
                            put("stepText", s.stepText)
                            put("verdict", s.verdict)
                            put("tuiTokens", s.tuiTokens)
                            put("usedVision", s.usedVision)
                            put("outcome", s.outcome)
                            put("raw", s.raw.take(RAW_LIMIT))
                            put("elapsedMs", s.elapsedMs)
                        },
                    )
                }
            },
        )
    }

    private fun resultFromJson(o: JSONObject): TaskResult {
        val spec = o.optJSONObject("spec")?.let(::specFromJson) ?: TaskSpec("restored")
        val status = runCatching {
            TaskStatus.valueOf(o.optString("status", TaskStatus.FAILED.name))
        }.getOrDefault(TaskStatus.FAILED)

        val stepsArr = o.optJSONArray("steps")
        val steps = if (stepsArr == null) {
            emptyList()
        } else {
            (0 until stepsArr.length()).mapNotNull { i ->
                stepsArr.optJSONObject(i)?.let { s ->
                    StepLog(
                        index = s.optInt("index", i + 1),
                        step = null, // 只存可读文本，不重建 Step
                        stepText = s.optString("stepText", "-"),
                        verdict = s.optString("verdict", ""),
                        tuiTokens = s.optInt("tuiTokens", 0),
                        usedVision = s.optBoolean("usedVision", false),
                        outcome = s.optString("outcome", ""),
                        raw = s.optString("raw", ""),
                        elapsedMs = s.optLong("elapsedMs", 0),
                    )
                }
            }
        }

        return TaskResult(
            spec = spec,
            status = status,
            message = o.optString("message", ""),
            steps = steps,
            promptTokens = o.optInt("promptTokens", 0),
            elapsedMs = o.optLong("elapsedMs", 0),
        )
    }
}
