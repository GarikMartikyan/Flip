package app.flipsilence

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Every app with a launcher icon, each with a switch: on means Flip still sounds for it while the
 * phone is face down. Changes are saved as they are made, so there is no Done.
 */
class AppPickerActivity : Activity() {

    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_picker)

        applySystemBarInsets(findViewById(R.id.scroll), findViewById(R.id.content))
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        list = findViewById(R.id.app_list)
        list.addRowNote(getString(R.string.loading_apps))

        // Labels and icons for a hundred-odd apps take long enough to stall the first frame.
        Thread {
            val apps = Exceptions.launchable(this)
            runOnUiThread { if (!isDestroyed) render(apps) }
        }.start()
    }

    private fun render(apps: List<Exceptions.App>) {
        list.removeAllViews()
        val allowed = Prefs(this).allowedApps
        val dp = resources.displayMetrics.density
        val pad = resources.getDimensionPixelSize(R.dimen.card_padding)

        apps.forEachIndexed { i, app ->
            if (i > 0) list.addRowDivider()

            val toggle = Switch(this).apply {
                isChecked = app.pkg in allowed
                showText = false
                // One UI sets a wider minimum; the drawables alone decide the size here.
                switchMinWidth = 0
                thumbDrawable = getDrawable(R.drawable.switch_thumb)
                trackDrawable = getDrawable(R.drawable.switch_track)
                // The whole row is the target; the switch only shows the state.
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = (60 * dp).roundToInt()
                setPadding(pad, (8 * dp).roundToInt(), pad, (8 * dp).roundToInt())
                background = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                    .run { getDrawable(0).also { recycle() } }
                isClickable = true
                isFocusable = true
                contentDescription = app.label
                stateDescription = stateText(toggle.isChecked)
            }
            row.addView(appIcon(app.icon, ICON_DP))
            row.addView(TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                text = app.label
                maxLines = 2
                setTextColor(getColor(R.color.ink))
                typeface = resources.getFont(R.font.jakarta)
                textSize = 16f
                setPadding((14 * dp).roundToInt(), 0, (12 * dp).roundToInt(), 0)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            row.addView(toggle)

            row.setOnClickListener {
                val on = !toggle.isChecked
                toggle.isChecked = on
                row.stateDescription = stateText(on)
                Exceptions.setAllowed(this, app.pkg, on)
            }
            list.addView(row)
        }
    }

    private fun stateText(on: Boolean) =
        getString(if (on) R.string.gets_through_state else R.string.silenced_state)

    private companion object {
        const val ICON_DP = 36f
    }
}
