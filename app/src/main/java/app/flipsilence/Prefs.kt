package app.flipsilence

import android.content.Context
import android.media.AudioManager

class Prefs(ctx: Context) {

    private val sp = ctx.applicationContext
        .getSharedPreferences("flip", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, false)
        set(v) = sp.edit().putBoolean(KEY_ENABLED, v).apply()

    var ruleId: String?
        get() = sp.getString(KEY_RULE_ID, null)
        set(v) = sp.edit().putString(KEY_RULE_ID, v).apply()

    var sensitivity: Sensitivity
        get() = Sensitivity.fromName(sp.getString(KEY_SENSITIVITY, null))
        set(v) = sp.edit().putString(KEY_SENSITIVITY, v.name).apply()

    /** Mirrors what was handed to the system, which does not read it back reliably. */
    var theme: ThemeMode
        get() = ThemeMode.fromName(sp.getString(KEY_THEME, null))
        set(v) = sp.edit().putString(KEY_THEME, v.name).apply()

    /** The two quick ticks on each transition. Read at the moment of vibrating, so no reload. */
    var haptics: Boolean
        get() = sp.getBoolean(KEY_HAPTICS, true)
        set(v) = sp.edit().putBoolean(KEY_HAPTICS, v).apply()

    /**
     * Whether screen-dark stretches are recorded as sleep. Off stops recording and hides last
     * night; nights already recorded are kept for when it is turned back on.
     */
    var sleepMonitoring: Boolean
        get() = sp.getBoolean(KEY_SLEEP_MONITORING, true)
        set(v) = sp.edit().putBoolean(KEY_SLEEP_MONITORING, v).apply()

    /** Packages whose notifications Flip still sounds for while the phone is face down. */
    var allowedApps: Set<String>
        get() = sp.getStringSet(KEY_ALLOWED_APPS, null)?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet(KEY_ALLOWED_APPS, v).apply()

    /** Whether the phone's starred contacts still ring. Off silences them without unstarring. */
    var favouritesRing: Boolean
        get() = sp.getBoolean(KEY_FAVOURITES_RING, true)
        set(v) = sp.edit().putBoolean(KEY_FAVOURITES_RING, v).apply()

    /** Whether a second call within 15 minutes gets through, from anyone. */
    var repeatCallers: Boolean
        get() = sp.getBoolean(KEY_REPEAT_CALLERS, true)
        set(v) = sp.edit().putBoolean(KEY_REPEAT_CALLERS, v).apply()

    /**
     * The ringer mode and notification volume the moment before silencing. Do Not Disturb mutes
     * the notification stream, so an alert for an allowed app has to be judged against these.
     */
    var ringerBefore: Int
        get() = sp.getInt(KEY_RINGER_BEFORE, AudioManager.RINGER_MODE_NORMAL)
        set(v) = sp.edit().putInt(KEY_RINGER_BEFORE, v).apply()

    var notificationVolumeBefore: Float
        get() = sp.getFloat(KEY_NOTIFICATION_VOLUME_BEFORE, 0.6f)
        set(v) = sp.edit().putFloat(KEY_NOTIFICATION_VOLUME_BEFORE, v).apply()

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_RULE_ID = "rule_id"
        const val KEY_SENSITIVITY = "sensitivity"
        const val KEY_HAPTICS = "haptics"
        const val KEY_SLEEP_MONITORING = "sleep_monitoring"
        const val KEY_THEME = "theme"
        const val KEY_ALLOWED_APPS = "allowed_apps"
        const val KEY_FAVOURITES_RING = "favourites_ring"
        const val KEY_REPEAT_CALLERS = "repeat_callers"
        const val KEY_RINGER_BEFORE = "ringer_before"
        const val KEY_NOTIFICATION_VOLUME_BEFORE = "notification_volume_before"
    }
}
