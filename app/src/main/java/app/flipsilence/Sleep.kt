package app.flipsilence

import android.content.Context
import org.json.JSONArray
import java.util.Calendar
import kotlin.math.abs

/**
 * Last night, read off how long the phone was left alone.
 *
 * Nothing here measures sleep. What Flip can see is the screen being dark and the phone lying face
 * down, and a night's sleep is the longest stretch of either that sits across midnight. It takes
 * both because neither is enough alone: the screen going dark is exact to the second, while on a
 * phone whose accelerometer cannot wake the CPU a face-down silence can begin hours after the phone
 * was actually put down; and a phone set face down with its screen still on is already asleep for
 * Flip's purposes, but not dark yet.
 *
 * The thresholds come from real nights on the device: overnight the screen stays dark for hours at
 * a time, broken only by an alarm or a glance lasting a few seconds, while an evening of use comes
 * apart into dark stretches of a few minutes.
 */
object Sleep {

    /** Screen-off stretches shorter than this are the gaps between uses, not rest. */
    private const val MIN_REST_MS = 60_000L

    /**
     * The screen being on for no longer than this does not break a stretch: checking the time,
     * snoozing an alarm, a notification lighting it up.
     */
    private const val BLIP_MS = 2 * 60_000L

    /**
     * A stretch shorter than this is not sleep, and is not joined onto a night either -- that is
     * what stops an evening with the phone on the charger from being counted as an early night.
     */
    private const val MIN_STRETCH_MS = 2 * 3_600_000L

    /** Up in the night for longer than this, and the two halves are not one night. */
    private const val MAX_WAKE_MS = 3_600_000L

    /**
     * How long raw stretches and moments are kept. [archive] re-reads windows opening as much as
     * three days and six hours back, so this has to outlast that: a night re-read from stretches
     * already dropped would come back cut short and overwrite the whole one on file.
     */
    private const val KEEP_MS = 4 * 24 * 3_600_000L
    private const val MAX_RESTS = 1_000

    private const val KEY = "rests"

    /** Unlocks and alarms going off, as [Moment]s kept with their start and end. */
    private const val MOMENTS_KEY = "moments"

    /** A screen-on within this of an alarm going off is the alarm lighting it, not a glance. */
    private const val ALARM_SLACK_MS = 60_000L

    /** Alarms are looked for this far past the end of a night: the one that ended it rings then. */
    private const val ALARM_AFTER_MS = 15 * 60_000L

    /** Finished nights outlive the raw stretches they were read from, so history can go back. */
    private const val NIGHTS_KEY = "nights"
    private const val MAX_NIGHTS = 120

    /**
     * Something that happened in the night: the screen lit up without being unlocked, the phone
     * was unlocked, or an alarm went off. Alarms have no length; the others last as long as the
     * screen stayed on.
     */
    class Moment(val span: Span, val kind: Int) {
        companion object {
            const val GLANCE = 0
            const val UNLOCK = 1
            const val ALARM = 2
        }
    }

    class Night(
        /** The stretches slept, in order; each gap between two of them is a wake-up. */
        val pieces: List<Span>,
        /** The parts of [pieces] that the phone spent silenced, face down. */
        val silenced: List<Span>,
        /** Every time the screen came on or an alarm rang, in order. Empty for nights before them. */
        val moments: List<Moment> = emptyList(),
    ) {
        val startMs: Long get() = pieces.first().startMs
        val endMs: Long get() = pieces.last().endMs
        val asleepMs: Long get() = pieces.sumOf { it.durationMs }
        val awakeMs: Long get() = endMs - startMs - asleepMs
        val wakeUps: Int get() = pieces.size - 1
        val silencedMs: Long get() = silenced.sumOf { it.durationMs }

        /** The times the phone was picked up in the night: the gaps between [pieces]. */
        val pickups: List<Span> get() = pieces.zipWithNext { a, b -> Span(a.endMs, b.startMs) }

        /** Midnight on the morning this night ended, which is the day it is filed under. */
        val morningMs: Long get() = at(endMs, 0, 0)

        /** Six in the evening before [morningMs]: bed and wake times are measured from here. */
        val eveningMs: Long get() = at(endMs, -1, 18)

        /** Bedtime and wake-up as minutes after [eveningMs], so they average across midnight. */
        val bedMin: Float get() = (startMs - eveningMs) / 60_000f
        val wakeMin: Float get() = (endMs - eveningMs) / 60_000f
    }

    /** The nights filed under the [days] mornings up to and including [lastMorningMs]. */
    fun within(nights: List<Night>, lastMorningMs: Long, days: Int): List<Night> {
        val first = at(lastMorningMs, -(days - 1), 0)
        return nights.filter { it.morningMs in first..lastMorningMs }
    }

    /** A night's worth of clock, six in the evening to noon, and the sleep found in it if any. */
    class LastNight(val window: Span, val night: Night?)

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("flip", Context.MODE_PRIVATE)

