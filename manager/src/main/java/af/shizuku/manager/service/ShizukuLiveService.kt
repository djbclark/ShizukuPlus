package af.shizuku.manager.service

import af.shizuku.manager.utils.LiveActivityNotificationManager
import af.shizuku.manager.utils.ShizukuStateMachine
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import timber.log.Timber

class ShizukuLiveService : Service() {
    companion object {
        private const val TAG = "ShizukuLiveService"
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // startForeground() must be called within 5s of startForegroundService().
        // Use current state for the initial notification.
        val isRunning = ShizukuStateMachine.get() == ShizukuStateMachine.State.RUNNING
        val notif =
            LiveActivityNotificationManager.buildNotification(
                this,
                if (isRunning) "System Bridge Active" else "Monitoring...",
            )
        // startForeground() must be reached first: a startForegroundService() start that stops
        // without it crashes the app on API 26+ (ForegroundServiceDidNotStartInTimeException).
        if (!startForegroundSafely(notif)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // Live activity is only shown inside the Watchdog's notification (1001).
        // With the Watchdog off (or on), there is no standalone notification: remove and stop.
        removeForegroundNotification()
        stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun removeForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    // Returns false when the platform refuses the FGS notification (CannotPostForegroundService-
    // NotificationException, ForegroundServiceStartNotAllowedException, etc.) so callers can
    // stopSelf() gracefully rather than crashing — SHIZUKUPLUS-5P.
    private fun startForegroundSafely(notif: Notification): Boolean =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    LiveActivityNotificationManager.NOTIFICATION_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(LiveActivityNotificationManager.NOTIFICATION_ID, notif)
            }
            true
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "startForeground refused; stopping service")
            false
        }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.cancel(LiveActivityNotificationManager.NOTIFICATION_ID)
        super.onDestroy()
    }
}
