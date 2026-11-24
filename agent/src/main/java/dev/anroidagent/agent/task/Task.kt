package dev.anroidagent.agent.task

import dev.anroidagent.agent.perceive.TuiItem
import dev.anroidagent.core.model.Step

data class TaskSpec(
    val instruction: String,
    val maxSteps: Int = 20,
    val id: String = "t" + (System.currentTimeMillis() % 1_000_000).toString(),
)

enum class TaskStatus {
    /** 模型给出了 DONE */
    DONE,

    /** 步数耗尽 / 连续失败 / 无可用动作 */
    FAILED,

    /** 策略暂停或需要人工接管 */
    PAUSED,

    /** 硬停（secure 窗口等）或用户取消 */
    STOPPED,
}

/**
 * 一帧观测。
 *
 * [image] 非空表示走了视觉兜底；[tui] 仍然保留（便于日志复盘，
 * 也便于模型在图之外还能看到结构化文本）。
 */
data class Frame(
    val tui: String,
    val items: List<TuiItem>,
    val image: ByteArray?,
    val isSecure: Boolean,
    val tuiTokens: Int,
    val packageName: String,
)

fun interface FrameProvider {
    suspend fun capture(): Frame
}

data class StepLog(
    val index: Int,
    val step: Step?,
    /** 步骤的可读描述；持久化时只存这个，不存 Step 对象 */
    val stepText: String,
    val verdict: String,
    val tuiTokens: Int,
    val usedVision: Boolean,
    val outcome: String,
    val raw: String,
    val elapsedMs: Long,
)

data class TaskResult(
    val spec: TaskSpec,
    val status: TaskStatus,
    val message: String,
    val steps: List<StepLog>,
    val promptTokens: Int,
    val elapsedMs: Long,
) {
    val ok: Boolean get() = status == TaskStatus.DONE
}

fun interface StepExecutor {
    suspend fun execute(step: Step, items: List<TuiItem>): Outcome

    sealed interface Outcome {
        data object Ok : Outcome
        data class Failed(val reason: String) : Outcome
    }
}
