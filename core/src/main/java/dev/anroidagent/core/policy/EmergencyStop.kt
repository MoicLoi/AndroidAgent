package dev.anroidagent.core.policy

import android.content.Context
import android.os.Process
import kotlin.system.exitProcess

/** 触发记录，同时作为审计证据 */
data class Trigger(
    val pkg: String,
    val rule: String,
    val at: Long,
)

/**
 * 自杀记录 + 冷却期。
 *
 * 冷却期的存在意义：如果不设，`killProcess` 后会被 WorkManager / AlarmManager /
 * BOOT_COMPLETED 立刻拉起，等于没杀。
 */
object SuicideRecord {

    const val COOLDOWN_MS: Long = 10 * 60 * 1000L

    private const val PREF = "policy_suicide"
    private const val K_PKG = "pkg"
    private const val K_RULE = "rule"
    private const val K_AT = "at"

    fun write(ctx: Context, trigger: Trigger) {
        prefs(ctx).edit()
            .putString(K_PKG, trigger.pkg)
            .putString(K_RULE, trigger.rule)
            .putLong(K_AT, trigger.at)
            .apply()
    }

    fun read(ctx: Context): Trigger? {
        val p = prefs(ctx)
        val at = p.getLong(K_AT, 0L)
        if (at <= 0L) return null
        return Trigger(
            pkg = p.getString(K_PKG, "") ?: "",
            rule = p.getString(K_RULE, "") ?: "",
            at = at,
        )
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }

    fun isCoolingDown(ctx: Context): Boolean {
        val t = read(ctx) ?: return false
        return System.currentTimeMillis() - t.at < COOLDOWN_MS
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}

/** 结构化审计日志（不含凭证明文与完整界面文本） */
data class PolicyEvent(
    val at: Long,
    val pkg: String,
    val rule: String,
    val action: String,
    val evidence: String,
    val level: String,
)

object PolicyLog {

    private const val PREF = "policy_events"
    private const val KEY = "lines"
    private const val MAX_LINES = 200

    fun record(ctx: Context, e: PolicyEvent) {
        val p = ctx.applicationContext
            .getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val old = p.getString(KEY, "") ?: ""
        val line = buildString {
            append(e.at).append('\t')
            append(e.level).append('\t')
            append(e.pkg).append('\t')
            append(e.rule).append('\t')
            append(e.action).append('\t')
            append(e.evidence.take(60).replace('\n', ' '))
        }
        val merged = (old.lines().filter { it.isNotBlank() } + line)
            .takeLast(MAX_LINES)
            .joinToString("\n")
        p.edit().putString(KEY, merged).apply()
    }

    fun dump(ctx: Context): List<String> =
        (ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY, "") ?: "")
            .lines()
            .filter { it.isNotBlank() }
}

/**
 * 紧急停止。
 *
 * 顺序不可调换：
 * 1. 落盘记录（供启动守卫与审计）
 * 2. 禁用无障碍自身 —— 不调用它，系统会立刻重启服务，杀了等于没杀
 * 3. 撤销所有后台调度
 * 4. 通知追加包一起退出
 * 5. 最后才 kill
 */
object EmergencyStop {

    fun trigger(
        ctx: Context,
        pkg: String,
        rule: String,
        disableA11y: (() -> Unit)? = null,
        cancelSchedules: (() -> Unit)? = null,
        shutdownAddon: (() -> Unit)? = null,
    ): Nothing {
        val now = System.currentTimeMillis()

        PolicyLog.record(
            ctx,
            PolicyEvent(
                at = now,
                pkg = pkg,
                rule = rule,
                action = "self-terminate",
                evidence = rule,
                level = "L3",
            ),
        )
        SuicideRecord.write(ctx, Trigger(pkg, rule, now))

        runCatching { disableA11y?.invoke() }
        runCatching { cancelSchedules?.invoke() }
        runCatching { shutdownAddon?.invoke() }

        Process.killProcess(Process.myPid())
        exitProcess(0)
    }
}
