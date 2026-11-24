package dev.anroidagent.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import dev.anroidagent.core.policy.PolicyEvent
import dev.anroidagent.core.policy.PolicyLog
import dev.anroidagent.core.policy.SuicideRecord
import dev.anroidagent.core.policy.TargetGuard
import dev.anroidagent.driver.A11yDriver
import dev.anroidagent.driver.Driver

class AgentService : AccessibilityService() {

    private var driverInstance: Driver? = null

    fun driver(): Driver = driverInstance ?: A11yDriver(this).also { driverInstance = it }

    override fun onServiceConnected() {
        super.onServiceConnected()

        if (SuicideRecord.isCoolingDown(this)) {
            disableSelf()
            return
        }
        AppGraph.init(applicationContext)
        AppGraph.attach(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        AppGraph.detach(this)
        driverInstance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isBlank() || pkg == packageName) return

        AppGraph.noteForeground(pkg)

        val guard = AppGraph.guard ?: return

        when (val d = guard.check(pkg)) {
            is TargetGuard.Decision.Allowed -> Unit
            is TargetGuard.Decision.Denied -> {
                PolicyLog.record(
                    this,
                    PolicyEvent(
                        at = System.currentTimeMillis(),
                        pkg = pkg,
                        rule = d.ruleId,
                        action = "window-changed",
                        evidence = d.why,
                        level = "L3",
                    ),
                )
                AppGraph.killFor(pkg, d.ruleId)
            }
        }
    }

    override fun onInterrupt() = Unit
}
