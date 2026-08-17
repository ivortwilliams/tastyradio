package com.tastyradio.record

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.tastyradio.TastyRadioApp
import com.tastyradio.ui.MainActivity

/**
 * Holds the `MediaProjection` while a recording runs.
 *
 * Separate from the playback service on purpose: that one is a `MediaSessionService` and manages its
 * own foreground notification, whereas this needs the `mediaProjection` foreground type. On API 34+
 * the order matters — the foreground service must be running *before* the projection is obtained
 * from the consent result, or the system refuses it.
 */
class RecorderService : Service() {

    private var projection: MediaProjection? = null

    private val recorder get() = (application as TastyRadioApp).recorder

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent)
            ACTION_STOP -> stopRecording()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifEmpty { "Tasty Radio" }

        if (data == null) {
            stopSelf()
            return
        }

        // Foreground first, projection second. Not optional on API 34+.
        startForegroundCompat()

        val manager = getSystemService(MediaProjectionManager::class.java)
        val projection = manager.getMediaProjection(resultCode, data) ?: run {
            stopSelf()
            return
        }
        this.projection = projection

        // A projection can be revoked from outside the app; if that happens, stop cleanly.
        projection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    recorder.stop()
                    stopSelf()
                }
            },
            Handler(Looper.getMainLooper()),
        )

        recorder.start(projection, title)
    }

    private fun stopRecording() {
        recorder.stop()
        // Let the encoder drain and close the file before the process loses its foreground slot.
        Handler(Looper.getMainLooper()).postDelayed({ stopSelf() }, 1_500)
    }

    private fun startForegroundCompat() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Recording",
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording the mix")
            .setContentText("Tasty Radio is capturing what you're hearing.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(open)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        runCatching { projection?.stop() }
        projection = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 2001

        const val ACTION_START = "com.tastyradio.record.START"
        const val ACTION_STOP = "com.tastyradio.record.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_TITLE = "title"

        fun start(context: Context, resultCode: Int, data: Intent, title: String) {
            val intent = Intent(context, RecorderService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
                putExtra(EXTRA_TITLE, title)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, RecorderService::class.java).apply { action = ACTION_STOP }
            )
        }
    }
}
