package app.flipsilence

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The one thing this screen is remembered by.
 *
 * You never actually see this app do its job -- the screen is face down when it fires. So the gauge
 * exists to answer "will it work when I am not looking": it is driven by the real accelerometer, so
 * tilting the phone moves it.
 *
 * Drawn as the phone itself, seen from the side, above a desk line: it turns with the real phone and
 * settles onto the desk as it nears face down, camera bump up. A dashed outline marks where it has to
 * land, and turns solid once the phone is inside the engage angle. While it holds still, the desk line
 * inks in from the middle; once silenced, the phone turns moss. The angle itself is only a small
 * caption under the desk.
 *
 * Sized from its width. Every stroke and every piece of type is a fraction of the width, so the gauge
 * can shrink to fit a cover screen or a phone held sideways.
 */
class TiltGauge @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private companion object {
        /** Largest the gauge gets, however much room there is. */
        const val MAX_SIDE_DP = 300f

        /** Never more than this share of the screen's height, so a sideways phone still shows the controls. */
        const val MAX_SCREEN_HEIGHT_SHARE = 0.5f

        /** Roughly how tall the gauge is for its width, caption included; used to honour the height cap. */
        const val HEIGHT_PER_WIDTH = 0.8f

        /**
         * How much of each new reading the gauge takes in. Low, so the hand tremor and sensor noise in
         * a 10 Hz stream average out instead of shaking the drawing.
         */
        const val INPUT_SMOOTHING = 0.3f

        /** Time constant of the glide toward the smoothed reading, in seconds. Frame-rate independent. */
        const val GLIDE_SECONDS = 0.18f

        /** The caption only moves once the angle has moved a whole degree past what it shows. */
        const val CAPTION_HYSTERESIS_DEG = 1f

