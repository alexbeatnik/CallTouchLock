package com.local.calltouchlock

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

enum class SimpleCallState {
    IDLE,
    RINGING,
    OFFHOOK
}

/**
 * Device-wide call state.
 *
 * On Android 12+ the telephony callback only reports the SIM that was the default when it was
 * registered, so a call on the other SIM of this dual-SIM phone (or after a SIM swap / early boot)
 * would go unnoticed. The audio mode covers every SIM and needs no permission:
 * MODE_IN_CALL = a cellular call is dialing / active, MODE_RINGTONE = ringing. The telephony state
 * is still used when available, mainly to spot a second call ringing during a call.
 */
class CallStateMonitor(
    context: Context,
    private val onStateChanged: (SimpleCallState) -> Unit
) {
    private val app = context.applicationContext
    private val telephony = app.getSystemService(TelephonyManager::class.java)
    private val audio = app.getSystemService(AudioManager::class.java)

    private var modernCallback: TelephonyCallback? = null
    private var legacyListener: PhoneStateListener? = null
    private var modeListener: AudioManager.OnModeChangedListener? = null
    private var telephonyState = SimpleCallState.IDLE
    private var published: SimpleCallState? = null
    private var listening = false

    fun start() {
        if (listening) return
        listening = true
        if (hasPhonePermission()) registerTelephony()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val l = AudioManager.OnModeChangedListener { publish() }
            audio.addOnModeChangedListener(ContextCompat.getMainExecutor(app), l)
            modeListener = l
        }
        refresh()
    }

    fun stop() {
        if (!listening) return
        listening = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            modernCallback?.let {
                try { telephony.unregisterTelephonyCallback(it) } catch (_: Exception) {}
            }
            modeListener?.let { audio.removeOnModeChangedListener(it) }
        }
        modernCallback = null
        modeListener = null
        legacyListener?.let {
            @Suppress("DEPRECATION")
            try { telephony.listen(it, PhoneStateListener.LISTEN_NONE) } catch (_: Exception) {}
        }
        legacyListener = null
        published = null
    }

    /** Re-read everything and report the state even if it did not change. */
    fun refresh() {
        if (hasPhonePermission()) {
            try {
                @Suppress("DEPRECATION") // device-wide on purpose; getCallStateForSubscription is per SIM
                telephonyState = map(telephony.callState)
            } catch (_: SecurityException) {
            }
        }
        published = null
        publish()
    }

    fun current(): SimpleCallState {
        val mode = audio.mode
        return when {
            // A (second) call is ringing: let the user answer it on the screen.
            telephonyState == SimpleCallState.RINGING || mode == AudioManager.MODE_RINGTONE -> SimpleCallState.RINGING
            telephonyState == SimpleCallState.OFFHOOK || mode == AudioManager.MODE_IN_CALL -> SimpleCallState.OFFHOOK
            else -> SimpleCallState.IDLE
        }
    }

    private fun publish() {
        val s = current()
        if (s == published) return
        published = s
        onStateChanged(s)
    }

    private fun registerTelephony() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        telephonyState = map(state)
                        publish()
                    }
                }
                telephony.registerTelephonyCallback(ContextCompat.getMainExecutor(app), callback)
                modernCallback = callback
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        telephonyState = map(state)
                        publish()
                    }
                }
                @Suppress("DEPRECATION")
                telephony.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                legacyListener = listener
            }
        } catch (_: SecurityException) {
        }
    }

    private fun hasPhonePermission(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    private fun map(state: Int): SimpleCallState = when (state) {
        TelephonyManager.CALL_STATE_RINGING -> SimpleCallState.RINGING
        TelephonyManager.CALL_STATE_OFFHOOK -> SimpleCallState.OFFHOOK
        else -> SimpleCallState.IDLE
    }
}
