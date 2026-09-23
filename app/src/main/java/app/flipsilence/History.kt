package app.flipsilence

import android.content.Context
import org.json.JSONArray

/**
 * A record of completed silences, so the screen can show that Flip works while you are not
 * watching it -- which is the only time it ever does anything.
 */
object History {

    data class Entry(val startMs: Long, val durationMs: Long) {
        val span: Span get() = Span(startMs, startMs + durationMs)
    }

    private const val KEY = "history"

    /** Enough to still hold last night after a full day of setting the phone down at a desk. */
    private const val MAX_ENTRIES = 200

    /** Anything shorter than this is a fumble, not a silence, and only clutters the list. */
    private const val MIN_DURATION_MS = 2_000L

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("flip", Context.MODE_PRIVATE)

    fun add(ctx: Context, startMs: Long, durationMs: Long) {
        if (durationMs < MIN_DURATION_MS) return
        val entries = (all(ctx) + Entry(startMs, durationMs)).takeLast(MAX_ENTRIES)
        val json = JSONArray()
        entries.forEach { json.put(JSONArray().put(it.startMs).put(it.durationMs)) }
        prefs(ctx).edit().putString(KEY, json.toString()).apply()
    }

    fun all(ctx: Context): List<Entry> {
        val raw = prefs(ctx).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val json = JSONArray(raw)
            (0 until json.length()).map { i ->
                val pair = json.getJSONArray(i)
                Entry(pair.getLong(0), pair.getLong(1))
            }
        }.getOrDefault(emptyList())
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(KEY).apply()
    }
}
