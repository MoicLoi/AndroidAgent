package dev.anroidagent.driver

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import dev.anroidagent.core.model.Bounds
import dev.anroidagent.core.model.UiNode
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 无障碍驱动。基础版唯一的注入/感知通道。
 */
class A11yDriver(
    private val service: AccessibilityService,
) : Driver {

    override suspend fun tap(x: Int, y: Int) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        dispatch(path, 0L, 50L, "tap")
    }

    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        dispatch(path, 0L, durationMs.coerceAtLeast(50L), "swipe")
    }

    override suspend fun inputText(text: String) {
        // 优先 ACTION_SET_TEXT（精确，不走输入法）；失败由上层降级为剪贴板粘贴
        val target = service.rootInActiveWindow?.findFocus(
            android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT,
        )
        if (target != null) {
            val args = android.os.Bundle().apply {
                putCharSequence(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text,
                )
            }
            val ok = target.performAction(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,
                args,
            )
            if (ok) return
        }
        throw DegradedException("set-text unsupported")
    }

    override suspend fun launch(pkg: String) {
        val intent = service.packageManager.getLaunchIntentForPackage(pkg)
            ?: throw DegradedException("no launcher for $pkg")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        service.startActivity(intent)
    }

    override suspend fun back() {
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    override suspend fun shot(): ByteArray? = null // 基础版无静默截图，走 MediaProjection

    override suspend fun tree(): UiNode? =
        service.rootInActiveWindow?.let { TreeReader.read(it) }

    private suspend fun dispatch(path: Path, startMs: Long, durationMs: Long, what: String) {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, startMs, durationMs))
            .build()

        suspendCancellableCoroutine { cont ->
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) {
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onCancelled(d: GestureDescription?) {
                    if (cont.isActive) {
                        cont.resumeWithException(DegradedException("$what cancelled"))
                    }
                }
            }
            val accepted = runCatching { service.dispatchGesture(gesture, callback, null) }
                .getOrDefault(false)
            if (!accepted && cont.isActive) {
                cont.resumeWithException(DegradedException("$what rejected"))
            }
        }
    }
}

/**
 * 无障碍树 → [UiNode]。
 *
 * 只保留策略与 TUI 需要的字段；[UiNode.hint] 单独取 `hintText`，
 * 因为凭证类输入框的真实用途往往在 hint 里而不是 text 里。
 */
object TreeReader {

    private const val MAX_NODES = 2000

    fun read(root: android.view.accessibility.AccessibilityNodeInfo): UiNode {
        var budget = MAX_NODES
        return convert(root, 0) { budget-- } ?: emptyNode()
    }

    private fun convert(
        node: android.view.accessibility.AccessibilityNodeInfo,
        depth: Int,
        tick: () -> Int,
    ): UiNode? {
        if (tick() <= 0) return null

        val rect = Rect()
        node.getBoundsInScreen(rect)

        val children = ArrayList<UiNode>(node.childCount)
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            convert(c, depth + 1, tick)?.let(children::add)
        }

        return UiNode(
            id = "${node.windowId}:${node.hashCode()}",
            label = node.text?.toString().orEmpty(),
            hint = node.hintText?.toString().orEmpty(),
            desc = node.contentDescription?.toString().orEmpty(),
            bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom),
            clickable = node.isClickable,
            editable = node.isEditable,
            scrollable = node.isScrollable,
            enabled = node.isEnabled,
            selected = node.isSelected,
            checked = node.isChecked,
            className = node.className?.toString().orEmpty(),
            depth = depth,
            children = children,
        )
    }

    private fun emptyNode() = UiNode(
        id = "empty",
        bounds = Bounds(0, 0, 0, 0),
        className = "",
    )
}
