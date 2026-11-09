package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity

/**
 * Gives an active Bluetooth voice session foreground priority while the user
 * switches to a music player, video app, or turns off the display.
 *
 * Bluetooth sockets and AudioTrack remain owned by the existing ViewModel.
 * This service does not request audio focus or alter other apps' playback.
 */
class BluetoothSessionService : Service() {
    companion object {
        private const val TAG = "BluetoothSession"
        private const val CHANNEL_ID = "bluetooth_voice_session"
        private const val NOTIFICATION_ID = 102

        fun start(context: Context) {
            try {
                val intent = Intent(context, BluetoothSessionService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (error: Exception) {
                Log.w(TAG, "Could not start foreground Bluetooth audio session", error)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, BluetoothSessionService::class.java))
            } catch (error: Exception) {
                Log.w(TAG, "Could not stop Bluetooth audio session", error)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Bluetooth voice session",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Bluetooth voice audio available while using other apps"
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val launchIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("BlueVoice session active")
            .setContentText("Voice audio can play alongside music and videos")
            .setContentIntent(launchIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )
                else -> startForeground(NOTIFICATION_ID, notification)
            }
        } catch (error: Exception) {
            Log.e(TAG, "Could not enter foreground; session cannot be kept alive", error)
            stopSelf(startId)
        }
        // A stopped connection must not be silently re-created after process death.
        return START_NOT_STICKY
    }
}
