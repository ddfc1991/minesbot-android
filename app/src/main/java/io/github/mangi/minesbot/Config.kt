package io.github.mangi.minesbot

/**
 * 连接配置。
 *
 * 注意：这里**没有模型 API key**。
 * 手机端只持有一个「设备令牌」，代表这台手机被允许接入；
 * 所有模型密钥、VM 账号、额度都在 VPS 侧，不下发到客户端。
 */
object Config {

    /** VPS 地址。改成你的实际域名。 */
    private const val HOST = "wss://bot.example.com"

    private var deviceToken: String = ""

    fun wsUrl(): String = "$HOST/phone"

    fun token(): String = deviceToken

    fun setToken(t: String) { deviceToken = t }
}
