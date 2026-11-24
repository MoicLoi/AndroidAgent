package dev.anroidagent.premium.shizuku

import android.content.pm.PackageManager
import java.io.ByteArrayOutputStream
import java.lang.reflect.Method
import rikka.shizuku.Shizuku

/**
 * shell 通道。
 *
 * 通过 `Shizuku.getBinder()` 背后的服务起 `sh -c`，拿到的是 shell uid (2000) 的
 * 真实进程，因此可用 `input` / `screencap` / `dumpsys` / `pm` 等命令。
 *
 * **实现说明（重要）**：Shizuku 13.x 把 `Shizuku.newProcess` 声明成了
 * `private static`（签名 `newProcess(String[], String[], String)`），
 * 没有公开的等价 API——官方推荐路线是用 `bindUserService` 跑一个
 * app_process 用户服务，但要额外写 AIDL 与 main 入口。
 * 这里选择反射调用那个 private 方法：少一个进程与一层 AIDL，
 * 代价是**依赖私有方法签名**，Shizuku 大版本更新时可能失效，
 * 失效时 [isUsable] 仍为 true 但 [execBytes] 会抛明确异常。
 *
 * 做不到的（写清楚，别指望）：
 * - 碰不到 `/dev/uinput`：AOSP 的 shell 域不允许，所以伪造 HID 设备需 root
 * - `input text` 只支持 ASCII，中文必须装 ADBKeyboard
 * - 读不到其它 App 的私有目录
 */
class ShizukuShell(private val timeoutMs: Long = 8_000L) {

    fun ping(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun granted(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun isUsable(): Boolean = ping() && granted()

    fun exec(cmd: String): String = String(execBytes(cmd), Charsets.UTF_8)

    /**
     * 执行命令并返回原始 stdout。
     *
     * 读取放在独立线程里：不这样做，大输出（如 `dumpsys window`）会在
     * 管道缓冲区填满时与 waitFor 互相死等。
     */
    fun execBytes(cmd: String): ByteArray {
        val process = spawn(arrayOf("sh", "-c", cmd))

        val buffer = ByteArrayOutputStream(8 * 1024)
        val reader = Thread {
            runCatching { process.inputStream.use { it.copyTo(buffer) } }
        }.apply { isDaemon = true; start() }

        reader.join(timeoutMs)
        if (reader.isAlive) {
            runCatching { process.destroy() }
            throw IllegalStateException("shell timeout: ${cmd.take(40)}")
        }

        val code = runCatching { process.waitFor() }.getOrDefault(-1)
        if (code != 0 && buffer.size() == 0) {
            val err = runCatching {
                process.errorStream?.bufferedReader()?.readText().orEmpty()
            }.getOrDefault("")
            throw IllegalStateException("shell exit=$code: ${err.take(120)}")
        }
        return buffer.toByteArray()
    }

    private fun spawn(argv: Array<String>): Process {
        val method = newProcessMethod
            ?: throw IllegalStateException(
                "Shizuku v${runCatching { Shizuku.getVersion() }.getOrDefault(-1)} " +
                    "没有可用的 newProcess，请改用 bindUserService 方案",
            )
        val result = method.invoke(null, argv, null, null)
        return result as? Process
            ?: throw IllegalStateException("newProcess 返回类型异常")
    }

    companion object {

        @Volatile
        private var cached: Method? = null

        @Volatile
        private var resolved = false

        private val newProcessMethod: Method?
            get() {
                if (resolved) return cached
                synchronized(this) {
                    if (!resolved) {
                        cached = runCatching {
                            Shizuku::class.java.getDeclaredMethod(
                                "newProcess",
                                Array<String>::class.java,
                                Array<String>::class.java,
                                String::class.java,
                            ).apply { isAccessible = true }
                        }.getOrNull() as Method?
                        resolved = true
                    }
                }
                return cached
            }
    }
}
