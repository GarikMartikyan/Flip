package app.flipsilence

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/*
 * The faces and app icons that stand for what still gets through, on the main screen's strip and
 * in the lists behind it.
 */


/** A contact's photo in a circle, or their initial when they have none. */
internal fun Context.avatar(name: String, photo: Uri?, sizeDp: Float): View {
    val size = dp(sizeDp)
    val circle = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(getColor(R.color.track))
        setStroke(dp(1f), getColor(R.color.border))
    }
    val view: View = if (photo != null) {
        ImageView(this).apply {
            setImageURI(photo)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
    } else {
        TextView(this).apply {
            text = name.trim().firstOrNull()?.uppercase() ?: "?"
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = resources.getFont(R.font.plus_jakarta_sans_semibold)
            setTextColor(getColor(R.color.ink))
            textSize = sizeDp * 0.42f
        }
    }
    return view.apply {
        background = circle
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(size, size)
    }
}

internal fun Context.appIcon(icon: Drawable, sizeDp: Float): View = ImageView(this).apply {
    setImageDrawable(icon)
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
}

/**
 * A faint stand-in for a face or an app icon, for the strip while nothing is chosen, so it shows
 * what will go there.
 */
internal fun Context.ghostMark(oval: Boolean, sizeDp: Float): View = ImageView(this).apply {
    setImageResource(if (oval) R.drawable.ic_person else R.drawable.ic_apps)
    scaleType = ImageView.ScaleType.CENTER
    background = GradientDrawable().apply {
        if (oval) shape = GradientDrawable.OVAL else cornerRadius = dp(sizeDp * 0.3f).toFloat()
        setColor(getColor(R.color.track))
    }
    alpha = 0.85f
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
}

/**
 * Overlapping marks for the strip: each sits in a ring of the strip's own colour, so where they
 * overlap the one in front reads as cut out of the one behind.
 */
internal fun LinearLayout.addStacked(mark: View, sizeDp: Float, first: Boolean, oval: Boolean) {
    val ctx = context
    val ring = ctx.dp(2f)
    val frame = FrameLayout(ctx).apply {
        background = GradientDrawable().apply {
            if (oval) shape = GradientDrawable.OVAL else cornerRadius = ctx.dp(sizeDp * 0.3f).toFloat()
            setColor(ctx.getColor(R.color.surface))
        }
        setPadding(ring, ring, ring, ring)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { if (!first) marginStart = -ctx.dp(sizeDp * 0.3f) }
    }
    frame.addView(mark, FrameLayout.LayoutParams(ctx.dp(sizeDp), ctx.dp(sizeDp)))
    addView(frame)
}

internal fun LinearLayout.addRowDivider() {
    addView(View(context).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(1f))
        setBackgroundColor(context.getColor(R.color.border))
    })
}

/**
 * A person or an app in a list: its mark, its name, and, when it can be taken off the list, a
 * button that does so.
 */
internal fun LinearLayout.addMarkRow(mark: View, title: String, onRemove: (() -> Unit)? = null) {
    val ctx = context
    val pad = resources.getDimensionPixelSize(R.dimen.card_padding)
    val row = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = ctx.dp(60f)
        setPadding(pad, ctx.dp(6f), if (onRemove != null) ctx.dp(4f) else pad, ctx.dp(6f))
    }
    row.addView(mark)
    row.addView(TextView(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        text = title
        maxLines = 2
        setTextColor(ctx.getColor(R.color.ink))
        typeface = resources.getFont(R.font.jakarta)
        textSize = 16f
        setPadding(ctx.dp(14f), 0, ctx.dp(8f), 0)
    })
    if (onRemove != null) row.addView(ImageButton(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(ctx.dp(48f), ctx.dp(48f))
        setImageResource(R.drawable.ic_close)
        background = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
            .run { getDrawable(0).also { recycle() } }
        contentDescription = ctx.getString(R.string.remove_x, title)
        setOnClickListener { onRemove() }
    })
    addView(row)
}

/** A line of explanation inside a rows card, padded like a row. */
internal fun LinearLayout.addRowNote(note: String) {
    val pad = resources.getDimensionPixelSize(R.dimen.card_padding)
    addView(TextView(context).apply {
        text = note
        setTextColor(context.getColor(R.color.ink_dim))
        typeface = resources.getFont(R.font.jakarta)
        textSize = 15f
        setLineSpacing(context.dp(3f).toFloat(), 1f)
        setPadding(pad, context.dp(16f), pad, context.dp(16f))
    })
}
