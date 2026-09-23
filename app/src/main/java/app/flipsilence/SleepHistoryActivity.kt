package app.flipsilence

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Calendar
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Every night on record, a week or a month at a time: the averages first, then when each night
 * began and ended side by side, then the nights themselves, each opening onto its own page.
 */
class SleepHistoryActivity : Activity() {

    private lateinit var segments: Map<Int, TextView>
    private lateinit var empty: View
    private lateinit var body: View
    private lateinit var chart: SleepChart
    private lateinit var pickedDay: TextView
    private lateinit var pickedDetail: TextView
    private lateinit var nightsList: LinearLayout

    private var range = 7
    private var nights: List<Sleep.Night> = emptyList()

    /** The morning of the night picked on the chart; none picked means the latest. */
    private var pickedMorning: Long? = null

    /** What each column of the chart is, so a tap on it can be turned back into a night. */
    private var slotNights: List<Sleep.Night?> = emptyList()

    private val density by lazy { resources.displayMetrics.density }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sleep_history)
        applySystemBarInsets(findViewById(R.id.scroll), findViewById(R.id.content))

        range = savedInstanceState?.getInt(STATE_RANGE, 7) ?: 7
        pickedMorning = savedInstanceState?.getLong(STATE_PICKED, 0L)?.takeIf { it != 0L }

        empty = findViewById(R.id.empty)
        body = findViewById(R.id.body)
        chart = findViewById(R.id.chart)
        pickedDay = findViewById(R.id.picked_day)
        pickedDetail = findViewById(R.id.picked_detail)
        nightsList = findViewById(R.id.nights)

        findViewById<View>(R.id.back).setOnClickListener { finish() }

        segments = mapOf(7 to findViewById(R.id.range_7), 30 to findViewById(R.id.range_30))
        segments.forEach { (days, segment) ->
            segment.setOnClickListener {
                range = days
                render()
            }
        }

        chart.onSelect = { index ->
            slotNights.getOrNull(index)?.let {
                pickedMorning = it.morningMs
                renderPicked(it)
            }
        }
        findViewById<View>(R.id.picked_row).setOnClickListener {
            val morning = pickedMorning ?: slotNights.lastOrNull { it != null }?.morningMs
            morning?.let { openNight(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        nights = Sleep.history(this, System.currentTimeMillis())
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_RANGE, range)
        pickedMorning?.let { outState.putLong(STATE_PICKED, it) }
    }

    private fun openNight(morningMs: Long) {
        startActivity(Intent(this, NightActivity::class.java).putExtra(NightActivity.EXTRA_MORNING, morningMs))
    }

    private fun render() {
        renderSegments()

        val today = Sleep.at(System.currentTimeMillis(), 0, 0)
        val mornings = (range - 1 downTo 0).map { Sleep.at(today, -it, 0) }
        val byMorning = Sleep.within(nights, today, range).associateBy { it.morningMs }
        val inRange = mornings.mapNotNull { byMorning[it] }

        if (inRange.isEmpty()) {
            empty.visibility = View.VISIBLE
            body.visibility = View.GONE
            return
        }
        empty.visibility = View.GONE
        body.visibility = View.VISIBLE

        renderAverages(inRange)

        slotNights = mornings.map { byMorning[it] }
        val last = mornings.lastIndex
        val slots = mornings.mapIndexed { i, morning ->
            val night = byMorning[morning]
            val tick = when {
                range <= 7 -> SleepText.weekday(morning).take(2)
                (last - i) % 7 == 0 -> Calendar.getInstance().apply { timeInMillis = morning }
                    .get(Calendar.DAY_OF_MONTH).toString()
                else -> ""
            }
            SleepChart.Slot(night?.bedMin, night?.wakeMin, tick)
        }
        val picked = inRange.firstOrNull { it.morningMs == pickedMorning } ?: inRange.last()
        chart.setData(
            slots,
            slotNights.indexOf(picked),
            inRange.map { it.bedMin }.average().toFloat(),
            inRange.map { it.wakeMin }.average().toFloat(),
        )
        chart.contentDescription = resources.getQuantityString(R.plurals.chart_description, range, range)
        renderPicked(picked)

        renderList(inRange.reversed())
    }

    private fun renderSegments() {
        segments.forEach { (days, segment) ->
            val selected = days == range
            // Lifted out of the track, as on the settings page: ink is kept for sound.
            segment.background = if (selected) pill(getColor(R.color.segment_selected)) else pill(null)
            segment.elevation = if (selected) 1f * density else 0f
            segment.isSelected = selected
            segment.setTextColor(getColor(if (selected) R.color.ink else R.color.ink_dim))
        }
    }

    private fun renderAverages(inRange: List<Sleep.Night>) {
        val asleep = inRange.map { it.asleepMs }
        findViewById<TextView>(R.id.avg_total).text = SleepText.minutes(this, asleep.average().toLong())
        findViewById<TextView>(R.id.avg_spread).text =
            if (inRange.size == 1) getString(R.string.one_night_so_far)
            else getString(R.string.shortest_longest, SleepText.minutes(this, asleep.min()), SleepText.minutes(this, asleep.max()))

        val evening = inRange.last().eveningMs
        fun clockAt(minutes: Double) = SleepText.clock(evening + (minutes * 60_000).toLong())
        val beds = inRange.map { it.bedMin.toDouble() }
        val wakes = inRange.map { it.wakeMin.toDouble() }
        findViewById<TextView>(R.id.avg_bed).text = clockAt(beds.average())
        findViewById<TextView>(R.id.avg_wake).text = clockAt(wakes.average())
        findViewById<TextView>(R.id.avg_bed_range).text = spread(beds.min(), beds.max(), ::clockAt)
        findViewById<TextView>(R.id.avg_wake_range).text = spread(wakes.min(), wakes.max(), ::clockAt)

        val share = inRange.map { if (it.asleepMs > 0) it.silencedMs.toDouble() / it.asleepMs else 0.0 }.average()
        findViewById<TextView>(R.id.avg_silenced).text = SleepText.percent(share)
        findViewById<TextView>(R.id.avg_pickups).text =
            String.format(Locale.getDefault(), "%.1f", inRange.map { it.wakeUps }.average())
    }

    private fun spread(min: Double, max: Double, clockAt: (Double) -> String): String =
        if (max - min < 1.0) getString(R.string.every_night) else getString(R.string.between_times, clockAt(min), clockAt(max))

    private fun renderPicked(night: Sleep.Night) {
        pickedDay.text = "${SleepText.weekday(night.morningMs)} ${SleepText.dayMonth(night.morningMs)}"
        pickedDetail.text = "${SleepText.clock(night.startMs)} → ${SleepText.clock(night.endMs)} · " +
            SleepText.minutes(this, night.asleepMs)
        findViewById<View>(R.id.picked_row).contentDescription =
            getString(R.string.open_this_night, pickedDay.text, pickedDetail.text)
    }

    /**
     * Newest first. Every row's strip covers the same hours of the evening and morning, so the
     * bars line up down the list the way the chart's do across it.
     */
    private fun renderList(newestFirst: List<Sleep.Night>) {
        nightsList.removeAllViews()
        val fromMin = floor(newestFirst.minOf { it.bedMin } / 60f) * 60f
        val toMin = ceil(newestFirst.maxOf { it.wakeMin } / 60f) * 60f
        val background = TypedValue().also {
            theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }.resourceId

        newestFirst.forEachIndexed { i, night ->
            if (i > 0) {
                nightsList.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1f))
                    setBackgroundColor(getColor(R.color.border))
                })
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(64f)
                val pad = resources.getDimensionPixelSize(R.dimen.card_padding)
                setPadding(pad, dp(10f), pad, dp(10f))
                setBackgroundResource(background)
                isClickable = true
                isFocusable = true
                setOnClickListener { openNight(night.morningMs) }
            }

            val day = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(dp(58f), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            day.addView(text(SleepText.weekday(night.morningMs), 15f, R.color.ink, R.font.plus_jakarta_sans_medium))
            day.addView(text(SleepText.dayMonth(night.morningMs), 12f, R.color.ink_dim, R.font.plus_jakarta_sans_regular))
            row.addView(day)

            val middle = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val range = "${SleepText.clock(night.startMs)} → ${SleepText.clock(night.endMs)}"
            middle.addView(text(range, 14f, R.color.ink_dim, R.font.plus_jakarta_sans_regular))
            middle.addView(DayStrip(this).apply {
                barHeight = 6f * density
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(6f) }
                setRange(
                    night.eveningMs + (fromMin * 60_000).toLong(),
                    night.eveningMs + (toMin * 60_000).toLong(),
                    nowMs = 0L,
                    labels = emptyList(),
                    silences = night.silenced,
                    spans = night.pieces,
                )
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            row.addView(middle)

            row.addView(text(SleepText.minutes(this, night.asleepMs), 15f, R.color.ink, R.font.plus_jakarta_sans_medium).apply {
                layoutParams = LinearLayout.LayoutParams(dp(72f), ViewGroup.LayoutParams.WRAP_CONTENT)
                gravity = Gravity.END
            })
            row.addView(ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(18f), dp(18f)).apply { marginStart = dp(6f) }
                setImageResource(R.drawable.ic_chevron_right)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            row.contentDescription = getString(
                R.string.night_row_description,
                "${SleepText.weekday(night.morningMs)} ${SleepText.dayMonth(night.morningMs)}",
                range,
                SleepText.minutes(this, night.asleepMs),
            )
            nightsList.addView(row)
        }
    }

    private fun text(value: String, sizeSp: Float, colorRes: Int, fontRes: Int) = TextView(this).apply {
        text = value
        textSize = sizeSp
        setTextColor(getColor(colorRes))
        typeface = resources.getFont(fontRes)
        maxLines = 1
    }


    private companion object {
        const val STATE_RANGE = "range"
        const val STATE_PICKED = "picked"
    }
}
