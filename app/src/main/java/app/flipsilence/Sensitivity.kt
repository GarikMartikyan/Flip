package app.flipsilence

import kotlin.math.acos

/**
 * How fussy the detector is about calling something "face down on a flat surface".
 *
 * The numbers are not invented. Real placements measured on this phone landed at gravity Z between
 * -9.53 and -9.73 with motion between 0.002 and 0.072, so Balanced keeps roughly 3x headroom over
 * the worst of those while still leaving room for an imperfect table. Strict rejects the shallow
 * -9.08 kind of placement seen on a surface that was not properly flat; Relaxed accepts it.
 *
 * Strict is the one meant to never fire in a pocket, and its numbers come from recording both. A
 * desk held motion under 0.017 and drifted under 0.035 degrees in any 2 s; a front pocket while
 * sitting, face to the thigh, sat 12-14 degrees off flat and never went a full 2 s below 0.018
 * motion *and* 0.07 degrees of drift together -- the body is never as quiet as wood. The old Strict
 * (0.12, 1.5 degrees, 14 degrees of tilt) let that pocket through in 2.6 s.
 */
enum class Sensitivity(
    /** String resources, so the choice reads in the phone's language. */
    val label: Int,
    /** Gravity Z at or below which the phone counts as face down. */
    val engageGz: Float,
    /** Hysteresis: only past this does it count as picked up. */
    val releaseGz: Float,
    /** Non-gravity acceleration, m/s^2, below which it counts as settled. */
    val stillMax: Float,
    /**
     * Degrees the phone may lean away from where it started before the hold restarts.
     *
     * A hand keeps the phone within [stillMax] easily -- holding something steady produces almost
     * no linear acceleration -- but it cannot keep an *angle*. A wrist drifts a few degrees over a
     * second or two; a table drifts a fifth of one. That difference is what separates "put down"
     * from "held flat, face down".
     */
    val maxDriftDeg: Float,
    /** How long all three conditions must hold before it silences. */
    val holdMs: Long,
    val blurb: Int,
) {
    STRICT(
        label = R.string.sens_strict,
        engageGz = -9.6f,
        releaseGz = -7.5f,
        stillMax = 0.03f,
        maxDriftDeg = 0.2f,
        holdMs = 2_000L,
        blurb = R.string.sens_strict_blurb,
    ),
    BALANCED(
        label = R.string.sens_balanced,
        engageGz = -9.0f,
        releaseGz = -7.0f,
        stillMax = 0.25f,
        maxDriftDeg = 2.5f,
        holdMs = 1_500L,
        blurb = R.string.sens_balanced_blurb,
    ),
    RELAXED(
        label = R.string.sens_relaxed,
        engageGz = -8.3f,
        releaseGz = -6.3f,
        stillMax = 0.45f,
        maxDriftDeg = 4f,
        holdMs = 1_000L,
        blurb = R.string.sens_relaxed_blurb,
    );

    /** The engage angle expressed as degrees away from perfectly face down, for the gauge notch. */
    val thresholdDeg: Float
        get() = Math.toDegrees(acos((-engageGz / 9.81f).coerceIn(-1f, 1f).toDouble())).toFloat()

    companion object {
        val DEFAULT = STRICT

        fun fromName(name: String?): Sensitivity =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
