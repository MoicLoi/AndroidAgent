package dev.anroidagent.agent.task

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 串行任务队列（带持久化）。
 *
 * 单设备天然串行：两个任务同时操作同一块屏幕必然互相破坏状态。
 *
 * 恢复语义：
 * - **待执行队列**重启后继续跑
 * - **执行中的任务**只记录"进程中断 + 死在第几步"，**不自动续跑**——
 *   屏幕状态早已变化，重放很可能点错东西
 */
class TaskQueue(
    scope: CoroutineScope,
    private val store: TaskStore = NoopTaskStore,
    private val handler: suspend (spec: TaskSpec, onProgress: (Int) -> Unit) -> TaskResult,
) {

    sealed interface State {
        data object Idle : State
        data class Running(val spec: TaskSpec) : State
        data class Finished(val result: TaskResult) : State
    }

    private val lock = Any()
    private val queue = ArrayDeque<TaskSpec>()
    private val signal = Channel<Unit>(Channel.CONFLATED)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _history = MutableStateFlow<List<TaskResult>>(emptyList())
    val history: StateFlow<List<TaskResult>> = _history.asStateFlow()

    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()

    @Volatile
    private var current: Job? = null

    init {
        recoverInterrupted()

        synchronized(lock) {
            queue.addAll(store.loadQueue())
            _pendingCount.value = queue.size
        }
        _history.value = store.loadResults()

        scope.launch { worker() }
        signal.trySend(Unit)
    }

    fun enqueue(spec: TaskSpec) {
        synchronized(lock) { queue.addLast(spec) }
        persistQueue()
        signal.trySend(Unit)
    }

    fun cancelCurrent() {
        current?.cancel()
    }

    fun clearQueue() {
        synchronized(lock) { queue.clear() }
        persistQueue()
    }

    // ---------------- 内部 ----------------

    private suspend fun CoroutineScope.worker() {
        while (true) {
            signal.receive()

            while (true) {
                val spec = synchronized(lock) { queue.removeFirstOrNull() } ?: break
                persistQueue()

                _state.value = State.Running(spec)
                val startedAt = System.currentTimeMillis()
                store.saveActive(ActiveTask(spec, startedAt, 0))

                val job = launch {
                    try {
                        runOne(spec, startedAt)
                    } finally {
                        store.saveActive(null)
                    }
                }
                current = job
                job.join()
                current = null
            }
        }
    }

    private suspend fun runOne(spec: TaskSpec, startedAt: Long) {
        val result = try {
            handler(spec) { stepIndex ->
                // 心跳：每步开始前落盘，进程被杀后能知道死在第几步
                store.saveActive(ActiveTask(spec, startedAt, stepIndex))
            }
        } catch (e: CancellationException) {
            TaskResult(
                spec, TaskStatus.STOPPED, "已取消", emptyList(), 0,
                System.currentTimeMillis() - startedAt,
            )
        } catch (e: Exception) {
            TaskResult(
                spec, TaskStatus.FAILED,
                "异常：${e.javaClass.simpleName}: ${e.message}",
                emptyList(), 0, System.currentTimeMillis() - startedAt,
            )
        }

        store.appendResult(result)
        _history.value = (_history.value + result).takeLast(20)
        _state.value = State.Finished(result)
    }

    private fun persistQueue() {
        val snapshot = synchronized(lock) { queue.toList() }
        store.saveQueue(snapshot)
        _pendingCount.value = snapshot.size
    }

    private fun recoverInterrupted() {
        val active = store.loadActive() ?: return
        store.appendResult(
            TaskResult(
                spec = active.spec,
                status = TaskStatus.STOPPED,
                message = "进程中断：上次运行到第 ${active.stepIndex} 步，" +
                    "未自动续跑（屏幕状态已变化，重放可能点错）",
                steps = emptyList(),
                promptTokens = 0,
                elapsedMs = System.currentTimeMillis() - active.startedAt,
            ),
        )
        store.saveActive(null)
    }
}
