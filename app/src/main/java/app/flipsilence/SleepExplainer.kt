package app.flipsilence

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The one habit sleep monitoring needs, shown rather than told: a phone on a nightstand, seen from
 * above, turns over onto its back before bed, goes quiet through the night, and turns back in the
 * morning. Loops every [LOOP_S] seconds.
 *
 * Drawn on a fixed 320 x 160 stage scaled to the view's width. The phone is a solid thing, not a card:
 * its frame shows along the lower edge, its side sweeps past as it turns about its long axis, and it
 * lifts off the table mid-flip while its shadow spreads and fades beneath it. Moss appears only once
 * the phone is face down, which is when Flip would silence it.
 *
 * Runs only while shown and [playing]. Stopped, it holds its first frame; with the system's
 * animations turned off it holds one still frame of the night instead.
 */
class SleepExplainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private companion object {
        const val STAGE_W = 320f
        const val STAGE_H = 160f
        const val LOOP_S = 8f

        /** The frame shown when animations are off: face down, under the moon. */
        const val STILL_S = 4.2f

        const val HALF_W = 22f
        const val HALF_H = 44f
        const val THICKNESS = 3.5f
        const val CORNER = 9f

        /** 23:40 and 07:10, in minutes after midnight the evening before. */
        const val BED_MIN = 1420f
        const val WAKE_MIN = 1870f
    }

    /** Called with 0, 1 or 2 whenever the step being shown changes, for the caption under it. */
    var onStep: ((Int) -> Unit)? = null

    /** Whether the loop runs. Off holds the first frame. */
    var playing = true
        set(v) {
            field = v
            startMs = SystemClock.uptimeMillis()
            invalidate()
        }

    private var startMs = SystemClock.uptimeMillis()
    private var shownStep = -1

    private val colorInk = context.getColor(R.color.ink)
    private val colorDim = context.getColor(R.color.ink_dim)
    private val colorQuiet = context.getColor(R.color.quiet)
    private val colorTrack = context.getColor(R.color.track)
    private val colorOnAwake = context.getColor(R.color.on_awake)
    private val colorEdge = context.getColor(R.color.phone_edge)
    private val colorShadow = context.getColor(R.color.phone_shadow)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorShadow }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = resources.getFont(R.font.plus_jakarta_sans_semibold)
        fontFeatureSettings = "tnum"
        letterSpacing = -0.01f
    }
    private val smallText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = resources.getFont(R.font.plus_jakarta_sans_medium)
        color = colorDim
        textSize = 10.5f
    }

    private val rect = RectF()
    private val moon = Path()
    private val moonCut = Path()
    private var scale = 1f

    private val summary = SleepText.minutes(context, ((WAKE_MIN - BED_MIN) * 60_000).toLong())
    private val asleep = context.getString(R.string.asleep)

    init {
        // The crescent is fixed in stage units; drawn under the canvas scale.
        moon.addCircle(264f, 38f, 11f, Path.Direction.CW)
        moonCut.addCircle(264f + 11f * .45f, 38f - 11f * .45f, 11f * .86f, Path.Direction.CW)
        moon.op(moonCut, Path.Op.DIFFERENCE)
        contentDescription = listOf(R.string.explainer_step_bed, R.string.explainer_step_night, R.string.explainer_step_morning)
            .joinToString(" ") { context.getString(it) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * STAGE_H / STAGE_W).roundToInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        scale = w / STAGE_W
        // Mask filters work in pixels, outside the canvas scale.
        shadow.maskFilter = BlurMaskFilter(max(1f, 6f * scale), BlurMaskFilter.Blur.NORMAL)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) invalidate()
    }

    private fun running() = playing && ValueAnimator.areAnimatorsEnabled() && isShown

    override fun onDraw(canvas: Canvas) {
        val t = when {
            !playing -> 0f
            !ValueAnimator.areAnimatorsEnabled() -> STILL_S
            else -> ((SystemClock.uptimeMillis() - startMs) / 1000f) % LOOP_S
        }
        canvas.save()
        canvas.scale(scale, scale)
        drawStage(canvas, t)
        canvas.restore()

        val step = if (t < 2.0f) 0 else if (t < 6.0f) 1 else 2
        if (step != shownStep) {
            shownStep = step
            onStep?.invoke(step)
        }
        if (running()) postInvalidateOnAnimation()
    }

    private fun drawStage(canvas: Canvas, t: Float) {
        // The nightstand.
        fill.color = colorTrack
        fill.alpha = 140
        rect.set(108f, 12f, 212f, 148f)
        canvas.drawRoundRect(rect, 22f, 22f, fill)
        fill.alpha = 255

        // Quiet: a moss halo that breathes while the phone lies face down.
        val quiet = seg(t, 2.1f, 2.6f) - seg(t, 5.8f, 6.1f)
        if (quiet > 0f) {
            stroke.color = colorQuiet
            stroke.strokeWidth = 2f
            stroke.alpha = alpha(quiet * (.55f + .3f * sin(t * 2.2f)))
            rect.set(126f, 20f, 194f, 134f)
            canvas.drawRoundRect(rect, 17f, 17f, stroke)
        }

        drawPhone(canvas, t)

        // Night, then morning, top right.
        val night = seg(t, 2.3f, 2.9f) - seg(t, 5.6f, 6.0f)
        if (night > 0f) {
            fill.color = colorInk
            fill.alpha = alpha(night)
            canvas.drawPath(moon, fill)
            fill.color = colorDim
            fill.alpha = alpha(night)
            canvas.drawCircle(240f, 24f, 1.6f, fill)
            canvas.drawCircle(288f, 62f, 1.3f, fill)
            canvas.drawCircle(248f, 72f, 1.1f, fill)
            fill.alpha = 255
        }
        val morning = seg(t, 5.9f, 6.4f) - seg(t, 7.5f, 7.95f)
        if (morning > 0f) drawSun(canvas, 264f, 38f, morning)

        // The clock on the left, running through the night.
        text.color = colorInk
        text.textSize = 15f
        text.textAlign = Paint.Align.LEFT
        text.alpha = alpha(.25f + .75f * (seg(t, 2.2f, 2.6f) - seg(t, 7.4f, 7.95f)).coerceIn(0f, 1f))
        canvas.drawText(clock(lerp(BED_MIN, WAKE_MIN, ease(seg(t, 2.6f, 5.6f)))), 34f, 84f, text)

        // In the morning, what the night came to.
        val sum = seg(t, 6.9f, 7.2f) - seg(t, 7.6f, 7.95f)
        if (sum > 0f) {
            text.textSize = 14f
            text.alpha = alpha(sum)
            canvas.drawText(summary, 230f, 102f, text)
            smallText.alpha = alpha(sum)
            canvas.drawText(asleep, 230f, 116f, smallText)
        }
        text.alpha = 255
    }

    /**
     * The phone turning about its long axis. Both faces sit [THICKNESS] apart; projected, the one
     * facing up is drawn squeezed by cos, and the band between the outermost edges is its side.
     */
    private fun drawPhone(canvas: Canvas, t: Float) {
        val p = ease(seg(t, 1.0f, 2.1f))
        val q = ease(seg(t, 6.0f, 7.1f))
        val turn = if (q > 0f) 1f - q else p
        val th = (PI * turn).toFloat()
        val c = cos(th)
        val s = sin(th)
        val lift = s
        val half = THICKNESS / 2 * s
        val x0 = min(-HALF_W * c, HALF_W * c) - abs(half)
        val x1 = max(-HALF_W * c, HALF_W * c) + abs(half)
        val w = x1 - x0
        val k = 1f + .07f * lift
        val cy = 72f - 12f * lift

        // The shadow stays on the table: it spreads and fades as the phone rises.
        shadow.alpha = alpha(.22f - .1f * lift)
        rect.set(160f + x0 * k + 3f + 6f * lift, 36f + 10f * lift, 0f, 0f)
        rect.right = rect.left + w * k
        rect.bottom = rect.top + 2 * HALF_H
        canvas.drawRoundRect(rect, 10f, 10f, shadow)

        canvas.save()
        canvas.translate(160f, cy)
        canvas.scale(k, k)

        // The frame: along the lower edge always, and down the side while it turns.
        val r = min(CORNER, w / 2)
        fill.color = colorEdge
        rect.set(x0, -HALF_H + 2.5f * (1f - .4f * lift), x1, HALF_H + 2.5f * (1f - .4f * lift))
        canvas.drawRoundRect(rect, r, r, fill)
        rect.set(x0, -HALF_H, x1, HALF_H)
        canvas.drawRoundRect(rect, r, r, fill)

        canvas.save()
        canvas.translate(if (c > 0f) half else -half, 0f)
        canvas.scale(max(.001f, abs(c)), 1f)
        fill.color = colorInk
        rect.set(-HALF_W, -HALF_H, HALF_W, HALF_H)
        canvas.drawRoundRect(rect, CORNER, CORNER, fill)
        if (c > 0f) {
            // The screen: the time and two notifications.
            text.color = colorOnAwake
            text.alpha = 255
            text.textSize = 12f
            text.textAlign = Paint.Align.CENTER
            canvas.drawText(if (t > 5f) "07:10" else "23:40", 0f, -16f, text)
            fill.color = colorOnAwake
            fill.alpha = 90
            rect.set(-15f, -2f, 15f, 5f)
            canvas.drawRoundRect(rect, 3.5f, 3.5f, fill)
            fill.alpha = 64
            rect.set(-15f, 9f, 7f, 16f)
            canvas.drawRoundRect(rect, 3.5f, 3.5f, fill)
        } else {
            // The back: the camera column.
            fill.color = colorOnAwake
            fill.alpha = 51
            rect.set(-15f, -36f, -4f, -8f)
            canvas.drawRoundRect(rect, 5.5f, 5.5f, fill)
            fill.color = colorInk
            fill.alpha = 255
            canvas.drawCircle(-9.5f, -30.5f, 2.6f, fill)
            canvas.drawCircle(-9.5f, -22f, 2.6f, fill)
            canvas.drawCircle(-9.5f, -13.5f, 2.6f, fill)
        }
        fill.alpha = 255
        canvas.restore()
        canvas.restore()
    }

    private fun drawSun(canvas: Canvas, cx: Float, cy: Float, a: Float) {
        fill.color = colorInk
        fill.alpha = alpha(a)
        canvas.drawCircle(cx, cy, 6.5f, fill)
        fill.alpha = 255
        stroke.color = colorInk
        stroke.strokeWidth = 2f
        stroke.alpha = alpha(a)
        for (i in 0 until 8) {
            val ang = i * PI.toFloat() / 4
            val dx = cos(ang)
            val dy = sin(ang)
            canvas.drawLine(cx + dx * 10f, cy + dy * 10f, cx + dx * 13.5f, cy + dy * 13.5f, stroke)
        }
    }

    private fun seg(t: Float, a: Float, b: Float) = ((t - a) / (b - a)).coerceIn(0f, 1f)
    private fun lerp(a: Float, b: Float, p: Float) = a + (b - a) * p
    private fun ease(x: Float) = if (x < .5f) 4 * x * x * x else 1 - (-2 * x + 2).pow(3) / 2
    private fun alpha(a: Float) = (a.coerceIn(0f, 1f) * 255).roundToInt()

    private fun clock(minutes: Float): String {
        val m = minutes.roundToInt()
        return "%02d:%02d".format((m / 60) % 24, m % 60)
    }
}
