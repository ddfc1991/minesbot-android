package io.github.mangi.minesbot

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * 唯一的下行通道：VPS → 手机。
 *
 * 重连策略：指数退避，天花板 30s。
 * 手机网络会抖（切 WiFi/流量、锁屏断流），
 * 必须能自动接回来，不能等用户手动重启。
 */
class ControlTransport(
    private val scope: CoroutineScope,
    private val onConnected: () -> Unit,
    private val onClosed: (String?) -> Unit,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)     // 保活，穿透 NAT
        .build()

    private var socket: WebSocket? = null
    private var loop: Job? = null
    private var attempts = 0

    fun start() {
        loop?.cancel()
        loop = scope.launch {
            while (isActive) {
                var ready = false
                runCatching { ready = openOnce() }
                if (!isActive) break
                attempts++
                onClosed(if (ready) "closed" else "connect failed")
                delay((1L shl (attempts - 1).coerceAtMost(5)).coerceAtMost(30L) * 1000)
            }
        }
    }

    private suspend fun openOnce(): Boolean =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            var ready = false
            val req = Request.Builder()
                .url(Config.wsUrl())
                .header("Authorization", "Bearer ${Config.token()}")
                .build()
            val ws = http.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(w: WebSocket, r: Response) {
                    ready = true
                    attempts = 0
                    Report.sink = { raw -> socket?.send(raw) }
                    onConnected()
                }
                override fun onMessage(w: WebSocket, text: String) = dispatch(text)
                override fun onFailure(w: WebSocket, t: Throwable, r: Response?) {
                    if (cont.isActive) cont.resumeWith(Result.success(ready))
                }
                override fun onClosed(w: WebSocket, code: Int, reason: String) {
                    if (cont.isActive) cont.resumeWith(Result.success(ready))
                }
            })
            socket = ws
            cont.invokeOnCancellation { ws.close(1000, "cancel") }
        }

    /** 收到的每条命令交给无障碍服务执行。 */
    private fun dispatch(raw: String) {
        val svc = PhoneControlService.instance
        if (svc == null) {
            // 服务没起来：明确告诉 VPS，别让它在黑夜里等
            val id = runCatching {
                org.json.JSONObject(raw).optString(Proto.F_ID)
            }.getOrDefault("")
            Report.error(id, "无障碍服务未启用：请在系统设置里开启 MinesBot 的辅助功能")
            return
        }
        svc.handle(raw)
    }

    fun close() {
        loop?.cancel()
        socket?.close(1000, "bye")
        socket = null
        Report.sink = null
    }
}
