package app.flipsilence

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** How times and lengths are written wherever sleep and silences are shown. */
internal object SleepText {

    fun clock(ms: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

    fun weekday(ms: Long): String = SimpleDateFormat("EEE", Locale.getDefault()).format(Date(ms))

    /** "Mon 21". */
    fun weekdayDay(ms: Long): String = SimpleDateFormat("EEE d", Locale.getDefault()).format(Date(ms))

    fun dayMonth(ms: Long): String = SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(ms))

    /** To the nearest minute, "7h 04m" or "42m"; anything at all shows as at least one. */
    fun minutes(ctx: Context, ms: Long): String {
        val total = (ms / 60_000.0).roundToInt().coerceAtLeast(if (ms > 0) 1 else 0)
        return if (total < 60) ctx.getString(R.string.duration_m, total)
        else ctx.getString(R.string.duration_hm, total / 60, total % 60)
    }

    /** Seconds matter for a silence lasting under a minute; nothing else needs them. */
    fun seconds(ctx: Context, ms: Long): String {
        val totalSeconds = (ms / 1000.0).roundToInt()
        return if (totalSeconds < 60) ctx.getString(R.string.duration_s, totalSeconds) else minutes(ctx, ms)
    }

    /** "Picked up 2 times", "once", or not at all. */
    fun times(ctx: Context, count: Int): String = when (count) {
        0 -> ctx.getString(R.string.times_never)
        1 -> ctx.getString(R.string.times_once)
        2 -> ctx.getString(R.string.times_twice)
        else -> ctx.resources.getQuantityString(R.plurals.times_count, count, count)
    }

    /** Within this many minutes, two lengths of sleep read as the same. */
    const val SAME_MIN = 5

    /** Minutes more (or, negative, fewer) than the average of [before]; null with nothing to go by. */
    fun diffMin(asleepMs: Long, before: List<Sleep.Night>): Int? =
        if (before.isEmpty()) null
        else ((asleepMs - before.map { it.asleepMs }.average()) / 60_000).roundToInt()

    /** [diffMin] in words: about the same, or so much longer or shorter than before. */
    fun versus(ctx: Context, diffMin: Int): String = when {
        kotlin.math.abs(diffMin) < SAME_MIN -> ctx.getString(R.string.about_the_same)
        diffMin > 0 -> ctx.getString(R.string.longer_than_before, minutes(ctx, diffMin * 60_000L))
        else -> ctx.getString(R.string.shorter_than_before, minutes(ctx, -diffMin * 60_000L))
    }

    /** A share of 0..1 the way the locale writes percentages: "42%", "%42", "42 %". */
    fun percent(share: Double): String =
        java.text.NumberFormat.getPercentInstance().format(share)
}
