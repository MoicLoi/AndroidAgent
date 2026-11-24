package dev.anroidagent.app

import dev.anroidagent.agent.task.ActiveTask
import dev.anroidagent.agent.task.StepLog
import dev.anroidagent.agent.task.TaskResult
import dev.anroidagent.agent.task.TaskSpec
import dev.anroidagent.agent.task.TaskStatus
import dev.anroidagent.app.task.TaskCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 持久化编解码。
 *
 * 目标是"坏文件不能把 App 卡死"：所有 decode 都必须能对脏数据返回空结果。
 */
class TaskCodecTest {

    private fun spec(id: String = "t1", text: String = "搜索耳机") =
        TaskSpec(instruction = text, maxSteps = 8, id = id)

    private fun result(id: String = "t1", status: TaskStatus = TaskStatus.DONE) = TaskResult(
        spec = spec(id),
        status = status,
        message = "完成",
        steps = listOf(
            StepLog(
                index = 1,
                step = null,
                stepText = "Tap(index=3)",
                verdict = "Pass",
                tuiTokens = 87,
                usedVision = false,
                outcome = "ok",
                raw = """{"action":{"type":"tap","index":3}}""",
                elapsedMs = 1200,
            ),
        ),
        promptTokens = 321,
        elapsedMs = 4500,
    )

    @Test
    fun `queue round trip`() {
        val specs = listOf(spec("a", "任务A"), spec("b", "任务B"))
        val back = TaskCodec.decodeQueue(TaskCodec.encodeQueue(specs))

        assertEquals(2, back.size)
        assertEquals("任务A", back[0].instruction)
        assertEquals(8, back[1].maxSteps)
        assertEquals("b", back[1].id)
    }

    @Test
    fun `empty queue encodes and decodes`() {
        assertTrue(TaskCodec.decodeQueue(TaskCodec.encodeQueue(emptyList())).isEmpty())
        assertTrue(TaskCodec.decodeQueue(null).isEmpty())
        assertTrue(TaskCodec.decodeQueue("").isEmpty())
    }

    @Test
    fun `active snapshot keeps heartbeat`() {
        val active = ActiveTask(spec = spec(), startedAt = 1000L, stepIndex = 7)
        val back = TaskCodec.decodeActive(TaskCodec.encodeActive(active))

        assertEquals(7, back?.stepIndex)
        assertEquals(1000L, back?.startedAt)
        assertEquals("搜索耳机", back?.spec?.instruction)
    }

    @Test
    fun `missing active decodes to null`() {
        assertNull(TaskCodec.decodeActive(null))
        assertNull(TaskCodec.decodeActive(""))
        assertNull(TaskCodec.decodeActive("{broken"))
    }

    @Test
    fun `results keep step text but drop step object`() {
        val back = TaskCodec.decodeResults(TaskCodec.encodeResults(listOf(result())))

        assertEquals(1, back.size)
        val r = back.first()
        assertEquals(TaskStatus.DONE, r.status)
        assertEquals(321, r.promptTokens)
        assertEquals(1, r.steps.size)
        assertEquals("Tap(index=3)", r.steps[0].stepText)
        assertNull("Step 对象不持久化，恢复后为 null", r.steps[0].step)
        assertEquals(87, r.steps[0].tuiTokens)
    }

    @Test
    fun `results are capped at ten`() {
        val many = (1..15).map { result(id = "t$it") }
        val back = TaskCodec.decodeResults(TaskCodec.encodeResults(many))

        assertEquals(10, back.size)
        assertEquals("t15", back.last().spec.id)
    }

    @Test
    fun `corrupt results degrade to empty`() {
        assertTrue(TaskCodec.decodeResults("not json").isEmpty())
        assertTrue(TaskCodec.decodeResults("[{]").isEmpty())
        assertTrue(TaskCodec.decodeResults(null).isEmpty())
    }

    @Test
    fun `unknown status falls back to FAILED`() {
        val json = """[{"spec":{"id":"x","instruction":"i","maxSteps":3},"status":"WEIRD"}]"""
        val back = TaskCodec.decodeResults(json)
        assertEquals(TaskStatus.FAILED, back.first().status)
    }
}
