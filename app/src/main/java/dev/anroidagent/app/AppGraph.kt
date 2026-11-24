package dev.anroidagent.app

import android.app.AppOpsManager
import android.content.Context
import android.os.Process
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import dev.anroidagent.agent.model.ModelConfig
import dev.anroidagent.agent.model.OpenAiCompatibleClient
import dev.anroidagent.agent.model.Planner
import dev.anroidagent.agent.task.TaskQueue
import dev.anroidagent.agent.task.TaskResult
import dev.anroidagent.agent.task.TaskStatus
import dev.anroidagent.agent.task.TaskStore
import dev.anroidagent.app.media.MediaScanner
import dev.anroidagent.app.media.TrashMover
import dev.anroidagent.app.media.TrashStore
import dev.anroidagent.app.overlay.OverlayConfig
import dev.anroidagent.app.script.PendingScript
import dev.anroidagent.app.script.ScriptRunner
import dev.anroidagent.app.script.ScriptStore
import dev.anroidagent.app.task.FileTaskStore
import dev.anroidagent.core.capability.Cap
import dev.anroidagent.core.capability.CapReport
import dev.anroidagent.core.media.DedupPlan
import dev.anroidagent.core.media.TrashEntry
import dev.anroidagent.core.policy.EmergencyStop
import dev.anroidagent.core.policy.InMemoryAllowlist
import dev.anroidagent.core.policy.PolicyEvent
import dev.anroidagent.core.policy.PolicyLog
import dev.anroidagent.core.policy.ResearchMode
import dev.anroidagent.core.policy.SuicideRecord
import dev.anroidagent.core.policy.TargetGuard
import dev.anroidagent.driver.A11yDriver
import dev.anroidagent.driver.Driver
import dev.anroidagent.driver.DriverRouter
import dev.anroidagent.driver.DryRunDriver
import dev.anroidagent.driver.ForegroundResolver
import dev.anroidagent.driver.GuardedDriver
import dev.anroidagent.driver.capture.MediaProjectionDriver
import dev.anroidagent.driver.capture.ScreenCapture
import dev.anroidagent.driver.shell.ShellOp
import dev.anroidagent.driver.shell.ShellOps
import dev.anroidagent.driver.shell.ShellResult

/**
 * 极简依赖容器。刻意不引入 DI 框架：组件少，且启动路径必须完全可控
 * （冷却期检查要能在任何初始化之前拦住）。
 */
object AppGraph {

    private lateinit var ctx: Context

    var allowlist: InMemoryAllowlist = InMemoryAllowlist()
        private set

    var guard: TargetGuard? = null
        private set

    var a11yService: AgentService? = null

    var lastForeground: String = ""
        private set

    private var lastForegroundAt: Long = 0

    var lastReport: CapReport = CapReport.EMPTY
        private set

    val initialized: Boolean get() = guard != null

    fun init(context: Context) {
        if (initialized) return
        ctx = context.applicationContext
        allowlist = InMemoryAllowlist(Prefs.loadAllowlist(ctx))
        guard = TargetGuard(ctx.packageManager, allowlist)
        lastReport = probeCapabilities()
    }

    fun attach(service: AgentService) {
        a11yService = service
        lastReport = probeCapabilities()
    }

    fun detach(service: AgentService) {
        if (a11yService === service) a11yService = null
    }

    fun noteForeground(pkg: String) {
        if (pkg.isNotBlank()) {
            lastForeground = pkg
            lastForegroundAt = System.currentTimeMillis()
        }
    }

    /**
     * 用 shell 的 `dumpsys activity` 精确刷新前台包名。
     *
     * 只在每帧开始时调一次（约 200ms）——不能放进 `GuardedDriver.gate()`，
     * 那里每个动作都会走一遍，会把 UI 线程拖死。
     *
     * 返回 null 表示 shell 不可用或解析失败，调用方应退回 A11y 缓存。
     */
    fun refreshForeground(): String? {
        val ops = shellOps() ?: return lastForeground.ifBlank { null }
        val parsed = runCatching { ops.run(ShellOp.TOP_ACTIVITY) }.getOrNull()
            ?.takeIf { it.ok }
            ?.output
            ?.trim()
            ?.ifBlank { null }

        if (parsed != null) {
            lastForeground = parsed
            lastForegroundAt = System.currentTimeMillis()
            return parsed
        }
        return lastForeground.ifBlank { null }
    }

    fun foregroundAgeMs(): Long =
        if (lastForegroundAt == 0L) Long.MAX_VALUE else System.currentTimeMillis() - lastForegroundAt

    /** 高级 shell 操作集合；基础版或未就绪时返回 null */
    fun shellOps(): ShellOps? {
        val provider = ShellBootstrap.provider ?: return null
        if (!provider.isReady) return null
        return runCatching { provider.ops(ctx) }.getOrNull()
    }

