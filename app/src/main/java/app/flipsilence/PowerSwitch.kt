package app.flipsilence

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator
import kotlin.math.abs
import kotlin.math.cos

/**
 * The on/off switch in the header, drawn as the thing it is about: a small phone in the track.
 *
 * Off, the phone lies face up at the start, outlined, its home bar showing. Turning it on slides it
 * across and turns it over, so it ends face down: a solid back on an ink track, with its camera
 * island in the corner. While Flip is holding the phone quiet, the top lens lights moss. Moss is
 * kept for the quiet, so it appears nowhere else.
 *
 * Only draws; the row around it takes the tap and speaks for it.
 */
class PowerSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private companion object {
        const val TRACK_W_DP = 62f
        const val TRACK_H_DP = 34f
        const val PHONE_W_DP = 15f
        const val PHONE_H_DP = 22f
        const val PHONE_INSET_DP = 8f
        const val PHONE_RADIUS_DP = 4f
        const val STROKE_DP = 1.5f
        const val FLIP_MS = 380L
        const val DOT_MS = 240L
        const val ISLAND_W_DP = 4.6f
        const val ISLAND_H_DP = 9.2f
        const val ISLAND_INSET_DP = 2.2f
        const val LENS_R_DP = 1.3f
        const val LENS_STEP_DP = 4.3f
    }

    private val dp = resources.displayMetrics.density
    private val colorTrack = context.getColor(R.color.track)
    private val colorBorder = context.getColor(R.color.border)
    private val colorInk = context.getColor(R.color.awake)
    private val colorOnInk = context.getColor(R.color.on_awake)
    private val colorDim = context.getColor(R.color.ink_dim)
    private val colorQuiet = context.getColor(R.color.quiet)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE_DP * dp
    }
    private val rect = RectF()
    private val ease = PathInterpolator(0.3f, 0.7f, 0.2f, 1f)

    /** 0 = off, face up at the start; 1 = on, face down at the end. */
    private var flip = 0f
    /** 0 = no light; 1 = the top lens lit moss, a phone being held quiet. */
    private var dot = 0f
    private var flipAnim: ValueAnimator? = null
    private var dotAnim: ValueAnimator? = null
    private var settled = false

    fun setState(on: Boolean, quiet: Boolean) {
        val flipTo = if (on) 1f else 0f
        val dotTo = if (on && quiet) 1f else 0f
        if (!settled || !isLaidOut) {
            // The first state is where the screen opens, not a change to watch.
            settled = true
            flip = flipTo
            dot = dotTo
            invalidate()
            return
        }
        flipAnim = animateTo(flipAnim, flip, flipTo, FLIP_MS) { flip = it }
        dotAnim = animateTo(dotAnim, dot, dotTo, DOT_MS) { dot = it }
    }

    private fun animateTo(
        running: ValueAnimator?,
        from: Float,
        to: Float,
        ms: Long,
        set: (Float) -> Unit,
    ): ValueAnimator? {
        if (from == to && running?.isRunning != true) return running
        running?.cancel()
        return ValueAnimator.ofFloat(from, to).apply {
            duration = (ms * abs(to - from)).toLong().coerceAtLeast(1L)
            interpolator = ease
            addUpdateListener {
                set(it.animatedValue as Float)
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize((TRACK_W_DP * dp).toInt(), widthMeasureSpec),
            resolveSize((TRACK_H_DP * dp).toInt(), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = TRACK_W_DP * dp
        val h = TRACK_H_DP * dp
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        val half = STROKE_DP * dp / 2f

        // Track: paper with an outline when off, solid ink when on.
        rect.set(left, top, left + w, top + h)
        fill.color = blend(colorTrack, colorInk, flip)
        canvas.drawRoundRect(rect, h / 2f, h / 2f, fill)
        if (flip < 1f) {
            stroke.color = colorBorder
            stroke.alpha = ((1f - flip) * 255).toInt()
            rect.inset(half, half)
            canvas.drawRoundRect(rect, h / 2f - half, h / 2f - half, stroke)
        }

        // The phone, sliding across and turning over about its long axis.
        val pw = PHONE_W_DP * dp
        val ph = PHONE_H_DP * dp
        val travel = w - pw - 2 * PHONE_INSET_DP * dp
        val cx = left + PHONE_INSET_DP * dp + pw / 2f + travel * flip
        val cy = top + h / 2f
        val turn = cos(Math.PI * flip).toFloat()
        val faceUp = turn >= 0f
        val squash = abs(turn).coerceAtLeast(0.06f)
        val r = PHONE_RADIUS_DP * dp

        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(squash, 1f)
        rect.set(-pw / 2f, -ph / 2f, pw / 2f, ph / 2f)
        if (faceUp) {
            stroke.color = colorDim
            stroke.alpha = 255
            rect.inset(half, half)
            canvas.drawRoundRect(rect, r - half, r - half, stroke)
            // Home bar, so it reads as a screen facing up.
            fill.color = colorDim
            val barW = 6f * dp
            val barY = ph / 2f - 5f * dp
            rect.set(-barW / 2f, barY, barW / 2f, barY + STROKE_DP * dp)
            canvas.drawRoundRect(rect, half, half, fill)
        } else {
            fill.color = colorOnInk
            canvas.drawRoundRect(rect, r, r, fill)
            canvas.drawCameras(ph)
        }
        canvas.restore()
    }

    /**
     * The camera island in the back's top corner, two lenses in it. The top one is the one light
     * left on: it turns moss while Flip holds the phone quiet.
     */
    private fun Canvas.drawCameras(ph: Float) {
        val islandW = ISLAND_W_DP * dp
        val islandH = ISLAND_H_DP * dp
        val left = -PHONE_W_DP * dp / 2f + ISLAND_INSET_DP * dp
        val top = -ph / 2f + ISLAND_INSET_DP * dp
        rect.set(left, top, left + islandW, top + islandH)
        fill.color = colorInk
        drawRoundRect(rect, islandW / 2f, islandW / 2f, fill)

        val cx = left + islandW / 2f
        val lensR = LENS_R_DP * dp
        val firstY = top + islandW / 2f
        fill.color = blend(colorOnInk, colorQuiet, dot)
        drawCircle(cx, firstY, lensR, fill)
        fill.color = colorOnInk
        drawCircle(cx, firstY + LENS_STEP_DP * dp, lensR, fill)
    }

    private fun blend(from: Int, to: Int, t: Float): Int {
        fun ch(shift: Int) =
            (((from shr shift) and 0xFF) + (((to shr shift) and 0xFF) - ((from shr shift) and 0xFF)) * t).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
