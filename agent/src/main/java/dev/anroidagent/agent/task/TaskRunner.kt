package dev.anroidagent.agent.task

import dev.anroidagent.agent.model.ChatMessage
import dev.anroidagent.agent.model.Planner
import dev.anroidagent.core.model.Step
import dev.anroidagent.core.policy.ActionGuard
import dev.anroidagent.core.policy.PolicyDecider
import dev.anroidagent.core.policy.Verdict

/**
 * 任务状态机。
 *
 * 刻意**不用**自由 ReAct 循环：每一步的失败都要能落到具体状态上，
 * 否则用户看到的就是"跑了一会儿，不知道为啥停了"。
 *
 * 状态迁移：
 * ```
 * RUNNING ──模型给 DONE──────────→ DONE
 *         ──策略硬升级 / Ask────→ PAUSED（等人工）
 *         ──secure 窗口 / Kill──→ STOPPED（硬停）
 *         ──无动作 / 连续失败───→ FAILED
 *         ──步数耗尽───────────→ FAILED
 * ```
 *
 * [onProgress] 是心跳：每步开始前回调一次，供上层把 [ActiveTask.stepIndex] 落盘。
 */
class TaskRunner(
    private val planner: Planner,
    private val maxConsecutiveFailures: Int = 2,
) {

    suspend fun run(
        spec: TaskSpec,
        frames: FrameProvider,
        executor: StepExecutor,
        onProgress: (Int) -> Unit = {},
        onBlocked: (Step, String) -> Unit = { _, _ -> },
    ): TaskResult {
        val startedAt = System.currentTimeMillis()
        val logs = ArrayList<StepLog>(spec.maxSteps)
        var promptTokens = 0
        var failures = 0

        fun result(status: TaskStatus, message: String) = TaskResult(
            spec = spec,
            status = status,
            message = message,
            steps = logs.toList(),
            promptTokens = promptTokens,
            elapsedMs = System.currentTimeMillis() - startedAt,
        )

        for (i in 1..spec.maxSteps) {
            onProgress(i)

            val t0 = System.currentTimeMillis()
            val frame = frames.capture()

            // 1. secure 窗口优先于一切：连模型都不该调
            if (frame.isSecure) {
                logs += log(i, null, "KILL", frame, "secure-window", "", 0)
                return result(TaskStatus.STOPPED, "命中 FLAG_SECURE，已硬停（第 $i 步）")
            }

            // 2. 规划
            val plan = planner.next(
                history = historyOf(logs),
                tui = frame.tui,
                image = frame.image,
                instruction = spec.instruction,
            )
            promptTokens += plan.usage?.promptTokens ?: plan.promptTokensLocal

            // 3. 策略判定（单调升级）
            val stepVerdict = plan.step?.let { ActionGuard.check(it) } ?: Verdict.Pass
            val verdict = PolicyDecider.decide(null, stepVerdict, plan.risk)

            if (verdict is Verdict.Kill) {
                logs += log(i, plan.step, verdict.toString(), frame, "kill", plan.raw, 0)
                return result(TaskStatus.STOPPED, "硬停：${verdict.reason}")
            }

            if (plan.isDone) {
                logs += log(i, plan.step, "DONE", frame, "done", plan.raw, 0)
                return result(TaskStatus.DONE, "任务完成（第 $i 步）")
            }

            if (verdict is Verdict.Escalate && !verdict.soft) {
                logs += log(i, plan.step, "ESCALATE:${verdict.risk.cls}", frame, "paused", plan.raw, 0)
                // 被拦的动作交给上层处置（例如落成待审脚本），绝不由本类执行
                plan.step?.let { step -> runCatching { onBlocked(step, verdict.risk.evidence) } }
                return result(
                    TaskStatus.PAUSED,
                    "暂停：${verdict.risk.cls}（${verdict.risk.evidence.take(40)}），需人工确认",
                )
            }

            val step = plan.step
            if (step == null) {
                logs += log(i, null, "NO-STEP", frame, plan.error ?: "empty", plan.raw, 0)
                return result(
                    TaskStatus.FAILED,
                    "模型没有给出可执行动作：${plan.error ?: plan.raw.take(80)}",
                )
            }

            if (step is Step.Ask) {
                logs += log(i, step, "ASK", frame, "handoff", plan.raw, 0)
                return result(TaskStatus.PAUSED, "需要人工接管：${step.question}")
            }

            // 4. 执行
            val outcome = executor.execute(step, frame.items)
            val elapsed = System.currentTimeMillis() - t0

            when (outcome) {
                is StepExecutor.Outcome.Ok -> {
                    failures = 0
                    logs += log(i, step, "OK", frame, "ok", plan.raw, elapsed)
                }

                is StepExecutor.Outcome.Failed -> {
                    failures++
                    logs += log(i, step, "FAIL", frame, "failed: ${outcome.reason}", plan.raw, elapsed)
                    if (failures >= maxConsecutiveFailures) {
                        return result(TaskStatus.FAILED, "连续 $failures 次执行失败：${outcome.reason}")
                    }
                }
            }
        }

        return result(TaskStatus.FAILED, "达到最大步数 ${spec.maxSteps}")
    }

    private fun log(
        index: Int,
        step: Step?,
        verdict: String,
        frame: Frame,
        outcome: String,
        raw: String,
        elapsedMs: Long,
    ) = StepLog(
        index = index,
        step = step,
        stepText = step?.toString() ?: "-",
        verdict = verdict,
        tuiTokens = frame.tuiTokens,
        usedVision = frame.image != null,
        outcome = outcome,
        raw = raw,
        elapsedMs = elapsedMs,
    )

    /** 把执行历史压成对话：模型需要知道自己做过什么，否则会原地打转 */
    private fun historyOf(logs: List<StepLog>): List<ChatMessage> = buildList {
        logs.forEach { log ->
            if (log.raw.isNotBlank()) add(ChatMessage.assistant(log.raw))
            add(ChatMessage.user("上一步（${log.stepText}）执行结果：${log.outcome}"))
        }
    }
}