    /**
     * 执行一条已经过 `ShellCommandPolicy` 分类的命令。
     *
     * 只做"能不能跑"的判断，不再做安全判定——分类与拦截在策略层，
     * 这里只负责把命令送下去。
     */
    fun runShellRaw(cmd: String): ShellResult? {
        val provider = ShellBootstrap.provider ?: return null
        if (!provider.isReady) return null
        return runCatching { provider.exec(cmd) }.getOrNull()
    }

    // ---------------- 待审脚本 ----------------

    private var scripts: ScriptStore? = null

    fun scriptStore(): ScriptStore =
        scripts ?: ScriptStore(java.io.File(ctx.filesDir, "scripts")).also { scripts = it }

    /**
     * 把被策略拦截的 shell 命令落成草稿。
     *
     * **只落盘，不执行**。执行必须由用户在界面上确认——这是脚本能力的全部安全边界，
     * 因为脚本内容不再经过命令分类。
     */
    fun draftScript(command: String, reason: String): PendingScript? {
        val id = "s" + System.currentTimeMillis().toString(36)
        val saved = scriptStore().add(
            PendingScript(
                id = id,
                createdAt = System.currentTimeMillis(),
                reason = reason,
                command = command,
            ),
        )
        if (saved != null) {
            PolicyLog.record(
                ctx,
                PolicyEvent(
                    at = System.currentTimeMillis(),
                    pkg = "",
                    rule = "draft-script",
                    action = "queued",
                    evidence = command.take(60),
                    level = "SCRIPT",
                ),
            )
        }
        return saved
    }

    fun pendingScripts(): List<PendingScript> = scriptStore().list()

    fun scriptContent(script: PendingScript): String = scriptStore().content(script)

    fun discardScript(id: String): Boolean = scriptStore().remove(id)

    fun clearScripts(): Int = scriptStore().clear()

    /**
     * 执行待审脚本。**只能由 UI 调用**——这条路绕过了命令分类，
     * 安全性完全依赖"人看过内容并点了确认"。
     */
    fun executeScript(script: PendingScript): ShellResult {
        if (ResearchMode.enabled) {
            PolicyLog.record(
                ctx,
                PolicyEvent(
                    at = System.currentTimeMillis(), pkg = "", rule = "run-script",
                    action = "would-execute", evidence = "research-mode", level = "SCRIPT",
                ),
            )
            return ShellResult.fail("研究模式：未执行脚本")
        }

        val provider = ShellBootstrap.provider ?: return ShellResult.fail("无 shell 通道")
        if (!provider.isReady) return ShellResult.fail("shell 未就绪")

        val result = runCatching { ScriptRunner.run(provider, script) }
            .getOrElse { ShellResult.fail(it.message ?: "failed") }

        PolicyLog.record(
            ctx,
            PolicyEvent(
                at = System.currentTimeMillis(),
                pkg = "",
                rule = "run-script",
                action = if (result.ok) "ok" else "fail",
                evidence = result.output.ifBlank { result.error.orEmpty() }.take(60),
                level = "SCRIPT",
            ),
        )

        if (result.ok) discardScript(script.id)
        return result
    }

    fun addToAllowlist(pkg: String) {
        if (pkg.isBlank()) return
        allowlist.add(pkg)
        Prefs.saveAllowlist(ctx, allowlist.all())
    }

    fun removeFromAllowlist(pkg: String) {
        allowlist.remove(pkg)
        Prefs.saveAllowlist(ctx, allowlist.all())
    }

    // ---------------- 驱动装配 ----------------

    /**
     * 动作通道：
     * - **注入**：只有用户显式开启 [isShellInjectionEnabled] 才用 shell，否则纯无障碍
     * - **取帧**：shell 就绪就用 `screencap`（只读能力，不受注入开关影响）
     * - **读树**：永远走无障碍
     */
    fun rawDriver(): Driver? {
        val a11y = a11yService?.let { A11yDriver(it) }
        val shellDriver = ShellBootstrap.provider?.takeIf { it.isReady }?.driver()
        val injectShell = if (shellDriver != null && isShellInjectionEnabled()) shellDriver else null

        if (a11y == null && shellDriver == null) return null

        val router = DriverRouter(
            shell = injectShell,
            a11y = a11y,
            shotSource = shellDriver,
        )
        return if (shellDriver == null) MediaProjectionDriver(actions = router) else router
    }

    fun isShellInjectionEnabled(): Boolean = Prefs.isShellInjectionEnabled(ctx)

    fun setShellInjectionEnabled(enabled: Boolean) =
        Prefs.setShellInjectionEnabled(ctx, enabled)

    // ---------------- 相册清理 ----------------

    /** 暂存区固定用 /sdcard 前缀：SafePaths 的保护规则也以它为准 */
    val trashDir: String get() = "/sdcard/Pictures/.agent_trash"

