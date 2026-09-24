package com.local.calltouchlock

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat

class MainActivity : AppCompatActivity() {

    private lateinit var settings: SettingsRepository
    private lateinit var statusIcon: ImageView
    private lateinit var statusTitle: TextView
    private lateinit var statusText: TextView
    private lateinit var rowSaver: SettingRow
    private lateinit var rowProtection: SettingRow
    private lateinit var rowGuard: SettingRow
    private lateinit var rowRinging: SettingRow
    private lateinit var rowOverlay: SettingRow
    private lateinit var rowPhone: SettingRow
    private lateinit var rowBoot: SettingRow
    private lateinit var rowTest: SettingRow

    private val phonePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // The monitor only registers the telephony callback when it starts.
            if (settings.serviceEnabled) CallMonitorService.refresh(this)
            refreshUi()
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshUi() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = SettingsRepository(this)

        statusIcon = findViewById(R.id.statusIcon)
        statusTitle = findViewById(R.id.statusTitle)
        statusText = findViewById(R.id.statusText)
        rowSaver = SettingRow(findViewById(R.id.rowSaver), R.drawable.ic_battery_alert, R.string.row_saver)
        rowProtection = SettingRow(findViewById(R.id.rowProtection), R.drawable.ic_shield, R.string.row_protection)
        rowRinging = SettingRow(findViewById(R.id.rowRinging), R.drawable.ic_ringing, R.string.row_ringing)
        rowGuard = SettingRow(findViewById(R.id.rowGuard), R.drawable.ic_lock, R.string.row_guard)
        rowOverlay = SettingRow(findViewById(R.id.rowOverlay), R.drawable.ic_layers, R.string.row_overlay)
        rowPhone = SettingRow(findViewById(R.id.rowPhone), R.drawable.ic_call, R.string.row_phone)
        rowBoot = SettingRow(findViewById(R.id.rowBoot), R.drawable.ic_power, R.string.row_boot)
        rowTest = SettingRow(findViewById(R.id.rowTest), R.drawable.ic_block, R.string.row_test)

