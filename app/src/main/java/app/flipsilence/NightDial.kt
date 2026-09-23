package app.flipsilence

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * One night laid round a twelve-hour clock face, the way the hands would have swept it: plain ink
 * while asleep with the sound on, moss while silenced, bare track while up with the phone.
 *
 * A night longer than twelve hours would draw over itself, so each further lap goes on its own
 * ring, one step inward. [laps] says how many there were, for the words beneath.
 *
 * `app:compact` draws it small beside the main screen's words: a thinner ring and no hour numbers.
 */
class NightDial @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val compact = context.obtainStyledAttributes(attrs, R.styleable.NightDial).use {
        it.getBoolean(R.styleable.NightDial_compact, false)
    }

    private val density = resources.displayMetrics.density
    private val wanted = (if (compact) 84f else 168f) * density
    private val stroke = (if (compact) 10f else 12f) * density
    private val lapStep = (if (compact) 13f else 17f) * density

    /** Room outside the outer ring for the hour numbers, or compact, just for the end markers. */
    private val outside = (if (compact) 8f else 22f) * density

    private val trackPaint = ringPaint(R.color.track)
    private val soundPaint = ringPaint(R.color.awake)
    private val quietPaint = ringPaint(R.color.quiet)
    private val hourPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.ink_dim)
        typeface = resources.getFont(R.font.plus_jakarta_sans_regular)
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.surface) }
    private val endFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.surface) }
    private val endRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = context.getColor(R.color.ink)
    }
    private val moon: Drawable = context.getDrawable(R.drawable.ic_moon)!!.mutate().apply { setTint(context.getColor(R.color.ink)) }
    private val sun: Drawable = context.getDrawable(R.drawable.ic_sun)!!.mutate().apply { setTint(context.getColor(R.color.ink)) }

    private val oval = RectF()
    private val calendar = Calendar.getInstance()

    private var startMs = 0L
    private var endMs = 1L
    private var pieces: List<Span> = emptyList()
    private var silences: List<Span> = emptyList()
    private var marks: List<Long> = emptyList()

    /** How many times the night went round the face: 1 up to twelve hours, 2 up to twenty-four. */
    var laps = 1
        private set

    private fun ringPaint(colorRes: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.BUTT
        color = context.getColor(colorRes)
    }

    /** [pieces] asleep, the [silences] within them, and [marks] (a screen lit, an alarm) as dots. */
    fun setNight(startMs: Long, endMs: Long, pieces: List<Span>, silences: List<Span>, marks: List<Long>) {
        this.startMs = startMs
        this.endMs = maxOf(endMs, startMs + 1)
        this.pieces = pieces
        this.silences = silences
        this.marks = marks
        laps = ((this.endMs - startMs + LAP_MS - 1) / LAP_MS).toInt().coerceAtLeast(1)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Beside the times on a narrow screen it gives up width to them rather than squeeze them.
        val available = MeasureSpec.getSize(widthMeasureSpec)
        val size = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) wanted
        else min(wanted, available * 0.58f)
        val side = size.roundToInt()
        setMeasuredDimension(side, side)
    }

    /** The clock-face angle of [ms], in degrees clockwise from twelve. */
    private fun angle(ms: Long): Float {
        calendar.timeInMillis = ms
        val minutes = calendar.get(Calendar.HOUR) * 60 + calendar.get(Calendar.MINUTE) + calendar.get(Calendar.SECOND) / 60f
        return minutes / 720f * 360f
    }

    private fun outerRadius(): Float = min(width, height) / 2f - outside

    /** The ring [ms] falls on: each twelve hours since the night began, one step in. */
    private fun radius(ms: Long): Float {
        val lap = ((ms - startMs) / LAP_MS).toInt().coerceIn(0, laps - 1)
        return (outerRadius() - lap * lapStep).coerceAtLeast(stroke)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r0 = outerRadius()

        for (lap in 0 until laps) {
            canvas.drawCircle(cx, cy, (r0 - lap * lapStep).coerceAtLeast(stroke), trackPaint)
        }
        pieces.forEach { drawSpan(canvas, it, soundPaint, cx, cy) }
        silences.forEach { drawSpan(canvas, it, quietPaint, cx, cy) }

        // Every hour, outside the rings so the laps inside keep clear of them.
        if (!compact) {
            val labelR = r0 + stroke / 2f + 9f * density
            val lift = -(hourPaint.fontMetrics.ascent + hourPaint.fontMetrics.descent) / 2f
            for (hour in 0 until 12) {
                val a = Math.toRadians(hour * 30.0 - 90.0)
                val label = if (hour == 0) "12" else hour.toString()
                canvas.drawText(label, cx + labelR * cos(a).toFloat(), cy + labelR * sin(a).toFloat() + lift, hourPaint)
            }
        }

        for (ms in marks) {
            if (ms !in startMs..endMs) continue
            point(ms, radius(ms), cx, cy) { x, y -> canvas.drawCircle(x, y, 2.6f * density, dotPaint) }
        }
        drawEnd(canvas, startMs, moon, cx, cy)
        drawEnd(canvas, endMs - 1, sun, cx, cy)
    }

    private inline fun point(ms: Long, r: Float, cx: Float, cy: Float, draw: (Float, Float) -> Unit) {
        val a = Math.toRadians(angle(ms) - 90.0)
        draw(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat())
    }

    private fun drawEnd(canvas: Canvas, ms: Long, icon: Drawable, cx: Float, cy: Float) {
        point(ms, radius(ms), cx, cy) { x, y ->
            val r = 7f * density
            canvas.drawCircle(x, y, r, endFill)
            canvas.drawCircle(x, y, r - endRing.strokeWidth / 2f, endRing)
            val half = (5f * density).roundToInt()
            icon.setBounds(x.roundToInt() - half, y.roundToInt() - half, x.roundToInt() + half, y.roundToInt() + half)
            icon.draw(canvas)
        }
    }

    /** One stretch, cut where it crosses from one lap to the next so each part sits on its own ring. */
    private fun drawSpan(canvas: Canvas, span: Span, paint: Paint, cx: Float, cy: Float) {
        var from = maxOf(span.startMs, startMs)
        val to = minOf(span.endMs, endMs)
        while (from < to) {
            val lapEnd = startMs + ((from - startMs) / LAP_MS + 1) * LAP_MS
            val until = minOf(to, lapEnd)
            val r = radius(from)
            // A two-minute silence is a hairline at this scale; keep it visible.
            val sweep = maxOf((until - from) / LAP_MS.toFloat() * 360f, MIN_SWEEP)
            oval.set(cx - r, cy - r, cx + r, cy + r)
            canvas.drawArc(oval, angle(from) - 90f, sweep, false, paint)
            from = until
        }
    }

    private companion object {
        const val LAP_MS = 12 * 60 * 60_000L
        const val MIN_SWEEP = 2f
    }
}
