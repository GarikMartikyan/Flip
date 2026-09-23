package app.flipsilence

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Following the system, or one of the languages Flip is written in. Each is named in its own
 * language so it can be found from any other, with the name in the current language underneath.
 */
class LanguageActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_language)

        applySystemBarInsets(findViewById(R.id.scroll), findViewById(R.id.content))
        findViewById<View>(R.id.back).setOnClickListener { finish() }

        val chosen = AppLanguage.chosen(this)
        val list = findViewById<LinearLayout>(R.id.language_list)
        list.addChoice(getString(R.string.language_system), AppLanguage.fromSystem(this).nativeName, chosen == null) {
            AppLanguage.choose(this, null)
        }
        AppLanguage.ALL.forEach { language ->
            list.addRowDivider()
            val local = language.localName(this).takeUnless { it == language.nativeName }
            list.addChoice(language.nativeName, local, language == chosen) {
                AppLanguage.choose(this, language)
            }
        }
    }

    private fun LinearLayout.addChoice(title: String, caption: String?, selected: Boolean, pick: () -> Unit) {
        val dp = resources.displayMetrics.density
        val pad = resources.getDimensionPixelSize(R.dimen.card_padding)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (60 * dp).roundToInt()
            setPadding(pad, (8 * dp).roundToInt(), pad, (8 * dp).roundToInt())
            background = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                .run { getDrawable(0).also { recycle() } }
            isSelected = selected
            contentDescription = listOfNotNull(title, caption).joinToString(", ")
            setOnClickListener {
                // Android recreates Flip's screens in the new language; this one has done its job.
                if (!selected) pick()
                finish()
            }
        }
        val words = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        words.addView(TextView(context, null, 0, R.style.RowTitle).apply { text = title })
        caption?.let {
            words.addView(TextView(context, null, 0, R.style.RowCaption).apply {
                text = it
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = (2 * dp).roundToInt() }
            })
        }
        row.addView(words)
        row.addView(ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams((20 * dp).roundToInt(), (20 * dp).roundToInt())
                .apply { marginStart = (16 * dp).roundToInt() }
            setImageResource(R.drawable.ic_check)
            visibility = if (selected) View.VISIBLE else View.INVISIBLE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        addView(row)
    }
}
