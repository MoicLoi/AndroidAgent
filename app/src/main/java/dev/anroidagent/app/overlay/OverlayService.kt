package dev.anroidagent.app.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.anroidagent.app.AgentRunner
import dev.anroidagent.app.AppGraph
import dev.anroidagent.app.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 悬浮控制面板。
 *
 * 两条硬约束：
 * 1. 用 `specialUse` 类型的前台服务：Android 14+ 所有 FGS 必须声明类型
 * 2. 执行前把窗口**整个移除**（不是设 GONE）——只要 overlay 存在，目标 App 收到的
 *    `MotionEvent` 就会带 `FLAG_WINDOW_IS_OBSCURED`，强风控 App 会直接忽略触摸。
 *    这是"悬浮窗给人看"和"反检测给机器看"的冲突点，只能靠时序分离解决。
 */
class OverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "overlay"
        private const val NOTIFICATION_ID = 42

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, OverlayService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, OverlayService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var windowManager: WindowManager? = null
    private var rootView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var statusView: TextView? = null

    private var busy = false

    private var downRawX = 0
    private var downRawY = 0
    private var startX = 0
    private var startY = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        if (rootView == null) attach()
        return START_STICKY
    }

    override fun onDestroy() {
        detach()
        scope.cancel()
        super.onDestroy()
    }

    // ---------------- 前台服务 ----------------

    private fun promoteToForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "悬浮控制面板", NotificationManager.IMPORTANCE_MIN),
                )
            }
        }
        val n = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("控制面板运行中")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    // ---------------- 窗口 ----------------

    private fun attach() {
        val wm = windowManager ?: return
        if (rootView != null) return

        val cfg = Prefs.loadOverlay(this)
        val dm = resources.displayMetrics
        val width = dp(cfg.widthDp)
        val height = dp(cfg.heightDp)
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels

        val x0 = if (cfg.x >= 0) cfg.x else screenW - width - dp(12)
        val y0 = if (cfg.y >= 0) cfg.y else dp(140)

        val p = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = x0.coerceIn(0, (screenW - width).coerceAtLeast(0))
            y = y0.coerceIn(0, (screenH - height).coerceAtLeast(0))
            alpha = cfg.alpha
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(cfg.cornerDp).toFloat()
                setColor(cfg.bgColor)
            }
        }

        val status = TextView(this).apply {
            text = "待命 · 拖动我移动"
            textSize = 11f
            maxLines = 2
            setTextColor(cfg.textColor)
        }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(
            miniButton("读屏", cfg.textColor) { runOnce() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(
            miniButton("收起", cfg.textColor) { stopSelf() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )

        container.addView(status)
        container.addView(row)

        status.setOnTouchListener(dragListener(wm, container))

        runCatching { wm.addView(container, p) }
            .onFailure {
                status.text = "添加失败：${it.message?.take(24)}"
                return
            }

        rootView = container
        layoutParams = p
        statusView = status
    }

    private fun detach() {
        val wm = windowManager ?: return
        val v = rootView ?: return
        runCatching { wm.removeViewImmediate(v) }
        rootView = null
        layoutParams = null
        statusView = null
    }

    private fun dragListener(wm: WindowManager, view: View) =
        View.OnTouchListener { _, e ->
            val p = layoutParams ?: return@OnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX.toInt()
                    downRawY = e.rawY.toInt()
                    startX = p.x
                    startY = p.y
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dm = resources.displayMetrics
                    p.x = (startX + (e.rawX.toInt() - downRawX))
                        .coerceIn(0, (dm.widthPixels - p.width).coerceAtLeast(0))
                    p.y = (startY + (e.rawY.toInt() - downRawY))
                        .coerceIn(0, (dm.heightPixels - p.height).coerceAtLeast(0))
                    runCatching { wm.updateViewLayout(view, p) }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    persistPosition(p.x, p.y)
                    true
                }

                else -> false
            }
        }

    private fun persistPosition(x: Int, y: Int) {
        val cfg = Prefs.loadOverlay(this)
        Prefs.saveOverlay(this, cfg.copy(x = x, y = y))
    }

    // ---------------- 动作 ----------------

    private fun runOnce() {
        if (busy) return

        val cfg = AppGraph.modelConfig()
        if (cfg == null) {
            setStatus("未配置模型")
            return
        }

        busy = true
        scope.launch {
            val overlayCfg = Prefs.loadOverlay(this@OverlayService)
            val hide = overlayCfg.hideWhileRunning
            try {
                setStatus("读屏中…")

                // 关键时序：先撤窗口，再读屏/调模型
                if (hide) detach()

                val outcome = AgentRunner.runOnce(this@OverlayService, cfg)

                if (hide) attach()
                setStatus(
                    buildString {
                        append(outcome.headline)
                        if (outcome.tuiTokens > 0) append(" · ${outcome.tuiTokens}t")
                    },
                )
            } catch (e: Exception) {
                if (hide && rootView == null) attach()
                setStatus("失败：${e.message?.take(28) ?: e.javaClass.simpleName}")
            } finally {
                busy = false
            }
        }
    }

    private fun setStatus(text: String) {
        statusView?.text = text
    }

    // ---------------- 小工具 ----------------

    private fun miniButton(label: String, color: Int, onClick: () -> Unit) =
        Button(this).apply {
            text = label
            textSize = 10f
            setTextColor(color)
            setBackgroundColor(0x00000000)
            minHeight = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            setOnClickListener { onClick() }
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