    private var scanner: MediaScanner? = null

    fun mediaScanner(): MediaScanner = scanner ?: MediaScanner(ctx).also { scanner = it }

    private var lastPlan: DedupPlan? = null

    fun lastDedupPlan(): DedupPlan? = lastPlan

    fun rememberDedupPlan(plan: DedupPlan) {
        lastPlan = plan
    }

    /** 移动必须走 shell：app 沙箱没有写公共目录的能力 */
    fun trashMover(): TrashMover? {
        val provider = ShellBootstrap.provider ?: return null
        if (!provider.isReady) return null
        return TrashMover(provider, trashDir)
    }

    private var trashIndex: TrashStore? = null

    fun trashStore(): TrashStore =
        trashIndex ?: TrashStore(java.io.File(ctx.filesDir, "trash")).also { trashIndex = it }

    fun trashRecords(): List<TrashEntry> = trashStore().list()

    /**
     * 移入暂存区并把索引落盘。
     *
     * 索引必须在移动成功后立刻写：先移动后写索引会有一个"文件已搬走但没记录"的窗口，
     * 那段时间里还原是不可能的。
     */
    fun moveToTrash(entries: List<TrashEntry>): TrashMover.Outcome? {
        val mover = trashMover() ?: return null
        val outcome = mover.moveAll(entries)
        if (outcome.moved.isNotEmpty()) {
            runCatching { trashStore().add(outcome.moved) }
        }
        return outcome
    }

    /** 还原。返回 (成功数, 失败数)，成功的会从索引里移除 */
    fun restoreTrash(entries: List<TrashEntry>): Pair<Int, Int> {
        val mover = trashMover() ?: return 0 to entries.size

        val restored = mutableSetOf<String>()
        var failed = 0

        entries.forEach { entry ->
            val ok = runCatching { mover.restore(entry) }.getOrDefault(false)
            if (ok) restored += entry.trashName else failed++
        }

        if (restored.isNotEmpty()) {
            runCatching { trashStore().removeAll(restored) }
        }
        return restored.size to failed
    }

    /** 真正删除暂存区内容（不可恢复），成功后清空索引 */
    fun purgeTrash(): Boolean {
        val mover = trashMover() ?: return false
        if (!mover.emptyTrash()) return false
        runCatching { trashStore().clear() }
        return true
    }

    /**
     * 策略守卫包装：所有动作在进入驱动前校验目标。
     * 前台包名未知时拒绝执行（fail-closed）。
     */
    fun guardedDriver(): Driver? {
        val inner = rawDriver() ?: return null
        val g = guard ?: return null
        val fg = ForegroundResolver {
            lastForeground.ifBlank { null }
        }

        // 研究模式：动作只记录不执行。
        // 放在守卫内层，红线判定照常跑——研究模式不改策略，只改"是否真动手"。
        val actionDriver: Driver = if (ResearchMode.enabled) {
            DryRunDriver(inner) { what ->
                PolicyLog.record(
                    ctx,
                    PolicyEvent(
                        at = System.currentTimeMillis(),
                        pkg = lastForeground,
                        rule = "dry-run",
                        action = "would-execute",
                        evidence = what.take(80),
                        level = "DRY",
                    ),
                )
            }
        } else {
            inner
        }

        return GuardedDriver(actionDriver, g, fg, ctx.packageName) { pkg, rule -> killFor(pkg, rule) }
    }

    // ---------------- 模型 ----------------

    fun modelConfig(): ModelConfig? = Prefs.loadModelConfig(ctx)

    fun saveModelConfig(cfg: ModelConfig) = Prefs.saveModelConfig(ctx, cfg)

    fun planner(cfg: ModelConfig): Planner = Planner(OpenAiCompatibleClient(cfg))

    fun planner(): Planner? = modelConfig()?.let { planner(it) }

    // ---------------- 任务队列 ----------------

    private val taskScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var queue: TaskQueue? = null

    private var store: TaskStore? = null

    private fun taskStore(): TaskStore = store ?: run {
        val created = FileTaskStore(java.io.File(ctx.filesDir, "tasks"))
        store = created
        created
    }

    fun taskQueue(context: Context): TaskQueue {
        queue?.let { return it }
        val created = TaskQueue(taskScope, taskStore()) { spec, onProgress ->
            val cfg = modelConfig()
            if (cfg == null) {
                TaskResult(spec, TaskStatus.FAILED, "未配置模型", emptyList(), 0, 0)
            } else {
                AgentRunner.runTask(context.applicationContext, cfg, spec, onProgress)
            }
        }
        queue = created
        return created
    }

    // ---------------- 能力 ----------------

