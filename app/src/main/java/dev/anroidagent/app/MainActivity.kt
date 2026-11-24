package dev.anroidagent.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.anroidagent.agent.model.ModelConfig
import dev.anroidagent.agent.task.TaskQueue
import dev.anroidagent.agent.task.TaskResult
import dev.anroidagent.agent.task.TaskSpec
import dev.anroidagent.app.media.ProgressText
import dev.anroidagent.app.overlay.OverlayService
import dev.anroidagent.app.script.PendingScript
import dev.anroidagent.core.capability.Cap
import dev.anroidagent.core.media.DedupPlan
import dev.anroidagent.core.media.DedupPlanner
import dev.anroidagent.core.media.DuplicateGroup
import dev.anroidagent.core.media.GroupSelection
import dev.anroidagent.core.media.TrashEntry
import dev.anroidagent.core.media.TrashPlanner
import dev.anroidagent.core.media.humanBytes
import dev.anroidagent.core.policy.PolicyEvent
import dev.anroidagent.core.policy.PolicyLog
import dev.anroidagent.core.policy.ResearchMode
import dev.anroidagent.core.policy.SuicideRecord
import dev.anroidagent.core.policy.TargetGuard
import dev.anroidagent.driver.InputChannelProbe
import dev.anroidagent.driver.capture.ScreenCapture
import dev.anroidagent.driver.capture.ScreenCaptureService
import dev.anroidagent.driver.shell.ShellOp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : Activity() {

    companion object {
        private const val REQ_CAPTURE = 1001
        private const val REQ_SHELL = 1002
        private const val REQ_MEDIA = 1003

        /** 勾选列表最多显示多少组：再多也没人逐个点 */
        private const val PICK_LIMIT = 200
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var status: TextView
    private lateinit var outputView: TextView
    private lateinit var baseUrlInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var apiKeyInput: EditText
    private lateinit var pkgInput: EditText
    private lateinit var hideToggle: Button
    private lateinit var instructionInput: EditText
    private lateinit var taskStatus: TextView
    private lateinit var injectToggle: Button
    private lateinit var scanButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (SuicideRecord.isCoolingDown(this)) {
            finish()
            return
        }
        AppGraph.init(applicationContext)
        setContentView(buildUi())
        loadModelFields()
        syncInjectLabel(AppGraph.isShellInjectionEnabled())
        refresh()
        observeQueue()
        restoreLastResult()
    }

    /** 重启后把上一条结果（可能是"进程中断"记录）显示出来 */
    private fun restoreLastResult() {
        val last = AppGraph.taskQueue(applicationContext).history.value.lastOrNull() ?: return
        say(renderResult(last))
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) refresh()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return

        if (resultCode == RESULT_OK && data != null) {
            ScreenCaptureService.start(this, resultCode, data)
            say("投屏已启动。FLAG_SECURE 页面会返回全黑帧，将按 secure 硬停。")
        } else {
            say("投屏授权被取消")
        }
        refresh()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_SHELL) return

        val granted = grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED
        ShellBootstrap.provider?.onRequestPermissionsResult(requestCode, granted)
        say("shell 授权：" + if (granted) "已授予" else "被拒绝")
        refresh()
    }

    // ---------------- UI ----------------

    private fun buildUi(): ViewGroup {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(12))
        }

        status = TextView(this).apply {
            textSize = 12f
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(status)

        root.addView(
            row(
                button("无障碍设置") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                button("悬浮窗授权") {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName"),
                        ),
                    )
                },
                button("投屏授权") { requestCapture() },
            ),
        )

        root.addView(section("shell 通道（追加包）"))
        root.addView(
            row(
                button("申请授权") { requestShell() },
                button("自检") { runShellSelfTest() },
                button("高级操作…") { showShellOps() },
            ),
        )
        injectToggle = button("shell 注入：关") { toggleShellInjection() }
        root.addView(
            row(
                injectToggle,
                button("读前台") { readTopActivity() },
                button("恢复全局") { restoreShellGlobals() },
            ),
        )
        root.addView(
            row(
                button("待审脚本") { showPendingScripts() },
                button("清空脚本") { clearScripts() },
                button("刷新") { refresh() },
            ),
        )

        root.addView(section("悬浮窗"))
        hideToggle = button("隐藏执行：开") { toggleHideWhileRunning() }
        root.addView(
            row(
                button("启动面板") { startOverlay() },
                button("停止面板") { OverlayService.stop(this) },
                hideToggle,
            ),
        )

        root.addView(section("模型（OpenAI 兼容）"))
        baseUrlInput = field("baseUrl，例如 https://api.example.com/v1")
        modelInput = field("model，例如 qwen-vl-max / gpt-4o-mini")
        apiKeyInput = field("apiKey（可留空，仅本地服务）")
        root.addView(baseUrlInput)
        root.addView(modelInput)
        root.addView(apiKeyInput)
        root.addView(
            row(
                button("保存配置") { saveModel() },
                button("单步试跑") { runOnce(0) },
                button("5 秒后试跑") { runOnce(5) },
            ),
        )

        root.addView(section("任务（多步）"))
        instructionInput = field("用一句话描述目标，例如：搜索无线耳机并按销量排序")
        root.addView(instructionInput)
        taskStatus = TextView(this).apply {
            textSize = 11f
            setPadding(0, dp(4), 0, dp(4))
        }
        root.addView(taskStatus)
        root.addView(
            row(
                button("入队") { enqueueTask() },
                button("取消当前") { AppGraph.taskQueue(applicationContext).cancelCurrent() },
                button("清空队列") {
                    AppGraph.taskQueue(applicationContext).clearQueue()
                    say("队列已清空")
                },
            ),
        )

        root.addView(section("相册清理（本地哈希，零 token）"))
        scanButton = button("扫描相册") { onScanClicked() }
        root.addView(
            row(
                scanButton,
                button("移入暂存区") { trashSelected() },
            ),
        )
        root.addView(
            row(
                button("撤销") { undoTrash() },
                button("清空暂存区") { emptyTrash() },
            ),
        )

        root.addView(section("白名单（为空则拒绝一切目标）"))
        pkgInput = field("包名，例如 com.example.shop")
        root.addView(pkgInput)
        root.addView(
            row(
                button("加入白名单") {
                    AppGraph.addToAllowlist(pkgInput.text.toString().trim())
                    pkgInput.setText("")
                    refresh()
                },
                button("移出白名单") {
                    AppGraph.removeFromAllowlist(pkgInput.text.toString().trim())
                    refresh()
                },
                button("导出配置") { exportConfig() },
            ),
        )

        outputView = TextView(this).apply {
            textSize = 11f
            setPadding(0, dp(8), 0, 0)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        root.addView(
            ScrollView(this).apply {
                addView(outputView)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                )
            },
        )
        return root
    }

    private fun section(title: String) = TextView(this).apply {
        text = title
        textSize = 12f
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun field(hintText: String) = EditText(this).apply {
        hint = hintText
        textSize = 12f
        setSingleLine()
    }

    private fun row(vararg views: Button): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach {
            addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun button(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 11f
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    // ---------------- 行为 ----------------

    private fun requestCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            say("请先授予悬浮窗权限")
            return
        }
        OverlayService.start(this)
        say("面板已启动。拖动标题移动，点「读屏」执行一次。")
        refresh()
    }

    private fun toggleHideWhileRunning() {
        val cfg = Prefs.loadOverlay(this)
        val next = cfg.copy(hideWhileRunning = !cfg.hideWhileRunning)
        Prefs.saveOverlay(this, next)
        syncHideLabel(next.hideWhileRunning)
        say(
            if (next.hideWhileRunning) {
                "执行时移除面板：目标 App 不会收到 FLAG_WINDOW_IS_OBSCURED（更安全，但执行中看不到面板）"
            } else {
                "执行时保留面板：能看到进度，但强风控 App 可能忽略注入的触摸"
            },
        )
    }

    private fun syncHideLabel(on: Boolean) {
        if (::hideToggle.isInitialized) {
            hideToggle.text = "隐藏执行：" + if (on) "开" else "关"
        }
    }

    private fun loadModelFields() {
        AppGraph.modelConfig()?.let {
            baseUrlInput.setText(it.baseUrl)
            modelInput.setText(it.model)
            apiKeyInput.setText(it.apiKey)
        }
        syncHideLabel(Prefs.loadOverlay(this).hideWhileRunning)
    }

    private fun saveModel() {
        val cfg = ModelConfig(
            baseUrl = baseUrlInput.text.toString().trim(),
            apiKey = apiKeyInput.text.toString().trim(),
            model = modelInput.text.toString().trim(),
        )
        if (!cfg.isValid) {
            say("baseUrl 与 model 不能为空")
            return
        }
        AppGraph.saveModelConfig(cfg)
        say("已保存：${cfg.model}")
        refresh()
    }

    private fun exportConfig() {
        val json = Prefs.exportConfig(this)
        say("配置（可复制保存）\n\n$json")
    }

    private fun runOnce(delaySeconds: Int) {
        val cfg = AppGraph.modelConfig()
        if (cfg == null) {
            say("请先保存模型配置")
            return
        }

        scope.launch {
            try {
                if (delaySeconds > 0) {
                    for (i in delaySeconds downTo 1) {
                        say("${i} 秒后执行，请切到目标 App…")
                        delay(1000)
                    }
                }
                say("读屏中…")
                val outcome = AgentRunner.runOnce(this@MainActivity, cfg)
                say(outcome.headline + "\n\n" + outcome.detail)
            } catch (e: Exception) {
                say("失败：${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /**
     * shell 注入开关。默认关闭：
     * 关闭时点击走无障碍；开启后走 shell，失败自动回退无障碍。
     */
    private fun toggleShellInjection() {
        val provider = ShellBootstrap.provider
        if (provider == null) {
            say("基础版没有 shell 通道。")
            return
        }
        if (!provider.isReady) {
            say("请先完成 Shizuku 授权与自检，再开启注入。")
            return
        }

        val next = !AppGraph.isShellInjectionEnabled()
        AppGraph.setShellInjectionEnabled(next)
        syncInjectLabel(next)

        say(
            if (next) {
                "已开启 shell 注入。\n\n" +
                    "• 点击/滑动/输入改走 shell（不再依赖无障碍）\n" +
                    "• 任一步失败会自动回退到无障碍点击\n" +
                    "• 代价：设备上会持续出现 shell 进程，adb 状态可能被目标 App 观察到\n" +
                    "• 随时可以关掉，关掉后立刻回退无障碍"
            } else {
                "已关闭 shell 注入，点击改回无障碍。\n" +
                    "静默截图、前台判定、高级操作仍然可用（它们属于只读能力）。"
            },
        )
        refresh()
    }

    private fun syncInjectLabel(on: Boolean) {
        if (::injectToggle.isInitialized) {
            injectToggle.text = "shell 注入：" + if (on) "开" else "关"
        }
    }

    private fun requestShell() {
        val provider = ShellBootstrap.provider
        if (provider == null) {
            say("这是基础版，不含 shell 通道。\n安装 AnroidAgent Pro（追加包）后该功能可用。")
            return
        }
        if (provider.isReady) {
            say("shell 通道已就绪（${provider.id}）")
            return
        }
        provider.requestPermission(this, REQ_SHELL)
        say("已请求 Shizuku 授权。\n若没有弹窗，请先打开 Shizuku 并确认它已启动（重启手机后需要重新激活）。")
        refresh()
    }

    private fun runShellSelfTest() {
        val provider = ShellBootstrap.provider ?: run {
            say("基础版不含 shell 通道")
            return
        }
        val ok = provider.selfTest()
        say(
            if (ok) "自检通过：ping / 命令执行 / dumpsys 均可用" else
                "自检失败：Shizuku 未连接或未授权",
        )
        refresh()
    }

    // ---------------- 任务 ----------------

    private fun enqueueTask() {
        if (AppGraph.modelConfig() == null) {
            say("请先保存模型配置")
            return
        }
        val text = instructionInput.text.toString().trim()
        if (text.isEmpty()) {
            say("请先填写任务描述")
            return
        }
        AppGraph.taskQueue(applicationContext).enqueue(TaskSpec(instruction = text))
        instructionInput.setText("")
        say("已入队：$text")
    }

    private fun observeQueue() {
        val queue = AppGraph.taskQueue(applicationContext)
        scope.launch {
            queue.state.collect { st ->
                taskStatus.text = when (st) {
                    is TaskQueue.State.Idle -> "队列空闲"
                    is TaskQueue.State.Running ->
                        "运行中：${st.spec.instruction.take(24)}（待执行 ${queue.pendingCount.value}）"
                    is TaskQueue.State.Finished -> "已结束：${st.result.status}｜${st.result.message}"
                }

                if (st is TaskQueue.State.Finished) {
                    say(renderResult(st.result))
                }
            }
        }
    }

    private fun renderResult(r: TaskResult): String = buildString {
        appendLine("== ${r.status} ==")
        appendLine(r.message)
        appendLine("步数 ${r.steps.size}｜prompt ${r.promptTokens} token｜${r.elapsedMs} ms")
        appendLine()
        r.steps.forEach { s ->
            append("#${s.index} ")
            append(s.stepText)
            append(" → ").append(s.outcome)
            if (s.usedVision) append(" [vision]")
            append("  (").append(s.verdict).append(")")
            if (s.outcome.startsWith("failed")) {
                appendLine()
                appendLine("   raw: ").append(s.raw.take(160))
            } else {
                appendLine()
            }
        }
    }

    // ---------------- shell 高级操作 ----------------

    private val shellOpLabels = arrayOf(
        "读取前台 Activity",
        "读取设备标识",
        "隐藏系统栏",
        "恢复系统栏",
        "关闭动画",
        "恢复动画",
        "启用本应用无障碍",
        "移除本应用无障碍",
        "清除应用数据 (pm clear)",
        "强制停止",
        "冻结 (pm suspend)",
        "解冻 (pm unsuspend)",
        "授予权限（目标填 pkg:perm）",
        "撤销权限（目标填 pkg:perm）",
        "设置 AppOps（目标填 pkg:OP:mode）",
    )

    private fun showShellOps() {
        if (AppGraph.shellOps() == null) {
            say("基础版无 shell 通道，或 Shizuku 未授权。\n先点「申请授权」并过自检。")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("shell 操作")
            .setItems(shellOpLabels) { _, which -> runShellOp(which) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun runShellOp(index: Int) {
        val target = pkgInput.text.toString().trim()

        var op: ShellOp? = null
        var arg: String? = null
        var destructive = false

        when (index) {
            0 -> op = ShellOp.TOP_ACTIVITY
            1 -> op = ShellOp.DEVICE_IDS
            2 -> { op = ShellOp.HIDE_SYSTEM_BARS; destructive = true }
            3 -> op = ShellOp.RESTORE_SYSTEM_BARS
            4 -> { op = ShellOp.ANIMATION_OFF; destructive = true }
            5 -> op = ShellOp.ANIMATION_RESTORE
            6 -> { op = ShellOp.ENABLE_A11Y; destructive = true }
            7 -> op = ShellOp.DISABLE_A11Y
            8 -> { op = ShellOp.CLEAR_APP_DATA; arg = target; destructive = true }
            9 -> { op = ShellOp.FORCE_STOP; arg = target; destructive = true }
            10 -> { op = ShellOp.SUSPEND_APP; arg = target; destructive = true }
            11 -> { op = ShellOp.UNSUSPEND_APP; arg = target }
            12 -> { op = ShellOp.GRANT_PERMISSION; arg = target; destructive = true }
            13 -> { op = ShellOp.REVOKE_PERMISSION; arg = target; destructive = true }
            14 -> { op = ShellOp.SET_APPOP; arg = target; destructive = true }
        }

        val finalOp = op ?: return

        // 应用类操作必须有目标；纯包名类还要过硬禁红线
        val needsTarget = index in 8..14
        if (needsTarget && target.isBlank()) {
            say("请先在「白名单」输入框填目标，再执行该操作")
            return
        }
        if (needsTarget && index in 8..11) {
            val decision = AppGraph.guard?.check(target)
            if (decision is TargetGuard.Decision.Denied) {
                say("目标被拒绝：$target → ${decision.why}")
                return
            }
        }

        performShellOp(finalOp, arg, destructive)
    }

    private fun performShellOp(op: ShellOp, arg: String?, destructive: Boolean) {
        // 研究模式：变更类操作只记录，不执行
        if (destructive && ResearchMode.enabled) {
            PolicyLog.record(
                this,
                PolicyEvent(
                    at = System.currentTimeMillis(),
                    pkg = arg.orEmpty(),
                    rule = op.name,
                    action = "would-execute",
                    evidence = "research-mode",
                    level = "DRY",
                ),
            )
            say("研究模式：已拦截 ${op.name}（参数：${arg ?: "-"}），只记录未执行。")
            return
        }

        val execute = {
            val result = AppGraph.shellOps()?.run(op, arg)
            if (result == null) {
                say("shell 未就绪")
            } else {
                PolicyLog.record(
                    this,
                    PolicyEvent(
                        at = System.currentTimeMillis(),
                        pkg = arg.orEmpty(),
                        rule = op.name,
                        action = "shell",
                        evidence = result.output.ifBlank { result.error.orEmpty() }.take(60),
                        level = if (destructive) "L3" else "L1",
                    ),
                )
                say(
                    (if (result.ok) "OK   " else "FAIL  ") + op.name +
                        "\n\n" + result.output.ifBlank { result.error.orEmpty() },
                )
            }
            refresh()
        }

        if (!destructive) {
            execute()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("确认执行 ${op.name}")
            .setMessage("参数：${arg ?: "-"}\n\n会修改系统或应用状态，不可自动撤销。")
            .setPositiveButton("执行") { _, _ -> execute() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun readTopActivity() {
        val ops = AppGraph.shellOps()
        if (ops == null) {
            say("基础版无 shell 通道")
            return
        }
        val result = ops.run(ShellOp.TOP_ACTIVITY)
        say(if (result.ok) "前台 = ${result.output}" else "失败：${result.error}")
        refresh()
    }

    private fun restoreShellGlobals() {
        val ops = AppGraph.shellOps()
        if (ops == null) {
            say("基础版无 shell 通道")
            return
        }
        val result = ops.restoreAll()
        say(if (result.ok) result.output else "失败：${result.error}")
        refresh()
    }

    // ---------------- 待审脚本 ----------------

    /**
     * 被策略拦截的 shell 命令会落在这里。
     * 执行按钮是唯一的执行入口——脚本内容不经过命令分类，安全性靠"人看过并确认"。
     */
    private fun showPendingScripts() {
        val list = AppGraph.pendingScripts()
        if (list.isEmpty()) {
            say("没有待审脚本。\n\n模型发出的 shell 命令只要被策略拦截（变更类/危险类），\n就会落在这里等你确认。")
            return
        }

        val labels = list.map { "${it.id} · ${it.reason.take(20)}\n${it.summary()}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("待审脚本（${list.size}）")
            .setItems(labels) { _, which -> showScriptDetail(list[which]) }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showScriptDetail(script: PendingScript) {
        AlertDialog.Builder(this)
            .setTitle("脚本 ${script.id}")
            .setMessage(
                "拦截原因：${script.reason}\n\n" +
                    "────────────\n" +
                    AppGraph.scriptContent(script).take(3000) +
                    "\n────────────\n\n" +
                    "⚠ 执行不会经过命令分类检查。只在你确认内容无误时执行。",
            )
            .setPositiveButton("执行") { _, _ ->
                val result = AppGraph.executeScript(script)
                say(
                    (if (result.ok) "OK\n\n" else "FAIL\n\n") +
                        result.output.ifBlank { result.error.orEmpty() },
                )
                refresh()
            }
            .setNeutralButton("丢弃") { _, _ ->
                AppGraph.discardScript(script.id)
                say("已丢弃 ${script.id}")
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun clearScripts() {
        val n = AppGraph.clearScripts()
        say("已清空 $n 份待审脚本")
        refresh()
    }

    // ---------------- 相册清理 ----------------

    @Volatile
    private var cancelScan = false

    private var scanJob: Job? = null

    private var scanStartedAt = 0L
    private var lastProgressAt = 0L

    private fun ensureMediaPermission(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val missing = needed.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return true

        requestPermissions(missing.toTypedArray(), REQ_MEDIA)
        return false
    }

    private fun onScanClicked() {
        if (scanJob?.isActive == true) {
            cancelScan = true
            say("正在取消…（当前这张处理完就停）")
            return
        }
        scanMedia()
    }

    private fun setScanRunning(running: Boolean) {
        if (::scanButton.isInitialized) {
            scanButton.text = if (running) "取消扫描" else "扫描相册"
        }
    }

    /** 节流推到主线程：每 200ms 最多刷一次，避免大相册把 UI 线程刷爆 */
    private fun postProgress(text: String) {
        val now = System.currentTimeMillis()
        if (now - lastProgressAt < 200) return
        lastProgressAt = now
        runOnUiThread { if (!isFinishing) say(text) }
    }

    private fun scanMedia() {
        if (!ensureMediaPermission()) {
            say(
                "需要媒体读取权限。\n\n" +
                    "也可以走追加包的高级操作直接授权：\n" +
                    "pm grant <本包> READ_MEDIA_IMAGES\n" +
                    "pm grant <本包> READ_MEDIA_VIDEO",
            )
            return
        }

        cancelScan = false
        scanStartedAt = System.currentTimeMillis()
        lastProgressAt = 0L
        setScanRunning(true)
        say(ProgressText.scan(0, 0, 0, "读取媒体库"))

        scanJob = scope.launch {
            try {
                val plan = withContext(Dispatchers.IO) {
                    val scanner = AppGraph.mediaScanner()

                    postProgress(ProgressText.scan(0, 0, elapsed(), "读取媒体库"))
                    val items = scanner.scan()

                    val hashed = scanner.hashPhotos(
                        items = items,
                        onProgress = { done, total ->
                            postProgress(ProgressText.scan(done, total, elapsed(), "哈希"))
                        },
                        shouldStop = { cancelScan },
                    )

                    if (cancelScan) return@withContext null

                    postProgress(ProgressText.scan(0, 0, elapsed(), "分组"))
                    DedupPlanner().plan(hashed).also { AppGraph.rememberDedupPlan(it) }
                }

                say(
                    if (plan == null) {
                        "已取消。已算完的哈希没有保存，下次会重算。"
                    } else {
                        renderDedup(plan)
                    },
                )
            } catch (e: Exception) {
                say("扫描失败：${e.javaClass.simpleName}: ${e.message}")
            } finally {
                setScanRunning(false)
                scanJob = null
            }
        }
    }

    private fun elapsed(): Long = System.currentTimeMillis() - scanStartedAt

    private fun renderDedup(plan: DedupPlan): String = buildString {
        appendLine("扫描 ${plan.scanned} 项（保护区跳过 ${plan.skippedProtected}，无哈希 ${plan.skippedNoHash}）")
        appendLine("重复 ${plan.groups.size} 组 · 可回收 ${humanBytes(plan.reclaimableBytes)}")
        appendLine("大视频 ${plan.largeVideos.size} 个 · ${humanBytes(plan.videoBytes)}")
        appendLine()
        append(plan.promptTable(limit = 30))
    }

    private fun trashSelected() {
        val plan = AppGraph.lastDedupPlan()
        if (plan == null || plan.groups.isEmpty()) {
            say("先点「扫描相册」")
            return
        }

        if (AppGraph.trashMover() == null) {
            say(
                "移入暂存区需要追加包。\n\n" +
                    "原因：app 沙箱没有写公共目录的权限，只有 shell 能做「复制 + 校验 + 删除」。\n" +
                    "基础版仍然可以扫描和查看建议。",
            )
            return
        }

        val groups = plan.groups.take(PICK_LIMIT)
        showGroupPicker(plan, groups, GroupSelection.defaultChecked(groups))
    }

    /**
     * 逐组勾选。
     *
     * 默认只勾 EXACT（字节级重复，删副本不可能丢内容）；
     * NEAR / BURST 是启发式，必须由用户主动勾上。
     */
    private fun showGroupPicker(
        plan: DedupPlan,
        groups: List<DuplicateGroup>,
        checked: BooleanArray,
    ) {
        val labels = groups.map { g ->
            "${g.kind} ${g.size}→1 · ${humanBytes(g.reclaimableBytes)} · ${g.keep.path.substringAfterLast('/')}"
        }.toTypedArray()

        val allChecked = checked.all { it }
        val suffix = if (plan.groups.size > groups.size) "，仅显示前 ${groups.size}" else ""

        AlertDialog.Builder(this)
            .setTitle("选择分组（共 ${plan.groups.size} 组$suffix）")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("下一步") { _, _ ->
                val ids = GroupSelection.selectedIds(groups, checked)
                if (ids.isEmpty()) {
                    say("没有选中任何分组")
                    return@setPositiveButton
                }
                confirmTrash(plan, ids)
            }
            .setNeutralButton(if (allChecked) "全不选" else "全选") { _, _ ->
                checked.fill(!allChecked)
                showGroupPicker(plan, groups, checked)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmTrash(plan: DedupPlan, selected: Set<String>) {
        val trashPlan = TrashPlanner().plan(
            groups = plan.groups,
            selectedGroupIds = selected,
            nowMs = System.currentTimeMillis(),
            trashDir = AppGraph.trashDir,
        )

        if (trashPlan.isEmpty) {
            say("选中的分组里没有可移入的文件（可能都被保护区规则或最小时限挡下了）")
            return
        }

        AlertDialog.Builder(this)
            .setTitle("移入暂存区？")
            .setMessage(
                "已选 ${selected.size} 组 → ${trashPlan.entries.size} 个文件 · ${humanBytes(trashPlan.totalBytes)}\n" +
                    "目录：${trashPlan.trashDir}\n\n" +
                    "流程：复制 → 校验大小与 sha256 → 通过后才删除源文件。\n" +
                    "校验不通过会保留源文件并删除副本。\n\n" +
                    "跳过：太新 ${trashPlan.skippedTooNew} · 超上限 ${trashPlan.cappedOut}",
            )
            .setPositiveButton("开始") { _, _ ->
                scope.launch {
                    val outcome = withContext(Dispatchers.IO) { AppGraph.moveToTrash(trashPlan.entries) }
                    if (outcome == null) {
                        say("移动失败：shell 通道不可用")
                        return@launch
                    }
                    say(
                        buildString {
                            appendLine("已移入 ${outcome.moved.size} 个 · ${humanBytes(outcome.movedBytes)}")
                            appendLine("失败 ${outcome.failed.size} 个")
                            outcome.failed.take(5).forEach { (entry, why) ->
                                appendLine("· ${entry.srcPath.substringAfterLast('/')}: $why")
                            }
                            if (outcome.moved.isNotEmpty()) {
                                appendLine()
                                appendLine("可用「撤销」还原；文件已在暂存区，源目录里已删除。")
                            }
                        },
                    )
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 撤销：从暂存区搬回原路径，搬完才从索引里移除 */
    private fun undoTrash() {
        val records = AppGraph.trashRecords()
        if (records.isEmpty()) {
            say("没有可撤销的记录。\n（暂存区索引为空，或还没移入过任何文件）")
            return
        }

        if (AppGraph.trashMover() == null) {
            say("还原需要追加包 shell 通道。")
            return
        }

        val total = humanBytes(records.sumOf { it.sizeBytes })

        AlertDialog.Builder(this)
            .setTitle("撤销移入")
            .setMessage("暂存区有 ${records.size} 个文件 · $total\n\n还原会先复制回原路径并校验 sha256，通过后才删除暂存区副本。")
            .setPositiveButton("全部还原") { _, _ -> restore(records) }
            .setNeutralButton("挑一个") { _, _ -> pickRecord(records) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pickRecord(records: List<TrashEntry>) {
        val shown = records.takeLast(50)
        val labels = shown.map { r ->
            "${r.srcPath.substringAfterLast('/')}  ${humanBytes(r.sizeBytes)}\n${r.srcPath.substringBeforeLast('/')}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("选择要还原的文件")
            .setItems(labels) { _, which -> restore(listOf(shown[which])) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun restore(entries: List<TrashEntry>) {
        scope.launch {
            val (ok, failed) = withContext(Dispatchers.IO) { AppGraph.restoreTrash(entries) }
            say(
                buildString {
                    appendLine("已还原 $ok 个")
                    if (failed > 0) {
                        appendLine("失败 $failed 个")
                        appendLine("可能原因：暂存区文件被改过（sha256 不匹配）、原目录不可写、目标已存在同名文件。")
                    }
                },
            )
        }
    }

    private fun emptyTrash() {
        val mover = AppGraph.trashMover()
        if (mover == null) {
            say("需要追加包 shell 通道")
            return
        }

        val n = AppGraph.trashRecords().size
        AlertDialog.Builder(this)
            .setTitle("清空暂存区（不可恢复）")
            .setMessage(
                "这会真正删除 ${AppGraph.trashDir} 下的所有文件，并清空索引（$n 条记录）。\n" +
                    "删除后无法还原。",
            )
            .setPositiveButton("删除") { _, _ ->
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { AppGraph.purgeTrash() }
                    say(if (ok) "已清空暂存区（$n 条索引一并清除）" else "清空失败")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refresh() {
        val report = AppGraph.probeCapabilities()
        val allowed = AppGraph.allowlist.all()
        val cfg = AppGraph.modelConfig()

        status.text = buildString {
            appendLine("无障碍：" + if (report.has(Cap.A11Y_TREE)) "已连接" else "未连接")
            appendLine("投屏：" + if (ScreenCapture.isActive) "运行中" else "未启动")
            appendLine("shell：" + AppGraph.shellStatus())
            appendLine("注入通道（上次）：" + InputChannelProbe.label())
            appendLine("模式：" + ResearchMode.label())
            appendLine("待审脚本：" + AppGraph.pendingScripts().size + " 份")
            appendLine("悬浮窗：" + if (report.has(Cap.OVERLAY)) "已授权" else "未授权")
            appendLine("模型：" + (cfg?.model ?: "未配置"))
            appendLine("前台：" + AppGraph.lastForeground.ifBlank { "未知" })
            appendLine("白名单：" + if (allowed.isEmpty()) "空（拒绝一切）" else allowed.joinToString())
            appendLine("冷却中：" + SuicideRecord.isCoolingDown(this@MainActivity))

            AppGraph.guard?.let { g ->
                append("自检：")
                append(
                    listOf("com.tencent.mm", "com.tencent.mobileqq", "com.unknown.app")
                        .joinToString("  ") { p ->
                            "$p=" + if (g.check(p) is TargetGuard.Decision.Denied) "DENY" else "ALLOW"
                        },
                )
            }
        }

        val logs = PolicyLog.dump(this)
        if (logs.isNotEmpty() && outputView.text.isNullOrBlank()) {
            outputView.text = "策略日志：\n" + logs.takeLast(20).joinToString("\n")
        }
    }

    private fun say(text: String) {
        outputView.text = text
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
