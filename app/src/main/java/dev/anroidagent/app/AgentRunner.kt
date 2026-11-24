package dev.anroidagent.app

import android.content.Context
import dev.anroidagent.agent.model.ModelConfig
import dev.anroidagent.agent.model.Usage
import dev.anroidagent.agent.perceive.Compressor
import dev.anroidagent.agent.perceive.TokenEstimate
import dev.anroidagent.agent.perceive.TuiRenderer
import dev.anroidagent.agent.perceive.VisionGate
import dev.anroidagent.agent.task.Frame
import dev.anroidagent.agent.task.FrameProvider
import dev.anroidagent.agent.task.StepExecutor
import dev.anroidagent.agent.task.TaskResult
import dev.anroidagent.agent.task.TaskRunner
import dev.anroidagent.agent.task.TaskSpec
import dev.anroidagent.core.model.PageContext
import dev.anroidagent.core.model.Step
import dev.anroidagent.core.policy.ActionGuard
import dev.anroidagent.core.policy.PolicyDecider
import dev.anroidagent.core.policy.RuleHit
import dev.anroidagent.core.policy.TargetGuard
import dev.anroidagent.core.policy.Verdict
import dev.anroidagent.driver.Driver
import kotlinx.coroutines.delay

/**
 * 把感知、规划、执行、策略接到一起。
 *
 * 这里**只做装配**，不含业务逻辑：
 * - 感知：无障碍树 → TUI；信息量不足时自动附截图（视觉兜底）
 * - 执行：Step → Driver 调用；索引越界直接判失败而不是乱点
 * - 策略：动作级红线 + 单调升级，判定与执行严格分两步
 */
object AgentRunner {

    // ---------------- 单步（调试用） ----------------

    data class Outcome(
        val ok: Boolean,
        val headline: String,
        val detail: String,
        val step: Step? = null,
        val verdict: Verdict? = null,
        val tuiTokens: Int = 0,
        val usedVision: Boolean = false,
        val usage: Usage? = null,
    )

    suspend fun runOnce(ctx: Context, cfg: ModelConfig): Outcome {
        precheck(ctx)?.let { return failure(it.first, it.second) }

        val frame = frameProvider(ctx).capture()

        if (frame.isSecure) {
            return failure("命中 secure 窗口", "截图全黑或 dumpsys 报告 FLAG_SECURE，按策略硬停")
        }

        val planner = AppGraph.planner(cfg)
        val plan = planner.next(emptyList(), frame.tui, frame.image)

        val stepVerdict = plan.step?.let { ActionGuard.check(it) } ?: Verdict.Pass
        val verdict = PolicyDecider.decide(null, stepVerdict, plan.risk)

        val headline = when {
            verdict is Verdict.Kill -> "硬停：${verdict.reason}"
            verdict is Verdict.Escalate && !verdict.soft -> "暂停：${verdict.risk.cls}"
            plan.step == null -> "无动作"
            else -> "step: ${plan.step}"
        }

        val detail = buildString {
            appendLine("目标 = ${frame.packageName}")
            appendLine("TUI  = ${frame.items.size} 元素 / ${frame.tuiTokens} token")
            appendLine("视觉 = " + if (frame.image != null) "已兜底（附截图）" else "未使用")
            appendLine()
            appendLine(frame.tui)
            appendLine()
            appendLine("== 模型原始输出 ==")
            appendLine(plan.raw.take(400))
            appendLine()
            appendLine("step    = ${plan.step}")
            appendLine("risk    = ${plan.risk}")
            appendLine("verdict = $verdict")
            appendLine("通道    = ${dev.anroidagent.driver.InputChannelProbe.label()}")
            appendLine(
                "usage   = prompt ${plan.usage?.promptTokens ?: "-"} / " +
                    "completion ${plan.usage?.completionTokens ?: "-"}",
            )
            appendLine("本地估算 = 输入 ${plan.promptTokensLocal} token / TUI ${frame.tuiTokens} token")
            plan.error?.let { appendLine("decode  = $it") }
        }

        return Outcome(
            ok = plan.step != null && verdict !is Verdict.Kill,
            headline = headline,
            detail = detail,
            step = plan.step,
            verdict = verdict,
            tuiTokens = frame.tuiTokens,
            usedVision = frame.image != null,
            usage = plan.usage,
        )
    }

    // ---------------- 多步任务 ----------------

