package io.github.mangi.minesbot

import org.json.JSONObject

/**
 * 事件上报总线。
 *
 * 无障碍服务是「被系统拉起」的，不知道 UI 在不在。
 * 所以事件走一个全局可观察的流，由 Transport 发出去；
 * UI 只是订阅同一个流显示状态。
 */
object Report {

    @Volatile var sink: ((String) -> Unit)? = null

    @Volatile var serviceAlive: Boolean = false
        private set

    fun status(s: String) {
        serviceAlive = (s == "connected")
        emit(Proto.event(Proto.STATUS) {
            put("service", s)
            put("alive", serviceAlive)
        })
    }

    fun ack(
        id: String,
        result: String,
        code: String = "",
        msg: String = "",
        hint: String = "",
    ) {
        emit(Proto.event(Proto.ACTION_ACK) {
            put(Proto.F_ID, id)
            put(Proto.F_RESULT, result)
            put(Proto.F_CODE, code)
            put(Proto.F_MSG, msg)
            put(Proto.F_HINT, hint)
        })
    }

    fun error(id: String, msg: String) {
        emit(Proto.event(Proto.ERROR) {
            put(Proto.F_ID, id)
            put(Proto.F_MSG, msg)
        })
    }

    /** 通用：任意已构造好的 event JSON。 */
    fun emit(raw: String) {
        sink?.invoke(raw)
        sinkHook?.invoke(raw)
    }

    /** UI 侧的观察钩子。与上行 sink 分开：一个发出去，一个显示。 */
    @Volatile var sinkHook: ((String) -> Unit)? = null

    fun event(type: String, build: JSONObject.() -> Unit = {}) {
        emit(Proto.event(type, build))
    }
}
