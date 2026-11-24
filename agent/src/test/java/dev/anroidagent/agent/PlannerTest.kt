package dev.anroidagent.agent

import dev.anroidagent.agent.model.ChatMessage
import dev.anroidagent.agent.model.Completion
import dev.anroidagent.agent.model.ModelClient
import dev.anroidagent.agent.model.Planner
import dev.anroidagent.agent.model.Usage
import dev.anroidagent.agent.prompt.SystemPrompt
import dev.anroidagent.core.model.Step
import dev.anroidagent.core.policy.RiskClass
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型客户端闭环测试（不联网）。
 *
 * 用假客户端验证：提示词装配、TUI 边界包裹、解码、risk 透传、修复重试。
 * 真正的网络行为需要真机/真 key，不放在单测里。
 */
class PlannerTest {

    private class FakeClient(private val replies: MutableList<String>) : ModelClient {
        val seen = mutableListOf<List<ChatMessage>>()

        override suspend fun complete(messages: List<ChatMessage>): Completion {
            seen += messages
            val reply = if (replies.size > 1) replies.removeAt(0) else replies.first()
            return Completion(reply, Usage(100, 20))
        }
    }

    private val tui = """
        # 1080x2400 shop s1
        [1]□输入关键词 [2]◻
        [3]推荐 [4]关注 [5]附近
        [6]火锅探店 ♥1.2w
        [7]⋮ 8 more
    """.trimIndent()

    @Test
    fun `closes the loop and decodes a tap`() = runBlocking {
        val client = FakeClient(
            mutableListOf("""{"action":{"type":"tap","index":6,"label":"火锅探店"},"risk":null}"""),
        )
        val planner = Planner(client = client, systemPrompt = SystemPrompt.build())

        val plan = planner.next(emptyList(), tui)

        val step = plan.step
        assertNotNull("应解析出 step", step)
        assertTrue(step is Step.Tap)
        assertEquals(6, (step as Step.Tap).index)

        // 提示词结构：system → 本帧 screen
        val messages = client.seen.single()
        assertEquals("system", messages.first().role)

        val last = messages.last().content
        assertTrue("屏幕内容必须被 <screen> 包裹", last.contains(SystemPrompt.SCREEN_OPEN))
        assertTrue(last.contains(SystemPrompt.SCREEN_CLOSE))
        assertTrue("必须包含 TUI 原文", last.contains("火锅探店"))

        assertEquals(100, plan.usage?.promptTokens)
        assertTrue("本地 token 估算应 > 0", plan.promptTokensLocal > 0)
    }

    @Test
    fun `retries once when the model returns prose`() = runBlocking {
        val client = FakeClient(
            mutableListOf(
                "抱歉，我需要更多信息。",
                """{"action":{"type":"tap","index":3,"label":"推荐"},"risk":null}""",
            ),
        )
        val planner = Planner(client = client)

        val plan = planner.next(emptyList(), tui)

        assertNotNull("修复重试后应成功", plan.step)
        assertEquals("应发生一次重试", 2, client.seen.size)
        assertTrue(
            "重试时应把错误输出回灌",
            client.seen[1].any { it.role == "assistant" && it.content.contains("抱歉") },
        )
    }

    @Test
    fun `risk from model is passed through untouched`() = runBlocking {
        val client = FakeClient(
            mutableListOf(
                """{"action":{"type":"wait","ms":500},"risk":{"class":"CREDENTIAL","evidence":"支付密码","conf":0.9}}""",
            ),
        )
        val planner = Planner(client = client)

        val plan = planner.next(emptyList(), tui)

        assertEquals(RiskClass.CREDENTIAL, plan.risk?.cls)
        assertEquals("支付密码", plan.risk?.evidence)
        assertTrue(plan.step is Step.Wait)
    }

    @Test
    fun `history is trimmed to maxHistory`() = runBlocking {
        val client = FakeClient(mutableListOf("""{"action":{"type":"back"},"risk":null}"""))
        val planner = Planner(client = client, maxHistory = 4)

        val history = (1..10).map { ChatMessage.user("turn $it") }
        planner.next(history, tui)

        // system + 4 条历史 + 本帧
        assertEquals(6, client.seen.single().size)
        assertTrue(client.seen.single().none { it.content == "turn 1" })
        assertTrue(client.seen.single().any { it.content == "turn 10" })
    }
}
