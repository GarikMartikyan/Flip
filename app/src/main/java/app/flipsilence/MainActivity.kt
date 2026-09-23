package app.flipsilence

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var gauge: TiltGauge
    private lateinit var powerToggle: View
    private lateinit var powerLabel: TextView
    private lateinit var powerSwitch: PowerSwitch
    private lateinit var coach: TextView
    private lateinit var permissionCard: LinearLayout
    private lateinit var permissionText: TextView
    private lateinit var dndButton: Button
    private lateinit var dayStrip: DayStrip
    private lateinit var historyList: LinearLayout
    private lateinit var todayAll: View
    private lateinit var todayAllLabel: TextView
    private lateinit var todaySince: TextView
    private lateinit var todaySummary: View
    private lateinit var todayTotal: TextView
    private lateinit var todaySentence: TextView
    private lateinit var nightDates: TextView
    private lateinit var nightSummary: View
    private lateinit var nightTotal: TextView
    private lateinit var nightBed: TextView
    private lateinit var nightWake: TextView
    private lateinit var nightPickups: TextView
    private lateinit var nightSilenced: TextView
    private lateinit var nightDial: NightDial
    private lateinit var nightList: LinearLayout
    private lateinit var sleepHistoryAvg: TextView
    private lateinit var exceptionsStrip: View
    private lateinit var exceptionsFaces: LinearLayout
    private lateinit var exceptionsDivider: View
    private lateinit var exceptionsApps: LinearLayout
    private lateinit var exceptionsTitle: TextView
    private lateinit var exceptionsCaption: TextView

    private val main = Handler(Looper.getMainLooper())

    private val density by lazy { resources.displayMetrics.density }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (!prefs.onboarded) {
            // Someone who set Flip up before there was a setup screen does not need it now.
            if (prefs.enabled || DndController.hasAccess(this)) {
                prefs.onboarded = true
            } else {
                startActivity(Intent(this, OnboardingActivity::class.java))
                finish()
                return
            }
        }
        setContentView(R.layout.activity_main)

        applySystemBarInsets(findViewById(R.id.scroll), findViewById(R.id.content))

        gauge = findViewById(R.id.gauge)
        powerToggle = findViewById(R.id.power_toggle)
        powerLabel = findViewById(R.id.power_label)
        powerSwitch = findViewById(R.id.power_switch)
        // Over the switch, not under it: as a background the opaque track hid half the highlight.
        powerToggle.foreground = pill(null)
        powerToggle.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Switch::class.java.name
                info.isCheckable = true
                info.isChecked = prefs.enabled
            }
        }
        coach = findViewById(R.id.coach)
        permissionCard = findViewById(R.id.permission_card)
        permissionText = findViewById(R.id.permission_text)
        dndButton = findViewById(R.id.dnd_button)
        dayStrip = findViewById(R.id.day_strip)
        historyList = findViewById(R.id.history_list)
        todayAll = findViewById(R.id.today_all)
        todayAllLabel = findViewById(R.id.today_all_label)
        findViewById<View>(R.id.today_all_row).setOnClickListener { startActivity(Intent(this, TodayActivity::class.java)) }
        todaySince = findViewById(R.id.today_since)
        todaySummary = findViewById(R.id.today_summary)
        todayTotal = findViewById(R.id.today_total)
        todaySentence = findViewById(R.id.today_sentence)
        nightDates = findViewById(R.id.night_dates)
        nightSummary = findViewById(R.id.night_summary)
        nightTotal = findViewById(R.id.night_total)
        nightBed = findViewById(R.id.night_bed)
        nightWake = findViewById(R.id.night_wake)
        nightPickups = findViewById(R.id.night_pickups)
        nightSilenced = findViewById(R.id.night_silenced)
        nightDial = findViewById(R.id.night_dial)
        nightList = findViewById(R.id.night_list)
        sleepHistoryAvg = findViewById(R.id.sleep_history_avg)
        exceptionsStrip = findViewById(R.id.exceptions_strip)
        exceptionsFaces = findViewById(R.id.exceptions_faces)
        exceptionsDivider = findViewById(R.id.exceptions_divider)
        exceptionsApps = findViewById(R.id.exceptions_apps)
        exceptionsTitle = findViewById(R.id.exceptions_title)
        exceptionsCaption = findViewById(R.id.exceptions_caption)

        exceptionsStrip.setOnClickListener {
            startActivity(Intent(this, ExceptionsActivity::class.java))
        }

        powerToggle.setOnClickListener {
            val turningOn = !prefs.enabled
            if (turningOn && !DndController.hasAccess(this)) {
                openDndAccess()
                return@setOnClickListener
            }
            prefs.enabled = turningOn
            if (turningOn) FlipService.start(this) else FlipService.stop(this)
            refresh()
        }

        findViewById<View>(R.id.sleep_history_row).setOnClickListener {
            startActivity(Intent(this, SleepHistoryActivity::class.java))
        }

        findViewById<View>(R.id.settings_button).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        dndButton.setOnClickListener { openDndAccess() }

        // A recreation (rotation, theme, language) redelivers the launch intent; act on it once.
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * Test hooks used while tuning against the real device:
     *   adb shell am start -n app.flipsilence/.MainActivity --ez enable true
     *   adb shell am start -n app.flipsilence/.MainActivity --es force on
     * This is the exported launcher activity, so any app could send these. Debug builds only.
     */
    private fun handleIntent(intent: Intent) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        if (intent.hasExtra(EXTRA_ENABLE)) {
            val on = intent.getBooleanExtra(EXTRA_ENABLE, false)
            prefs.enabled = on
            if (on) FlipService.start(this) else FlipService.stop(this)
        }
        intent.getStringExtra(EXTRA_FORCE)?.let { DndController.setEngaged(this, it == "on") }
    }

    private fun openDndAccess() {
        DndController.openAccessSettings(this)
    }

    override fun onResume() {
        super.onResume()
        // Revoking notifications in system settings kills the process, service and all.
        if (prefs.enabled && DndController.hasAccess(this)) FlipService.start(this)
        FlipState.observe { info -> main.post { render(info) } }
        refresh()
    }

    override fun onPause() {
        FlipState.observe(null)
        super.onPause()
    }

    private fun refresh() {
        val hasDnd = DndController.hasAccess(this)

        val on = prefs.enabled
        powerLabel.setText(if (on) R.string.power_on else R.string.power_off)
        powerLabel.setTextColor(getColor(if (on) R.color.ink else R.color.ink_dim))
        powerToggle.contentDescription = getString(R.string.app_name)
        renderPower(FlipState.info.sample?.engaged == true)

        dndButton.visibility = if (hasDnd) View.GONE else View.VISIBLE
        permissionCard.visibility = if (hasDnd) View.GONE else View.VISIBLE
        permissionText.setText(R.string.dnd_needed)

        renderExceptions()

        val now = System.currentTimeMillis()
        val tracking = prefs.sleepMonitoring
        findViewById<View>(R.id.night_header).visibility = if (tracking) View.VISIBLE else View.GONE
        findViewById<View>(R.id.night_card).visibility = if (tracking) View.VISIBLE else View.GONE
        // With no night to go by, today simply starts at midnight.
        val night = if (tracking) Sleep.lastNight(this, now).night else null
        if (tracking) renderNight(night, now)
        renderToday(night, now)
        render(FlipState.info)
    }

    /**
     * Last night: a small clock face of it, and beside it how long, from when to when, how much
     * of it Flip kept quiet, and how often the phone was picked up. The full breakdown is on the
     * night's own page.
     */
    private fun renderNight(night: Sleep.Night?, now: Long) {
        nightList.removeAllViews()

        renderHistoryRow(night, now)

        if (night == null) {
            nightSummary.visibility = View.GONE
            nightDates.text = ""
            nightList.addNote(getString(R.string.night_empty))
            nightList.visibility = View.VISIBLE
            return
        }

        nightSummary.visibility = View.VISIBLE
        nightDates.text = "${SleepText.weekdayDay(night.eveningMs)} → ${SleepText.weekdayDay(night.morningMs)}"
        nightTotal.text = SleepText.minutes(this, night.asleepMs)
        nightBed.text = SleepText.clock(night.startMs)
        nightWake.text = SleepText.clock(night.endMs)
        nightPickups.text = SleepText.times(this, night.wakeUps)
        if (night.silencedMs > 0) {
            nightSilenced.text = getString(R.string.tl_stretch_silenced, SleepText.minutes(this, night.silencedMs))
            nightSilenced.setTextColor(getColor(R.color.quiet))
        } else {
            nightSilenced.text = getString(R.string.night_not_silenced)
            nightSilenced.setTextColor(getColor(R.color.ink_dim))
        }

        nightDial.setNight(night.startMs, night.endMs, night.pieces, night.silenced, night.moments.map { it.span.startMs })
        nightDial.contentDescription = getString(
            R.string.last_night_description,
            nightTotal.text, nightBed.text, nightWake.text, SleepText.minutes(this, night.silencedMs),
        )

        if (nightDial.laps > 1) nightList.addNote(getString(R.string.night_laps))
        nightList.visibility = if (nightList.childCount > 0) View.VISIBLE else View.GONE
    }

    /**
     * The way into the sleep history: the week's average in moss and, when there is a week before
     * it to go by, an arrow for how far it moved. Within five minutes is no move at all.
     */
    private fun renderHistoryRow(night: Sleep.Night?, now: Long) {
        val history = Sleep.history(this, now, night)
        val today = Sleep.at(now, 0, 0)
        val week = Sleep.within(history, today, 7)
        val row = findViewById<View>(R.id.sleep_history_row)
        if (week.isEmpty()) {
            sleepHistoryAvg.visibility = View.GONE
            row.contentDescription = null
            return
        }
        val avgMs = week.map { it.asleepMs }.average().toLong()
        val before = Sleep.within(history, Sleep.at(today, -7, 0), 7)
        val diffMin = SleepText.diffMin(avgMs, before)

        sleepHistoryAvg.visibility = View.VISIBLE
        sleepHistoryAvg.text = SpannableStringBuilder(getString(R.string.history_avg, SleepText.minutes(this, avgMs))).apply {
            setSpan(ForegroundColorSpan(getColor(R.color.quiet)), 0, length, 0)
            setSpan(TypefaceSpan(resources.getFont(R.font.plus_jakarta_sans_semibold)), 0, length, 0)
            if (diffMin != null && abs(diffMin) >= SleepText.SAME_MIN) append("  ·  ${if (diffMin > 0) "↑" else "↓"} ${SleepText.minutes(this@MainActivity, abs(diffMin) * 60_000L)}")
        }
        row.contentDescription = listOfNotNull(
            getString(R.string.sleep_history),
            getString(R.string.avg_7_nights, SleepText.minutes(this, avgMs)),
            diffMin?.let { SleepText.versus(this, it) },
        ).joinToString(", ")
    }

    /**
     * Today, from waking up: the quiet since then as one number, then when it happened on a
     * midnight-to-midnight strip whose night-time share is struck through, then each silence.
     */
    private fun renderToday(night: Sleep.Night?, now: Long) {
        historyList.removeAllViews()

        val dayStart = Sleep.at(now, 0, 0)
        // Not start + 24h: a clocks-change day is 23 or 25 hours long.
        val dayEnd = Sleep.at(now, 1, 0)
        val woke = TodayActivity.woke(night, now)
        val entries = TodayActivity.silences(this, night, now)
        todayAll.visibility = if (entries.size > TODAY_ROWS) View.VISIBLE else View.GONE
        todayAllLabel.text = getString(R.string.show_all, entries.size)

        todaySince.text = woke?.let { getString(R.string.since_you_woke, SleepText.clock(it)) } ?: ""
        dayStrip.setRange(
            dayStart,
            dayEnd,
            now,
            labels = listOf("00", "06", "12", "18", "24"),
            silences = entries.map { it.span },
            hatch = woke?.let { Span(dayStart, it) },
            nowLabel = getString(R.string.now),
        )

        if (entries.isEmpty()) {
            todaySummary.visibility = View.GONE
            val note = getString(if (woke != null) R.string.today_empty else R.string.history_empty)
            historyList.addNote(note)
            dayStrip.contentDescription = note
            return
        }

        todaySummary.visibility = View.VISIBLE
        val total = entries.sumOf { it.durationMs }
        val longest = entries.maxBy { it.durationMs }
        todayTotal.text = SleepText.seconds(this, total)
        todaySentence.text = if (entries.size == 1) {
            getString(R.string.today_once, SleepText.clock(longest.startMs))
        } else {
            resources.getQuantityString(
                R.plurals.today_many, entries.size,
                entries.size, SleepText.seconds(this, longest.durationMs), SleepText.clock(longest.startMs),
            )
        }
        dayStrip.contentDescription = getString(R.string.today_description, todayTotal.text, todaySentence.text)

        // Only the latest few, so a busy day does not stretch the page; the rest are a tap away.
        entries.take(TODAY_ROWS).forEach { historyList.addSilenceRow(it) }
    }

    /**
     * The strip under the gauge: up to three faces and three app icons, and the count in words.
     * With nothing chosen it shows faint stand-ins for both and becomes the way in, and with apps
     * chosen but no notification access it says so, since those apps would stay silent.
     */
    private fun renderExceptions() {
        val people = if (prefs.favouritesRing) Exceptions.people(this) else emptyList()
        val apps = Exceptions.apps(this)

        exceptionsFaces.removeAllViews()
        people.take(STRIP_MARKS).forEachIndexed { i, p ->
            exceptionsFaces.addStacked(avatar(p.name, p.photo, STRIP_MARK_DP), STRIP_MARK_DP, first = i == 0, oval = true)
        }
        exceptionsApps.removeAllViews()
        apps.take(STRIP_MARKS).forEachIndexed { i, a ->
            exceptionsApps.addStacked(appIcon(a.icon, STRIP_MARK_DP), STRIP_MARK_DP, first = i == 0, oval = false)
        }
        val nothing = people.isEmpty() && apps.isEmpty()
        if (nothing) {
            repeat(GHOST_MARKS) { i ->
                exceptionsFaces.addStacked(ghostMark(oval = true, STRIP_MARK_DP), STRIP_MARK_DP, first = i == 0, oval = true)
                exceptionsApps.addStacked(ghostMark(oval = false, STRIP_MARK_DP), STRIP_MARK_DP, first = i == 0, oval = false)
            }
        }
        exceptionsApps.setPaddingRelative(if (nothing) (4 * density).roundToInt() else 0, 0, 0, 0)
        exceptionsFaces.visibility = if (people.isEmpty() && !nothing) View.GONE else View.VISIBLE
        exceptionsApps.visibility = if (apps.isEmpty() && !nothing) View.GONE else View.VISIBLE
        exceptionsDivider.visibility = if (people.isNotEmpty() && apps.isNotEmpty()) View.VISIBLE else View.GONE

        val parts = buildList {
            if (people.isNotEmpty()) add(resources.getQuantityString(R.plurals.people, people.size, people.size))
            if (apps.isNotEmpty()) add(resources.getQuantityString(R.plurals.apps, apps.size, apps.size))
        }
        val appsMuted = apps.isNotEmpty() && !Exceptions.hasListenerAccess(this)
        if (parts.isEmpty()) {
            exceptionsTitle.setText(R.string.no_exceptions_yet)
            exceptionsCaption.setText(R.string.tap_to_choose_who_rings)
        } else {
            exceptionsTitle.text = parts.joinToString(", ")
            exceptionsCaption.setText(
                when {
                    appsMuted -> R.string.apps_need_access
                    people.size + apps.size == 1 -> R.string.still_gets_through
                    else -> R.string.still_get_through
                }
            )
        }
        exceptionsCaption.setTextColor(getColor(if (appsMuted) R.color.ink else R.color.ink_dim))
    }

    /** The header switch: off, on and watching, or on and holding the phone quiet right now. */
    private fun renderPower(engaged: Boolean) {
        val on = prefs.enabled
        val quiet = on && engaged
        powerSwitch.setState(on, quiet)
        powerToggle.stateDescription = getString(
            when {
                quiet -> R.string.silenced
                on -> R.string.watching
                else -> R.string.not_watching
            }
        )
    }

    private fun render(info: FlipState.Info) {
        val s = info.sample
        val level = prefs.sensitivity

        val tilt = if (s == null) 180f else {
            Math.toDegrees(acos((-s.gravityZ / 9.81f).coerceIn(-1f, 1f).toDouble())).toFloat()
        }
        val heldFraction =
            if (s == null || s.holdTargetMs == 0L) 0f else s.heldMs.toFloat() / s.holdTargetMs
        val engaged = s?.engaged == true

        val since = info.lastSilencedAtMs.takeIf { engaged && it > 0L }?.let { SleepText.clock(it) }
        gauge.setState(tilt, heldFraction, engaged, level.thresholdDeg, since)
        renderPower(engaged)

        coach.setText(
            when {
                !DndController.hasAccess(this) -> R.string.coach_grant
                !prefs.enabled -> R.string.coach_turn_on
                engaged -> R.string.coach_silenced
                s == null -> R.string.coach_starting
                !s.steady -> R.string.coach_tilting
                s.heldMs > 0 -> R.string.coach_almost
                s.faceDown && !s.still -> R.string.coach_hold_still
                else -> R.string.coach_put_down
            }
        )

    }

    private companion object {
        const val EXTRA_ENABLE = "enable"
        const val EXTRA_FORCE = "force"
        const val STRIP_MARKS = 3
        const val STRIP_MARK_DP = 30f
        /** Stand-ins of each kind while nothing is chosen. */
        const val GHOST_MARKS = 2
        /** Silences listed under today's strip before the rest move to their own screen. */
        const val TODAY_ROWS = 3
    }
}
