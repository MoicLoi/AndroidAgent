package dev.anroidagent.agent.task

/**
 * 正在运行的任务快照。
 *
 * [stepIndex] 是心跳：每步开始前更新一次。进程被杀后靠它知道死在哪儿，
 * 但**不用于自动续跑**——屏幕状态早已变化，重放很可能点错东西。
 */
data class ActiveTask(
    val spec: TaskSpec,
    val startedAt: Long,
    val stepIndex: Int,
)

/**
 * 队列与结果的持久化。
 *
 * 只存「任务描述 + 结果摘要」，**不存**截图、屏幕文本、账号或凭证。
 */
interface TaskStore {

    fun loadQueue(): List<TaskSpec>

    fun saveQueue(specs: List<TaskSpec>)

    fun loadActive(): ActiveTask?

    fun saveActive(active: ActiveTask?)

    fun loadResults(): List<TaskResult>

    fun appendResult(result: TaskResult)
}

/** 无持久化时的空实现（单测与"关闭持久化"开关都用它） */
object NoopTaskStore : TaskStore {
    override fun loadQueue(): List<TaskSpec> = emptyList()
    override fun saveQueue(specs: List<TaskSpec>) = Unit
    override fun loadActive(): ActiveTask? = null
    override fun saveActive(active: ActiveTask?) = Unit
    override fun loadResults(): List<TaskResult> = emptyList()
    override fun appendResult(result: TaskResult) = Unit
}