        /** Within this many degrees of face down the phone is drawn lying on the desk. */
        const val RESTING_DEG = 2f
    }

    private val phonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val deskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val heldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val hatchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = resources.getFont(R.font.plus_jakarta_sans_medium)
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.04f
    }

    private val rect = RectF()
    private val density = resources.displayMetrics.density

    /** The ghost's dashes while it is still out of range; made once, not on every frame. */
    private val ghostDash = DashPathEffect(floatArrayOf(4f * density, 4f * density), 0f)

    private val colorAwake = context.getColor(R.color.awake)
    private val colorQuiet = context.getColor(R.color.quiet)
    private val colorInk = context.getColor(R.color.ink)
    private val colorDim = context.getColor(R.color.ink_dim)
    private val colorBorder = context.getColor(R.color.border)
    private val silencedText = context.getString(R.string.silenced)

    /** Degrees away from perfectly face down. 0 = face down, 180 = face up. */
    private var targetTilt = 180f
    private var shownTilt = 180f
    private var heldFraction = 0f
    private var engaged = false
    private var thresholdTilt = 23.4f
    private var sinceText: String? = null
    private var hasReading = false
    private var lastFrameNanos = 0L
    private var captionDeg = 180

    /** The caption follows the system font size, like every other piece of text on the screen. */
    private val minLabel =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)

    private class Geometry(
        val length: Float,
        val thickness: Float,
        val bumpWidth: Float,
        val bumpHeight: Float,
        val lift: Float,
        val deskY: Float,
        val hatch: Float,
        val labelSize: Float,
        val height: Float,
    )

    /** Everything is derived from the width, so measuring and drawing cannot disagree. */
    private fun geometry(side: Float): Geometry {
        val length = side * 0.56f
        val thickness = length * 0.1f
        val bumpHeight = thickness * 0.38f
        val lift = side * 0.1f
        // Room above the desk for the phone standing on end at 90 degrees, lifted, plus a margin.
        val deskY = length + lift + thickness + 4f * density
        val hatch = side * 0.035f
        // A very large system font must not blow the caption up past the drawing it annotates.
        val labelSize = max(minLabel, side * 0.04f).coerceAtMost(side * 0.06f)
        val height = deskY + hatch + labelSize * 2.4f
        return Geometry(length, thickness, length * 0.16f, bumpHeight, lift, deskY, hatch, labelSize, height)
    }

    init {
        hatchPaint.color = colorBorder
        labelPaint.color = colorDim
        deskPaint.color = colorDim
        heldPaint.color = colorInk
    }

    /**
     * @param tiltDeg degrees away from face down
     * @param heldFraction how far through the hold-still debounce, 0..1
     * @param thresholdDeg the angle at which engagement happens; inside it the landing outline turns solid
     * @param since clock time the current silence began, shown under the desk
     */
    fun setState(
        tiltDeg: Float,
        heldFraction: Float,
        engaged: Boolean,
        thresholdDeg: Float,
        since: String? = null,
    ) {
        // The first reading is shown as it is; easing is for movement, not for opening the screen.
        if (!hasReading) {
            targetTilt = tiltDeg
            shownTilt = tiltDeg
            captionDeg = tiltDeg.coerceIn(0f, 180f).roundToInt()
            hasReading = true
        } else {
            targetTilt += (tiltDeg - targetTilt) * INPUT_SMOOTHING
        }
        this.heldFraction = heldFraction
        this.engaged = engaged
        this.thresholdTilt = thresholdDeg
        this.sinceText = since
        // Readings arrive at ~10 Hz; only touch the description when what it says changes.
        val description =
            if (engaged) listOfNotNull(silencedText, since?.let { context.getString(R.string.gauge_since, it) }).joinToString(" ")
            else context.getString(R.string.gauge_description, max(0f, min(180f, tiltDeg)).roundToInt())
        if (description != contentDescription) contentDescription = description
        postInvalidateOnAnimation()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val screenHeightPx = resources.configuration.screenHeightDp * density
        val cap = min(MAX_SIDE_DP * density, screenHeightPx * MAX_SCREEN_HEIGHT_SHARE / HEIGHT_PER_WIDTH)
        val available = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.UNSPECIFIED -> cap
            else -> MeasureSpec.getSize(widthMeasureSpec).toFloat()
        }
        val side = min(available, cap)
        setMeasuredDimension(side.roundToInt(), ceil(geometry(side).height).toInt())
    }

    /**
     * How far below its centre the turned phone reaches, bump included -- so it can be set down on
     * the desk at any angle without sinking into it. Face up, that lowest point is the camera bump.
     */
    private fun reachBelowCentre(g: Geometry, rotationDeg: Float): Float {
        val rad = Math.toRadians(rotationDeg.toDouble())
        val s = sin(rad).toFloat()
        val c = cos(rad).toFloat()
        val halfL = g.length / 2f
        val halfT = g.thickness / 2f
        val bumpStart = halfL * 0.42f
        val bumpEnd = bumpStart + g.bumpWidth
        var lowest = 0f
        fun point(x: Float, y: Float) {
            lowest = max(lowest, x * s + y * c)
        }
        point(-halfL, -halfT); point(halfL, -halfT); point(-halfL, halfT); point(halfL, halfT)
        point(bumpStart, -halfT - g.bumpHeight); point(bumpEnd, -halfT - g.bumpHeight)
        return lowest
    }

    /** The phone in its own frame: screen side down (+y), camera bump on its back (-y), centred on 0,0. */
    private fun drawPhone(canvas: Canvas, g: Geometry, paint: Paint) {
        val halfL = g.length / 2f
        val halfT = g.thickness / 2f
        val corner = g.thickness * 0.4f
        rect.set(-halfL, -halfT, halfL, halfT)
        canvas.drawRoundRect(rect, corner, corner, paint)
        // Only the body is drawn when outlining where the phone should land.
        if (paint.style == Paint.Style.FILL) {
            val bumpStart = halfL * 0.42f
            rect.set(bumpStart, -halfT - g.bumpHeight, bumpStart + g.bumpWidth, -halfT + corner)
            canvas.drawRoundRect(rect, g.bumpHeight * 0.6f, g.bumpHeight * 0.6f, paint)
        }
    }

    override fun onDraw(canvas: Canvas) {
        // Glide toward the reading so a 10 Hz sensor does not look like a 10 Hz gauge. Driven by the
        // time since the last frame, so a 120 Hz screen glides as slowly as a 60 Hz one.
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 0f else ((now - lastFrameNanos) / 1e9f).coerceAtMost(0.1f)
        val delta = targetTilt - shownTilt
        if (abs(delta) > 0.05f) {
            shownTilt += delta * (1f - exp(-dt / GLIDE_SECONDS))
            lastFrameNanos = now
            postInvalidateOnAnimation()
        } else {
            shownTilt = targetTilt
            lastFrameNanos = 0L
        }
        val shownDeg = shownTilt.coerceIn(0f, 180f)
        if (abs(shownDeg - captionDeg) >= CAPTION_HYSTERESIS_DEG) captionDeg = shownDeg.roundToInt()

        val side = width.toFloat()
        val g = geometry(side)
        val cx = side / 2f
        val tilt = if (engaged) 0f else shownTilt.coerceIn(0f, 180f)

        deskPaint.strokeWidth = max(1.5f * density, side * 0.005f)
        heldPaint.strokeWidth = max(3f * density, side * 0.012f)
        hatchPaint.strokeWidth = max(1f * density, side * 0.005f)
        ghostPaint.strokeWidth = max(1.5f * density, side * 0.005f)

        // Desk: a hairline with a strip of short hatching under it, drawn only as wide as the phone
        // needs, so it reads as a surface rather than a divider.
        val deskHalf = g.length * 0.75f
        val deskTop = g.deskY
        canvas.drawLine(cx - deskHalf, deskTop, cx + deskHalf, deskTop, deskPaint)
        val step = g.hatch * 1.4f
        var x = cx - deskHalf + step
        while (x <= cx + deskHalf) {
            canvas.drawLine(x, deskTop + g.hatch * 0.35f, x - g.hatch * 0.6f, deskTop + g.hatch, hatchPaint)
            x += step
        }

        // The hold-still countdown inks the desk in from under the phone's middle.
        if (heldFraction > 0f && !engaged) {
            val half = deskHalf * heldFraction.coerceIn(0f, 1f)
            canvas.drawLine(cx - half, deskTop, cx + half, deskTop, heldPaint)
        }

        val restY = deskTop - deskPaint.strokeWidth / 2f - g.thickness / 2f

        // Where it has to land: dashed while still out of range, solid once inside the engage angle.
        if (!engaged && tilt > RESTING_DEG) {
            val inRange = tilt <= thresholdTilt
            ghostPaint.color = if (inRange) colorInk else colorDim
            ghostPaint.pathEffect =
                if (inRange) null else ghostDash
            canvas.save()
            canvas.translate(cx, restY)
            drawPhone(canvas, g, ghostPaint)
            canvas.restore()
        }

        // The phone turns with the real one -- face up is upside down, bump underneath -- and floats
        // higher the further it is from face down, up to a quarter turn.
        val rotation = -tilt
        val gap = if (tilt <= RESTING_DEG) 0f else g.lift * min(tilt, 90f) / 90f
        val centreY = deskTop - deskPaint.strokeWidth / 2f - reachBelowCentre(g, rotation) - gap
        phonePaint.color = if (engaged) colorQuiet else colorAwake
        canvas.save()
        canvas.translate(cx, centreY)
        canvas.rotate(rotation)
        drawPhone(canvas, g, phonePaint)
        canvas.restore()

        // The angle survives only as a small caption under the desk; once silenced, when it began.
        labelPaint.textSize = g.labelSize
        val caption =
            if (engaged) sinceText?.let { context.getString(R.string.gauge_since, it) }
            else "$captionDeg°"
        if (caption != null) {
            canvas.drawText(caption, cx, deskTop + g.hatch + g.labelSize * 1.6f, labelPaint)
        }
    }
}