    fun probeCapabilities(): CapReport {
        val granted = buildSet {
            if (a11yService != null) {
                add(Cap.A11Y_TREE)
                add(Cap.A11Y_GESTURE)
                add(Cap.A11Y_SET_TEXT)
            }
            if (Settings.canDrawOverlays(ctx)) add(Cap.OVERLAY)
            if (hasUsageStats(ctx)) add(Cap.USAGE_STATS)
            if (ScreenCapture.isActive) add(Cap.MEDIA_PROJ)

            if (ShellBootstrap.ready) {
                add(Cap.SHELL_SHOT)
                add(Cap.SHELL_INPUT)
                add(Cap.SHELL_PM)
                add(Cap.SHELL_DUMPSYS)
            }
        }
        return CapReport(granted)
    }

    /**
     * secure 窗口判定：有 shell 就精确查 `dumpsys`，否则退回全黑帧推断。
     * 两者都是 fail-closed 方向的信号。
     */
    fun isSecureWindow(): Boolean {
        val exact = ShellBootstrap.provider?.takeIf { it.isReady }?.secureWindow()
        return exact ?: ScreenCapture.lastFrameUniformlyBlack
    }

    /** UI 用的一行状态 */
    fun shellStatus(): String = when {
        !ShellBootstrap.available -> "无（基础版）"
        !ShellBootstrap.ready -> "已装追加包，未授权/未连接"
        isShellInjectionEnabled() -> "就绪 · 注入已开启"
        else -> "就绪 · 注入关闭（走无障碍）"
    }

    /**
     * 终止路径。所有 L3 判定最终都汇到这里，顺序一致：
     * 记录 → 禁用无障碍 → 撤销调度 → 杀进程。
     */
    fun killFor(pkg: String, rule: String): Nothing {
        PolicyLog.record(
            ctx,
            PolicyEvent(System.currentTimeMillis(), pkg, rule, "deny", "target-denied", "L3"),
        )
        EmergencyStop.trigger(
            ctx = ctx,
            pkg = pkg,
            rule = rule,
            disableA11y = { a11yService?.disableSelf() },
            cancelSchedules = { ScreenCapture.release() },
        )
    }

    fun isCoolingDown(): Boolean = SuicideRecord.isCoolingDown(ctx)

    fun appContext(): Context = ctx

    private fun hasUsageStats(context: Context): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        val mode = ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }
}

/** 白名单与模型配置持久化。不存任何账号、凭证或屏幕内容。 */
object Prefs {

    private const val PREF = "agent_prefs"
    private const val K_ALLOW = "allowlist"
    private const val K_BASE_URL = "model_base_url"
    private const val K_MODEL = "model_name"
    private const val K_API_KEY = "model_api_key"
    private const val K_OVERLAY = "overlay_config"
    private const val K_SHELL_INJECT = "shell_injection"

    fun loadAllowlist(ctx: Context): Set<String> =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getStringSet(K_ALLOW, emptySet())
            ?.toSet()
            .orEmpty()

    fun saveAllowlist(ctx: Context, list: Set<String>) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(K_ALLOW, list)
            .apply()
    }

    fun loadModelConfig(ctx: Context): ModelConfig? {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val baseUrl = p.getString(K_BASE_URL, "").orEmpty()
        val model = p.getString(K_MODEL, "").orEmpty()
        if (baseUrl.isBlank() || model.isBlank()) return null
        return ModelConfig(
            baseUrl = baseUrl,
            apiKey = p.getString(K_API_KEY, "").orEmpty(),
            model = model,
        )
    }

    fun saveModelConfig(ctx: Context, cfg: ModelConfig) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(K_BASE_URL, cfg.baseUrl)
            .putString(K_MODEL, cfg.model)
            .putString(K_API_KEY, cfg.apiKey)
            .apply()
    }

    fun loadOverlay(ctx: Context): OverlayConfig =
        OverlayConfig.fromJson(
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString(K_OVERLAY, null),
        )

    /**
     * shell 注入开关，**默认关闭**。
     *
     * 改变设备行为的东西必须显式开启：默认只走无障碍，
     * 用户明确打开后才把点击交给 shell，且失败自动回退。
     */
    fun isShellInjectionEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean(K_SHELL_INJECT, false)

    fun setShellInjectionEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(K_SHELL_INJECT, enabled)
            .apply()
    }

    fun saveOverlay(ctx: Context, cfg: OverlayConfig) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(K_OVERLAY, cfg.toJson())
            .apply()
    }

    /** 配置导出：换机、复现 bug、灰度对比都用它 */
    fun exportConfig(ctx: Context): String = org.json.JSONObject().apply {
        put("allowlist", org.json.JSONArray(loadAllowlist(ctx).toList()))
        put("model_base_url", ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(K_BASE_URL, ""))
        put("model_name", ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(K_MODEL, ""))
        put("overlay", org.json.JSONObject(loadOverlay(ctx).toJson()))
    }.toString(2)
}
