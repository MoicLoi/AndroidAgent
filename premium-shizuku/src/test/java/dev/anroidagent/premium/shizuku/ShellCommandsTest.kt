package dev.anroidagent.premium.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 命令构造层测试。
 *
 * 重点是**注入防护**：这些字符串会被拼进 `sh -c`，
 * 任何进入命令的参数都必须过白名单，否则一个带 `;` 的包名就是任意命令执行。
 */
class ShellCommandsTest {

    // ---------------- 注入防护 ----------------

    @Test
    fun `accepts normal package names`() {
        listOf(
            "com.example.app",
            "com.tencent.mm",
            "a.b",
            "org.x_y.z1",
        ).forEach { assertTrue("应通过：$it", ShellCommands.isSafePackage(it)) }
    }

    @Test
    fun `rejects packages with shell metacharacters`() {
        listOf(
            "com.x;rm -rf /sdcard",
            "com.x && reboot",
            "com.x|cat /etc/passwd",
            "com.x`id`",
            "com.x\$(id)",
            "com.x\nreboot",
            "com.x y",
            "com",
            "",
            "../data",
        ).forEach { assertFalse("应拒绝：$it", ShellCommands.isSafePackage(it)) }
    }

    @Test
    fun `builders throw on unsafe package`() {
        listOf(
            { ShellCommands.clearAppData("a;b") },
            { ShellCommands.forceStop("a b") },
            { ShellCommands.suspend("\$(id)") },
            { ShellCommands.unsuspend("`id`") },
        ).forEach { block ->
            val threw = runCatching { block() }.isFailure
            assertTrue("非法包名必须抛异常", threw)
        }
    }

    @Test
    fun `permission and appop validators`() {
        assertTrue(ShellCommands.isSafePermission("android.permission.CAMERA"))
        assertFalse(ShellCommands.isSafePermission("android.permission.CAMERA;reboot"))
        assertTrue(ShellCommands.isSafeAppOp("CAMERA"))
        assertFalse(ShellCommands.isSafeAppOp("camera"))
        assertFalse(ShellCommands.isSafeAppOp("CAMERA;x"))
        assertTrue(ShellCommands.isSafeAppOpMode("allow"))
        assertFalse(ShellCommands.isSafeAppOpMode("allow;x"))
    }

    // ---------------- 命令拼接 ----------------

    @Test
    fun `builds expected commands`() {
        assertEquals("pm clear com.a.b", ShellCommands.clearAppData("com.a.b"))
        assertEquals("am force-stop com.a.b", ShellCommands.forceStop("com.a.b"))
        assertEquals("pm suspend com.a.b", ShellCommands.suspend("com.a.b"))
        assertEquals(
            "pm grant com.a.b android.permission.CAMERA",
            ShellCommands.grant("com.a.b", "android.permission.CAMERA"),
        )
        assertEquals(
            "cmd appops set com.a.b CAMERA deny",
            ShellCommands.setAppOp("com.a.b", "CAMERA", "deny"),
        )
        assertEquals(
            "settings put global policy_control immersive.full=*",
            ShellCommands.hideSystemBars(),
        )
        assertEquals(3, ShellCommands.animationOff().size)
    }

    @Test
    fun `quote escapes single quotes`() {
        assertEquals("'abc'", ShellCommands.quote("abc"))
        assertEquals("'a'\\''b'", ShellCommands.quote("a'b"))
    }

    @Test
    fun `restore bars falls back when previous is null`() {
        assertEquals(
            "settings put global policy_control null*",
            ShellCommands.restoreSystemBars(null),
        )
        assertEquals(
            "settings put global policy_control null*",
            ShellCommands.restoreSystemBars("null"),
        )
        assertEquals(
            "settings put global policy_control 'immersive.status=*'",
            ShellCommands.restoreSystemBars("immersive.status=*"),
        )
    }

    @Test
    fun `animation restore defaults to 1_0`() {
        val cmds = ShellCommands.animationRestore(emptyMap())
        assertEquals(3, cmds.size)
        assertTrue(cmds.all { it.endsWith(" 1.0") })
    }

    // ---------------- 无障碍服务列表 ----------------

    @Test
    fun `merge a11y keeps existing services`() {
        val current = "com.other/.Svc:com.another/.Svc"
        val merged = ShellCommands.mergeA11y(current, "dev.anroidagent.app/.AgentService")

        assertTrue(merged.contains("com.other/.Svc"))
        assertTrue(merged.contains("com.another/.Svc"))
        assertTrue(merged.contains("dev.anroidagent.app/.AgentService"))
    }

    @Test
    fun `merge a11y is idempotent`() {
        val c = "dev.anroidagent.app/.AgentService"
        val once = ShellCommands.mergeA11y("", c)
        val twice = ShellCommands.mergeA11y(once, c)
        assertEquals(once, twice)
    }

    @Test
    fun `merge a11y handles empty current`() {
        assertEquals(
            "dev.anroidagent.app/.AgentService",
            ShellCommands.mergeA11y(null, "dev.anroidagent.app/.AgentService"),
        )
    }

    @Test
    fun `remove a11y drops only ours`() {
        val current = "com.other/.Svc:dev.anroidagent.app/.AgentService"
        assertEquals(
            "com.other/.Svc",
            ShellCommands.removeA11y(current, "dev.anroidagent.app/.AgentService"),
        )
        assertEquals("", ShellCommands.removeA11y(null, "dev.anroidagent.app/.AgentService"))
    }

    // ---------------- dumpsys 解析 ----------------

    @Test
    fun `parses resumed activity`() {
        val dump = """
            ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
              Display #0 (activitiyContainers=3)
                Stack #0:
                  taskId=42
                    mResumedActivity: ActivityRecord{2a1b3c4 u0 com.tencent.mm/.ui.LauncherUI t42}
                    mLastPausedActivity: ActivityRecord{9f8e7d6 u0 com.android.launcher/.Launcher t41}
        """.trimIndent()

        assertEquals("com.tencent.mm", ShellCommands.parseTopActivity(dump))
    }

    @Test
    fun `falls back to topResumedActivity`() {
        val dump = """
            topResumedActivity=ActivityRecord{abc u0 com.example.shop/.MainActivity t7}
        """.trimIndent()

        assertEquals("com.example.shop", ShellCommands.parseTopActivity(dump))
    }

    @Test
    fun `returns null when nothing matches`() {
        assertNull(ShellCommands.parseTopActivity("no activities here"))
        assertNull(ShellCommands.parseTopActivity(""))
    }

    @Test
    fun `rejects malformed activity line`() {
        // 没有 "/" 分隔符时不应误判成包名
        assertNull(ShellCommands.parseTopActivity("mResumedActivity: ActivityRecord{abc u0 com.foo t1}"))
    }
}
