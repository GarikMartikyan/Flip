package app.flipsilence

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.icu.text.MeasureFormat
import android.icu.text.NumberFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.icu.text.DateFormat
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The onboarding's table: a phone lying on it, seen from a little above and square to the screen,
 * that turns over when told to. It is a solid thing, not a card: its back, its frame and its screen
 * are separate planes [THICKNESS] apart, each projected through the same perspective, so the frame
 * shows as the phone turns about its long axis and lifts off the table.
 *
 * Face up, rings of sound spread from it across the table; face down, a moss halo breathes under it.
 * A chip at the top says which. Drawn on a fixed [STAGE_W] x [STAGE_H] stage, scaled to fit the view
 * and centred in it.
 *
 * With the system's animations off it jumps straight to each end state and holds still.
 */
class OnboardingStage @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private companion object {
        const val STAGE_W = 390f
        const val STAGE_H = 400f

        /** Where the phone's centre sits, and where the eye looks from. */
        const val CENTRE_X = 195f
        const val CENTRE_Y = 208f
        const val EYE_Y = 160f
        const val EYE_DISTANCE = 900f

        /** How far the table is tilted away: enough to see the frame, little enough to read the screen. */
        const val TILT_DEG = 26f

        const val HALF_W = 68f
        const val HALF_H = 141f
        const val CORNER = 27f
        const val THICKNESS = 13.5f
        const val BEZEL = 5f

        /** Layers of frame between back and screen, so the side reads as a solid band. */
        const val EDGE_LAYERS = 16

