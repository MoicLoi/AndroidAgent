package dev.anroidagent.driver

import dev.anroidagent.core.model.UiNode

/**
 * 只记录不执行的驱动装饰器。
 *
 * 用于研究模式：把"本来会做什么"完整记录下来，但**不碰设备**。
 * 读操作（`tree` / `shot`）照常透传——不读就无从记录。
 *
 * 放在 [GuardedDriver] 内层，因此红线判定（目标白名单、fail-closed）
 * 仍然照常执行：研究模式不改策略，只改"是否真的动手"。
 */
class DryRunDriver(
    private val inner: Driver,
    private val record: (what: String) -> Unit = {},
) : Driver {

    private fun dry(what: String) {
        InputChannelProbe.note(InputChannelProbe.DRY)
        runCatching { record(what) }
    }

    override suspend fun tap(x: Int, y: Int) = dry("tap($x, $y)")

    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) =
        dry("swipe($x1,$y1 -> $x2,$y2, ${durationMs}ms)")

    override suspend fun inputText(text: String) = dry("type(\"${text.take(40)}\")")

    override suspend fun launch(pkg: String) = dry("launch($pkg)")

    override suspend fun back() = dry("back()")

    /** 读操作透传 */
    override suspend fun shot(): ByteArray? = inner.shot()

    override suspend fun tree(): UiNode? = inner.tree()
}
