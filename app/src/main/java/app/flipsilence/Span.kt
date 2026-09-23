package app.flipsilence

/** A stretch of wall-clock time, in ms since the epoch. */
data class Span(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
}
