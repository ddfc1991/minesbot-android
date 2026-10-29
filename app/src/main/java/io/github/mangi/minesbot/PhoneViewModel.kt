package io.github.mangi.minesbot

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogKind { GOOD, BAD, PLAIN }
data class LogEntry(val text: String, val kind: LogKind, val time: String)

data class PhoneUiState(
    val serviceAlive: Boolean = false,
    val connected: Boolean = false,
    val lastError: String? = null,
    val recent: List<LogEntry> = emptyList(),
)

class PhoneViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(PhoneUiState())
    val state: StateFlow<PhoneUiState> = _state.asStateFlow()

    private val transport = ControlTransport(
        scope = viewModelScope,
        onConnected = {
            _state.update { it.copy(connected = true, lastError = null) }
            log("已连上 VPS", LogKind.GOOD)
        },
        onClosed = { reason ->
            _state.update { it.copy(connected = false, lastError = reason) }
        },
    )

    init {
        // 无障碍服务的原始事件 → UI 日志
        Report.sinkHook = { raw -> render(raw) }
        _state.update { it.copy(serviceAlive = Report.serviceAlive) }
        transport.start()
    }

    fun reconnect() { transport.start() }

    /** 手动触发一次观察，用来验证整条链路是通的。 */
    fun requestObserve() {
        val id = System.nanoTime().toString()
        val svc = PhoneControlService.instance
        if (svc == null) {
            log("无障碍服务未启用", LogKind.BAD)
            return
        }
        svc.handle(Proto.command(id, Proto.OBSERVE))
        log("已请求截图 + 节点树", LogKind.PLAIN)
    }

    /** 把服务回传的 JSON 翻译成人话。 */
    private fun render(raw: String) {
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return
        when (o.optString(Proto.F_TYPE)) {
            Proto.STATUS -> {
                val alive = o.optBoolean("alive")
                _state.update { it.copy(serviceAlive = alive) }
                log("无障碍服务 ${o.optString("service")}", if (alive) LogKind.GOOD else LogKind.BAD)
            }
            Proto.SCREENSHOT -> {
                val kb = o.optString(Proto.F_JPEG).length * 3 / 4 / 1024
                log("截图已回传（约 ${kb}KB）", LogKind.GOOD)
            }
            Proto.NODE_TREE -> {
                val n = o.optJSONArray(Proto.F_NODES)?.length() ?: 0
                log("节点树 ${n} 个", LogKind.GOOD)
            }
            Proto.ACTION_ACK -> {
                val result = o.optString(Proto.F_RESULT)
                val kind = when (result) {
                    Proto.OK -> LogKind.GOOD
                    Proto.OUTCOME_UNKNOWN -> LogKind.BAD   // 醒目提示：这个不能重试
                    else -> LogKind.PLAIN
                }
                val label = when (result) {
                    Proto.OK -> "完成"
                    Proto.FAILED -> "失败"
                    Proto.OUTCOME_UNKNOWN -> "结果未知（勿重试）"
                    Proto.PRECONDITION -> "前置不满足"
                    else -> result
                }
                log("$label ${o.optString(Proto.F_CODE)} ${o.optString(Proto.F_MSG)}", kind)
            }
            Proto.ERROR -> log("错误：${o.optString(Proto.F_MSG)}", LogKind.BAD)
        }
    }

    private fun log(text: String, kind: LogKind) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _state.update {
            it.copy(recent = (it.recent + LogEntry(text, kind, t)).takeLast(50))
        }
    }

    override fun onCleared() {
        Report.sinkHook = null
        super.onCleared()
    }
}
