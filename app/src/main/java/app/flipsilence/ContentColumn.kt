package app.flipsilence

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import kotlin.math.roundToInt

/**
 * A vertical column that stops growing at a readable width.
 *
 * On a phone it is simply the screen. On a tablet, an unfolded foldable or a phone turned sideways
 * a full-width column stretches the segmented control into a bar and puts the gauge's caption a
 * long way from its numbers, so past [MAX_WIDTH_DP] it stays that wide and the parent centres it.
 */
class ContentColumn @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private companion object {
        /** Includes the gutters: roughly 540dp of content, which is a large phone held upright. */
        const val MAX_WIDTH_DP = 600
    }

    private val maxWidthPx = (MAX_WIDTH_DP * resources.displayMetrics.density).roundToInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val size = MeasureSpec.getSize(widthMeasureSpec)
        val capped =
            if (mode != MeasureSpec.UNSPECIFIED && size > maxWidthPx) {
                MeasureSpec.makeMeasureSpec(maxWidthPx, MeasureSpec.EXACTLY)
            } else {
                widthMeasureSpec
            }
        super.onMeasure(capped, heightMeasureSpec)
    }
}
