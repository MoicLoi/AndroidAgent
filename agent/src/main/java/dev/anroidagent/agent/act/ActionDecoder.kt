package dev.anroidagent.agent.act

import dev.anroidagent.core.model.Step
import dev.anroidagent.core.policy.Risk
import dev.anroidagent.core.policy.RiskClass
import org.json.JSONObject

/**
 * 模型输出解码器。
 *
 * 约定模型每次只输出一个 JSON 对象：
 * ```
 * {"action": {...}, "risk": null}
 * ```
 * 索引引用（`index`）而非坐标，配合本地映射可把 grounding 错误率降一个数量级。
 *
 * 生产环境应配合约束解码（JSON Schema / grammar）使用；本解码器负责容错兜底。
 * 用 Android 内置 `org.json`，不引入序列化编译器插件（减少版本耦合）。
 */
class ActionDecoder {

    data class Decoded(
        val step: Step?,
        val risk: Risk?,
        val error: String? = null,
    )

    fun decode(raw: String): Decoded {
        val body = extractJson(raw) ?: return Decoded(null, null, "no-json")

        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: return Decoded(null, null, "bad-json")

        val risk = root.optJSONObject("risk")?.let { parseRisk(it) }

        val action = root.optJSONObject("action")
            ?: return Decoded(null, risk, "no-action")

        val step = stepOf(action) ?: return Decoded(null, risk, "unsupported-action")
        return Decoded(step, risk)
    }

    private fun parseRisk(o: JSONObject): Risk? {
        val cls = o.optString("class").trim()
        if (cls.isEmpty() || cls.equals("null", true)) return null

        val mapped = runCatching { RiskClass.valueOf(cls.uppercase()) }.getOrNull()
            ?: RiskClass.UNKNOWN_SENSITIVE

        return Risk(
            cls = mapped,
            evidence = o.optString("evidence"),
            conf = o.optDouble("conf", 0.0).toFloat(),
        )
    }

    private fun stepOf(a: JSONObject): Step? = when (a.optString("type").lowercase()) {

        "tap" -> {
            val idx = a.optInt("index", -1)
            if (idx <= 0) null else Step.Tap(idx, a.optString("label"))
        }

        "type" -> {
            val idx = a.optInt("index", -1)
            if (idx <= 0) {
                null
            } else {
                Step.Type(
                    index = idx,
                    text = a.optString("text"),
                    targetLabel = a.optString("label"),
                    targetHint = a.optString("hint"),
                )
            }
        }

        "swipe" -> {
            val x1 = a.optInt("x1", -1)
            val y1 = a.optInt("y1", -1)
            val x2 = a.optInt("x2", -1)
            val y2 = a.optInt("y2", -1)
            if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) {
                null
            } else {
                Step.Swipe(x1, y1, x2, y2, a.optLong("ms", 300L))
            }
        }

        "launch" -> Step.Launch(a.optString("app"))

        "back" -> Step.Back

        "wait" -> Step.Wait(a.optLong("ms", 500L))

        "ask" -> Step.Ask(a.optString("question"))

        "shell" -> {
            val cmd = a.optString("cmd").trim()
            if (cmd.isEmpty()) null else Step.Shell(cmd)
        }

        "scroll" -> Step.Scroll

        else -> null
    }

    private fun extractJson(raw: String): String? {
        val s = raw.indexOf('{')
        val e = raw.lastIndexOf('}')
        if (s < 0 || e <= s) return null
        return raw.substring(s, e + 1)
    }
}

/**
 * 动作 → 索引引用校验：模型可能引用上一帧已消失的索引，
 * 执行前必须校验存在性，否则会把动作打到错误元素上。
 */
object IndexResolver {

    fun resolve(step: Step, itemCount: Int): Step? = when (step) {
        is Step.Tap -> if (step.index in 1..itemCount) step else null
        is Step.Type -> if (step.index in 1..itemCount) step else null
        else -> step
    }
}
