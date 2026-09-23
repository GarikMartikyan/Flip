package app.flipsilence

import android.text.SpannableStringBuilder
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import android.view.View
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One night filled into [root], an inflated `activity_night`: how long, when, the strip, a few
 * numbers at a glance, and what happened in order. Kept apart from the activity so the page can be
 * drawn from made-up nights too.
 */
internal class NightPage(private val root: View) {

    private val ctx = root.context

    private fun text(id: Int): TextView = root.findViewById(id)

    fun bind(night: Sleep.Night, history: List<Sleep.Night>) {
        val weekday = SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(night.eveningMs))
        text(R.id.night_title).text = ctx.getString(R.string.night_title, weekday)
        text(R.id.night_dates).text = "${SleepText.weekdayDay(night.eveningMs)} → ${SleepText.weekdayDay(night.morningMs)}"

        text(R.id.total).text = SpannableStringBuilder(SleepText.minutes(ctx, night.asleepMs)).apply {
            append("\u00A0\u00A0")
            val from = length
            append(ctx.getString(R.string.asleep))
            setSpan(AbsoluteSizeSpan(15, true), from, length, 0)
            setSpan(ForegroundColorSpan(ctx.getColor(R.color.ink_dim)), from, length, 0)
            setSpan(TypefaceSpan(ctx.resources.getFont(R.font.plus_jakarta_sans_regular)), from, length, 0)
        }
        text(R.id.compare).apply {
            val words = compare(night, history)
            visibility = if (words == null) View.GONE else View.VISIBLE
            text = words
        }

        text(R.id.bed_time).apply {
            text = SleepText.clock(night.startMs)
            setCompoundDrawablesRelativeWithIntrinsicBounds(tinted(R.drawable.ic_moon), null, null, null)
        }
        text(R.id.wake_time).apply {
            text = SleepText.clock(night.endMs)
            setCompoundDrawablesRelativeWithIntrinsicBounds(tinted(R.drawable.ic_sun), null, null, null)
        }
        val dial = root.findViewById<NightDial>(R.id.dial).apply {
            setNight(night.startMs, night.endMs, night.pieces, night.silenced, night.moments.map { it.span.startMs })
            contentDescription = ctx.getString(R.string.asleep_from_to, SleepText.clock(night.startMs), SleepText.clock(night.endMs))
        }
        text(R.id.laps).visibility = if (dial.laps > 1) View.VISIBLE else View.GONE
        text(R.id.laps).text = ctx.getString(R.string.night_laps)
        root.findViewById<LinearLayout>(R.id.legend).apply {
            removeAllViews()
            addNightLegend(night)
        }

