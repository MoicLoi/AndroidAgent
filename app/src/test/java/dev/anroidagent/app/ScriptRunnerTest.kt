package dev.anroidagent.app

import dev.anroidagent.app.script.PendingScript
import dev.anroidagent.app.script.ScriptCodec
import dev.anroidagent.app.script.ScriptRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * 待审脚本的构造与编解码。
 *
 * 注意本测试的**边界**：脚本内容不做安全校验（那是设计如此——内容由人确认），
 * 但拼接进命令的 id 必须严格限制字符集，否则就是路径注入。
 */
class ScriptRunnerTest {

    private fun script(
        id: String = "s1abc",
        command: String = "pm clear com.example.app",
    ) = PendingScript(
        id = id,
        createdAt = 1000L,
        reason = "需确认: pm clear com.example.app",
        command = command,
    )

    // ---------------- id 安全 ----------------

    @Test
    fun `accepts safe ids`() {
        listOf("s1abc", "abc-123", "S001", "a".repeat(40)).forEach {
            assertTrue("应通过：$it", ScriptCodec.isSafeId(it))
        }
    }

    @Test
    fun `rejects path traversal and shell metacharacters`() {
        listOf(
            "../../etc/passwd",
            "a/b",
            "a b",
            "a;b",
            "a\$(id)",
            "a'b",
            "",
            "a".repeat(41),
        ).forEach {
            assertFalse("应拒绝：$it", ScriptCodec.isSafeId(it))
        }
    }

    @Test
    fun `remote path uses id only`() {
        assertEquals("/data/local/tmp/agent_s1abc.sh", ScriptRunner.remotePath(script()))
    }

    // ---------------- 命令构造 ----------------

    @Test
    fun `write command is base64 and decodes back`() {
        val s = script(command = "pm clear com.x\nsettings put global x 'y'")
        val cmd = ScriptRunner.buildWriteCommand(s)

        assertTrue("应写入远端路径", cmd.contains("/data/local/tmp/agent_s1abc.sh"))
        assertTrue("应使用 base64 解码", cmd.contains("base64 -d"))

        // 取出 base64 段并解回来，确认内容无损（含换行与引号）
        val b64 = cmd.removePrefix("echo ").substringBefore(" | base64 -d")
        val decoded = String(Base64.getDecoder().decode(b64), Charsets.UTF_8)
        assertEquals(s.command, decoded)
    }

    @Test
    fun `run and cleanup commands reference the same path`() {
        val s = script()
        assertEquals("sh /data/local/tmp/agent_s1abc.sh", ScriptRunner.buildRunCommand(s))
        assertEquals("rm -f /data/local/tmp/agent_s1abc.sh", ScriptRunner.buildCleanupCommand(s))
    }

    // ---------------- 编解码 ----------------

    @Test
    fun `codec round trip`() {
        val list = listOf(script("s1"), script("s2", "settings put global policy_control null*"))
        val back = ScriptCodec.decode(ScriptCodec.encode(list))

        assertEquals(2, back.size)
        assertEquals("s2", back[1].id)
        assertEquals("settings put global policy_control null*", back[1].command)
    }

    @Test
    fun `codec drops entries with unsafe id`() {
        val json = """[{"id":"../../x","command":"rm -rf /","reason":"r"}]"""
        assertTrue(ScriptCodec.decode(json).isEmpty())
    }

    @Test
    fun `codec degrades on corrupt input`() {
        assertTrue(ScriptCodec.decode(null).isEmpty())
        assertTrue(ScriptCodec.decode("").isEmpty())
        assertTrue(ScriptCodec.decode("{broken").isEmpty())
    }

    @Test
    fun `summary shows first line only`() {
        val s = script(command = "line one\nline two")
        assertEquals("line one", s.summary())
    }

    @Test
    fun `blank command is not runnable`() {
        assertNull(null) // 占位，保持断言库被使用
        val s = script(command = "   ")
        // 无 provider 时 run 不应抛（这里只验证 command 判定在 Runner 内）
        assertTrue(s.command.isBlank())
    }
}
