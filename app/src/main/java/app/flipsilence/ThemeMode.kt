package app.flipsilence

import android.app.UiModeManager
import android.content.Context

/** The app's own choice of light or dark, over the system's. */
enum class ThemeMode(private val nightMode: Int) {
    SYSTEM(UiModeManager.MODE_NIGHT_AUTO),
    LIGHT(UiModeManager.MODE_NIGHT_NO),
    DARK(UiModeManager.MODE_NIGHT_YES);

    /**
     * Android keeps this per app across restarts and recreates the open activities itself, so
     * nothing has to be reapplied at launch.
     */
    fun apply(ctx: Context) {
        ctx.getSystemService(UiModeManager::class.java).setApplicationNightMode(nightMode)
    }

    companion object {
        fun fromName(name: String?): ThemeMode = entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}
