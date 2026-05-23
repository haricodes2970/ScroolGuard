package com.hari.scrollguard

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    private lateinit var toggle: SwitchMaterial
    private lateinit var statusText: TextView
    private lateinit var gracePeriodSlider: SeekBar
    private lateinit var gracePeriodValue: TextView

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toggle = findViewById(R.id.toggleScrollBlocking)
        statusText = findViewById(R.id.statusText)
        gracePeriodSlider = findViewById(R.id.gracePeriodSlider)
        gracePeriodValue = findViewById(R.id.gracePeriodValue)

        requestNotificationsIfNeeded()

        val savedGrace = ScrollGuardService.getGracePeriodMinutes(this)
        gracePeriodSlider.max = 30
        gracePeriodSlider.progress = savedGrace
        updateGracePeriodLabel(savedGrace)

        gracePeriodSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateGracePeriodLabel(progress)
                if (fromUser) {
                    ScrollGuardService.persistGracePeriodMinutes(this@MainActivity, progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        toggle.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !isAccessibilityServiceEnabled()) {
                toggle.isChecked = false
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                updateStatus()
                return@setOnCheckedChangeListener
            }
            ScrollGuardService.isEnabled = isChecked
            ScrollGuardService.persistEnabled(this, isChecked)
            updateStatus()
        }
    }

    override fun onResume() {
        super.onResume()
        syncToggleFromPrefs()
        updateStatus()
    }

    private fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun syncToggleFromPrefs() {
        val prefsEnabled = ScrollGuardService.isBlockingEnabledInPrefs(this)
        val accessibilityOn = isAccessibilityServiceEnabled()
        val shouldBeChecked = prefsEnabled && accessibilityOn
        toggle.setOnCheckedChangeListener(null)
        toggle.isChecked = shouldBeChecked
        ScrollGuardService.isEnabled = shouldBeChecked
        toggle.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !isAccessibilityServiceEnabled()) {
                toggle.isChecked = false
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                updateStatus()
                return@setOnCheckedChangeListener
            }
            ScrollGuardService.isEnabled = isChecked
            ScrollGuardService.persistEnabled(this, isChecked)
            updateStatus()
        }
    }

    private fun updateGracePeriodLabel(minutes: Int) {
        gracePeriodValue.text = if (minutes == 0) {
            getString(R.string.grace_period_immediate)
        } else {
            getString(R.string.grace_period_minutes, minutes)
        }
    }

    private fun updateStatus() {
        statusText.text = when {
            !isAccessibilityServiceEnabled() ->
                getString(R.string.status_enable_accessibility)
            toggle.isChecked ->
                getString(R.string.status_active)
            else ->
                getString(R.string.status_paused)
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val component = ComponentName(this, ScrollGuardService::class.java)
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(':').any {
            ComponentName.unflattenFromString(it)?.equals(component) == true
        }
    }
}
