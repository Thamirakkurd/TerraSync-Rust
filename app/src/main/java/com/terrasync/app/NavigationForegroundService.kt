package com.terrasync.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Lightweight location foreground service that keeps turn-by-turn GPS updates and
 * TTS voice guidance alive when the screen is off or a field call interrupts MainActivity.
 */
class NavigationForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "terrasync_navigation_live"
        private const val NOTIF_ID = 39202
        private const val ACTION_START_OR_UPDATE = "com.terrasync.app.action.NAV_START_OR_UPDATE"
        private const val ACTION_STOP_NAV = "com.terrasync.app.action.NAV_STOP"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_BODY = "extra_body"

        @Volatile var onStopRequested: (() -> Unit)? = null
        @Volatile private var running = false
        @Volatile private var lastTitle = ""
        @Volatile private var lastBody = ""
        @Volatile private var lastNotifyMs = 0L

        private fun hasLocationPerm(ctx: Context): Boolean {
            val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
            val coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
            return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
        }

        fun start(context: Context, title: String, body: String) {
            if ("robolectric".equals(Build.FINGERPRINT, ignoreCase = true)) return
            val appCtx = context.applicationContext
            if (!hasLocationPerm(appCtx)) return
            lastTitle = title
            lastBody = body
            lastNotifyMs = SystemClock.elapsedRealtime()
            val intent = Intent(appCtx, NavigationForegroundService::class.java).apply {
                action = ACTION_START_OR_UPDATE
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_BODY, body)
            }
            runCatching { ContextCompat.startForegroundService(appCtx, intent) }
        }

        fun updateProgress(context: Context, title: String, body: String, force: Boolean = false) {
            if (!running) return
            val now = SystemClock.elapsedRealtime()
            val changed = title != lastTitle
            if (!force && !changed && now - lastNotifyMs < 1500L) return
            lastTitle = title
            lastBody = body
            lastNotifyMs = now
            val appCtx = context.applicationContext
            runCatching {
                val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
                nm.notify(NOTIF_ID, buildNotificationStatic(appCtx, title, body))
            }
        }

        fun stop(context: Context) {
            running = false
            lastTitle = ""
            lastBody = ""
            if ("robolectric".equals(Build.FINGERPRINT, ignoreCase = true)) return
            runCatching {
                context.applicationContext.stopService(
                    Intent(context.applicationContext, NavigationForegroundService::class.java)
                )
            }
        }

        private fun buildNotificationStatic(ctx: Context, title: String, body: String): Notification {
            val openIntent = PendingIntent.getActivity(
                ctx,
                21,
                Intent(ctx, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val stopIntent = PendingIntent.getService(
                ctx,
                22,
                Intent(ctx, NavigationForegroundService::class.java).apply {
                    action = ACTION_STOP_NAV
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_nav_arrow)
                .setContentTitle(title.ifBlank { "TerraSync Navigation" })
                .setContentText(body.ifBlank { "Turn-by-turn guidance active" })
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(openIntent)
                .addAction(0, "Stop", stopIntent)
                .build()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        if (intent?.action == ACTION_STOP_NAV) {
            running = false
            AppExecutors.postMain { onStopRequested?.invoke() }
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!hasLocationPerm(this)) {
            running = false
            stopSelf()
            return START_NOT_STICKY
        }
        val title = intent?.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() }
            ?: lastTitle.ifBlank { "TerraSync Navigation" }
        val body = intent?.getStringExtra(EXTRA_BODY)?.takeIf { it.isNotBlank() }
            ?: lastBody.ifBlank { "Turn-by-turn guidance active" }
        val notif = buildNotificationStatic(this, title, body)
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        val started = runCatching {
            ServiceCompat.startForeground(this, NOTIF_ID, notif, serviceType)
            true
        }.getOrDefault(false)
        if (!started) {
            running = false
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "Turn-by-Turn Navigation",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps live GPS and voice guidance active during navigation"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        nm.createNotificationChannel(ch)
    }
}
