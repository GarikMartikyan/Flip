package app.flipsilence

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/**
 * Nights side by side, each a bar from going to bed down to waking up, so an early night, a lie-in
 * and a run of late ones are visible at a glance. The dashed lines are the average bedtime and
 * wake-up. Tapping a bar picks that night.
 *
 * Times are minutes after six in the evening before each night's morning, which keeps a bedtime
 * of 23:30 and one of 00:30 an hour apart rather than a day.
 */
class SleepChart @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** One column: the night filed under that morning if there is one, and its label. */
    class Slot(val bedMin: Float?, val wakeMin: Float?, val tick: String)

    var onSelect: ((Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val chartHeight = 220f * density
    private val axisWidth = 30f * density
    private val tickGap = 8f * density

    private fun sp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.track)
        strokeWidth = 1f * density
    }
    private val avgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.ink_dim)
        strokeWidth = 1f * density
        pathEffect = DashPathEffect(floatArrayOf(3f * density, 2f * density), 0f)
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.ink_dim) }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.ink) }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.ink)
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.ink_dim)
        typeface = resources.getFont(R.font.plus_jakarta_sans_regular)
        textSize = sp(11f)
    }
    private val selectedLabelPaint = Paint(labelPaint).apply {
        color = context.getColor(R.color.ink)
        typeface = resources.getFont(R.font.plus_jakarta_sans_bold)
    }

    private val rect = RectF()

    private var slots: List<Slot> = emptyList()
    private var selected = -1
    private var avgBed: Float? = null
    private var avgWake: Float? = null
    private var fromMin = DEFAULT_FROM
    private var toMin = DEFAULT_TO

    fun setData(slots: List<Slot>, selected: Int, avgBedMin: Float?, avgWakeMin: Float?) {
        this.slots = slots
        this.selected = selected
        this.avgBed = avgBedMin
        this.avgWake = avgWakeMin
        // Whole hours, and never tighter than 22:00 to 10:00, so a normal week does not fill the
        // chart edge to edge and look extreme.
        val beds = slots.mapNotNull { it.bedMin }
        val wakes = slots.mapNotNull { it.wakeMin }
        fromMin = min(DEFAULT_FROM, floor((beds.minOrNull() ?: DEFAULT_FROM) / 60f) * 60f)
        toMin = maxOf(DEFAULT_TO, ceil((wakes.maxOrNull() ?: DEFAULT_TO) / 60f) * 60f)
        invalidate()
    }

    private fun labelHeight(): Float = labelPaint.fontMetrics.let { it.descent - it.ascent }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wanted = paddingTop + chartHeight + tickGap + labelHeight() + paddingBottom
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(ceil(wanted).toInt(), heightMeasureSpec),
        )
    }

    private fun chartLeft() = paddingLeft + axisWidth
    private fun chartRight() = (width - paddingRight).toFloat()
    private fun slotWidth() = (chartRight() - chartLeft()) / slots.size.coerceAtLeast(1)
    private fun yAt(minutes: Float): Float =
        paddingTop + chartHeight * ((minutes - fromMin) / (toMin - fromMin)).coerceIn(0f, 1f)

    override fun onDraw(canvas: Canvas) {
        val left = chartLeft()
        val right = chartRight()

        // An hour line every three hours, labelled with the clock hour.
        labelPaint.textAlign = Paint.Align.LEFT
        var m = ceil(fromMin / 180f) * 180f
        while (m <= toMin) {
            val y = yAt(m)
            canvas.drawLine(left, y, right, y, gridPaint)
            val hour = ((18 + (m / 60f).toInt()) % 24)
            val fm = labelPaint.fontMetrics
            canvas.drawText("%02d".format(hour), paddingLeft.toFloat(), y - (fm.ascent + fm.descent) / 2f, labelPaint)
            m += 180f
        }

        avgBed?.let { canvas.drawLine(left, yAt(it), right, yAt(it), avgPaint) }
        avgWake?.let { canvas.drawLine(left, yAt(it), right, yAt(it), avgPaint) }

        val slotW = slotWidth()
        val barW = min(slotW * 0.6f, 14f * density)
        val ring = 3f * density
        val baseline = paddingTop + chartHeight + tickGap - labelPaint.fontMetrics.ascent
        slots.forEachIndexed { i, slot ->
            val cx = left + slotW * (i + 0.5f)
            val isSelected = i == selected
            val bed = slot.bedMin
            val wake = slot.wakeMin
            if (bed != null && wake != null) {
                rect.set(cx - barW / 2f, yAt(bed), cx + barW / 2f, yAt(wake))
                val r = barW / 2f
                canvas.drawRoundRect(rect, r, r, if (isSelected) selectedPaint else barPaint)
                if (isSelected) {
                    rect.inset(-ring, -ring)
                    canvas.drawRoundRect(rect, r + ring, r + ring, ringPaint)
                }
            }
            if (slot.tick.isNotEmpty()) {
                val paint = if (isSelected) selectedLabelPaint else labelPaint
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText(slot.tick, cx, baseline, paint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                val index = ((event.x - chartLeft()) / slotWidth()).toInt()
                if (index in slots.indices && slots[index].bedMin != null) {
                    selected = index
                    invalidate()
                    onSelect?.invoke(index)
                }
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    private companion object {
        /** 22:00 and 10:00, as minutes after six in the evening. */
        const val DEFAULT_FROM = 240f
        const val DEFAULT_TO = 960f
    }
}
