package io.github.mangi.minesbot

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

/**
 * App 是纯 UI 壳。
 *
 * 设计约束（用户明确要求）：
 *   - 逻辑全部跑在云端，App 只负责渲染和交互
 *   - 不在本地跑 agent 循环、不持有 API key、不落业务状态
 *   - 只有一份「连接配置」存在 DataStore 里
 *
 * 由此带来的好处：
 *   - APK 体积极小，权限只有网络 + 通知
 *   - 换手机、重装、多设备都能接同一个后端
 *   - 后端升级不需要发版
 */
class MinesBotApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(
            CHANNEL_TASK,
            getString(R.string.channel_task),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = getString(R.string.channel_task_desc)
        }
        mgr.createNotificationChannel(ch)
    }

    companion object {
        const val CHANNEL_TASK = "minesbot_task"
    }
}
