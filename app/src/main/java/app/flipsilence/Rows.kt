package app.flipsilence

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/*
 * The small rows the sleep and silence cards are built from. Their number depends on the data, so
 * they are made here rather than in layout files, and shared so every screen draws them alike.
 */


/** A key for a colour on a dial: a round dot, filled, or outlined for the bare track. */
internal fun Context.swatch(colorRes: Int, outlined: Boolean = false) =
    GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setSize(dp(8f), dp(8f))
        if (outlined) {
            setColor(getColor(R.color.surface))
            setStroke(dp(2f), getColor(R.color.ink))
        } else {
            setColor(getColor(colorRes))
        }
    }

/** A line of explanation standing in for rows that do not exist yet. */
internal fun LinearLayout.addNote(note: String) {
    addView(TextView(context).apply {
        text = note
        setTextColor(context.getColor(R.color.ink_dim))
        typeface = resources.getFont(R.font.plus_jakarta_sans_regular)
        textSize = 15f
        setLineSpacing(context.dp(3f).toFloat(), 1f)
    })
}

/** One silence: from when to when, and how long it lasted. */
internal fun LinearLayout.addSilenceRow(entry: History.Entry) {
    val ctx = context
    val range = "${SleepText.clock(entry.startMs)} – ${SleepText.clock(entry.startMs + entry.durationMs)}"
    addValueRow(ctx.swatch(R.color.quiet), range, SleepText.seconds(ctx, entry.durationMs)).minimumHeight = ctx.dp(36f)
}

/** A swatch in the colour it has on the strip above, what it is, and how long. */
internal fun LinearLayout.addValueRow(swatch: GradientDrawable, label: String, value: String): View {
    val ctx = context
    val row = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = ctx.dp(40f)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    row.addView(View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(swatch.intrinsicWidth, swatch.intrinsicHeight)
        background = swatch
    })

    row.addView(TextView(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        text = label
        setTextColor(ctx.getColor(R.color.ink_dim))
        typeface = resources.getFont(R.font.plus_jakarta_sans_regular)
        textSize = 15f
        setPadding(ctx.dp(12f), 0, ctx.dp(12f), 0)
    })

    row.addView(TextView(ctx).apply {
        text = value
        setTextColor(ctx.getColor(R.color.ink))
        typeface = resources.getFont(R.font.plus_jakarta_sans_medium)
        textSize = 15f
    })

    addView(row)
    return row
}

/**
 * What the colours on a night's dial add up to: silenced, left alone with the sound on, and the
 * time spent picked up. A share that did not happen is left out, except silence, whose absence
 * is worth saying.
 */
internal fun LinearLayout.addNightLegend(night: Sleep.Night) {
    val ctx = context
    if (night.silencedMs > 0) {
        addValueRow(ctx.swatch(R.color.quiet), ctx.getString(R.string.legend_silenced), SleepText.minutes(ctx, night.silencedMs))
    } else {
        addValueRow(ctx.swatch(R.color.track, outlined = true), ctx.getString(R.string.night_not_silenced), "")
    }
    val leftAlone = night.asleepMs - night.silencedMs
    if (leftAlone >= 60_000L) {
        addValueRow(ctx.swatch(R.color.awake), ctx.getString(R.string.legend_left_alone), SleepText.minutes(ctx, leftAlone))
    }
    if (night.awakeMs > 0) {
        addValueRow(ctx.swatch(R.color.track, outlined = true), ctx.getString(R.string.legend_awake), SleepText.minutes(ctx, night.awakeMs))
    }
}