        bindStats(night)
        bindTimeline(night)
    }

    private fun tinted(res: Int) = ctx.getDrawable(res)!!.mutate().apply { setTint(ctx.getColor(R.color.ink_dim)) }

    /** Set against the week before it, when there is one. Within five minutes is the same. */
    private fun compare(night: Sleep.Night, history: List<Sleep.Night>): String? {
        val before = Sleep.within(history, Sleep.at(night.morningMs, -1, 0), 7)
        return SleepText.diffMin(night.asleepMs, before)?.let { SleepText.versus(ctx, it) }
    }

    /**
     * Four answers: how much of it was quiet, whether you got up, how often the screen came on,
     * and the alarms. The last two were not recorded before Flip learned to, so older nights say so.
     */
    private fun bindStats(night: Sleep.Night) {
        val share = if (night.asleepMs > 0) night.silencedMs.toDouble() / night.asleepMs else 0.0
        text(R.id.stat_silenced_value).apply {
            text = SleepText.percent(share)
            setTextColor(ctx.getColor(if (night.silencedMs > 0) R.color.quiet else R.color.ink))
        }
        text(R.id.stat_silenced_caption).text = ctx.getString(
            R.string.stat_silenced_caption,
            SleepText.minutes(ctx, night.silencedMs),
        )

        text(R.id.stat_woke_value).text = SleepText.times(ctx, night.wakeUps)
        text(R.id.stat_woke_caption).text = if (night.wakeUps == 0) ctx.getString(R.string.stat_woke_none)
        else ctx.getString(R.string.stat_woke_caption, SleepText.minutes(ctx, night.awakeMs))

        val recorded = night.moments.isNotEmpty()
        val lit = night.moments.filter { it.kind != Sleep.Moment.ALARM }
        val alarms = night.moments.filter { it.kind == Sleep.Moment.ALARM }
        text(R.id.stat_screen_value).text = if (recorded) SleepText.times(ctx, lit.size) else DASH
        text(R.id.stat_screen_caption).text = when {
            !recorded -> ctx.getString(R.string.stat_not_recorded)
            lit.isEmpty() -> ctx.getString(R.string.stat_screen_none)
            else -> ctx.getString(R.string.stat_screen_caption, SleepText.seconds(ctx, lit.sumOf { it.span.durationMs }))
        }
        text(R.id.stat_alarms_value).text = if (recorded) alarms.size.toString() else DASH
        text(R.id.stat_alarms_caption).text = when {
            !recorded -> ctx.getString(R.string.stat_not_recorded)
            alarms.isEmpty() -> ctx.getString(R.string.stat_alarms_none)
            else -> ctx.getString(R.string.stat_alarms_caption, SleepText.clock(alarms.first().span.startMs))
        }
        evenStatCards()
    }

    /**
     * The four cards made as tall as the tallest once they are laid out, so the two rows read as
     * one grid however their words wrap.
     */
    private fun evenStatCards() {
        val cards = listOf(R.id.stat_silenced_value, R.id.stat_woke_value, R.id.stat_screen_value, R.id.stat_alarms_value)
            .map { root.findViewById<View>(it).parent as View }
        cards.forEach { it.minimumHeight = 0 }
        root.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                root.viewTreeObserver.removeOnPreDrawListener(this)
                val tallest = cards.maxOf { it.height }
                if (cards.all { it.height == tallest }) return true
                cards.forEach { it.minimumHeight = tallest }
                return false
            }
        })
    }

    private class Moment(val atMs: Long, val iconRes: Int, val tone: Tone, val title: String, val detail: String?)

    /**
     * The night as it happened, top to bottom, with the stretches between the moments drawn in
     * what the phone was doing. Silences a moment apart are one silence here, the way [Sleep]
     * joins them into one stretch of sleep, so fidgeting at bedtime does not read as a string of
     * re-silencings.
     */
    private fun bindTimeline(night: Sleep.Night) {
        val moments = ArrayList<Moment>()
        val silences = Sleep.merge(night.silenced, JOIN_MS)
        val lit = if (night.moments.isEmpty()) night.pickups
        else night.moments.filter { it.kind != Sleep.Moment.ALARM }.map { it.span }

        val settled = silences.firstOrNull()?.takeIf { it.startMs - night.startMs <= JOIN_MS }
        moments += Moment(
            night.startMs,
            R.drawable.ic_moon,
            if (settled != null) Tone.QUIET else Tone.INK,
            ctx.getString(R.string.tl_put_down),
            ctx.getString(if (settled != null) R.string.tl_put_down_silenced else R.string.tl_put_down_sound_on),
        )

        silences.forEachIndexed { i, s ->
            if (s === settled) return@forEachIndexed
            val before = silences.getOrNull(i - 1)
            // Sound came back with the screen dark: the phone was moved or turned over, not picked up.
            if (before != null && lit.none { it.startMs < s.startMs && it.endMs > before.endMs }) {
                moments += Moment(
                    before.endMs, R.drawable.ic_moved, Tone.FAINT,
                    ctx.getString(R.string.tl_moved),
                    ctx.getString(R.string.tl_moved_detail, SleepText.minutes(ctx, s.startMs - before.endMs)),
                )
            }
            moments += Moment(
                s.startMs, R.drawable.ic_quiet, Tone.QUIET,
                ctx.getString(if (before == null && settled == null) R.string.tl_silenced else R.string.tl_silenced_again),
                ctx.getString(R.string.tl_silenced_detail),
            )
        }

        if (night.moments.isEmpty()) {
            night.pickups.forEach { p ->
                moments += Moment(
                    p.startMs, R.drawable.ic_phone, Tone.FAINT,
                    ctx.getString(R.string.tl_picked_up),
                    ctx.getString(R.string.tl_for, SleepText.minutes(ctx, p.durationMs)),
                )
            }
        }
        night.moments.forEach { m ->
            val at = m.span.startMs
            moments += when (m.kind) {
                Sleep.Moment.ALARM -> Moment(at, R.drawable.ic_bell, Tone.INK, ctx.getString(R.string.event_alarm), null)
                Sleep.Moment.UNLOCK -> Moment(
                    at, R.drawable.ic_unlock, Tone.FAINT,
                    ctx.getString(R.string.tl_unlocked),
                    ctx.getString(R.string.tl_for, SleepText.seconds(ctx, m.span.durationMs)),
                )
                else -> Moment(
                    at, R.drawable.ic_screen, Tone.FAINT,
                    ctx.getString(R.string.tl_screen_lit),
                    ctx.getString(R.string.tl_screen_lit_detail, SleepText.seconds(ctx, m.span.durationMs)),
                )
            }
        }
        moments += Moment(night.endMs, R.drawable.ic_sun, Tone.INK, ctx.getString(R.string.woke_up), null)

        val sorted = moments.sortedBy { it.atMs }
        val stretches = sorted.zipWithNext { a, b -> between(night, a.atMs, b.atMs) }

        val list = root.findViewById<LinearLayout>(R.id.events)
        list.removeAllViews()
        sorted.forEachIndexed { i, m ->
            list.addTimelineMoment(SleepText.clock(m.atMs), m.iconRes, m.tone, m.title, m.detail)
            val next = sorted.getOrNull(i + 1) ?: return@forEachIndexed
            val gap = next.atMs - m.atMs
            val kind = stretches[i]
            list.addTimelineStretch(kind, if (gap >= LABEL_MS) stretchLabel(kind, gap) else null)
        }
    }

    private fun stretchLabel(kind: Between, ms: Long): String = ctx.getString(
        when (kind) {
            Between.SILENCED -> R.string.tl_stretch_silenced
            Between.SOUND_ON -> R.string.tl_stretch_sound_on
            Between.AWAKE -> R.string.tl_stretch_awake
        },
        SleepText.minutes(ctx, ms),
    )

    /** What most of the time from [fromMs] to [toMs] was: silenced, asleep with sound on, or up. */
    private fun between(night: Sleep.Night, fromMs: Long, toMs: Long): Between {
        val length = (toMs - fromMs).coerceAtLeast(1)
        fun overlap(spans: List<Span>) = spans.sumOf { (minOf(it.endMs, toMs) - maxOf(it.startMs, fromMs)).coerceAtLeast(0) }
        return when {
            overlap(night.pickups) * 2 > length -> Between.AWAKE
            overlap(night.silenced) * 2 > length -> Between.SILENCED
            else -> Between.SOUND_ON
        }
    }

    private companion object {
        /** The same two minutes [Sleep] lets the screen be on without breaking a stretch. */
        const val JOIN_MS = 2 * 60_000L

        /** A stretch this long between two moments is worth saying in words on the rule. */
        const val LABEL_MS = 60_000L

        const val DASH = "—"
    }
}