    suspend fun runTask(
        ctx: Context,
        cfg: ModelConfig,
        spec: TaskSpec,
        onProgress: (Int) -> Unit = {},
    ): TaskResult {
        precheck(ctx)?.let { (headline, detail) ->
            return TaskResult(
                spec = spec,
                status = dev.anroidagent.agent.task.TaskStatus.FAILED,
                message = "$headline：$detail",
                steps = emptyList(),
                promptTokens = 0,
                elapsedMs = 0,
            )
        }

        val planner = AppGraph.planner(cfg)
        return TaskRunner(planner).run(
            spec = spec,
            frames = frameProvider(ctx),
            executor = stepExecutor(ctx),
            onProgress = onProgress,
            // 被策略拦下的 shell 命令落成待审脚本：只落盘，等人点
            onBlocked = { step, reason ->
                if (step is Step.Shell) AppGraph.draftScript(step.cmd, reason)
            },
        )
    }

    // ---------------- 感知 ----------------

    fun frameProvider(ctx: Context): FrameProvider = FrameProvider {
        // 有 shell 就用 dumpsys 精确刷新前台包名（A11y 事件会滞后）
        AppGraph.refreshForeground()

        val driver = AppGraph.guardedDriver()
            ?: throw IllegalStateException("没有可用驱动：无障碍未连接")

        val tree = driver.tree()
            ?: throw IllegalStateException("无法读取无障碍树")

        val dm = ctx.resources.displayMetrics
        val target = AppGraph.lastForeground.ifBlank { ctx.packageName }
        val secure = AppGraph.isSecureWindow()

        val page = PageContext(
            packageName = target,
            screenWidth = dm.widthPixels,
            screenHeight = dm.heightPixels,
            step = 1,
            isSecureWindow = secure,
        )

        val items = Compressor().compress(tree, page.screen)
        val tui = TuiRenderer().render(page, items)

        // 视觉兜底：只在无障碍信息量不足时才付截图的钱
        val needsVision = VisionGate.needsVision(tree)
        val image = if (needsVision && !secure) {
            runCatching { driver.shot() }.getOrNull()
        } else {
            null
        }

        Frame(
            tui = tui,
            items = items,
            image = image,
            isSecure = secure,
            tuiTokens = TokenEstimate.of(tui),
            packageName = target,
        )
    }

    // ---------------- 执行 ----------------

    fun stepExecutor(ctx: Context): StepExecutor = StepExecutor { step, items ->
        val driver: Driver = AppGraph.guardedDriver()
            ?: return@StepExecutor StepExecutor.Outcome.Failed("没有可用驱动")

        try {
            when (step) {
                is Step.Tap -> {
                    val item = items.getOrNull(step.index - 1)
                        ?: throw IllegalStateException("索引 ${step.index} 已失效（共 ${items.size} 项）")
                    driver.tap(item.bounds.centerX, item.bounds.centerY)
                }

                is Step.Type -> {
                    val item = items.getOrNull(step.index - 1)
                        ?: throw IllegalStateException("索引 ${step.index} 已失效")
                    driver.tap(item.bounds.centerX, item.bounds.centerY)
                    delay(150)
                    driver.inputText(step.text)
                }

                is Step.Swipe -> driver.swipe(
                    step.fromX, step.fromY, step.toX, step.toY, step.durationMs,
                )

                is Step.Launch -> driver.launch(step.pkg)

                Step.Back -> driver.back()

                is Step.Wait -> delay(step.ms)

                Step.Scroll -> {
                    val dm = ctx.resources.displayMetrics
                    driver.swipe(
                        dm.widthPixels / 2,
                        (dm.heightPixels * 0.75).toInt(),
                        dm.widthPixels / 2,
                        (dm.heightPixels * 0.25).toInt(),
                        300L,
                    )
                }

                is Step.Ask -> Unit

                is Step.Shell -> {
                    val result = AppGraph.runShellRaw(step.cmd)
                        ?: throw IllegalStateException("shell 通道不可用（需要追加包）")
                    if (!result.ok) {
                        throw IllegalStateException(result.error ?: "命令执行失败")
                    }
                }
            }
            StepExecutor.Outcome.Ok
        } catch (e: Exception) {
            StepExecutor.Outcome.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    // ---------------- 前置自查 ----------------

    /**
     * 守卫是 fail-closed 的：目标不在白名单时 `driver.tree()` 会直接杀进程。
     * 所以必须在碰驱动之前自查，把"被杀"变成一条可读的错误。
     */
    private fun precheck(ctx: Context): Pair<String, String>? {
        if (AppGraph.guardedDriver() == null) {
            return "无障碍未连接" to "请先在系统设置 → 无障碍里开启 AnroidAgent"
        }

        val target = AppGraph.lastForeground.ifBlank { ctx.packageName }
        if (target == ctx.packageName) return null

        val decision = AppGraph.guard?.check(target)
        if (decision is TargetGuard.Decision.Denied) {
            return "目标被拒绝" to "$target → ${decision.why}；请先把包名加入白名单"
        }
        return null
    }

    private fun failure(headline: String, detail: String) =
        Outcome(ok = false, headline = headline, detail = detail)
}
