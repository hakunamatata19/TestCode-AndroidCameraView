package com.chen.androidcameraview

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.IBinder
import android.util.Log

/**
 * 录屏前台服务
 *
 * Android 14（API 34）强制要求：
 *   getMediaProjection() 必须在 foregroundServiceType=mediaProjection 的前台服务
 *   已调用 startForeground() 之后才能调用。
 *
 * 正确做法：把 resultCode + data 传入服务，由服务在 startForeground() 完成后
 *   调用 getMediaProjection()，再通过 Binder 把 MediaProjection 交给 Activity。
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "screen_capture_channel"
        private const val NOTIFICATION_ID = 1001
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_PROJECTION_DATA = "extra_projection_data"
    }

    // ---- Binder，供 Activity 拿到 MediaProjection ---------------------------
    inner class LocalBinder : Binder() {
        val service: ScreenCaptureService get() = this@ScreenCaptureService
    }

    private val binder = LocalBinder()
    private var mediaProjection: MediaProjection? = null

    /** Activity 通过 ServiceConnection 拿到服务后调用此方法获取 MediaProjection */
    fun getProjection(): MediaProjection? = mediaProjection

    // =========================================================================
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        Log.d(TAG, "startForeground 已完成")
    }

    /**
     * Activity startForegroundService() 后，系统调用此方法。
     * 此时 startForeground() 已调用，可以安全调用 getMediaProjection()。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)

        if (resultCode != 0 && data != null) {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mgr.getMediaProjection(resultCode, data)
            Log.d(TAG, "MediaProjection 获取成功: $mediaProjection")
        } else {
            Log.w(TAG, "onStartCommand: 缺少 resultCode 或 data")
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        mediaProjection?.stop()
        mediaProjection = null
        Log.d(TAG, "服务已销毁")
    }

    // =========================================================================
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "录屏服务", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "视频录制中" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("录制中")
            .setContentText("正在录制视频，点击返回应用")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
}
