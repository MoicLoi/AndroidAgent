package dev.anroidagent.driver.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * 前台投屏服务。
 *
 * 顺序不可调换（Android 14+ 强制）：
 * 1. `startForeground`（且 type = mediaProjection）必须先于 `getMediaProjection`
 * 2. `registerCallback` 必须先于 `createVirtualDisplay`
 * 否则系统直接抛 SecurityException。
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCapture"
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 41

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        fun start(ctx: Context, resultCode: Int, data: Intent) {
            val i = Intent(ctx, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ScreenCaptureService::class.java))
        }
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val stopCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            teardown()
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 1. 先进入前台，并声明 mediaProjection 类型
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )

        if (projection != null) return START_NOT_STICKY

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultData == null) {
            Log.w(TAG, "missing projection data")
            stopSelf()
            return START_NOT_STICKY
        }

        val manager = getSystemService(MediaProjectionManager::class.java)
        val p = runCatching { manager.getMediaProjection(resultCode, resultData) }.getOrNull()
        if (p == null) {
            Log.w(TAG, "getMediaProjection returned null")
            stopSelf()
            return START_NOT_STICKY
        }

        // 2. 回调必须先注册
        p.registerCallback(stopCallback, Handler(Looper.getMainLooper()))
        projection = p

        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels

        val reader = ImageReader.newInstance(w, h, android.graphics.PixelFormat.RGBA_8888, 2)
        imageReader = reader

        virtualDisplay = p.createVirtualDisplay(
            "agent-capture",
            w,
            h,
            dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null,
        )

        ScreenCapture.bind(reader, w, h)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        ScreenCapture.release()
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { projection?.unregisterCallback(stopCallback) }
        runCatching { projection?.stop() }
        projection = null
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "屏幕采集", NotificationManager.IMPORTANCE_LOW),
                )
            }
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("屏幕采集运行中")
            .setContentText("用于本地推理；随时可在系统通知里停止")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }
}
