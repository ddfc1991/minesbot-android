package io.github.mangi.minesbot

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** 节点树限额。逆向出的精确值，别随手调大。 */
object TreeLimits {
    const val MAX_DEPTH = 24
    const val MAX_NODES = 48
    const val MAX_SCAN = 512
}

/**
 * 节点快照 + 指纹。
 *
 * 指纹 = uniqueId|className|viewId|text|contentDesc
 * VPS 靠它跨快照追踪同一个控件，并统计位移判断滑动是否生效。
 */
data class NodeSnap(
    val fingerprint: String,
    val className: String,
    val viewId: String,
    val text: String,
    val desc: String,
    val bounds: Rect,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val depth: Int,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("fingerprint", fingerprint)
        put("className", className)
        put("viewId", viewId)
        put("text", text)
        put("desc", desc)
        put("bounds", JSONObject().apply {
            put("left", bounds.left); put("top", bounds.top)
            put("right", bounds.right); put("bottom", bounds.bottom)
        })
        put("clickable", clickable)
        put("editable", editable)
        put("scrollable", scrollable)
        put("depth", depth)
    }
}

fun AccessibilityNodeInfo.snap(depth: Int = 0): NodeSnap {
    val fp = buildString {
        append(uniqueId ?: ""); append('|')
        append(className?.toString() ?: ""); append('|')
        append(viewIdResourceName ?: ""); append('|')
        append(text?.toString() ?: ""); append('|')
        append(contentDescription?.toString() ?: "")
    }
    val r = Rect().also { getBoundsInScreen(it) }
    return NodeSnap(
        fingerprint = fp,
        className = className?.toString() ?: "",
        viewId = viewIdResourceName ?: "",
        text = text?.toString() ?: "",
        desc = contentDescription?.toString() ?: "",
        bounds = r,
        clickable = isClickable,
        editable = isEditable,
        scrollable = isScrollable,
        depth = depth,
    )
}

/** 广度优先找第一个满足条件的节点。 */
fun AccessibilityNodeInfo.findFirstOrNull(
    pred: (AccessibilityNodeInfo) -> Boolean
): AccessibilityNodeInfo? {
    val visited = HashSet<AccessibilityNodeInfo>()
    val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
    queue.addLast(this to 0)
    while (queue.isNotEmpty() && visited.size < TreeLimits.MAX_SCAN) {
        val (n, d) = queue.removeFirst()
        if (!visited.add(n)) continue
        if (pred(n)) return n
        if (d < TreeLimits.MAX_DEPTH) {
            for (i in 0 until n.childCount) {
                val c = runCatching { n.getChild(i) }.getOrNull()
                if (c != null) queue.addLast(c to d + 1)
            }
        }
    }
    return null
}

/** 四态之一。 */
data class Action(
    val result: String,
    val code: String = "",
    val msg: String = "",
    val hint: String = "",
) {
    companion object {
        fun ok(action: String, hint: String = "") =
            Action(Proto.OK, action, "", hint)

        fun failed(code: String, msg: String) =
            Action(Proto.FAILED, code, msg)

        /** 请求发出去了但不知道成没成 —— 上层不许当失败重试 */
        fun unknown(msg: String = "动作可能已执行，但系统未在时限内返回结果；请先重新观察，避免重复操作") =
            Action(Proto.OUTCOME_UNKNOWN, "", msg)

        fun precondition(code: String, msg: String, hint: String) =
            Action(Proto.PRECONDITION, code, msg, hint)
    }
}

/**
 * ScreenshotResult → Bitmap。
 *
 * 两个必须：
 *  1. HARDWARE 配置的 Bitmap 不能直接编码，必须 copy 成 ARGB_8888
 *  2. HardwareBuffer 必须 close，否则 native 内存泄漏
 */
fun AccessibilityService.ScreenshotResult.toBitmap(): Bitmap? {
    val buffer = hardwareBuffer
    try {
        val wrapped = Bitmap.wrapHardwareBuffer(buffer, colorSpace) ?: return null
        return if (wrapped.config == Bitmap.Config.HARDWARE) {
            val copy = wrapped.copy(Bitmap.Config.ARGB_8888, false)
            if (!wrapped.isRecycled) wrapped.recycle()
            copy
        } else wrapped
    } finally {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) buffer.close()
    }
}

/** 缩放 + JPEG + base64。直接传原图会把 50GB 流量瞬间烧光。 */
fun Bitmap.toJpegBase64(scale: Float = 0.6f, quality: Int = 60): String {
    val w = (width * scale).toInt().coerceAtLeast(1)
    val h = (height * scale).toInt().coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(this, w, h, true)
    val bos = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, quality, bos)
    scaled.recycle()
    return android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
}
