package app.flipsilence

import android.content.Context
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/*
 * A night told top to bottom: one row per moment, its time first, and between two moments a thin
 * rule saying what the phone was doing meanwhile -- moss words while it kept quiet, ink while
 * sound could still get through, faint while it was in your hand.
 */

/** How a moment is marked: a moss disc for a silence, an ink icon for something that rang out, or faint. */
internal enum class Tone { QUIET, INK, FAINT }

/** What the phone was doing between two moments, which is the colour of the words on the rule. */
internal enum class Between { SILENCED, SOUND_ON, AWAKE }


private const val MARK_SIZE = 28f
private const val GAP = 14f
private const val TIME_SP = 15f

private fun TextView.asTime(time: String) {
    text = time
    setTextColor(context.getColor(R.color.ink))
    typeface = resources.getFont(R.font.plus_jakarta_sans_semibold)
    textSize = TIME_SP
    fontFeatureSettings = "tnum"
    includeFontPadding = false
    maxLines = 1
}

/**
 * Room for the widest time there is, at whatever size the system font makes it. Measured once per
 * list, on the list itself, rather than with a throwaway TextView for every row.
 */
private fun LinearLayout.timeWidth(): Int {
    (getTag(R.id.time_width) as? Int)?.let { return it }
    val paint = Paint().apply {
        typeface = resources.getFont(R.font.plus_jakarta_sans_semibold)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TIME_SP, resources.displayMetrics)
        fontFeatureSettings = "tnum"
    }
    return (paint.measureText("00:00").roundToInt() + context.dp(2f)).also { setTag(R.id.time_width, it) }
}

/** One moment: the time, its mark, what happened and, beneath it, anything worth adding. */
internal fun LinearLayout.addTimelineMoment(time: String, iconRes: Int, tone: Tone, title: String, detail: String?) {
    val ctx = context
    val row = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, ctx.dp(12f), 0, ctx.dp(12f))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    row.addView(TextView(ctx).apply {
        asTime(time)
        setPadding(0, ctx.dp(6f), 0, 0)
    }, LinearLayout.LayoutParams(timeWidth(), ViewGroup.LayoutParams.WRAP_CONTENT))

    val mark = ctx.dp(MARK_SIZE)
    row.addView(ImageView(ctx).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ctx.getColor(if (tone == Tone.QUIET) R.color.quiet else R.color.track))
        }
        setImageDrawable(ctx.getDrawable(iconRes)?.mutate()?.apply {
            setTint(ctx.getColor(when (tone) {
                Tone.QUIET -> R.color.on_awake
                Tone.INK -> R.color.ink
                Tone.FAINT -> R.color.ink_dim
            }))
        })
        scaleType = ImageView.ScaleType.FIT_CENTER
        val inset = ctx.dp(6.5f)
        setPadding(inset, inset, inset, inset)
    }, LinearLayout.LayoutParams(mark, mark).apply { marginStart = ctx.dp(GAP) })

    val words = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, ctx.dp(4f), 0, 0)
    }
    words.addView(TextView(ctx).apply {
        text = title
        setTextColor(ctx.getColor(R.color.ink))
        typeface = resources.getFont(R.font.plus_jakarta_sans_medium)
        textSize = 15f
    })
    if (detail != null) {
        words.addView(TextView(ctx).apply {
            text = detail
            setTextColor(ctx.getColor(R.color.ink_dim))
            typeface = resources.getFont(R.font.plus_jakarta_sans_regular)
            textSize = 13f
            setPadding(0, ctx.dp(2f), 0, 0)
        })
    }
    row.addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        marginStart = ctx.dp(GAP)
    })

    row.contentDescription = listOfNotNull(time, title, detail).joinToString(", ")
    row.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    addView(row)
}

/** The rule between two moments, indented past the times, saying what went on when [label] is given. */
internal fun LinearLayout.addTimelineStretch(between: Between, label: String?) {
    val ctx = context
    val row = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPaddingRelative(timeWidth() + ctx.dp(GAP), 0, 0, 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    fun rule(width: Int, weight: Float) = View(ctx).apply {
        setBackgroundColor(ctx.getColor(R.color.border))
        layoutParams = LinearLayout.LayoutParams(width, ctx.dp(1f), weight)
    }
    row.addView(rule(0, 1f))
    if (label != null) {
        row.addView(TextView(ctx).apply {
            text = label
            setTextColor(ctx.getColor(when (between) {
                Between.SILENCED -> R.color.quiet
                Between.SOUND_ON -> R.color.ink
                Between.AWAKE -> R.color.ink_dim
            }))
            typeface = resources.getFont(R.font.plus_jakarta_sans_medium)
            textSize = 12f
            fontFeatureSettings = "tnum"
            maxLines = 1
            setPaddingRelative(ctx.dp(10f), 0, ctx.dp(10f), 0)
        })
        row.addView(rule(ctx.dp(16f), 0f))
    }
    addView(row)
}
