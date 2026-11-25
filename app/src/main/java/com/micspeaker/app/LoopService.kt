package com.micspeaker.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/** Keeps the mic running when the screen is off or another app is open. */
class LoopService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Engine.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Live audio", NotificationManager.IMPORTANCE_LOW))

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, LoopService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val route = "${Engine.input?.name ?: "Mic"} → ${Engine.output?.name ?: "Speaker"}"
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Mic to Speaker is live")
            .setContentText(route)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, 1, n, type)

        Engine.onEnded = { stopSelf() }
        Engine.start(applicationContext)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Engine.onEnded = null
        Engine.stop()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "live"
        const val ACTION_STOP = "com.micspeaker.app.STOP"
    }
}
