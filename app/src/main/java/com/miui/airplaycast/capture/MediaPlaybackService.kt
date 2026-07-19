package com.miui.airplaycast.capture

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * 媒体播放服务占位
 *
 * 当前由 [com.miui.airplaycast.airplay.MediaCastSession] 直接通过 HTTP 控制
 * 接收端播放，本地不参与媒体解码，因此本服务暂未实现具体逻辑
 *
 * 未来扩展点: 在本端同步显示播放进度条、媒体元数据等
 */
class MediaPlaybackService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
}
