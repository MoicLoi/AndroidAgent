package dev.anroidagent.driver

import dev.anroidagent.core.model.UiNode
import dev.anroidagent.core.policy.TargetGuard

class DegradedException(message: String) : Exception(message)

/**
 * 设备动作抽象。上层业务只依赖这个接口，不关心底下是
 * 无障碍、shell 注入、uinput 还是外部 HID。
 */
interface Driver {
    suspend fun tap(x: Int, y: Int)
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300L)
    suspend fun inputText(text: String)
    suspend fun launch(pkg: String)
    suspend fun back()

    /** 返回 null 表示截不到（secure 页面 / 无权限） */
    suspend fun shot(): ByteArray?

    /** 返回 null 表示无障碍树不可读 */
    suspend fun tree(): UiNode?
}

/** 前台包名解析器，两条独立路径（无障碍事件 / dumpsys），都失败就算未知 */
fun interface ForegroundResolver {
    fun foregroundPackage(): String?
}

/**
 * 记录上一次动作实际走的通道。
 *
 * 复盘时第一个要看的信息：某一步到底是 shell 注入还是无障碍点击。
 * 用一个 volatile 字段而不是往 StepLog 里塞参数，是为了不把驱动层概念
 * 泄漏到 agent 层。
 */
object InputChannelProbe {
    const val SHELL = "shell"
    const val A11Y = "a11y"
    const val DRY = "dry-run"
    const val NONE = "none"

    @Volatile
    var last: String = NONE
        private set

    fun note(channel: String) {
        last = channel
    }

    fun label(): String = when (last) {
        SHELL -> "shell 注入"
        A11Y -> "无障碍点击"
        DRY -> "研究模式（未执行）"
        else -> "未执行"
    }
}

/**
 * 驱动路由。
 *
 * 语义（与产品约束一致）：
 * - **注入通道**：[shell] 非空才用 shell，失败自动降级 [a11y]
 * - **取帧**：单独走 [shotSource]（shell 的 `screencap` 属只读能力，
 *   不应该被"注入开关"关掉）
 * - **读树**：只能走 [a11y]
 *
 * [shell] 是否为空由 `app` 层的显式开关决定——默认关闭，必须用户授权开启。
 */
class DriverRouter(
    private val shell: Driver? = null,
    private val a11y: Driver? = null,
    private val shotSource: Driver? = null,
) : Driver {

    /** shell 优先，失败降级无障碍；两条都不行才抛 */
    private suspend fun run(action: suspend (Driver) -> Unit) {
        val s = shell
        if (s != null) {
            val ok = runCatching { action(s) }.onFailure {
                // 保留异常细节到日志由上层处理，这里只做通道切换
            }.isSuccess
            if (ok) {
                InputChannelProbe.note(InputChannelProbe.SHELL)
                return
            }
        }

        val a = a11y ?: throw DegradedException("no driver available")
        InputChannelProbe.note(InputChannelProbe.A11Y)
        action(a)
    }

    override suspend fun tap(x: Int, y: Int) = run { it.tap(x, y) }

    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) =
        run { it.swipe(x1, y1, x2, y2, durationMs) }

    override suspend fun inputText(text: String) = run { it.inputText(text) }

    override suspend fun launch(pkg: String) {
        // 启动应用优先走无障碍（Intent），失败再试 shell
        val a = a11y
        if (a != null) {
            if (runCatching { a.launch(pkg) }.isSuccess) {
                InputChannelProbe.note(InputChannelProbe.A11Y)
                return
            }
        }
        val s = shell ?: throw DegradedException("no driver available")
        InputChannelProbe.note(InputChannelProbe.SHELL)
        s.launch(pkg)
    }

    override suspend fun back() = run { it.back() }

    override suspend fun shot(): ByteArray? {
        val source = shotSource ?: shell ?: a11y ?: return null
        return runCatching { source.shot() }.getOrNull()
    }

    override suspend fun tree(): UiNode? {
        val a = a11y ?: shell ?: throw DegradedException("no driver available")
        return a.tree()
    }
}

/**
 * 策略守卫（四个执行点之一）。
 *
 * 所有注入动作在进入 [Driver] 之前必须经过它。
 * 前台包名拿不到时**拒绝执行**（fail-closed），而不是放行。
 */
class GuardedDriver(
    private val inner: Driver,
    private val guard: TargetGuard,
    private val foreground: ForegroundResolver,
    private val selfPackage: String? = null,
    private val onKill: (pkg: String, rule: String) -> Nothing,
) : Driver {

    private fun gate() {
        val pkg = foreground.foregroundPackage()
            ?: onKill("", "fg-unknown")

        // 自家包不参与白名单判定，否则调试时一读界面就把自己杀掉
        if (selfPackage != null && pkg == selfPackage) return

        when (val d = guard.check(pkg)) {
            is TargetGuard.Decision.Allowed -> Unit
            is TargetGuard.Decision.Denied -> onKill(pkg, d.ruleId)
        }
    }

    override suspend fun tap(x: Int, y: Int) {
        gate(); inner.tap(x, y)
    }

    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) {
        gate(); inner.swipe(x1, y1, x2, y2, durationMs)
    }

    override suspend fun inputText(text: String) {
        gate(); inner.inputText(text)
    }

    override suspend fun launch(pkg: String) {
        gate(); inner.launch(pkg)
    }

    override suspend fun back() {
        gate(); inner.back()
    }

    override suspend fun shot(): ByteArray? {
        gate(); return inner.shot()
    }

    override suspend fun tree(): UiNode? {
        gate(); return inner.tree()
    }
}
