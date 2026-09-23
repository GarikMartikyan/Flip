package app.flipsilence

import android.app.Activity
import android.os.Bundle
import android.view.View

/** One night on its own: the strip drawn large, the times, and what happened in order. */
class NightActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_night)
        applySystemBarInsets(findViewById(R.id.scroll), findViewById(R.id.content))
        findViewById<View>(R.id.back).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        val morning = intent.getLongExtra(EXTRA_MORNING, 0L)
        val history = Sleep.history(this, System.currentTimeMillis())
        val night = history.firstOrNull { it.morningMs == morning }
        findViewById<View>(R.id.empty).visibility = if (night == null) View.VISIBLE else View.GONE
        findViewById<View>(R.id.body).visibility = if (night == null) View.GONE else View.VISIBLE
        if (night != null) NightPage(findViewById(R.id.content)).bind(night, history)
    }

    companion object {
        const val EXTRA_MORNING = "morning"
    }
}