        const val FLIP_MS = 1400L
        const val LIFT = 80f
        const val RING_MS = 2400L
        const val BREATHE_MS = 4000L
    }

    /** One value easing towards a target, starting after a delay. */
    private class Fade(var value: Float) {
        private var from = value
        private var target = value
        private var start = 0L
        private var delay = 0L
        private var duration = 1L

        fun to(target: Float, now: Long, delay: Long, duration: Long) {
            if (target == this.target) return
            from = at(now)
            this.target = target
            start = now
            this.delay = delay
            this.duration = duration
        }

        fun snap(target: Float) {
            from = target
            this.target = target
            value = target
        }

        fun at(now: Long): Float {
            val p = ((now - start - delay).toFloat() / duration).coerceIn(0f, 1f)
            value = from + (target - from) * p
            return value
        }
    }

    private val colorInk = context.getColor(R.color.ink)
    private val colorDim = context.getColor(R.color.ink_dim)
    private val colorQuiet = context.getColor(R.color.quiet)
    private val colorSurface = context.getColor(R.color.surface)
    private val colorBorder = context.getColor(R.color.border)
    private val colorEdge = context.getColor(R.color.phone_edge)
    private val colorShadow = context.getColor(R.color.phone_shadow)

    /** The phone itself does not change with the theme: it is the same object in a dark room. */
    private val colorBezel = 0xFF2B2824.toInt()
    private val colorScreen = 0xFF171613.toInt()
    private val colorBack = 0xFF3D3934.toInt()
    private val colorRim = 0xFF6E685F.toInt()
    private val colorLensRing = 0xFF5E5850.toInt()
    private val colorLens = 0xFF141311.toInt()
    private val colorFlash = 0xFF8A8378.toInt()
    private val colorOnScreen = 0xFFECE7DD.toInt()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorShadow }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorQuiet }
    private val clockText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = resources.getFont(R.font.plus_jakarta_sans_semibold)
        fontFeatureSettings = "tnum"
        letterSpacing = -0.02f
        textAlign = Paint.Align.CENTER
        textSize = 34f
        color = colorOnScreen
    }
    private val dateText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = resources.getFont(R.font.plus_jakarta_sans_medium)
        textAlign = Paint.Align.CENTER
        textSize = 9f
        color = colorOnScreen
        alpha = 180
    }
    private val chipText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = resources.getFont(R.font.plus_jakarta_sans_semibold)
        textSize = 14f
        // Steady digits, so the chip does not twitch as the countdown ticks.
        fontFeatureSettings = "tnum"
    }

    private val bell: Drawable = context.getDrawable(R.drawable.ic_bell)!!.mutate()
    private val chipSoundIcon: Drawable = context.getDrawable(R.drawable.ic_bell)!!.mutate().apply { setTint(colorInk) }
    private val chipQuietIcon: Drawable = context.getDrawable(R.drawable.ic_quiet)!!.mutate().apply { setTint(colorQuiet) }
    private val chipSound = context.getString(R.string.onb_chip_sound)
    private val chipQuiet = context.getString(R.string.onb_chip_quiet)
    private val chipHoldIcon: Drawable = context.getDrawable(R.drawable.ic_timer)!!.mutate().apply { setTint(colorInk) }
    private val countdown = MeasureFormat.getInstance(
        resources.configuration.locales[0],
        MeasureFormat.FormatWidth.SHORT,
        NumberFormat.getInstance(resources.configuration.locales[0]).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        },
    )
    private var holdLabelTenths = -1L
    private var holdLabel = ""

    /**
     * How long the phone must lie still before it goes quiet: the chosen sensitivity's. Once it
     * lands face down this counts down in its own chip, and only then does the quiet come.
     */
    var holdMs = Sensitivity.DEFAULT.holdMs

    private val rect = RectF()
    private val chipClip = Path()
    private val layer = Matrix()
    private val src = floatArrayOf(-HALF_W, -HALF_H, HALF_W, -HALF_H, HALF_W, HALF_H, -HALF_W, HALF_H)
    private val dst = FloatArray(8)

    private val tiltCos = cos(Math.toRadians(TILT_DEG.toDouble())).toFloat()
    private val tiltSin = sin(Math.toRadians(TILT_DEG.toDouble())).toFloat()

    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    /** Whether the phone is (or is turning to lie) face down. */
    var faceDown = false
        private set

    // The turn: from one angle to the next, 0 face up and 180 face down; face up again is 360.
    private var turnFrom = 0f
    private var turnTo = 0f
    private var turnStart = 0L

    private val waves = Fade(1f)
    private val halo = Fade(0f)
    private val soundChip = Fade(1f)
    private val quietChip = Fade(0f)
    private val holdChip = Fade(0f)

    private val epoch = SystemClock.uptimeMillis()

    init {
        bell.setTint(colorOnScreen)
        updateDescription()
    }

    /**
     * Lays the phone face down or face up. Animated, it lifts and turns over, and the sound or the
     * quiet follows once it has landed; not animated, it is simply there.
     */
    fun setFaceDown(down: Boolean, animate: Boolean) {
        val now = SystemClock.uptimeMillis()
        val moving = animate && ValueAnimator.areAnimatorsEnabled()
        if (down == faceDown) {
            if (!moving) settle(now)
            return
        }
        faceDown = down
        turnFrom = currentTurn(now) % 360f
        turnTo = if (down) 180f else 360f
        turnStart = if (moving) now else now - FLIP_MS
        if (moving) {
            if (down) {
                waves.to(0f, now, 0, 600)
                soundChip.to(0f, now, 0, 400)
                holdChip.to(1f, now, FLIP_MS - 300, 300)
                halo.to(1f, now, FLIP_MS + holdMs, 800)
                quietChip.to(1f, now, FLIP_MS + holdMs + 200, 500)
            } else {
                halo.to(0f, now, 0, 800)
                quietChip.to(0f, now, 0, 400)
                holdChip.to(0f, now, 0, 200)
                waves.to(1f, now, 900, 600)
                soundChip.to(1f, now, 900, 500)
            }
        } else {
            settle(now)
        }
        updateDescription()
        invalidate()
    }

    private fun settle(now: Long) {
        turnFrom = if (faceDown) 180f else 0f
        turnTo = turnFrom
        turnStart = now - FLIP_MS
        waves.snap(if (faceDown) 0f else 1f)
        soundChip.snap(if (faceDown) 0f else 1f)
        halo.snap(if (faceDown) 1f else 0f)
        quietChip.snap(if (faceDown) 1f else 0f)
        holdChip.snap(0f)
        invalidate()
    }

    private fun updateDescription() {
        contentDescription = context.getString(if (faceDown) R.string.onb_stage_down else R.string.onb_stage_up)
    }

    private fun turnProgress(now: Long) = ((now - turnStart).toFloat() / FLIP_MS).coerceIn(0f, 1f)

    private fun currentTurn(now: Long) = turnFrom + (turnTo - turnFrom) * ease(turnProgress(now))

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        scale = min(w / STAGE_W, h / STAGE_H)
        offsetX = (w - STAGE_W * scale) / 2
        offsetY = (h - STAGE_H * scale) / 2
        // Mask filters work in pixels, outside the canvas scale.
        shadow.maskFilter = BlurMaskFilter(max(1f, 9f * scale), BlurMaskFilter.Blur.NORMAL)
        glow.maskFilter = BlurMaskFilter(max(1f, 30f * scale), BlurMaskFilter.Blur.NORMAL)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val still = !ValueAnimator.areAnimatorsEnabled()
        val t = now - epoch

        val p = ease(turnProgress(now))
        val turn = turnFrom + (turnTo - turnFrom) * p
        val lift = if (turnFrom == turnTo) 0f else LIFT * sin(PI.toFloat() * p)

        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)

        drawShadow(canvas, lift)
        drawHalo(canvas, halo.at(now), if (still) 0f else breathe(t))
        drawWaves(canvas, waves.at(now), t, still)
        drawPhone(canvas, Math.toRadians(turn.toDouble()).toFloat(), lift)
        drawChip(canvas, chipSound, chipSoundIcon, colorInk, colorBorder, soundChip.at(now))
        drawHoldChip(canvas, now)
        drawChip(canvas, chipQuiet, chipQuietIcon, colorQuiet, colorQuiet, quietChip.at(now), borderAlpha = .45f)

        canvas.restore()

        if (!still && isShown) postInvalidateOnAnimation()
    }

    // --- Projection ------------------------------------------------------------------------------

    /**
     * Where a point on the phone lands on the stage. [x] across the phone, [y] along it (down the
     * screen), [z] up from its back. The phone turns by [turn] about its long axis through its middle,
     * rises by [lift], and then the table tilts away from the eye.
     */
    private fun project(x: Float, y: Float, z: Float, turn: Float, lift: Float, out: FloatArray, i: Int) {
        val z0 = z - THICKNESS / 2
        val c = cos(turn)
        val s = sin(turn)
        val xr = x * c + z0 * s
        val zr = -x * s + z0 * c + THICKNESS / 2 + lift
        projectWorld(xr, y, zr, out, i)
    }

    private fun projectWorld(x: Float, y: Float, z: Float, out: FloatArray, i: Int) {
        val wy = y * tiltCos - z * tiltSin
        val wz = y * tiltSin + z * tiltCos
        val k = EYE_DISTANCE / (EYE_DISTANCE - wz)
        out[i] = CENTRE_X + x * k
        out[i + 1] = EYE_Y + (CENTRE_Y + wy - EYE_Y) * k
    }

    /** Maps the phone's outline at height [z] onto the stage; [mirror] for the back, seen from behind. */
    private fun phoneLayer(z: Float, turn: Float, lift: Float, mirror: Boolean): Boolean {
        for (i in 0 until 4) {
            val x = src[i * 2] * if (mirror) -1f else 1f
            project(x, src[i * 2 + 1], z, turn, lift, dst, i * 2)
        }
        layer.setPolyToPoly(src, 0, dst, 0, 4)
        return facing()
    }

    /** Maps a rectangle lying on the table, [w] by [h] around the phone's resting place. */
    private fun tableLayer(w: Float, h: Float, dx: Float = 0f, dy: Float = 0f) {
        val corners = floatArrayOf(-w, -h, w, -h, w, h, -w, h)
        for (i in 0 until 4) projectWorld(corners[i * 2] + dx, corners[i * 2 + 1] + dy, 0.5f, dst, i * 2)
        layer.setPolyToPoly(corners, 0, dst, 0, 4)
    }

    /** Whether the plane just projected faces the eye: its corners keep their winding. */
    private fun facing(): Boolean {
        var area = 0f
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            area += dst[i * 2] * dst[j * 2 + 1] - dst[j * 2] * dst[i * 2 + 1]
        }
        return area > 0f
    }

    // --- The table -------------------------------------------------------------------------------

    /** The shadow stays on the table: it spreads, slides and fades as the phone rises. */
    private fun drawShadow(canvas: Canvas, lift: Float) {
        val up = lift / LIFT
        val grow = 1f + .18f * up
        tableLayer(HALF_W * grow, HALF_H * grow, 0f, 8f + 12f * up)
        shadow.alpha = alpha(.30f - .19f * up)
        canvas.save()
        canvas.concat(layer)
        rect.set(-HALF_W * grow, -HALF_H * grow, HALF_W * grow, HALF_H * grow)
        canvas.drawRoundRect(rect, CORNER * grow, CORNER * grow, shadow)
        canvas.restore()
    }

    private fun drawHalo(canvas: Canvas, a: Float, breath: Float) {
        if (a <= 0f) return
        val grow = 1f + .045f * breath
        val w = (HALF_W + 20f) * grow
        val h = (HALF_H + 20f) * grow
        tableLayer(w, h)
        canvas.save()
        canvas.concat(layer)
        rect.set(-w, -h, w, h)
        val r = (CORNER + 16f) * grow
        glow.alpha = alpha(.22f * a)
        canvas.drawRoundRect(rect, r, r, glow)
        fill.color = colorQuiet
        fill.alpha = alpha(.14f * a)
        canvas.drawRoundRect(rect, r, r, fill)
        stroke.color = colorQuiet
        stroke.strokeWidth = 1.5f
        stroke.alpha = alpha(.6f * a)
        canvas.drawRoundRect(rect, r, r, stroke)
        canvas.restore()
    }

    /** Three rings of sound, each spreading out from the phone and fading as it goes. */
    private fun drawWaves(canvas: Canvas, a: Float, t: Long, still: Boolean) {
        if (a <= 0f) return
        val rings = if (still) floatArrayOf(.3f) else FloatArray(3) { ((t + it * RING_MS / 3) % RING_MS).toFloat() / RING_MS }
        stroke.color = colorInk
        stroke.strokeWidth = 1.5f
        for (ph in rings) {
            // Kept to what the stage can hold, so a ring never meets the edge before it has faded.
            val grow = 1f + .42f * (1f - (1f - ph).pow(2))
            val w = (HALF_W + 7f) * grow
            val h = (HALF_H + 7f) * grow
            tableLayer(w, h)
            stroke.alpha = alpha(.5f * (1f - ph) * a)
            canvas.save()
            canvas.concat(layer)
            rect.set(-w, -h, w, h)
            canvas.drawRoundRect(rect, (CORNER + 6f) * grow, (CORNER + 6f) * grow, stroke)
            canvas.restore()
        }
    }

    // --- The phone -------------------------------------------------------------------------------

    /**
     * Back, frame and screen, drawn far side first: whichever face points at the eye goes last,
     * and the other is skipped.
     */
    private fun drawPhone(canvas: Canvas, turn: Float, lift: Float) {
        val screenUp = phoneLayer(THICKNESS, turn, lift, mirror = false)
        val edges = (1..EDGE_LAYERS).map { THICKNESS * it / (EDGE_LAYERS + 1) }
        val order = if (screenUp) edges else edges.reversed()
        if (!screenUp) {
            phoneLayer(THICKNESS - .5f, turn, lift, mirror = false)
            drawSlab(canvas, colorRim)
        }
        for (z in order) {
            phoneLayer(z, turn, lift, mirror = false)
            drawSlab(canvas, colorEdge)
        }
        if (screenUp) {
            phoneLayer(THICKNESS - .5f, turn, lift, mirror = false)
            drawSlab(canvas, colorRim)
            phoneLayer(THICKNESS, turn, lift, mirror = false)
            canvas.save()
            canvas.concat(layer)
            drawScreen(canvas)
            canvas.restore()
        } else if (phoneLayer(0f, turn, lift, mirror = true)) {
            canvas.save()
            canvas.concat(layer)
            drawBack(canvas)
            canvas.restore()
        }
    }

    private fun drawSlab(canvas: Canvas, color: Int) {
        canvas.save()
        canvas.concat(layer)
        fill.color = color
        fill.alpha = 255
        rect.set(-HALF_W, -HALF_H, HALF_W, HALF_H)
        canvas.drawRoundRect(rect, CORNER, CORNER, fill)
        canvas.restore()
    }

    /** The lock screen: the time now, the date, and a bell for the sound that is on. */
    private fun drawScreen(canvas: Canvas) {
        fill.alpha = 255
        fill.color = colorBezel
        rect.set(-HALF_W, -HALF_H, HALF_W, HALF_H)
        canvas.drawRoundRect(rect, CORNER, CORNER, fill)
        fill.color = colorScreen
        rect.inset(BEZEL, BEZEL)
        canvas.drawRoundRect(rect, CORNER - BEZEL, CORNER - BEZEL, fill)
        fill.color = 0xFF000000.toInt()
        canvas.drawCircle(0f, -HALF_H + 15f, 4f, fill)

        val now = Date()
        canvas.drawText(SleepText.clock(now.time), 0f, -HALF_H + 80f, clockText)
        canvas.drawText(dateFormat.format(now), 0f, -HALF_H + 98f, dateText)
        bell.setBounds(-9, (HALF_H - 44f).roundToInt(), 9, (HALF_H - 26f).roundToInt())
        bell.alpha = 216
        bell.draw(canvas)
    }

    private val dateFormat = DateFormat.getInstanceForSkeleton("EEEEdMMMM", Locale.getDefault())

    /** The back: a column of three lenses near the top corner, and the flash beside them. */
    private fun drawBack(canvas: Canvas) {
        fill.alpha = 255
        fill.color = colorBack
        rect.set(-HALF_W, -HALF_H, HALF_W, HALF_H)
        canvas.drawRoundRect(rect, CORNER, CORNER, fill)
        for (i in 0 until 3) {
            val cx = -HALF_W + 27f
            val cy = -HALF_H + 31f + i * 30f
            fill.color = colorLensRing
            canvas.drawCircle(cx, cy, 14f, fill)
            fill.color = colorLens
            canvas.drawCircle(cx, cy, 11f, fill)
        }
        fill.color = colorFlash
        canvas.drawCircle(-HALF_W + 51.5f, -HALF_H + 27.5f, 3.5f, fill)
    }

    // --- The chip --------------------------------------------------------------------------------

    /**
     * Between landing and going quiet: the seconds left, counting down, with the chip filling as
     * they run out. It gives way to the quiet chip the moment the hold is over.
     */
    private fun drawHoldChip(canvas: Canvas, now: Long) {
        if (!faceDown) {
            drawChip(canvas, holdText(0L), chipHoldIcon, colorInk, colorBorder, holdChip.at(now))
            return
        }
        val held = (now - turnStart - FLIP_MS).coerceIn(0L, holdMs)
        val over = now - turnStart - FLIP_MS - holdMs
        // Out as the quiet comes in, rather than fading over it.
        val a = if (over > 0) holdChip.at(now) * (1f - over / 200f).coerceIn(0f, 1f) else holdChip.at(now)
        drawChip(canvas, holdText(holdMs - held), chipHoldIcon, colorInk, colorBorder, a, progress = held.toFloat() / holdMs)
    }

    /** "Still · 1.5 sec", counting down in tenths; rebuilt only when the tenth changes. */
    private fun holdText(remainingMs: Long): String {
        val tenths = (remainingMs + 99) / 100
        if (tenths != holdLabelTenths) {
            holdLabelTenths = tenths
            val time = countdown.format(Measure(tenths / 10.0, MeasureUnit.SECOND)).replace(' ', '\u00A0')
            holdLabel = context.getString(R.string.onb_chip_hold, time)
        }
        return holdLabel
    }

    private fun drawChip(
        canvas: Canvas,
        label: String,
        icon: Drawable,
        ink: Int,
        border: Int,
        a: Float,
        borderAlpha: Float = 1f,
        progress: Float = -1f,
    ) {
        if (a <= 0f) return
        val textW = chipText.measureText(label)
        val w = 12f + 18f + 8f + textW + 16f
        val left = CENTRE_X - w / 2
        val top = 22f - 6f * (1f - a)
        rect.set(left, top, left + w, top + 36f)
        fill.color = colorSurface
        fill.alpha = alpha(a)
        canvas.drawRoundRect(rect, 18f, 18f, fill)
        if (progress > 0f) {
            canvas.save()
            chipClip.reset()
            chipClip.addRoundRect(rect, 18f, 18f, Path.Direction.CW)
            canvas.clipPath(chipClip)
            fill.color = colorInk
            fill.alpha = alpha(.12f * a)
            canvas.drawRect(left, top, left + w * progress, top + 36f, fill)
            canvas.restore()
        }
        stroke.color = border
        stroke.strokeWidth = 1f
        stroke.alpha = alpha(a * borderAlpha)
        canvas.drawRoundRect(rect, 18f, 18f, stroke)
        icon.setBounds((left + 12f).roundToInt(), (top + 9f).roundToInt(), (left + 30f).roundToInt(), (top + 27f).roundToInt())
        icon.alpha = alpha(a)
        icon.draw(canvas)
        chipText.color = ink
        chipText.alpha = alpha(a)
        canvas.drawText(label, left + 38f, top + 23f, chipText)
    }

    // --- Helpers ---------------------------------------------------------------------------------

    private fun breathe(t: Long) = .5f - .5f * cos(2f * PI.toFloat() * (t % BREATHE_MS) / BREATHE_MS)
    private fun ease(x: Float) = if (x < .5f) 4 * x * x * x else 1 - (-2 * x + 2).pow(3) / 2
    private fun alpha(a: Float) = (a.coerceIn(0f, 1f) * 255).roundToInt()
}
