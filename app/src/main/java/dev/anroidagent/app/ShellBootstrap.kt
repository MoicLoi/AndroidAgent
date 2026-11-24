package dev.anroidagent.app

import dev.anroidagent.driver.shell.ShellProvider

/**
 * shell 提供方的反射加载点。
 *
 * 基础包不依赖 `:premium-shizuku`，所以这里**只能**用反射：
 * 类不存在时返回 null，基础包的表现就是"没有 shell 通道"，不会崩。
 *
 * 同一段代码在 pro 包里能直接找到实现——这是两个 APK 共用一份代码、
 * 又在构造上隔离 shell 能力的关键。
 */
object ShellBootstrap {

    private const val IMPL = "dev.anroidagent.premium.shizuku.ShizukuShellProvider"

    val provider: ShellProvider? by lazy {
        runCatching {
            Class.forName(IMPL)
                .getDeclaredConstructor()
                .newInstance() as ShellProvider
        }.getOrNull()
    }

    val available: Boolean get() = provider != null

    val ready: Boolean get() = provider?.isReady == true
}
