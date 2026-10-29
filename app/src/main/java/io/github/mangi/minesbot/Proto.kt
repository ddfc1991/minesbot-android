package io.github.mangi.minesbot

import org.json.JSONObject

/**
 * VPS ↔ 手机 协议。
 *
 * 方向约定：
 *   down = VPS → 手机（命令）
 *   up   = 手机 → VPS（事件）
 *
 * 为什么不用 REST 轮询：手机操控是长任务，VPS 要持续下指令，
 * 而且手机要持续回传画面/节点树。轮询延迟高且费流量。
 */
object Proto {

    // ---------------- down: VPS → 手机 ----------------

    const val OBSERVE = "observe"           // 拍一张屏 + 节点树
    const val TAP = "tap"                   // 点坐标
    const val TAP_NODE = "tap_node"         // 点指纹对应的节点
    const val SWIPE = "swipe"               // 滑动
    const val SCROLL = "scroll"             // 滚动（优先 node action）
    const val SET_TEXT = "set_text"         // 输入（绕过 IME）
    const val SYSTEM = "system"             // back/home/recents/notifications
    const val LAUNCH = "launch"             // 打开 app
    const val READ_CLIP = "read_clip"
    const val WRITE_CLIP = "write_clip"

    // ---------------- up: 手机 → VPS ----------------

    const val SCREENSHOT = "screenshot"     // 整屏合成图（base64 JPEG）
    const val NODE_TREE = "node_tree"       // 结构化节点快照
    const val ACTION_ACK = "action_ack"     // 动作结果（四态）
    const val CLIP = "clip"
    const val ERROR = "error"
    const val STATUS = "status"             // 无障碍服务连接状态

    /** 一个动作的四种结局。对应逆向出的 Eta 四态协议。 */
    const val OK = "ok"
    const val FAILED = "failed"
    const val OUTCOME_UNKNOWN = "outcome_unknown"   // ⚠️ 不许当失败重试
    const val PRECONDITION = "precondition"

    // ---------------- 字段名 ----------------

    const val F_TYPE = "type"
    const val F_ID = "id"                   // 命令 id，ack 原样带回
    const val F_X = "x"
    const val F_Y = "y"
    const val F_DURATION = "duration_ms"
    const val F_FINGERPRINT = "fingerprint"
    const val F_TEXT = "text"
    const val F_DIR = "dir"                 // up/down/left/right
    const val F_PACKAGE = "package"
    const val F_ACTION = "action"           // system action
    const val F_JPEG = "jpeg"
    const val F_NODES = "nodes"
    const val F_RESULT = "result"
    const val F_CODE = "code"
    const val F_MSG = "msg"
    const val F_HINT = "hint"

    /** 下发命令的 JSON。id 必填，ack 靠它配对。 */
    fun command(id: String, type: String, build: JSONObject.() -> Unit = {}): String =
        JSONObject().apply {
            put(F_TYPE, type)
            put(F_ID, id)
            build()
        }.toString()

    /** 上报事件。 */
    fun event(type: String, build: JSONObject.() -> Unit = {}): String =
        JSONObject().apply {
            put(F_TYPE, type)
            build()
        }.toString()
}
