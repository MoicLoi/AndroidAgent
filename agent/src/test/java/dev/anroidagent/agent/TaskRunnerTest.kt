package dev.anroidagent.agent

import dev.anroidagent.agent.model.ChatMessage
import dev.anroidagent.agent.model.Completion
import dev.anroidagent.agent.model.ModelClient
import dev.anroidagent.agent.model.Planner
import dev.anroidagent.agent.model.Usage
import dev.anroidagent.agent.perceive.TokenEstimate
import dev.anroidagent.agent.task.Frame
import dev.anroidagent.agent.task.FrameProvider
import dev.anroidagent.agent.task.StepExecutor
import dev.anroidagent.agent.task.TaskRunner
import dev.anroidagent.agent.task.TaskSpec
import dev.anroidagent.agent.task.TaskStatus
import dev.anroidagent.core.model.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务状态机测试。
 *
 * 用脚本化的假模型跑完整的 Planner + TaskRunner 链路（不联网），
 * 验证五条退出路径：DONE / PAUSED / STOPPED / FAILED(连续失败) / FAILED(步数耗尽)。
 */
class TaskRunnerTest {

    private class Scripted(replies: List<String>) : ModelClient {
        private val queue = ArrayDeque(replies)
        var calls = 0
            private set
        val seen = mutableListOf<List<ChatMessage>>()

        override suspend fun complete(messages: List<ChatMessage>): Completion {
            calls++
            seen += messages
            val reply = if (queue.size > 1) queue.removeFirst() else queue.first()
            return Completion(reply, Usage(120, 30))
        }
    }

    private fun tapReply(index: Int = 1) =
        """{"action":{"type":"tap","index":$index,"label":"搜索"},"risk":null}"""

    private val doneReply = """{"action":{"type":"ask","question":"DONE"},"risk":null}"""

    private fun frame(secure: Boolean = false, image: ByteArray? = null): Frame {
        val tui = "# 1080x2400 shop s1\n[1]搜索 [2]◻"
        return Frame(
            tui = tui,
            items = emptyList(),
            image = image,
            isSecure = secure,
            tuiTokens = TokenEstimate.of(tui),
            packageName = "com.example.shop",
        )
    }

    private fun okExecutor(onCall: () -> Unit = {}): StepExecutor =
        StepExecutor { _, _ ->
            onCall()
            StepExecutor.Outcome.Ok
        }

    private fun failExecutor(): StepExecutor =
        StepExecutor { _, _ -> StepExecutor.Outcome.Failed("节点不可点") }

    private fun runner(client: ModelClient) = TaskRunner(Planner(client))

    @Test
    fun `finishes when model says DONE`() = kotlinx.coroutines.runBlocking {
        val client = Scripted(listOf(tapReply(), doneReply))
        val result = runner(client).run(
            TaskSpec("测试任务", maxSteps = 5),
            FrameProvider { frame() },
            okExecutor(),
        )

        assertEquals(TaskStatus.DONE, result.status)
        assertEquals(2, result.steps.size)
        assertTrue(result.ok)
        assertEquals(2, client.calls)
    }

    @Test
    fun `pauses on credential risk and never executes`() = kotlinx.coroutines.runBlocking {
        val risky = """{"action":{"type":"tap","index":1,"label":"立即支付"},""" +
            """"risk":{"class":"CREDENTIAL","evidence":"支付密码","conf":0.9}}"""
        val client = Scripted(listOf(risky))
        var executed = false

        val result = runner(client).run(
            TaskSpec("付款", maxSteps = 5),
            FrameProvider { frame() },
            okExecutor { executed = true },
        )

        assertEquals(TaskStatus.PAUSED, result.status)
        assertFalse("命中硬升级时不允许执行动作", executed)
        assertTrue(result.message.contains("CREDENTIAL"))
    }

    @Test
    fun `stops immediately on secure frame without calling model`() = kotlinx.coroutines.runBlocking {
        val client = Scripted(listOf(tapReply()))

        val result = runner(client).run(
            TaskSpec("任意", maxSteps = 5),
            FrameProvider { frame(secure = true) },
            okExecutor(),
        )

        assertEquals(TaskStatus.STOPPED, result.status)
        assertEquals("secure 帧不应该触发模型调用", 0, client.calls)
        assertEquals(1, result.steps.size)
    }

    @Test
    fun `fails after consecutive execution failures`() = kotlinx.coroutines.runBlocking {
        val client = Scripted(listOf(tapReply(), tapReply(), tapReply(), tapReply()))

        val result = runner(client).run(
            TaskSpec("点不到", maxSteps = 10),
            FrameProvider { frame() },
            failExecutor(),
        )

        assertEquals(TaskStatus.FAILED, result.status)
        assertTrue(result.message.contains("连续"))
        assertEquals(2, result.steps.size)
    }

    @Test
    fun `fails when step budget exhausted`() = kotlinx.coroutines.runBlocking {
        val client = Scripted(listOf(tapReply()))

        val result = runner(client).run(
            TaskSpec("无限点", maxSteps = 3),
            FrameProvider { frame() },
            okExecutor(),
        )

        assertEquals(TaskStatus.FAILED, result.status)
        assertTrue(result.message.contains("最大步数"))
        assertEquals(3, result.steps.size)
    }

    @Test
    fun `uses vision when frame carries an image`() = kotlinx.coroutines.runBlocking {
        val client = Scripted(listOf(tapReply(), doneReply))

        runner(client).run(
            TaskSpec("看图", maxSteps = 3),
            FrameProvider { frame(image = byteArrayOf(1, 2, 3)) },
            okExecutor(),
        )

        val last = client.seen.first().last()
        assertEquals("视觉兜底时应携带图片", 1, last.images.size)
    }

    @Test
    fun `mutating shell command is blocked and reported upstream`() = kotlinx.coroutines.runBlocking {
        val reply = """{"action":{"type":"shell","cmd":"pm clear com.example.app"},"risk":null}"""
        val client = Scripted(listOf(reply))
        var blockedStep: Step? = null
        var executed = false

        val result = runner(client).run(
            TaskSpec("清数据", maxSteps = 3),
            FrameProvider { frame() },
            okExecutor { executed = true },
            onBlocked = { step, _ -> blockedStep = step },
        )

        assertEquals(TaskStatus.PAUSED, result.status)
        assertTrue("变更类 shell 必须被拦", blockedStep is Step.Shell)
        assertFalse("被拦的命令不能执行", executed)
    }

    @Test
    fun `read only shell command executes and continues`() = kotlinx.coroutines.runBlocking {
        val reply = """{"action":{"type":"shell","cmd":"dumpsys activity activities"},"risk":null}"""
        val client = Scripted(listOf(reply, doneReply))
        var blocked = false

        val result = runner(client).run(
            TaskSpec("查前台", maxSteps = 3),
            FrameProvider { frame() },
            okExecutor(),
            onBlocked = { _, _ -> blocked = true },
        )

        assertEquals(TaskStatus.DONE, result.status)
        assertFalse("只读命令不应触发拦截", blocked)
    }
}
