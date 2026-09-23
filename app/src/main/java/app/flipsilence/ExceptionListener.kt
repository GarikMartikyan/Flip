package app.flipsilence

import android.app.Notification
import android.app.NotificationManager
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Sees every notification, including the ones Do Not Disturb is muting, and alerts for the ones
 * from allowed apps while Flip has the phone silenced. Only the fact that one arrived and which app
 * posted it is used; the content is never read.
 */
class ExceptionListener : NotificationListenerService() {

    /** Notifications already alerted for in this silence, so an update does not alert again. */
    private val alerted = HashSet<String>()
    private var lastAlertAt = 0L

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (FlipState.info.sample?.engaged != true) {
            alerted.clear()
            return
        }
        if (sbn.packageName !in Prefs(this).allowedApps) return

        val n = sbn.notification
        val call = isIncomingCall(n)
        // Ongoing work and group summaries are not news: the summary's children alert instead.
        // A VoIP app's ringing call is posted as ongoing too, and that one is the most urgent of all.
        if (!call && n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_GROUP_SUMMARY) != 0) return
        if (n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0 && sbn.key in alerted) return

        val ranking = Ranking()
        if (currentRanking.getRanking(sbn.key, ranking)) {
            // A channel the user or the app made silent stays silent here too.
            if (ranking.importance < NotificationManager.IMPORTANCE_DEFAULT) return
            // Do Not Disturb let this one through by itself (the user's own settings allow it, or the
            // rule is not actually on), so the system is already sounding it. Once is enough.
            if (ranking.matchesInterruptionFilter()) return
        }

        // A burst of messages arriving together is one alert, not a drum roll.
        val now = SystemClock.elapsedRealtime()
        alerted.add(sbn.key)
        if (!call && now - lastAlertAt < BURST_MS) return
        lastAlertAt = now

        Log.i(TAG, "alert for ${sbn.packageName}")
        Exceptions.alert(this)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        alerted.remove(sbn.key)
    }

    /**
     * A call that is ringing now, as opposed to one already in progress. CallStyle says which since
     * Android 12; an app that does not use it still gives a ringing call a full-screen intent.
     */
    private fun isIncomingCall(n: Notification): Boolean {
        if (n.category != Notification.CATEGORY_CALL) return false
        return when (n.extras.getInt(Notification.EXTRA_CALL_TYPE, Notification.CallStyle.CALL_TYPE_UNKNOWN)) {
            Notification.CallStyle.CALL_TYPE_INCOMING, Notification.CallStyle.CALL_TYPE_SCREENING -> true
            Notification.CallStyle.CALL_TYPE_ONGOING -> false
            else -> n.fullScreenIntent != null
        }
    }

    private companion object {
        const val TAG = "Flip"
        const val BURST_MS = 3_000L
    }
}