        rowSaver.setSubtitle(getString(R.string.row_saver_sub))
        rowSaver.chevron.visibility = View.VISIBLE
        rowSaver.root.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
            } catch (_: Exception) {
            }
        }
        rowProtection.setSubtitle(getString(R.string.row_protection_sub))
        rowOverlay.setSubtitle(getString(R.string.row_overlay_sub))
        rowPhone.setSubtitle(getString(R.string.row_phone_sub))
        rowBoot.setSubtitle(getString(R.string.row_boot_sub))
        rowTest.setSubtitle(getString(R.string.row_test_sub))
        rowOverlay.chevron.visibility = View.VISIBLE
        rowPhone.chevron.visibility = View.VISIBLE

        // DPAD focus wraps from the last row to the first and back.
        rowProtection.root.nextFocusUpId = R.id.rowTest
        rowTest.root.nextFocusDownId = R.id.rowProtection

        rowProtection.root.setOnClickListener {
            settings.serviceEnabled = !settings.serviceEnabled
            if (settings.serviceEnabled) CallMonitorService.start(this) else CallMonitorService.stop(this)
            refreshUi()
        }
        rowRinging.setSubtitle(getString(R.string.row_ringing_sub))
        rowRinging.root.setOnClickListener {
            settings.blockWhileRinging = !settings.blockWhileRinging
            if (settings.serviceEnabled) CallMonitorService.refresh(this)
            refreshUi()
        }
        rowGuard.root.setOnClickListener {
            if (!isGuardServiceEnabled()) {
                // Accessibility services are switched on by the user, in Settings.
                toast(R.string.guard_open_settings)
                try {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (_: Exception) {
                }
            } else {
                settings.lockScreenGuard = !settings.lockScreenGuard
                LockGuardService.instance?.refresh()
                refreshUi()
            }
        }
        rowOverlay.root.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
            }
        }
        rowPhone.root.setOnClickListener {
            if (hasPhonePermission()) {
                openAppSettings()
            } else {
                phonePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
            }
        }
        rowBoot.root.setOnClickListener {
            settings.startAfterBoot = !settings.startAfterBoot
            refreshUi()
        }
        rowTest.root.setOnClickListener {
            when {
                !settings.serviceEnabled -> toast(R.string.test_needs_on)
                !Settings.canDrawOverlays(this) && !LockGuardService.running -> toast(R.string.test_needs_overlay)
                else -> {
                    CallMonitorService.test(this)
                    toast(R.string.test_started)
                }
            }
        }

        requestNotificationPermissionIfNeeded()
        rowProtection.root.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        CallMonitorService.uiListener = { refreshUi() }
        LockGuardService.uiListener = { refreshUi() }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the permission screens: (re)start with the new rights.
        if (settings.serviceEnabled) CallMonitorService.start(this) else CallMonitorService.stop(this)
        refreshUi()
    }

    override fun onStop() {
        CallMonitorService.uiListener = null
        LockGuardService.uiListener = null
        super.onStop()
    }

    private fun refreshUi() {
        val enabled = settings.serviceEnabled
        val overlay = Settings.canDrawOverlays(this)
        val shielding = CallMonitorService.isShielding()
        // Unisoc's "close app after screen lock" runs in Battery Saver and force-stops this app.
        val saver = getSystemService(PowerManager::class.java)?.isPowerSaveMode == true

        val (title, text, color) = when {
            !enabled -> Triple(R.string.status_off, R.string.status_off_text, R.color.ja_text_3)
            !overlay -> Triple(R.string.status_needs_overlay, R.string.status_needs_overlay_text, R.color.ja_red)
            shielding -> Triple(R.string.status_blocking, R.string.status_blocking_text, R.color.ja_accent)
            saver -> Triple(R.string.status_saver, R.string.status_saver_text, R.color.ja_red)
            else -> Triple(R.string.status_on, R.string.status_on_text, R.color.ja_green)
        }
        val showSaver = enabled && saver
        rowSaver.root.visibility = if (showSaver) View.VISIBLE else View.GONE
        // Keep DPAD wrap-around including the warning row when it is shown.
        val first = if (showSaver) rowSaver.root else rowProtection.root
        rowProtection.root.nextFocusUpId = if (showSaver) R.id.rowSaver else R.id.rowTest
        rowSaver.root.nextFocusUpId = R.id.rowTest
        rowTest.root.nextFocusDownId = first.id
        statusTitle.setText(title)
        statusText.setText(text)
        ImageViewCompat.setImageTintList(statusIcon, ColorStateList.valueOf(ContextCompat.getColor(this, color)))

        rowProtection.setToggle(enabled)
        rowRinging.setToggle(settings.blockWhileRinging)
        val guardService = isGuardServiceEnabled()
        rowGuard.setSubtitle(getString(when {
            !guardService -> R.string.row_guard_off_sub
            !LockGuardService.running -> R.string.row_guard_stuck_sub
            else -> R.string.row_guard_sub
        }))
        rowGuard.setToggle(guardService && settings.lockScreenGuard)
        rowOverlay.setBadge(if (overlay) R.string.granted else R.string.required, overlay, warn = !overlay)
        val phone = hasPhonePermission()
        rowPhone.setBadge(if (phone) R.string.granted else R.string.optional, phone, warn = false)
        rowBoot.setToggle(settings.startAfterBoot)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Our lock screen service is switched on in Settings → Accessibility. */
    private fun isGuardServiceEnabled(): Boolean {
        val cr = contentResolver
        if (Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) return false
        val mine = ComponentName(this, LockGuardService::class.java)
        return Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.any { ComponentName.unflattenFromString(it) == mine } == true
    }

    private fun hasPhonePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    private fun openAppSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
        }
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    private class SettingRow(val root: View, iconRes: Int, titleRes: Int) {
        private val subtitle: TextView = root.findViewById(R.id.settingSubtitle)
        private val switch: SwitchCompat = root.findViewById(R.id.settingSwitch)
        private val value: TextView = root.findViewById(R.id.settingValue)
        val chevron: ImageView = root.findViewById(R.id.settingChevron)

        init {
            root.findViewById<ImageView>(R.id.settingIcon).setImageResource(iconRes)
            root.findViewById<TextView>(R.id.settingTitle).setText(titleRes)
        }

        fun setSubtitle(text: CharSequence?) {
            subtitle.text = text
            subtitle.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

        fun setToggle(on: Boolean) {
            switch.visibility = View.VISIBLE
            if (switch.isChecked != on) switch.isChecked = on
        }

        fun setBadge(textRes: Int, good: Boolean, warn: Boolean) {
            val ctx = root.context
            value.visibility = View.VISIBLE
            value.setText(textRes)
            value.setBackgroundResource(if (good) R.drawable.bg_pill_on else R.drawable.bg_pill_off)
            val color = when {
                good -> R.color.ja_green
                warn -> R.color.ja_red
                else -> R.color.ja_text_2
            }
            value.setTextColor(ContextCompat.getColor(ctx, color))
        }
    }
}
