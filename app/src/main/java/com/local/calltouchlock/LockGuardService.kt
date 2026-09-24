package com.local.calltouchlock

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Lock screen, keys only: while the keyguard is up, a transparent accessibility overlay (the only
 * kind of app window Android draws above the lock screen) swallows touch, so a pocket can't swipe
 * the phone open. The window never takes focus, so the keys stay with the system, and this
 * firmware unlocks with the left key, then *.
 *
 * A call (see CallMonitorService) or a ringing alarm over the keyguard gets touch back. Window events
 * can't tell "in front of the keyguard" from "behind it" (apps report state changes while the screen
 * goes off), so only these reliable signals count.
 */
class LockGuardService : AccessibilityService() {

    private lateinit var settings: SettingsRepository
    private lateinit var keyguard: KeyguardManager
    private lateinit var audio: AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var shield: View? = null
    private var hint: TextView? = null
    private var callMonitor: CallStateMonitor? = null
    private var callState = SimpleCallState.IDLE
    /** From screen off to screen on: the keyguard may still be on its way, keep the shield. */
    private var screenOff = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // The keyguard is not locked yet at screen off, but it shows before the screen comes back:
            // raise the shield now so the first touch after waking is already caught. Screen on /
            // unlock correct it.
            screenOff = intent.action == Intent.ACTION_SCREEN_OFF
            update()
        }
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) = update()
    }

    private val hideHint = Runnable { hint?.animate()?.alpha(0f)?.setDuration(250)?.start() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        running = true
        settings = SettingsRepository(this)
        keyguard = getSystemService(KeyguardManager::class.java)
        audio = getSystemService(AudioManager::class.java)
        screenOff = getSystemService(android.os.PowerManager::class.java)?.isInteractive == false
        ContextCompat.registerReceiver(
            this, screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            // USER_PRESENT comes from SystemUI, not the system, so a not-exported receiver misses it.
            // All three are protected broadcasts: no other app can send them.
            ContextCompat.RECEIVER_EXPORTED
        )
        audio.registerAudioPlaybackCallback(playbackCallback, handler)
        callMonitor = CallStateMonitor(this) { state ->
            callState = state
            update()
        }.also { it.start() }
        update()
        uiListener?.invoke()
    }

    /** Only a nudge to re-check the keyguard; which app the event came from is not trusted. */
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) update()
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    /** The in-app switch changed. */
    fun refresh() = update()

    private fun shutdown() {
        if (!running) return
        running = false
        if (instance === this) instance = null
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        try { audio.unregisterAudioPlaybackCallback(playbackCallback) } catch (_: Exception) {}
        callMonitor?.stop()
        callMonitor = null
        handler.removeCallbacksAndMessages(null)
        hideShield()
        uiListener?.invoke()
    }

    private fun update() {
        if (shouldShield()) showShield() else hideShield()
    }

    private fun shouldShield(): Boolean {
        if (!settings.lockScreenGuard) return false
        // Ringing: answer it by touch. In a call: the call shield of CallMonitorService takes over.
        if (callState != SimpleCallState.IDLE) return false
        // An alarm or timer is on screen and needs its buttons.
        if (urgentSoundPlaying()) return false
        return screenOff || keyguard.isKeyguardLocked
    }

    private fun urgentSoundPlaying(): Boolean =
        audio.activePlaybackConfigurations.any { it.audioAttributes.usage in URGENT_USAGES }

    private fun showShield() {
        if (shield != null) return
        val wm = getSystemService(WindowManager::class.java) ?: return
        val pill = TextView(this).apply {
            setText(R.string.guard_hint)
            setTextColor(ContextCompat.getColor(context, R.color.ja_on_accent))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            val padH = dp(16)
            val padV = dp(9)
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                colors = intArrayOf(
                    ContextCompat.getColor(context, R.color.ja_accent),
                    ContextCompat.getColor(context, R.color.ja_accent_2)
                )
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
            }
            alpha = 0f
        }
        val root = FrameLayout(this).apply {
            // Fully transparent windows composite as black on some OEM builds; 1/255 alpha blends properly.
            setBackgroundColor(Color.argb(1, 0, 0, 0))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(pill, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = dp(56) })
            setOnTouchListener { _, e -> onShieldTouch(e) }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Not focusable: keys (left key, then *) keep going to the keyguard.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        try {
            wm.addView(root, params)
            shield = root
            hint = pill
            Log.i(TAG, "shield up")
        } catch (t: Throwable) {
            Log.w(TAG, "cannot add shield", t)
        }
    }

    private fun onShieldTouch(e: MotionEvent): Boolean {
        if (e.actionMasked != MotionEvent.ACTION_DOWN) return true
        // Never stand in the way of an unlocked phone, whatever event got lost.
        if (!keyguard.isKeyguardLocked) {
            hideShield()
            return true
        }
        hint?.let {
            handler.removeCallbacks(hideHint)
            it.animate().alpha(1f).setDuration(150).start()
            handler.postDelayed(hideHint, HINT_MS)
        }
        return true
    }

    private fun hideShield() {
        val view = shield ?: return
        shield = null
        hint = null
        handler.removeCallbacks(hideHint)
        try {
            getSystemService(WindowManager::class.java)?.removeViewImmediate(view)
            Log.i(TAG, "shield down")
        } catch (_: Exception) {
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "CallTouchLock"
        private const val HINT_MS = 2500L
        private val URGENT_USAGES = setOf(
            AudioAttributes.USAGE_ALARM,
            AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
        )

        @Volatile
        var running = false
            private set
        var instance: LockGuardService? = null
            private set
        /** Set by MainActivity while it is visible. */
        var uiListener: (() -> Unit)? = null
    }
}
