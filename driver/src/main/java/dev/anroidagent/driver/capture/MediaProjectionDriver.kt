package dev.anroidagent.driver.capture

import dev.anroidagent.core.model.UiNode
import dev.anroidagent.driver.DegradedException
import dev.anroidagent.driver.Driver

/**
 * 只提供截图通道的 Driver，动作全部委托给 [actions]（通常是 A11yDriver）。
 *
 * 单独成类而不是塞进 A11yDriver，是为了让 `DriverRouter` 能按能力自由组合：
 * 基础版 = A11y 动作 + MediaProjection 截图；追加包 = shell 动作 + shell 截图。
 */
class MediaProjectionDriver(
    private val actions: Driver? = null,
) : Driver {

    override suspend fun shot(): ByteArray? = ScreenCapture.latestJpeg()

    override suspend fun tap(x: Int, y: Int) =
        actions?.tap(x, y) ?: throw DegradedException("no action channel")

    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) =
        actions?.swipe(x1, y1, x2, y2, durationMs) ?: throw DegradedException("no action channel")

    override suspend fun inputText(text: String) =
        actions?.inputText(text) ?: throw DegradedException("no action channel")

    override suspend fun launch(pkg: String) =
        actions?.launch(pkg) ?: throw DegradedException("no action channel")

    override suspend fun back() {
        actions?.back() ?: throw DegradedException("no action channel")
    }

    override suspend fun tree(): UiNode? = actions?.tree()
}
