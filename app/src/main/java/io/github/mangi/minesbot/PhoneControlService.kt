package io.github.mangi.minesbot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 手机操控执行层。
 *
 * 移植自 io.github.mangi.eta 的 AgentAccessibilityService（1692 行逆向）。
 * 保留了三个最关键的设计，别改：
 *
 *  1) 四态返回：ok / failed / outcome_unknown / precondition
 *     —— outcome_unknown 表示"请求发出去了但不知道成没成"，
 *        绝不能被上层当失败重试，否则会重复下单、重复发送。
 *
 *  2) 限额：节点深度 ≤ 24、节点数 ≤ 48、搜索 ≤ 512
 *     —— 不是拍脑袋，是为了不让节点树撑爆 VPS 侧的 LLM 上下文。
 *
 *  3) 输入绕过 IME：ACTION_SET_TEXT + 回读校验
 *     —— 比坐标点输入框再敲键盘快一个数量级，也不受输入法状态干扰。
 *
 * 没有用 MediaProjection：那个每次启动都弹授权框，
 * 不适合常驻的远程操控场景。
 */
class PhoneControlService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        instance = this
        Report.status("connected")
    }

    override fun onDestroy() {
        instance = null
        Report.status("disconnected")
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 事件驱动留给后续：VPS 想监听窗口变化时用
    }

    // ================================================================
    // 派发入口
    // ================================================================

    /**
     * 处理一条 VPS 命令，返回结果 JSON（通过 Report 回传）。
     * 同步执行，由 Worker 线程调用。
     */
    fun handle(cmd: String) {
        val id = jsonOf(cmd) { it.optString(Proto.F_ID) } ?: ""
        try {
            val o = org.json.JSONObject(cmd)
            when (o.optString(Proto.F_TYPE)) {
                Proto.OBSERVE -> doObserve(id)
                Proto.TAP -> doTap(id, o.optDouble(Proto.F_X).toFloat(),
                    o.optDouble(Proto.F_Y).toFloat(),
                    o.optLong(Proto.F_DURATION, 80L))
                Proto.SWIPE -> doSwipe(id, o)
                Proto.SET_TEXT -> doSetText(id, o.optString(Proto.F_TEXT))
                Proto.SYSTEM -> doSystem(id, o.optString(Proto.F_ACTION))
                Proto.LAUNCH -> doLaunch(id, o.optString(Proto.F_PACKAGE))
                Proto.SCROLL -> doScroll(id, o.optString(Proto.F_DIR))
                else -> Report.ack(id, Proto.FAILED, "UNKNOWN_CMD", "未知命令 ${o.optString(Proto.F_TYPE)}")
            }
        } catch (t: Throwable) {
            Report.ack(id, Proto.FAILED, "EXCEPTION", t.message ?: t::class.java.simpleName)
        }
    }

    // ================================================================
    // 观察：逐窗口截图 + 节点树
    // ================================================================

    private fun doObserve(id: String) {
        // 1) 节点树（结构化，优先于图像）
        val root = rootInActiveWindow
        val nodes = if (root != null) collect(root) else emptyList()
        Report.event(Proto.NODE_TREE) {
            put(Proto.F_ID, id)
            put(Proto.F_NODES, org.json.JSONArray().also { a ->
                nodes.forEach { a.put(it.toJson()) }
            })
        }

        // 2) 整屏截图
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            val done = CountDownLatch(1)
            val out = AtomicReference<Bitmap?>(null)
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, executor, object : TakeScreenshotCallback {
                override fun onSuccess(r: ScreenshotResult) {
                    out.set(r.toBitmap())
                    done.countDown()
                }
                override fun onFailure(code: Int) { done.countDown() }
            })
            done.await(3000, TimeUnit.MILLISECONDS)
            val bmp = out.get()
            if (bmp != null) {
                val jpeg = bmp.toJpegBase64(scale = 0.6f, quality = 60)
                bmp.recycle()
                Report.event(Proto.SCREENSHOT) {
                    put(Proto.F_ID, id)
                    put(Proto.F_JPEG, jpeg)
                }
            }
        }
        Report.ack(id, Proto.OK, code = "", msg = "", hint = "observed ${nodes.size} nodes")
    }

    // ================================================================
    // 动作
    // ================================================================

    private fun doTap(id: String, x: Float, y: Float, dur: Long) {
        val path = Path().apply { moveTo(x, y); lineTo(x, y) }
        val r = gesture(path, dur.coerceIn(1, 3000), "GESTURE_TAP")
        finish(id, r)
    }

    private fun doSwipe(id: String, o: org.json.JSONObject) {
        val x1 = o.optDouble(Proto.F_X).toFloat()
        val y1 = o.optDouble(Proto.F_Y).toFloat()
        val x2 = o.optDouble("x2", x1.toDouble()).toFloat()
        val y2 = o.optDouble("y2", y1.toDouble()).toFloat()
        val dur = o.optLong(Proto.F_DURATION, 300L)
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        finish(id, gesture(path, dur.coerceIn(1, 3000), "GESTURE_SWIPE"))
    }

    private fun doSetText(id: String, text: String) {
        val node = findEditable() ?: run {
            Report.ack(id, Proto.PRECONDITION, "NO_FOCUSED_EDITABLE",
                "没有获得输入焦点的可编辑节点", "先点一下输入框再输入")
            return
        }
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
            )
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            Report.ack(id, Proto.FAILED, "ACTION_FAILED", "输入节点拒绝文本修改动作")
            return
        }
        // 回读校验；密码框跳过（读不到，且触发安全限制）
        val refreshed = runCatching { node.refresh() }.getOrDefault(false)
        if (node.isPassword) {
            Report.ack(id, Proto.OK, code = "ACTION_SET_TEXT", hint = "password field, not verified")
            return
        }
        val readback = if (refreshed) node.text?.toString() else null
        if (refreshed && readback == text) {
            Report.ack(id, Proto.OK, code = "ACTION_SET_TEXT_AND_SELECTION", hint = "verified")
        } else {
            Report.ack(id, Proto.OUTCOME_UNKNOWN, code = "",
                msg = "动作可能已执行，但未能回读确认；请先重新观察，避免重复操作")
        }
    }

    private fun doSystem(id: String, action: String) {
        val g = when (action.uppercase()) {
            "BACK" -> GLOBAL_ACTION_BACK
            "HOME" -> GLOBAL_ACTION_HOME
            "RECENTS" -> GLOBAL_ACTION_RECENTS
            "NOTIFICATIONS" -> GLOBAL_ACTION_NOTIFICATIONS
            "QUICK_SETTINGS" -> GLOBAL_ACTION_QUICK_SETTINGS
            else -> {
                Report.ack(id, Proto.FAILED, "INVALID_ARGUMENT", "不支持的系统动作 $action")
                return
            }
        }
        val ok = performGlobalAction(g)
        if (ok) Report.ack(id, Proto.OK, code = "GLOBAL_$action")
        else Report.ack(id, Proto.FAILED, "GLOBAL_ACTION_FAILED", "系统拒绝了 $action")
    }

    private fun doLaunch(id: String, pkg: String) {
        if (pkg.isBlank()) {
            Report.ack(id, Proto.FAILED, "INVALID_ARGUMENT", "缺少包名")
            return
        }
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            Report.ack(id, Proto.FAILED, "APP_NOT_FOUND", "找不到 $pkg 的启动入口")
            return
        }
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        Report.ack(id, Proto.OK, code = "LAUNCH")
    }

    private fun doScroll(id: String, dir: String) {
        val node = findScrollable(dir)
        if (node == null) {
            Report.ack(id, Proto.PRECONDITION, "NO_SCROLLABLE",
                "没找到支持 $dir 滚动的节点", "换用手势滑动")
            return
        }
        val action = when (dir.lowercase()) {
            "up", "down" -> if (dir.lowercase() == "up")
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
            else AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
            "left" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
            else -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
        }
        val ok = node.performAction(action)
        if (ok) Report.ack(id, Proto.OK, code = "SCROLL_${dir.uppercase()}")
        else Report.ack(id, Proto.OUTCOME_UNKNOWN, msg = "滚动动作未被确认；请重新观察")
    }

    // ================================================================
    // 节点树
    // ================================================================

    private fun collect(root: AccessibilityNodeInfo): List<NodeSnap> {
        val out = ArrayList<NodeSnap>(48)
        val visited = HashSet<AccessibilityNodeInfo>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.addLast(root to 0)

        while (queue.isNotEmpty()) {
            if (out.size >= TreeLimits.MAX_NODES) break
            val (node, depth) = queue.removeFirst()
            if (depth > TreeLimits.MAX_DEPTH) break
            if (!visited.add(node)) continue           // 防环
            if (!node.isVisibleToUser) {
                visited.remove(node)                    // 不可见的不占名额
                continue
            }
            out.add(node.snap(depth))
            if (depth < TreeLimits.MAX_DEPTH) {
                for (i in 0 until node.childCount) {
                    val c = runCatching { node.getChild(i) }.getOrNull()
                    if (c != null) queue.addLast(c to depth + 1)
                }
            }
        }
        return out
    }

    private fun findEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        // 先看当前焦点
        val focused = runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        if (focused != null && focused.isEditable && focused.isEnabled && focused.isVisibleToUser) {
            return focused
        }
        // 再广度优先找第一个可编辑的
        val visited = HashSet<AccessibilityNodeInfo>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.addLast(root to 0)
        while (queue.isNotEmpty() && visited.size < TreeLimits.MAX_SCAN) {
            val (node, depth) = queue.removeFirst()
            if (!visited.add(node)) continue
            if (node.isEditable && node.isEnabled && node.isVisibleToUser) return node
            if (depth < TreeLimits.MAX_DEPTH) {
                for (i in 0 until node.childCount) {
                    val c = runCatching { node.getChild(i) }.getOrNull()
                    if (c != null) queue.addLast(c to depth + 1)
                }
            }
        }
        return null
    }

    private fun findScrollable(dir: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val vertical = dir.lowercase() in setOf("up", "down")
        return root.findFirstOrNull { n ->
            n.isScrollable || n.actionList.any {
                it.id in if (vertical) SCROLL_VERTICAL else SCROLL_HORIZONTAL
            }
        }
    }

    // ================================================================
    // 手势：必须离开主线程等待，但要 post 到主线程派发
    // ================================================================

    private fun gesture(path: Path, dur: Long, tag: String): Action {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return Action.failed("GESTURE_ON_MAIN_THREAD", "不能在无障碍主线程同步等待手势")
        }
        val latch = CountDownLatch(1)
        val outcome = AtomicReference(GestureOutcome.PENDING)
        val posted = main.post {
            val g = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, dur))
                .build()
            dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) {
                    outcome.set(GestureOutcome.COMPLETED); latch.countDown()
                }
                override fun onCancelled(d: GestureDescription?) {
                    outcome.set(GestureOutcome.CANCELLED); latch.countDown()
                }
            }, null)
        }
        if (!posted) return Action.failed("GESTURE_NOT_DISPATCHED", "主线程拒绝手势任务")

        val done = latch.await(dur + 1500, TimeUnit.MILLISECONDS)
        return when {
            !done -> Action.unknown()          // 关键：不是 failed
            outcome.get() == GestureOutcome.COMPLETED -> Action.ok(tag)
            else -> Action.unknown()
        }
    }

    private fun finish(id: String, a: Action) {
        Report.ack(id, a.result, a.code, a.msg, a.hint)
    }

    private fun <T> jsonOf(s: String, f: (org.json.JSONObject) -> T): T? = null

    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()

    companion object {
        @Volatile var instance: PhoneControlService? = null

        private val SCROLL_VERTICAL = setOf(
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id,
        )
        private val SCROLL_HORIZONTAL = setOf(
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id,
        )

        private enum class GestureOutcome { PENDING, COMPLETED, CANCELLED }
    }
}
