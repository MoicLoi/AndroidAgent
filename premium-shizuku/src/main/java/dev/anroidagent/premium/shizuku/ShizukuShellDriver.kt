package dev.anroidagent.premium.shizuku

import android.util.Base64
import dev.anroidagent.core.model.UiNode
import dev.anroidagent.driver.DegradedException
import dev.anroidagent.driver.Driver

/**
 * 基于 shell 的驱动。
 *
 * 与 `A11yDriver` 的差别（也是追加包的全部价值）：
 * - 注入不需要无障碍服务（`INJECT_EVENTS` 在 shell 域可用）
 * - 截图静默（`screencap`），不像 MediaProjection 每次会话都要用户确认
 * - 能读 `dumpsys`，从而精确判定 `FLAG_SECURE`
 *
 * 做不到的（写清楚，别指望）：
 * - 碰不到 `/dev/uinput`（所以伪造不了 HID 设备，"假蓝牙鼠标"需要 root）
 * - `input text` 只支持 ASCII，中文必须装 ADBKeyboard
 * - 拿不到其它 App 的私有目录
 */
class ShizukuShellDriver(
    private val shell: ShizukuShell,
) : Driver {

    override suspend fun tap(x: Int, y: Int) {
        shell.exec("input tap $x $y")
    }

    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) {
        shell.exec("input swipe $x1 $y1 $x2 $y2 ${durationMs.coerceAtLeast(50)}")
    }

    override suspend fun inputText(text: String) {
        if (text.isEmpty()) return

        if (text.all { it.code < 128 }) {
            shell.exec("input text '${escape(text)}'")
            return
        }

        // 非 ASCII 走 ADBKeyboard；没装就明确失败，而不是静默丢字符
        val b64 = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val out = runCatching {
            shell.exec("am broadcast -a ADB_INPUT_B64 --es msg $b64")
        }.getOrElse {
            throw DegradedException("非 ASCII 输入失败：${it.message}")
        }
        if (!out.contains("Broadcast completed")) {
            throw DegradedException("需要 ADBKeyboard 才能输入中文/emoji")
        }
    }

    override suspend fun launch(pkg: String) {
        val out = shell.exec(
            "monkey -p $pkg -c android.intent.category.LAUNCHER 1",
        )
        if (out.contains("No activities found")) {
            throw DegradedException("no launcher for $pkg")
        }
    }

    override suspend fun back() {
        shell.exec("input keyevent 4")
    }

    override suspend fun shot(): ByteArray? {
        val path = "/data/local/tmp/agent_shot.png"
        runCatching { shell.exec("screencap -p $path") }.getOrNull() ?: return null

        // base64 而不是直接读二进制：部分 ROM 的 shell 流会把 \n 改写，
        // 编码后再传可避免 PNG 被破坏（代价是体积 +33%，仅兜底路径用）
        val encoded = runCatching {
            shell.exec("base64 $path | tr -d '\\n'; rm -f $path")
        }.getOrNull() ?: return null

        return runCatching {
            Base64.decode(encoded.trim(), Base64.DEFAULT)
        }.getOrNull()
    }

    /** 树仍然走无障碍；shell 拿不到窗口内容 */
    override suspend fun tree(): UiNode? = null

    private fun escape(s: String): String = s.replace("'", "'\\''")
}