    /** Records the screen having been off from [startMs] for [durationMs]. */
    fun addRest(ctx: Context, startMs: Long, durationMs: Long) {
        if (durationMs < MIN_REST_MS) return
        putRest(ctx, startMs, durationMs)
        archive(ctx, startMs + durationMs)
    }

    /** Records the phone being unlocked, or an alarm going off, from [atMs] to [endMs]. */
    fun addMoment(ctx: Context, kind: Int, atMs: Long, endMs: Long = atMs) {
        val moments = (rawMoments(ctx) + Moment(Span(atMs, endMs), kind))
            .filter { it.span.startMs >= atMs - KEEP_MS }
            .takeLast(MAX_RESTS)
        val json = JSONArray()
        moments.forEach { json.put(JSONArray().put(it.span.startMs).put(it.kind).put(it.span.endMs)) }
        prefs(ctx).edit().putString(MOMENTS_KEY, json.toString()).apply()
    }

    private fun rawMoments(ctx: Context): List<Moment> {
        val raw = prefs(ctx).getString(MOMENTS_KEY, null) ?: return emptyList()
        return runCatching {
            val json = JSONArray(raw)
            (0 until json.length()).map { i ->
                val pair = json.getJSONArray(i)
                Moment(Span(pair.getLong(0), pair.optLong(2, pair.getLong(0))), pair.getInt(1))
            }
        }.getOrDefault(emptyList())
    }

    private fun putRest(ctx: Context, startMs: Long, durationMs: Long) {
        val cutoff = startMs + durationMs - KEEP_MS
        val rests = (rests(ctx) + Span(startMs, startMs + durationMs))
            .filter { it.endMs >= cutoff }
            .takeLast(MAX_RESTS)
        val json = JSONArray()
        rests.forEach { json.put(JSONArray().put(it.startMs).put(it.durationMs)) }
        prefs(ctx).edit().putString(KEY, json.toString()).apply()
    }

