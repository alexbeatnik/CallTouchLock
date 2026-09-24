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
 * Full-screen touch shield over the in-call UI.
 * Must stay visually transparent so the user can still see who they are calling;
 * only touch input is consumed. Hardware keys (red end-call) stay with the system
 * because the window is FLAG_NOT_FOCUSABLE.
 */
class TouchLockOverlay(private val context: Context) {

    private val windowManager =
        context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var overlayView: View? = null

    val isShowing: Boolean
        get() = overlayView != null

    fun show() {
        if (overlayView != null) return
        if (!Settings.canDrawOverlays(context)) return

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

        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        val flags = (
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
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
            windowManager.addView(view, params)
            overlayView = view
        } catch (_: Exception) {
            overlayView = null
        }
    }

    fun hide() {
        val view = overlayView ?: return
        overlayView = null
        try {
            windowManager.removeViewImmediate(view)
        } catch (_: Exception) {
            try {
                windowManager.removeView(view)
            } catch (_: Exception) {
            }
        }
    }
}
