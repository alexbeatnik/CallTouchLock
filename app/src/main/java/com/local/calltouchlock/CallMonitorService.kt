package com.local.calltouchlock

import android.app.Notification
import android.app.NotificationChannel
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
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/** Keeps the call-state watch alive and shows the touch shield while a call is active. */
class CallMonitorService : Service() {

    private lateinit var settings: SettingsRepository
    private lateinit var overlay: TouchLockOverlay
    private val handler = Handler(Looper.getMainLooper())
    private var callMonitor: CallStateMonitor? = null
    private var lastState: SimpleCallState = SimpleCallState.IDLE
    /** A "test" from the app keeps the shield up until this time even without a call. */
    private var testUntil = 0L

    /** While the shield is up, re-check the real call state so it can never get stuck. */
    private val watchdog = object : Runnable {
        override fun run() {
            val m = callMonitor
            if (m != null) m.refresh() else applyState(lastState)
            if (overlay.isShowing) handler.postDelayed(this, WATCHDOG_MS)
        }
    }

    private val endTest = Runnable {
        testUntil = 0L
        applyState(lastState)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SettingsRepository(this)
        overlay = TouchLockOverlay(this)
        overlay.hide()
        startAsForeground()
        startMonitor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            settings.serviceEnabled = false
            cleanupAndStop()
            return START_NOT_STICKY
        }
        if (!settings.serviceEnabled) {
            cleanupAndStop()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_REFRESH -> restartMonitor()
            ACTION_TEST -> startTest()
            else -> startMonitor()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        callMonitor?.stop()
        callMonitor = null
        overlay.hide()
        if (instance === this) instance = null
        notifyUi()
        super.onDestroy()
    }

    private fun restartMonitor() {
        callMonitor?.stop()
        callMonitor = null
        startMonitor()
    }

    private fun startMonitor() {
        if (callMonitor != null) return
        callMonitor = CallStateMonitor(this) { state ->
            lastState = state
            applyState(state)
        }.also { it.start() }
    }

    private fun startTest() {
        testUntil = SystemClock.uptimeMillis() + TEST_MS
        handler.removeCallbacks(endTest)
        handler.postDelayed(endTest, TEST_MS)
        applyState(lastState)
    }

    private fun applyState(state: SimpleCallState) {
        val testing = SystemClock.uptimeMillis() < testUntil
        val inCall = settings.serviceEnabled && settings.blockDuringCalls && state == SimpleCallState.OFFHOOK
        if ((inCall || testing) && Settings.canDrawOverlays(this)) {
            if (!overlay.isShowing) {
                overlay.show()
                handler.removeCallbacks(watchdog)
                handler.postDelayed(watchdog, WATCHDOG_MS)
            }
        } else {
            overlay.hide()
            handler.removeCallbacks(watchdog)
        }
        notifyUi()
    }

    private fun cleanupAndStop() {
        handler.removeCallbacksAndMessages(null)
        testUntil = 0L
        callMonitor?.stop()
        callMonitor = null
        overlay.hide()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.app_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.service_notification_text)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_text))
            .setSmallIcon(R.drawable.ic_stat_call_lock)
            .setColor(ContextCompat.getColor(this, R.color.ja_accent))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        const val ACTION_STOP = "com.local.calltouchlock.STOP"
        const val ACTION_REFRESH = "com.local.calltouchlock.REFRESH"
        const val ACTION_TEST = "com.local.calltouchlock.TEST"
        const val TEST_MS = 5_000L
        private const val WATCHDOG_MS = 3_000L
        private const val CHANNEL_ID = "call_touch_lock"
        private const val NOTIFICATION_ID = 42

        /** Running instance (main thread only), for the status card in the app. */
        var instance: CallMonitorService? = null
            private set

        /** Called on the main thread whenever the shield state may have changed. */
        var uiListener: (() -> Unit)? = null

        private fun notifyUi() = uiListener?.invoke()

        fun isShielding(): Boolean = instance?.overlay?.isShowing == true

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, CallMonitorService::class.java))
        }

        fun stop(context: Context) {
            // startService (not startForegroundService) for control commands: a no-op when the
            // service is already gone, and no ForegroundServiceStartNotAllowedException.
            try {
                context.startService(Intent(context, CallMonitorService::class.java).setAction(ACTION_STOP))
            } catch (_: Exception) {
            }
        }

        fun refresh(context: Context) {
            try {
                context.startService(Intent(context, CallMonitorService::class.java).setAction(ACTION_REFRESH))
            } catch (_: Exception) {
            }
        }

        fun test(context: Context) {
            try {
                context.startService(Intent(context, CallMonitorService::class.java).setAction(ACTION_TEST))
            } catch (_: Exception) {
            }
        }
    }
}
