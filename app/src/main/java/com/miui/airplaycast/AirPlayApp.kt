package com.miui.airplaycast

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class AirPlayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            val mirrorChannel = NotificationChannel(
                CHANNEL_SCREEN_CAPTURE,
                "屏幕镜像",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "投屏服务运行中"
                setShowBadge(false)
            }
            nm.createNotificationChannel(mirrorChannel)
        }
    }

    companion object {
        const val CHANNEL_SCREEN_CAPTURE = "screen_capture"
    }
}
