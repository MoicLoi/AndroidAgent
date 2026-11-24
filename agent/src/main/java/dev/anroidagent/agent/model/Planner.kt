package dev.anroidagent.agent.model

import dev.anroidagent.agent.act.ActionDecoder
import dev.anroidagent.agent.perceive.TokenEstimate
import dev.anroidagent.agent.prompt.SystemPrompt
import dev.anroidagent.core.model.Step
import dev.anroidagent.core.policy.Risk

/**
 * 单步规划器：把观测（TUI 文本，必要时附截图）喂给模型，拿回 action + risk。
 *
 * ```
 * a11y 树 → Compressor → TuiRenderer ─┐
 *                                     ├→ Planner.next() → ActionDecoder → Step
 *              截图（视觉兜底）────────┘
 * ```
 *
 * 设计约束：
 * - 历史只保留最近 [maxHistory] 轮，避免上下文无界增长（省 token 的一半在这里）
 * - 屏幕内容用 `<screen>` 包裹并声明为数据，防 prompt injection
 * - 解析失败重试一次，第二次附加"只输出 JSON"，仍失败则返回 null 让上层停
 */
class Planner(
    private val client: ModelClient,
    private val decoder: ActionDecoder = ActionDecoder(),
    private val systemPrompt: String = SystemPrompt.build(),
    private val maxHistory: Int = 6,
) {

    data class Plan(
        val step: Step?,
        val risk: Risk?,
        val raw: String,
        val usage: Usage?,
        val promptTokensLocal: Int,
        val error: String? = null,
        /** 本轮是否使用了截图（视觉兜底） */
        val usedVision: Boolean = false,
    ) {
        /** 模型用 ask/module 约定的 DONE 表示任务完成 */
        val isDone: Boolean
            get() = (step as? Step.Ask)?.question?.trim()?.equals("DONE", ignoreCase = true) == true
    }

    suspend fun next(
        history: List<ChatMessage>,
        tui: String,
        image: ByteArray? = null,
        instruction: String? = null,
    ): Plan {
        val messages = buildList {
            add(ChatMessage.system(systemPrompt))
            addAll(history.takeLast(maxHistory))
            add(observation(tui, image, instruction))
        }

        val localTokens = messages.sumOf { TokenEstimate.of(it.content) } +
            messages.count { it.images.isNotEmpty() } * IMAGE_TOKEN_ESTIMATE

        var completion = runCatching { client.complete(messages) }.getOrElse {
            return Plan(null, null, "", null, localTokens, "client: ${it.message}", image != null)
        }

        var decoded = decoder.decode(completion.text)
        if (decoded.step == null) {
            // 一次修复重试：把模型的错误输出回灌，要求只输出 JSON
            val repair = messages + listOf(
                ChatMessage.assistant(completion.text),
                ChatMessage.user(
                    "上一条输出无法解析（${decoded.error}）。只输出一个 JSON 对象，" +
                        "不要任何解释、不要 markdown 代码块。",
                ),
            )
            completion = runCatching { client.complete(repair) }.getOrElse {
                return Plan(
                    null, decoded.risk, completion.text, completion.usage,
                    localTokens, "client: ${it.message}", image != null,
                )
            }
            decoded = decoder.decode(completion.text)
        }

        return Plan(
            step = decoded.step,
            risk = decoded.risk,
            raw = completion.text,
            usage = completion.usage,
            promptTokensLocal = localTokens,
            error = decoded.error,
            usedVision = image != null,
        )
    }

    private fun observation(tui: String, image: ByteArray?, instruction: String?): ChatMessage {
        val body = buildString {
            if (!instruction.isNullOrBlank()) {
                appendLine("任务：$instruction")
            }
            if (tui.isNotBlank()) {
                appendLine(SystemPrompt.SCREEN_OPEN)
                appendLine(tui)
                appendLine(SystemPrompt.SCREEN_CLOSE)
            }
            if (image != null) {
                appendLine("（已附当前屏幕截图：无障碍信息不足，请直接看图定位元素）")
            }
        }.trim()

        return ChatMessage(
            role = "user",
            content = body,
            images = listOfNotNull(image),
        )
    }

    companion object {
        /** 一张 1080p 截图的粗略 token 估计，仅用于本地对账 */
        const val IMAGE_TOKEN_ESTIMATE = 1500
    }
}
