package dev.anroidagent.app.task

import dev.anroidagent.agent.task.ActiveTask
import dev.anroidagent.agent.task.TaskResult
import dev.anroidagent.agent.task.TaskSpec
import dev.anroidagent.agent.task.TaskStore
import java.io.File

/**
 * 文件持久化。
 *
 * 三个小文件放在 `filesDir/tasks/`：
 * - `queue.json`   待执行队列
 * - `active.json`  运行中快照（心跳，每步刷新）
 * - `results.json` 最近 10 条结果
 *
 * 写入用「临时文件 + rename」避免半写坏档；读取失败一律降级为空，
 * 坏文件不能让 App 起不来。
 */
class FileTaskStore(dir: File) : TaskStore {

    private val base: File = dir.apply { mkdirs() }

    private val queueFile = File(base, "queue.json")
    private val activeFile = File(base, "active.json")
    private val resultsFile = File(base, "results.json")

    override fun loadQueue(): List<TaskSpec> = TaskCodec.decodeQueue(read(queueFile))

    override fun saveQueue(specs: List<TaskSpec>) =
        write(queueFile, TaskCodec.encodeQueue(specs))

    override fun loadActive(): ActiveTask? = TaskCodec.decodeActive(read(activeFile))

    override fun saveActive(active: ActiveTask?) {
        if (active == null) {
            runCatching { activeFile.delete() }
        } else {
            write(activeFile, TaskCodec.encodeActive(active))
        }
    }

    override fun loadResults(): List<TaskResult> = TaskCodec.decodeResults(read(resultsFile))

    override fun appendResult(result: TaskResult) {
        val all = (loadResults() + result).takeLast(10)
        write(resultsFile, TaskCodec.encodeResults(all))
    }

    private fun read(file: File): String? = runCatching {
        if (file.exists()) file.readText() else null
    }.getOrNull()

    private fun write(file: File, text: String) {
        runCatching {
            val tmp = File(base, file.name + ".tmp")
            tmp.writeText(text)
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }
    }
}
