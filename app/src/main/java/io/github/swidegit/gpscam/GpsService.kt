package io.github.swidegit.gpscam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/** Foreground service that owns GPS tracking, so the trip continues with the screen off. */
class GpsService : Service() {
    private var scope: CoroutineScope? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        AppSettings.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(NOTIFICATION_ID, notification(getString(R.string.notification_waiting)), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        GpsTracker.start(this)
        if (scope == null) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { s ->
                // One update per change of the displayed text, not per GNSS callback.
                combine(GpsTracker.state, AppSettings.state) { gps, settings -> statusText(gps, settings.unit) }
                    .distinctUntilChanged()
                    .onEach { nm.notify(NOTIFICATION_ID, notification(it)) }
                    .launchIn(s)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        GpsTracker.stop()
        super.onDestroy()
    }

    private fun statusText(gps: GpsState, unit: SpeedUnit): String {
        val fix = gps.fix
        val sats = getString(R.string.notification_sats, gps.usedInFix, gps.satellites.size)
        return if (fix?.speedMs != null) "${Format.speed(fix.speedMs, unit)} ${unit.label} · $sats"
        else "${getString(R.string.notification_waiting)} · $sats"
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, GpsService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.notification_stop), stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "tracking"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "io.github.swidegit.gpscam.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, GpsService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GpsService::class.java))
        }
    }
}
