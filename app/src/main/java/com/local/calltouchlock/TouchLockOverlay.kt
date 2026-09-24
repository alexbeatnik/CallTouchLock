package com.local.calltouchlock

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Full-screen touch shield over the in-call UI (ringing or active).
 * With the lock screen service on it is an accessibility overlay: over a locked phone the call
 * screen sits on top of the keyguard, where Android may hide ordinary app overlays.
 * Must stay visually transparent so the user can still see who they are calling;
 * only touch input is consumed. Hardware keys (red end-call) stay with the system
 * because the window is FLAG_NOT_FOCUSABLE.
 */
class TouchLockOverlay(private val context: Context) {

    private val windowManager =
        context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var overlayView: View? = null
    private var overlayWm: WindowManager? = null

    val isShowing: Boolean
        get() = overlayView != null

    /** A shield can be drawn: through the lock screen service, or with "Display over other apps". */
    fun canShow(): Boolean = LockGuardService.instance != null || Settings.canDrawOverlays(context)

    fun show() {
        if (overlayView != null) return
        val guard = LockGuardService.instance
        if (guard == null && !Settings.canDrawOverlays(context)) return
        val wm = if (guard != null) guard.getSystemService(WindowManager::class.java) else windowManager

        // Fully Color.TRANSPARENT often composites as opaque black on some OEM
        // overlays (including feature phones). 1/255 alpha forces real blending
        // while remaining effectively invisible.
        val seeThrough = Color.argb(1, 0, 0, 0)

        val view = View(context.applicationContext).apply {
            setBackgroundColor(seeThrough)
            setOnTouchListener { _, _ -> true }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            contentDescription = null
            isClickable = true
            isFocusable = false
            // Software layer avoids HW-composer black-fill bugs on some devices.
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }

        val type = if (guard != null) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }

        @Suppress("DEPRECATION") // still honoured for non-activity windows over an occluded keyguard
        val flags = (
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            )
        // No FLAG_NOT_TOUCHABLE — we must eat touches.
        // No FLAG_DIM_BEHIND — must not dim/black out the call UI.

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            dimAmount = 0f
            // Keep window alpha at full; transparency comes from the view color.
            alpha = 1f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        try {
            wm.addView(view, params)
            overlayView = view
            overlayWm = wm
        } catch (_: Exception) {
            overlayView = null
        }
    }

    fun hide() {
        val view = overlayView ?: return
        overlayView = null
        val wm = overlayWm ?: windowManager
        overlayWm = null
        try {
            wm.removeViewImmediate(view)
        } catch (_: Exception) {
            try {
                wm.removeView(view)
            } catch (_: Exception) {
            }
        }
    }
}
