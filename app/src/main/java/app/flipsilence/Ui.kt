package app.flipsilence

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.SystemClock
import android.view.View
import android.view.WindowInsets
import kotlin.math.roundToInt

internal fun Context.dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

/**
 * Asks for [permissions], or opens [settings] once Android has stopped asking. That state has no
 * API of its own: it is a permission once refused (the rationale showed) that now has no rationale
 * left. A dialog merely dismissed never shows the rationale, so it is asked again rather than being
 * sent off to settings. Pair with [notePermissionResult].
 */
internal fun Activity.requestOrOpenSettings(permissions: Array<String>, requestCode: Int, settings: Intent) {
    val first = permissions.first()
    val refused = getSharedPreferences("flip", Context.MODE_PRIVATE).getBoolean(REFUSED_PREFIX + first, false)
    if (refused && !shouldShowRequestPermissionRationale(first)) {
        startActivity(settings)
    } else {
        requestedAt = SystemClock.elapsedRealtime()
        requestPermissions(permissions, requestCode)
    }
}

/**
 * Remembers a refusal the user made, so [requestOrOpenSettings] can tell it from a dismissal.
 *
 * A permission switched off in system settings never got a refusal here, yet Android treats it as
 * refused for good: it answers at once, with no dialog, and would go on doing so on every tap. An
 * answer that quick is not a person's, so it is remembered as a refusal and [settings] opens now.
 */
internal fun Activity.notePermissionResult(permissions: Array<out String>, grantResults: IntArray, settings: Intent) {
    val first = permissions.firstOrNull() ?: return
    if (grantResults.firstOrNull() != PackageManager.PERMISSION_DENIED) return
    val rationale = shouldShowRequestPermissionRationale(first)
    val silent = !rationale && SystemClock.elapsedRealtime() - requestedAt < NO_DIALOG_MS
    if (rationale || silent) {
        getSharedPreferences("flip", Context.MODE_PRIVATE).edit().putBoolean(REFUSED_PREFIX + first, true).apply()
    }
    if (silent) startActivity(settings)
}

private const val REFUSED_PREFIX = "refused_"

/** Faster than anyone can read a permission dialog and tap it. */
private const val NO_DIALOG_MS = 400L

private var requestedAt = 0L

/**
 * A fully rounded fill with press feedback clipped to the same shape. With no fill it is only the
 * ripple, for a segment that is not selected.
 */
internal fun Context.pill(fill: Int?): RippleDrawable {
    val density = resources.displayMetrics.density
    fun shape(color: Int) = GradientDrawable().apply {
        cornerRadius = 100f * density
        setColor(color)
    }
    val ripple = ColorStateList.valueOf(getColor(R.color.ripple))
    return if (fill == null) RippleDrawable(ripple, null, shape(Color.WHITE))
    else RippleDrawable(ripple, shape(fill), null)
}

/**
 * Targeting SDK 35+ forces edge-to-edge, so a screen sits behind the status bar. Add the real inset
 * rather than a guessed dp, or the title collides with the clock. Sideways, the navigation bar and
 * the camera cutout sit at the left or right edge instead, so those go on [scroll]: the centred
 * [content] column then stays centred in what is actually visible.
 */
internal fun applySystemBarInsets(scroll: View, content: View) {
    val basePaddingTop = content.paddingTop
    val basePaddingBottom = content.paddingBottom
    scroll.setOnApplyWindowInsetsListener { v, insets ->
        val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        v.setPadding(bars.left, 0, bars.right, 0)
        content.setPadding(
            content.paddingLeft,
            basePaddingTop + bars.top,
            content.paddingRight,
            basePaddingBottom + bars.bottom,
        )
        insets
    }
}