    private fun rests(ctx: Context): List<Span> {
        val raw = prefs(ctx).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val json = JSONArray(raw)
            (0 until json.length()).map { i ->
                val pair = json.getJSONArray(i)
                val start = pair.getLong(0)
                Span(start, start + pair.getLong(1))
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Files away every night whose window has closed in the last couple of days, while the stretches
     * it was read from are still kept. Refiling one that is already there replaces it, which is
     * what lets a night pick up a silence that was only recorded when the phone was picked up.
     */
    fun archive(ctx: Context, nowMs: Long) {
        val silences = History.all(ctx).map { it.span }
        val rests = rests(ctx)
        val marks = rawMoments(ctx)
        val found = (0..2).mapNotNull { back ->
            val window = Span(at(nowMs, -back - 1, 18), at(nowMs, -back, 12))
            if (window.endMs > nowMs) null
            else find(rests, silences, marks, window, at(nowMs, -back, 0))
        }
        if (found.isEmpty()) return
        val byMorning = storedNights(ctx).associateBy { it.morningMs }.toMutableMap()
        found.forEach { byMorning[it.morningMs] = it }
        val nights = byMorning.values.sortedBy { it.startMs }.takeLast(MAX_NIGHTS)

        val json = JSONArray()
        nights.forEach { night ->
            val moments = JSONArray()
            night.moments.forEach { moments.put(it.span.startMs).put(it.span.endMs).put(it.kind) }
            json.put(JSONArray().put(flatten(night.pieces)).put(flatten(night.silenced)).put(moments))
        }
        // Most calls find the same nights already on file; leave the store alone then.
        val encoded = json.toString()
        if (encoded != prefs(ctx).getString(NIGHTS_KEY, null)) prefs(ctx).edit().putString(NIGHTS_KEY, encoded).apply()
    }

    /**
     * Every night on record, oldest first, with last night included even before it is filed. A
     * caller that has already worked out [last] passes it in rather than having it found again.
     */
    fun history(ctx: Context, nowMs: Long, last: Night? = lastNight(ctx, nowMs).night): List<Night> {
        archive(ctx, nowMs)
        val byMorning = storedNights(ctx).associateBy { it.morningMs }.toMutableMap()
        last?.let { byMorning[it.morningMs] = it }
        return byMorning.values.sortedBy { it.startMs }
    }

    private fun storedNights(ctx: Context): List<Night> {
        val raw = prefs(ctx).getString(NIGHTS_KEY, null) ?: return emptyList()
        return runCatching {
            val json = JSONArray(raw)
            (0 until json.length()).mapNotNull { i ->
                val entry = json.getJSONArray(i)
                val pieces = unflatten(entry.getJSONArray(0))
                val moments = entry.optJSONArray(2)?.let { m ->
                    (0 until m.length() / 3).map { Moment(Span(m.getLong(3 * it), m.getLong(3 * it + 1)), m.getInt(3 * it + 2)) }
                }.orEmpty()
                if (pieces.isEmpty()) null else Night(pieces, unflatten(entry.getJSONArray(1)), moments)
            }
        }.getOrDefault(emptyList())
    }

    private fun flatten(spans: List<Span>): JSONArray =
        JSONArray().apply { spans.forEach { put(it.startMs).put(it.endMs) } }

    private fun unflatten(json: JSONArray): List<Span> =
        (0 until json.length() / 2).map { Span(json.getLong(2 * it), json.getLong(2 * it + 1)) }

    fun lastNight(ctx: Context, nowMs: Long): LastNight {
        val silences = History.all(ctx).map { it.span }
        val rests = rests(ctx)
        val marks = rawMoments(ctx)

        val latest = Span(at(nowMs, -1, 18), at(nowMs, 0, 12))
        // Before dawn, tonight is still being slept: a glance at 3am must not turn its first half
        // into "last night". So the night before comes first, and tonight only stands in for it
        // when there is none.
        if (nowMs < at(nowMs, 0, 6)) {
            val before = Span(at(nowMs, -2, 18), at(nowMs, -1, 12))
            find(rests, silences, marks, before, at(nowMs, -1, 0))?.let { return LastNight(before, it) }
        }
        return LastNight(latest, find(rests, silences, marks, latest, at(nowMs, 0, 0)))
    }

    /** [hour]:00 on the day [dayOffset] days from the one [dayMs] falls on. */
    internal fun at(dayMs: Long, dayOffset: Int, hour: Int): Long = Calendar.getInstance().apply {
        timeInMillis = dayMs
        add(Calendar.DAY_OF_MONTH, dayOffset)
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /**
     * The night in [window]: long untouched stretches are joined into runs wherever the gap between
     * them is under [MAX_WAKE_MS], and the run with the most time asleep, centred after [midnight],
     * is the night. Centred after midnight is what tells the night from a long evening away from the
     * phone. Weighing whole runs rather than single stretches is what keeps a night broken by a
     * wake-up from losing to one unbroken morning with the phone face down on a desk.
     */
    internal fun find(rests: List<Span>, silences: List<Span>, marks: List<Moment>, window: Span, midnight: Long): Night? {
        val untouched = rests + silences
        val inWindow = untouched.filter { it.endMs > window.startMs && it.startMs < window.endMs }
        val long = merge(inWindow, BLIP_MS).filter { it.durationMs >= MIN_STRETCH_MS }
        val runs = ArrayList<MutableList<Span>>()
        for (stretch in long) {
            val run = runs.lastOrNull()
            if (run != null && stretch.startMs - run.last().endMs <= MAX_WAKE_MS) run += stretch
            else runs += mutableListOf(stretch)
        }
        val pieces = runs
            .filter { (it.first().startMs + it.last().endMs) / 2 >= midnight }
            .maxByOrNull { run -> run.sumOf { it.durationMs } }
            ?: return null

        val silenced = merge(silences, 0L).flatMap { s ->
            pieces.mapNotNull { p ->
                val start = maxOf(s.startMs, p.startMs)
                val end = minOf(s.endMs, p.endMs)
                if (end > start) Span(start, end) else null
            }
        }
        return Night(pieces, silenced, moments(rests, marks, pieces.first().startMs, pieces.last().endMs))
    }

    /**
     * What lit the screen between [startMs] and [endMs]: each gap between two dark stretches is
     * one time the screen was on, told apart by whether an unlock fell inside it or an alarm rang
     * as it began. The alarms themselves are kept too, including the one that ended the night.
     */
    private fun moments(rests: List<Span>, marks: List<Moment>, startMs: Long, endMs: Long): List<Moment> {
        val alarms = marks.filter { it.kind == Moment.ALARM && it.span.startMs in startMs..endMs + ALARM_AFTER_MS }
        val unlocks = marks.filter { it.kind == Moment.UNLOCK }.map { it.span.startMs }
        val dark = merge(rests.filter { it.endMs > startMs && it.startMs < endMs }, 0L)
        val lit = dark.zipWithNext { a, b -> Span(a.endMs, b.startMs) }.mapNotNull { on ->
            if (alarms.any { abs(it.span.startMs - on.startMs) <= ALARM_SLACK_MS }) return@mapNotNull null
            val kind = if (unlocks.any { it in on.startMs..on.endMs }) Moment.UNLOCK else Moment.GLANCE
            Moment(on, kind)
        }
        return (lit + alarms).sortedBy { it.span.startMs }
    }

    /** Sorted, with overlaps and anything no more than [gapMs] apart joined into one. */
    internal fun merge(spans: List<Span>, gapMs: Long): List<Span> {
        val out = ArrayList<Span>()
        for (s in spans.sortedBy { it.startMs }) {
            val last = out.lastOrNull()
            if (last != null && s.startMs - last.endMs <= gapMs) {
                out[out.lastIndex] = Span(last.startMs, maxOf(last.endMs, s.endMs))
            } else {
                out += s
            }
        }
        return out
    }
}
