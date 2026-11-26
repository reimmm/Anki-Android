// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.kiosk

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ichi2.anki.DeckPicker
import com.ichi2.anki.NotificationChannel
import com.ichi2.anki.R
import com.ichi2.anki.setupNotificationChannels
import timber.log.Timber

/**
 * Foreground countdown service running while a Kiosk reward break is active.
 * Automatically brings AnkiDroid back to the foreground and re-locks `LockTask` when time expires.
 */
class KioskBreakTimerService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastDisplayedDuration: String? = null

    private val tickRunnable =
        object : Runnable {
            override fun run() {
                if (!KioskManager.isKioskLocked(this@KioskBreakTimerService)) {
                    stopSelf()
                    return
                }
                val remaining = KioskManager.getRemainingBreakMillis(this@KioskBreakTimerService)
                if (remaining <= 0L) {
                    KioskManager.onBreakExpired(this@KioskBreakTimerService)
                    stopSelf()
                    return
                }
                val formatted = KioskManager.formatDuration(remaining)
                if (formatted != lastDisplayedDuration) {
                    lastDisplayedDuration = formatted
                    val manager = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager
                    manager?.notify(NOTIFICATION_ID, buildNotification(formatted))
                }
                handler.postDelayed(this, TICK_INTERVAL_MS)
            }
        }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        setupNotificationChannels(applicationContext)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_END_BREAK_EARLY -> {
                handler.removeCallbacks(tickRunnable)
                KioskManager.endBreakEarlyAndRelock(this)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_BREAK_ALARM_EXPIRED -> {
                handler.removeCallbacks(tickRunnable)
                if (KioskManager.getRemainingBreakMillis(this) <= 0L) {
                    KioskManager.onBreakExpired(this)
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }

        if (!KioskManager.isKioskLocked(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        val remaining = KioskManager.getRemainingBreakMillis(this)
        if (remaining <= 0L) {
            KioskManager.onBreakExpired(this)
            stopSelf()
            return START_NOT_STICKY
        }

        val formatted = KioskManager.formatDuration(remaining)
        lastDisplayedDuration = formatted
        val fgsType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(formatted),
                fgsType,
            )
        }.onFailure { e ->
            Timber.e(e, "Failed to promote KioskBreakTimerService to foreground")
        }

        handler.removeCallbacks(tickRunnable)
        handler.postDelayed(tickRunnable, TICK_INTERVAL_MS)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tickRunnable)
        super.onDestroy()
    }

    private fun buildNotification(formattedRemaining: String): Notification {
        val openAppIntent =
            Intent(this, DeckPicker::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        val contentPendingIntent =
            PendingIntentCompat.getActivity(
                this,
                REQUEST_OPEN_APP,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT,
                false,
            )

        val endEarlyIntent =
            Intent(this, KioskBreakTimerService::class.java).apply {
                action = ACTION_END_BREAK_EARLY
            }
        val endEarlyPendingIntent =
            PendingIntentCompat.getService(
                this,
                REQUEST_END_EARLY,
                endEarlyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT,
                false,
            )

        return NotificationCompat
            .Builder(this, NotificationChannel.KIOSK_BREAK.id)
            .setSmallIcon(R.drawable.ic_star_notify)
            .setContentTitle(getString(R.string.kiosk_break_notification_title))
            .setContentText(getString(R.string.kiosk_break_notification_text, formattedRemaining))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentPendingIntent)
            .addAction(
                0,
                getString(R.string.kiosk_action_end_break_early),
                endEarlyPendingIntent,
            ).build()
    }

    companion object {
        private const val NOTIFICATION_ID = 29402
        private const val REQUEST_OPEN_APP = 29403
        private const val REQUEST_END_EARLY = 29404
        private const val TICK_INTERVAL_MS = 1000L

        const val ACTION_START = "com.ichi2.anki.kiosk.ACTION_START_BREAK_TIMER"
        const val ACTION_END_BREAK_EARLY = "com.ichi2.anki.kiosk.ACTION_END_BREAK_EARLY"
        const val ACTION_BREAK_ALARM_EXPIRED = "com.ichi2.anki.kiosk.ACTION_BREAK_ALARM_EXPIRED"

        fun start(context: Context) {
            val appCtx = context.applicationContext
            val intent =
                Intent(appCtx, KioskBreakTimerService::class.java).apply {
                    action = ACTION_START
                }
            runCatching {
                ContextCompat.startForegroundService(appCtx, intent)
            }.onFailure { e ->
                Timber.e(e, "Failed to start KioskBreakTimerService")
            }
        }

        fun stop(context: Context) {
            val appCtx = context.applicationContext
            runCatching {
                appCtx.stopService(Intent(appCtx, KioskBreakTimerService::class.java))
            }
        }
    }
}
