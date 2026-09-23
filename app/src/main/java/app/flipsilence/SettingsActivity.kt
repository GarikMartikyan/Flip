package app.flipsilence

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import java.util.Locale

/**
 * Everything that is set once and then left alone, kept off the main screen so that screen can be
 * about the one thing it shows: whether the phone is quiet.
 */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var sensBlurb: TextView
    private lateinit var sensSegments: Map<Sensitivity, TextView>
    private lateinit var themeSegments: Map<ThemeMode, ViewGroup>
    private lateinit var hapticsSwitch: Switch
    private lateinit var sleepSwitch: Switch
    private lateinit var explainer: SleepExplainer
    private lateinit var notificationSwitch: Switch
    private lateinit var dndStatus: TextView
    private lateinit var batteryStatus: TextView
    private lateinit var diagnosticsToggle: TextView
    private lateinit var diagnosticsBody: LinearLayout
    private lateinit var readout: TextView

    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = Prefs(this)

        applySystemBarInsets(findViewById(R.id.scroll), findViewById(R.id.content))

        sensBlurb = findViewById(R.id.sens_blurb)
        hapticsSwitch = findViewById(R.id.haptics_switch)
        notificationSwitch = findViewById(R.id.notification_switch)
        dndStatus = findViewById(R.id.dnd_status)
        batteryStatus = findViewById(R.id.battery_status)
        diagnosticsToggle = findViewById(R.id.diagnostics_toggle)
        diagnosticsBody = findViewById(R.id.diagnostics_body)
        readout = findViewById(R.id.readout)

        findViewById<View>(R.id.back).setOnClickListener { finish() }

        themeSegments = mapOf(
            ThemeMode.SYSTEM to findViewById(R.id.theme_system),
            ThemeMode.LIGHT to findViewById(R.id.theme_light),
            ThemeMode.DARK to findViewById(R.id.theme_dark),
        )
        themeSegments.forEach { (mode, segment) ->
            segment.setOnClickListener {
                if (mode == prefs.theme) return@setOnClickListener
                prefs.theme = mode
                // Recreates this screen in the new theme when the choice changes what shows.
                mode.apply(this)
                renderTheme()
            }
        }
        renderTheme()

        sensSegments = mapOf(
            Sensitivity.STRICT to findViewById(R.id.sens_strict),
            Sensitivity.BALANCED to findViewById(R.id.sens_balanced),
            Sensitivity.RELAXED to findViewById(R.id.sens_relaxed),
        )
        sensSegments.forEach { (level, segment) ->
            segment.setText(level.label)
            segment.setOnClickListener {
                prefs.sensitivity = level
                // Applies to the running service without restarting it, so a change mid-session
                // does not drop the sensor registration.
                FlipService.reload(this)
                renderSensitivity()
            }
        }

        sleepSwitch = findViewById(R.id.sleep_switch)
        explainer = findViewById(R.id.sleep_explainer)
        val explainerCaption = findViewById<TextView>(R.id.sleep_explainer_caption)
        val steps = intArrayOf(R.string.explainer_step_bed, R.string.explainer_step_night, R.string.explainer_step_morning)
        explainer.onStep = { explainerCaption.setText(steps[it]) }
        sleepSwitch.isChecked = prefs.sleepMonitoring
        renderSleep()
        sleepSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.sleepMonitoring = checked
            renderSleep()
        }

        hapticsSwitch.isChecked = prefs.haptics
        hapticsSwitch.setOnCheckedChangeListener { _, checked -> prefs.haptics = checked }

        // Mirrors the system's own switch for Flip: an app cannot turn its notifications off, and
        // Android will not let it hide a foreground service's notification any other way.
        notificationSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked == notificationsOn()) return@setOnCheckedChangeListener
            notificationSwitch.isChecked = !checked
            if (checked && !hasNotificationPermission()) {
                requestOrOpenSettings(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS, notificationSettings())
            } else {
                openNotificationSettings()
            }
        }

        findViewById<View>(R.id.dnd_row).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
        findViewById<View>(R.id.language_row).setOnClickListener {
            startActivity(Intent(this, LanguageActivity::class.java))
        }
        findViewById<View>(R.id.battery_row).setOnClickListener {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }

        diagnosticsToggle.setOnClickListener {
            val open = diagnosticsBody.visibility != View.VISIBLE
            diagnosticsBody.visibility = if (open) View.VISIBLE else View.GONE
            renderDiagnosticsToggle(open)
            if (open) renderReadout(FlipState.info)
        }
        renderDiagnosticsToggle(open = false)
    }

    override fun onResume() {
        super.onResume()
        // Both of these can change behind our back: the user returns here from the system screens.
        renderSensitivity()
        renderLanguage()
        renderSystem()
        notificationSwitch.isChecked = notificationsOn()
        FlipState.observe { info -> main.post { renderReadout(info) } }
    }

    override fun onPause() {
        FlipState.observe(null)
        super.onPause()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS) notePermissionResult(permissions, grantResults)
        notificationSwitch.isChecked = notificationsOn()
    }

    private fun hasNotificationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun notificationsOn(): Boolean =
        hasNotificationPermission() && getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    private fun notificationSettings(): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)

    private fun openNotificationSettings() = startActivity(notificationSettings())

    /** Off, the explainer stops on its first frame and fades back, like a setting that is not in use. */
    private fun renderSleep() {
        val on = prefs.sleepMonitoring
        explainer.playing = on
        explainer.alpha = if (on) 1f else 0.45f
    }

    private fun renderTheme() {
        val mode = prefs.theme
        val density = resources.displayMetrics.density
        themeSegments.forEach { (segmentMode, segment) ->
            val selected = segmentMode == mode
            // Lifted like the sensitivity thirds: a setting, so no ink fill.
            segment.background = if (selected) pill(getColor(R.color.segment_selected)) else pill(null)
            segment.elevation = if (selected) 1f * density else 0f
            segment.isSelected = selected
            val ink = getColor(if (selected) R.color.ink else R.color.ink_dim)
            (segment.getChildAt(0) as ImageView).imageTintList = ColorStateList.valueOf(ink)
            (segment.getChildAt(1) as TextView).setTextColor(ink)
        }
    }

    /** The system's language shows as such, with what it currently comes to underneath. */
    private fun renderLanguage() {
        val chosen = AppLanguage.chosen(this)
        findViewById<TextView>(R.id.language_name).text = chosen?.nativeName ?: getString(R.string.language_system)
        findViewById<TextView>(R.id.language_caption).apply {
            text = chosen?.localName(this@SettingsActivity)?.takeUnless { it == chosen.nativeName }
                ?: if (chosen == null) AppLanguage.fromSystem(this@SettingsActivity).nativeName else null
            visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun renderSensitivity() {
        val level = prefs.sensitivity
        val density = resources.displayMetrics.density
        sensSegments.forEach { (segmentLevel, segment) ->
            val selected = segmentLevel == level
            // The chosen third is lifted out of the track rather than filled: in this palette ink
            // means sound, so it is not spent on a setting.
            segment.background = if (selected) pill(getColor(R.color.segment_selected)) else pill(null)
            segment.elevation = if (selected) 1f * density else 0f
            segment.isSelected = selected
            segment.setTextColor(getColor(if (selected) R.color.ink else R.color.ink_dim))
        }
        sensBlurb.setText(level.blurb)
    }

    private fun renderSystem() {
        val hasDnd = DndController.hasAccess(this)
        dndStatus.setText(if (hasDnd) R.string.dnd_granted else R.string.dnd_missing)
        dndStatus.setTextColor(getColor(if (hasDnd) R.color.ink_dim else R.color.ink))

        val unrestricted = getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(packageName)
        batteryStatus.setText(if (unrestricted) R.string.battery_unrestricted else R.string.battery_optimized)
        batteryStatus.setTextColor(getColor(if (unrestricted) R.color.ink_dim else R.color.ink))
    }

    private fun renderDiagnosticsToggle(open: Boolean) {
        diagnosticsToggle.compoundDrawablesRelative[2]?.level = if (open) 10_000 else 0
        diagnosticsToggle.stateDescription = getString(if (open) R.string.expanded else R.string.collapsed)
    }

    private fun renderReadout(info: FlipState.Info) {
        // Samples arrive at ~10 Hz; there is no point formatting them into a collapsed section.
        if (diagnosticsBody.visibility != View.VISIBLE) return
        val s = info.sample
        val level = prefs.sensitivity
        readout.text = buildString {
            append("service    ").append(if (info.running) "running" else "stopped").append('\n')
            append("sensor     ").append(info.sensorName.ifEmpty { "—" }).append('\n')
            append("wake-up    ").append(if (info.wakeUpSensor) "yes" else "no (screen-on backstop)")
                .append('\n')
            append("rate       ").append(String.format(Locale.US, "%.1f Hz", info.eventsPerSec))
                .append("\n\n")
            append("profile    ").append(level.name.lowercase()).append('\n')
            if (s == null) {
                append("no samples yet")
            } else {
                append("gravity z  ").append(String.format(Locale.US, "%+6.2f", s.gravityZ))
                    .append("   engage ≤ ").append(level.engageGz).append('\n')
                append("motion     ").append(String.format(Locale.US, "%6.3f", s.motion))
                    .append("   still < ").append(level.stillMax).append('\n')
                append("drift      ").append(String.format(Locale.US, "%6.2f", s.driftDeg))
                    .append("°  steady < ").append(level.maxDriftDeg).append("°\n")
                append("held       ").append(s.heldMs).append(" / ").append(s.holdTargetMs)
                    .append(" ms\n")
                append("proximity  ").append(
                    s.proximity?.let {
                        String.format(Locale.US, "%.1f", it) +
                            if (s.proximityNear == true) "  near" else "  far"
                    } ?: "not exposed"
                )
            }
        }
    }

    private companion object {
        const val REQUEST_NOTIFICATIONS = 1
    }
}
