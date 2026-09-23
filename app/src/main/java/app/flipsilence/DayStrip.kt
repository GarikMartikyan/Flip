package app.flipsilence

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import kotlin.math.ceil
import kotlin.math.max

/**
 * A stretch of the clock as one bar -- today from midnight to midnight, or last night from six in
 * the evening to noon -- with each silence laid on it where it happened.
 *
 * The list below it says how long; this says when, which is the part a list of times makes you
 * work out for yourself. Everything is proportional to the view's width, so it reads the same on a
 * cover screen as on a tablet.
 */
class DayStrip @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    /** Thicker on a screen about one night than in a card that shares the page. */
    var barHeight = 12f * density
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }
    private val labelGap = 8f * density
    private val nowGap = 6f * density

    /** A two-minute silence is a hairline at this scale; keep it visible. */
    private val minSegment = 3f * density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.track) }
    private val spanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.awake) }
    private val silencePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.quiet) }
    private val nowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.ink_dim)
        strokeWidth = 1.5f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val hatchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.border)
        strokeWidth = 1.5f * density
    }
    private val nowLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.ink)
        typeface = resources.getFont(R.font.plus_jakarta_sans_bold)
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.ink_dim)
        typeface = resources.getFont(R.font.plus_jakarta_sans_regular)
        // In sp, so it follows the system font size like the text around it.
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
    }

    private val rect = RectF()
    private val clip = Path()

    private var dayStartMs = 0L
    private var dayEndMs = 1L
    private var nowMs = 0L
    private var labels: List<String> = emptyList()
    private var silences: List<Span> = emptyList()
    private var spans: List<Span> = emptyList()
    private var hatch: Span? = null
    private var nowLabel: String? = null

    /**
     * [labels] are spread evenly from end to end. [spans] are drawn in plain ink, the colour of sound
     * still getting through, and the [silences] in moss on top of them. [hatch] is struck through
     * lightly, for a part of the range that belongs to something else (today's share of last
     * night). [nowLabel], if given, is written above the line marking now.
     */
    fun setRange(
        startMs: Long,
        endMs: Long,
        nowMs: Long,
        labels: List<String>,
        silences: List<Span>,
        spans: List<Span> = emptyList(),
        hatch: Span? = null,
        nowLabel: String? = null,
    ) {
        this.dayStartMs = startMs
        this.dayEndMs = max(endMs, startMs + 1)
        this.nowMs = nowMs
        this.labels = labels
        this.silences = silences
        this.spans = spans
        this.hatch = hatch
        this.nowLabel = nowLabel
        // The height depends on whether there are labels below and a "now" above.
        requestLayout()
        invalidate()
    }

    private fun labelHeight(): Float = labelPaint.fontMetrics.let { it.descent - it.ascent }

    /** Room above the bar for the word "now", when there is one to write. */
    private fun nowHeight(): Float =
        if (nowLabel == null) 0f else nowLabelPaint.fontMetrics.let { it.descent - it.ascent } + nowGap

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val below = if (labels.isEmpty()) 0f else labelGap + labelHeight()
        val wanted = paddingTop + nowHeight() + barHeight + below + paddingBottom
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(ceil(wanted).toInt(), heightMeasureSpec),
        )
    }

    private fun xAt(ms: Long, left: Float, width: Float): Float =
        left + width * ((ms - dayStartMs).toFloat() / (dayEndMs - dayStartMs)).coerceIn(0f, 1f)

    override fun onDraw(canvas: Canvas) {
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val w = right - left
        val top = paddingTop + nowHeight()
        val radius = barHeight / 2f

        rect.set(left, top, right, top + barHeight)
        canvas.drawRoundRect(rect, radius, radius, trackPaint)

        hatch?.let { drawHatch(canvas, it, left, right, top) }
        drawSegments(canvas, spans, spanPaint, left, right, top)
        drawSegments(canvas, silences, silencePaint, left, right, top)

        if (nowMs in dayStartMs..dayEndMs) {
            val x = xAt(nowMs, left, w)
            canvas.drawLine(x, top - 3f * density, x, top + barHeight + 3f * density, nowPaint)
            nowLabel?.let { label ->
                val half = nowLabelPaint.measureText(label) / 2f
                val cx = x.coerceIn(left + half, right - half)
                canvas.drawText(label, cx, top - nowGap - nowLabelPaint.fontMetrics.descent, nowLabelPaint)
            }
        }

        // A time axis reads left to right even in right-to-left locales.
        val baseline = top + barHeight + labelGap - labelPaint.fontMetrics.ascent
        labels.forEachIndexed { i, label ->
            labelPaint.textAlign = when (i) {
                0 -> Paint.Align.LEFT
                labels.lastIndex -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            canvas.drawText(label, left + w * i / labels.lastIndex.coerceAtLeast(1), baseline, labelPaint)
        }
    }

    private fun drawHatch(canvas: Canvas, span: Span, left: Float, right: Float, top: Float) {
        if (span.endMs <= dayStartMs || span.startMs >= dayEndMs) return
        val w = right - left
        val x0 = xAt(span.startMs, left, w)
        val x1 = xAt(span.endMs, left, w)
        if (x1 <= x0) return
        val radius = barHeight / 2f
        rect.set(x0, top, x1, top + barHeight)
        clip.reset()
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        val step = 5f * density
        var x = x0 - barHeight
        while (x < x1) {
            canvas.drawLine(x, top + barHeight, x + barHeight, top, hatchPaint)
            x += step
        }
        canvas.restore()
    }

    private fun drawSegments(canvas: Canvas, segments: List<Span>, paint: Paint, left: Float, right: Float, top: Float) {
        val w = right - left
        val radius = barHeight / 2f
        for (s in segments) {
            if (s.endMs <= dayStartMs || s.startMs >= dayEndMs) continue
            var x0 = xAt(s.startMs, left, w)
            var x1 = xAt(s.endMs, left, w)
            if (x1 - x0 < minSegment) {
                val mid = (x0 + x1) / 2f
                x0 = (mid - minSegment / 2f).coerceAtLeast(left)
                x1 = (x0 + minSegment).coerceAtMost(right)
            }
            rect.set(x0, top, x1, top + barHeight)
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }
}
