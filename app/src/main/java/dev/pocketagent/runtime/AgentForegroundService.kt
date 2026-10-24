package dev.pocketagent.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

class AgentForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Pocket Agent 正在执行")
            .setContentText("保持 Agent、构建和测试任务在后台运行")
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        ensureNotificationChannel(this)
    }

    companion object {
        private const val CHANNEL_ID = "pocket_agent_tasks"
        private const val NOTIFICATION_ID = 4101

        fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Agent 长任务",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "显示正在运行的 Agent、构建和测试任务"
                    setShowBadge(false)
                },
            )
        }
    }
}
