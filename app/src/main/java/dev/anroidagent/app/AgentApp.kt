package dev.anroidagent.app

import android.app.Application
import dev.anroidagent.core.policy.PolicyEvent
import dev.anroidagent.core.policy.PolicyLog
import dev.anroidagent.core.policy.ResearchMode
import dev.anroidagent.core.policy.SuicideRecord

class AgentApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // 研究模式是编译期常量，最早配置，保证任何动作之前就生效
        ResearchMode.configure(BuildConfig.RESEARCH_MODE)

        // 冷却期检查必须在任何组件初始化之前。
        // 否则杀掉进程后会被系统/调度器立刻拉起，等于没杀。
        if (SuicideRecord.isCoolingDown(this)) {
            PolicyLog.record(
                this,
                PolicyEvent(
                    at = System.currentTimeMillis(),
                    pkg = "",
                    rule = "cooldown",
                    action = "startup-blocked",
                    evidence = SuicideRecord.read(this)?.rule.orEmpty(),
                    level = "L3",
                ),
            )
            return
        }

        AppGraph.init(this)
    }
}
