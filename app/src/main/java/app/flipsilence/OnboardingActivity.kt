package app.flipsilence

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.window.OnBackInvokedDispatcher

/**
 * First launch. The phone on the table acts out what Flip does (over is quiet, back up is sound),
 * then the three things Flip needs are asked for one at a time, each with why. Once they are given,
 * Flip is switched on there and then, so the setup ends with it already watching.
 *
 * Only Do Not Disturb access is required; notifications and unrestricted battery can be put off.
 * Coming back from a system screen with a permission granted moves on by itself, and a step whose
 * permission is already there is skipped.
 */
class OnboardingActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var stage: OnboardingStage
    private lateinit var progress: LinearLayout
    private lateinit var words: View
    private lateinit var eyebrow: TextView
    private lateinit var title: TextView
    private lateinit var body: TextView
    private lateinit var back: View
    private lateinit var primary: Button
    private lateinit var hint: TextView
    private lateinit var later: Button

    private var step = WELCOME

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)
        prefs = Prefs(this)

        applySystemBarInsets(findViewById(R.id.scroll), findViewById(R.id.content))

        stage = findViewById(R.id.stage)
        stage.holdMs = prefs.sensitivity.holdMs
        progress = findViewById(R.id.progress)
        words = findViewById(R.id.words)
        eyebrow = findViewById(R.id.eyebrow)
        title = findViewById(R.id.title)
        body = findViewById(R.id.body)
        back = findViewById(R.id.back)
        primary = findViewById(R.id.primary)
        hint = findViewById(R.id.hint)
        later = findViewById(R.id.later)

        repeat(PROGRESS_STEPS) { i ->
            progress.addView(View(this).apply {
                setBackgroundResource(R.drawable.progress_segment)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    if (i > 0) marginStart = dp(4f)
                }
            })
        }

        back.setOnClickListener { goBack() }
        primary.setOnClickListener { onPrimary() }
        later.setOnClickListener { show(nextFrom(step + 1), animate = true) }

        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { goBack() }

        step = savedInstanceState?.getInt(STATE_STEP, WELCOME) ?: WELCOME
        show(step, animate = false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_STEP, step)
    }

    override fun onResume() {
        super.onResume()
        // Back from a system screen: if what this step asked for was given there, move on.
        if (step in DND..BATTERY && granted(step)) show(nextFrom(step), animate = true)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_NOTIFICATIONS) return
        notePermissionResult(permissions, grantResults, notificationSettings())
        if (step == NOTIFICATIONS && granted(NOTIFICATIONS)) show(nextFrom(NOTIFICATIONS), animate = true)
    }

    private fun onPrimary() {
        when (step) {
            WELCOME, FACE_DOWN -> show(step + 1, animate = true)
            PICK_UP -> show(nextFrom(DND), animate = true)
            DND -> DndController.openAccessSettings(this)
            NOTIFICATIONS -> requestOrOpenSettings(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS, notificationSettings(),
            )
            BATTERY -> openBatterySettings()
            DONE -> {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        }
    }

    /** Back walks through the explanation; from the permissions or the start it leaves; at the end it goes in. */
    private fun goBack() {
        when (step) {
            FACE_DOWN, PICK_UP -> show(step - 1, animate = true)
            DONE -> onPrimary()
            else -> finish()
        }
    }

    /** The first step from [from] on that still has something to ask; past the last, done. */
    private fun nextFrom(from: Int): Int {
        var s = from
        while (s in DND..BATTERY && granted(s)) s++
        return s
    }

    private fun granted(s: Int): Boolean = when (s) {
        DND -> DndController.hasAccess(this)
        NOTIFICATIONS -> checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        BATTERY -> getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        else -> false
    }

    private fun notificationSettings(): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)

    private fun show(target: Int, animate: Boolean) {
        step = target
        if (step == DONE) finishSetup()

        // The phone lies face down to show the quiet, and again at the end, where it is for real.
        stage.setFaceDown(step == FACE_DOWN || step == DONE, animate)

        repeat(PROGRESS_STEPS) { i ->
            progress.getChildAt(i).backgroundTintList =
                if (i < step) getColorStateList(R.color.ink) else null
        }
        progress.contentDescription = getString(R.string.onb_progress, minOf(step + 1, PROGRESS_STEPS), PROGRESS_STEPS)

        val s = STEPS[step]
        eyebrow.setText(s.eyebrow)
        title.setText(s.title)
        // The wait before silencing is the chosen sensitivity's, so the words match what the phone does.
        body.text = if (step == FACE_DOWN) withHoldTime(s.body) else getString(s.body)
        primary.setText(s.action)
        hint.setTextOrHide(s.hint)
        back.visibility = if (step == FACE_DOWN || step == PICK_UP) View.VISIBLE else View.GONE
        later.visibility = if (step == NOTIFICATIONS || step == BATTERY) View.VISIBLE else View.GONE

        if (animate && ValueAnimator.areAnimatorsEnabled()) {
            words.alpha = 0f
            words.translationY = dp(10f).toFloat()
            words.animate().alpha(1f).translationY(0f).setDuration(WORDS_MS).start()
        }
        // Read the new step out, since nothing else on screen changes focus.
        if (animate) title.announceForAccessibility(title.text)
    }

    /** Flip goes on as the setup ends: the one permission it cannot do without is there by now. */
    private fun finishSetup() {
        prefs.onboarded = true
        if (DndController.hasAccess(this)) {
            prefs.enabled = true
            FlipService.start(this)
        }
    }

    /** How long the phone must lie still, in the phone's language: "1.5 sec", "1,5 с". */
    private fun holdTime(): String =
        MeasureFormat.getInstance(resources.configuration.locales[0], MeasureFormat.FormatWidth.SHORT)
            .format(Measure(prefs.sensitivity.holdMs / 1000.0, MeasureUnit.SECOND))
            // Kept on one line: "1,5" at the end of one and "с" alone at the start of the next reads wrong.
            .replace(' ', '\u00A0')

    /** The step's words with the wait in them set in ink and semibold, so it is the part that is read. */
    private fun withHoldTime(res: Int): CharSequence {
        val time = holdTime()
        val text = getString(res, time)
        val at = text.indexOf(time)
        return SpannableString(text).apply {
            if (at >= 0) {
                setSpan(ForegroundColorSpan(getColor(R.color.ink)), at, at + time.length, 0)
                setSpan(TypefaceSpan(resources.getFont(R.font.plus_jakarta_sans_semibold)), at, at + time.length, 0)
            }
        }
    }

    private fun TextView.setTextOrHide(res: Int) {
        if (res == 0) {
            visibility = View.GONE
        } else {
            setText(res)
            visibility = View.VISIBLE
        }
    }

    private class Step(val eyebrow: Int, val title: Int, val body: Int, val action: Int, val hint: Int = 0)

    private companion object {
        const val WELCOME = 0
        const val FACE_DOWN = 1
        const val PICK_UP = 2
        const val DND = 3
        const val NOTIFICATIONS = 4
        const val BATTERY = 5
        const val DONE = 6

        /** Every step but the last has a segment, filled once it is behind. */
        const val PROGRESS_STEPS = 6

        const val REQUEST_NOTIFICATIONS = 1
        const val STATE_STEP = "step"
        const val WORDS_MS = 450L

        val STEPS = listOf(
            Step(R.string.onb_welcome_eyebrow, R.string.onb_welcome_title, R.string.onb_welcome_body, R.string.onb_show),
            Step(R.string.onb_how_1, R.string.onb_down_title, R.string.onb_down_body, R.string.onb_next),
            Step(R.string.onb_how_2, R.string.onb_up_title, R.string.onb_up_body, R.string.onb_set_up),
            Step(R.string.onb_perm_1, R.string.dnd_access, R.string.onb_dnd_body, R.string.onb_open_settings, R.string.onb_dnd_hint),
            Step(R.string.onb_perm_2, R.string.notification_show, R.string.onb_notif_body, R.string.onb_allow_notif, R.string.onb_notif_hint),
            Step(R.string.onb_perm_3, R.string.onb_batt_title, R.string.onb_batt_body, R.string.onb_open_batt, R.string.onb_batt_hint),
            Step(R.string.onb_done_eyebrow, R.string.onb_done_title, R.string.onb_done_body, R.string.onb_home),
        )
    }
}
