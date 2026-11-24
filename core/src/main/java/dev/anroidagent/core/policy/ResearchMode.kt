package dev.anroidagent.core.policy

/**
 * 研究模式（只记录不执行）。
 *
 * 由**编译期**属性 `anroidagent.researchMode` 决定，通过 `BuildConfig` 注入，
 * 运行期只读——刻意不提供远程/本地开关：一旦可以运行时关闭，
 * "这个包不会改变设备状态"这句话就不成立了。
 *
 * 开启后：
 * - 所有注入动作（点击/滑动/输入/启动/返回）只记录不执行
 * - 读操作（读树、截图、dumpsys 判前台）照常，否则无从记录
 * - 变更类的 shell 操作（pm clear 等）同样被拦截
 */
object ResearchMode {

    @Volatile
    var enabled: Boolean = false
        private set

    fun configure(enabled: Boolean) {
        this.enabled = enabled
    }

    /** 供 UI 显示的一行标记 */
    fun label(): String = if (enabled) "研究模式：只记录不执行" else "正常模式"
}
