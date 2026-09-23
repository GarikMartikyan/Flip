package app.flipsilence

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Every silence today, newest first. The main screen lists only the latest few. */
class TodayActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_today)
        applySystemBarInsets(findViewById(R.id.scroll), findViewById(R.id.content))
        findViewById<View>(R.id.back).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        val now = System.currentTimeMillis()
        val night = if (Prefs(this).sleepMonitoring) Sleep.lastNight(this, now).night else null
        val woke = woke(night, now)
        val entries = silences(this, night, now)

        val since = findViewById<TextView>(R.id.since)
        since.text = woke?.let { getString(R.string.since_you_woke, SleepText.clock(it)) } ?: ""
        since.visibility = if (woke == null) View.GONE else View.VISIBLE

        val list = findViewById<LinearLayout>(R.id.list)
        list.removeAllViews()
        if (entries.isEmpty()) {
            list.addNote(getString(if (woke != null) R.string.today_empty else R.string.history_empty))
        } else {
            entries.forEach { list.addSilenceRow(it) }
        }
    }

    companion object {
        /** When last night ended, if that was today: today is counted from then, not from midnight. */
        fun woke(night: Sleep.Night?, now: Long): Long? =
            night?.endMs?.takeIf { it in Sleep.at(now, 0, 0)..now }

        /** Today's silences, newest first, leaving out any that last night already counts. */
        fun silences(ctx: Context, night: Sleep.Night?, now: Long): List<History.Entry> {
            val since = woke(night, now) ?: Sleep.at(now, 0, 0)
            return History.all(ctx).filter { it.startMs >= since }.sortedByDescending { it.startMs }
        }
    }
}
