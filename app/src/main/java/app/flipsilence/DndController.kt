package app.flipsilence

import android.app.Activity
import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.service.notification.Condition
import android.service.notification.ZenPolicy
import android.util.Log

/**
 * Owns exactly one AutomaticZenRule and toggles it.
 *
 * Since Android 15, an app cannot set the device's global Do Not Disturb state -- calls to
 * setInterruptionFilter create an implicit rule instead, and an app may only clear a rule it owns.
 * Owning both the on and the off transition here is what keeps face-up reliably un-silencing.
 *
 * The rule carries its own ZenPolicy for calls: starred contacts ring if the user wants them, and
 * so do repeat callers. Starred is all-or-nothing, since a rule cannot name individual contacts. Alarms are
 * allowed explicitly, since Flip's own haptics and its alerts for allowed apps go out as alarms.
 * Everything the policy leaves unset is inherited from the user's own Do Not Disturb settings.
 */
object DndController {

    private const val TAG = "Flip"
    private val CONDITION_ID: Uri = Uri.parse("condition://app.flipsilence/facedown")

    private fun nm(ctx: Context) =
        ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun hasAccess(ctx: Context): Boolean = nm(ctx).isNotificationPolicyAccessGranted

    /**
     * Opens Flip's own Do Not Disturb access page, one switch, rather than the list of every app
     * that asked. That page's action is a system API, but Settings exports it (it is there on
     * One UI 8.5); where it is missing, the list it is.
     */
    fun openAccessSettings(activity: Activity) {
        val own = Intent(ACTION_ACCESS_DETAIL, Uri.fromParts("package", activity.packageName, null))
        try {
            activity.startActivity(own)
        } catch (_: ActivityNotFoundException) {
            activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        } catch (_: SecurityException) {
            activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
    }

    /** Settings.ACTION_NOTIFICATION_POLICY_ACCESS_DETAIL_SETTINGS, which apps cannot name directly. */
    private const val ACTION_ACCESS_DETAIL = "android.settings.NOTIFICATION_POLICY_ACCESS_DETAIL_SETTINGS"

    /**
     * Returns the id of our rule, creating it if the user deleted it or this is a first run.
     * Null if Do Not Disturb access has not been granted yet.
     */
    private fun ruleId(ctx: Context): String? {
        val nm = nm(ctx)
        if (!nm.isNotificationPolicyAccessGranted) return null

        val prefs = Prefs(ctx)
        val existing = prefs.ruleId
        if (existing != null && nm.automaticZenRules.containsKey(existing)) return existing

        // Recover a rule created by a previous install before minting a duplicate.
        nm.automaticZenRules.entries
            .firstOrNull { it.value.conditionId == CONDITION_ID }
            ?.let {
                prefs.ruleId = it.key
                return it.key
            }

        return try {
            val id = nm.addAutomaticZenRule(newRule(ctx))
            prefs.ruleId = id
            Log.i(TAG, "created zen rule $id")
            id
        } catch (t: Throwable) {
            Log.e(TAG, "could not create zen rule", t)
            null
        }
    }

    /**
     * AutomaticZenRule.Builder only exists from Android 15. On 14 the older constructor builds the
     * same rule; a null owner is fine, since the configuration activity stands in for it.
     */
    private fun newRule(ctx: Context): AutomaticZenRule {
        val name = ctx.getString(R.string.zen_rule_name)
        val config = ComponentName(ctx, MainActivity::class.java)
        val filter = NotificationManager.INTERRUPTION_FILTER_PRIORITY
        if (Build.VERSION.SDK_INT < 35) {
            return AutomaticZenRule(name, null, config, CONDITION_ID, policy(ctx), filter, true)
        }
        return AutomaticZenRule.Builder(name, CONDITION_ID)
            .setConfigurationActivity(config)
            .setInterruptionFilter(filter)
            .setZenPolicy(policy(ctx))
            .setType(AutomaticZenRule.TYPE_OTHER)
            .setEnabled(true)
            .build()
    }

    private fun policy(ctx: Context): ZenPolicy = ZenPolicy.Builder()
        .allowCalls(if (Prefs(ctx).favouritesRing) ZenPolicy.PEOPLE_TYPE_STARRED else ZenPolicy.PEOPLE_TYPE_NONE)
        .allowRepeatCallers(Prefs(ctx).repeatCallers)
        .allowAlarms(true)
        .build()

    /**
     * Writes the current policy onto the rule. Needed after either calls switch changes, and
     * once for a rule made by an older Flip, which had no policy of its own.
     */
    fun syncPolicy(ctx: Context) {
        val id = ruleId(ctx) ?: return
        val nm = nm(ctx)
        try {
            val rule = nm.getAutomaticZenRule(id) ?: return
            val wanted = policy(ctx)
            if (sameCalls(rule.zenPolicy, wanted)) return
            rule.zenPolicy = wanted
            nm.updateAutomaticZenRule(id, rule)
            Log.i(TAG, "zen rule $id policy updated")
        } catch (t: Throwable) {
            Log.e(TAG, "could not update zen rule policy", t)
        }
    }

    /**
     * Compares only what [policy] sets. The system may hand the rule back with its other fields
     * filled in, and a whole-object comparison would then never match and rewrite it on every start.
     */
    private fun sameCalls(a: ZenPolicy?, b: ZenPolicy): Boolean =
        a != null &&
            a.priorityCallSenders == b.priorityCallSenders &&
            a.priorityCategoryCalls == b.priorityCategoryCalls &&
            a.priorityCategoryRepeatCallers == b.priorityCategoryRepeatCallers &&
            a.priorityCategoryAlarms == b.priorityCategoryAlarms

    fun setEngaged(ctx: Context, on: Boolean) {
        val id = ruleId(ctx) ?: run {
            Log.w(TAG, "no zen rule; DND access missing?")
            return
        }
        val condition = Condition(
            CONDITION_ID,
            ctx.getString(if (on) R.string.condition_face_down else R.string.condition_face_up),
            if (on) Condition.STATE_TRUE else Condition.STATE_FALSE,
        )
        try {
            nm(ctx).setAutomaticZenRuleState(id, condition)
            Log.i(TAG, "zen rule $id -> ${if (on) "ON" else "OFF"}")
        } catch (t: Throwable) {
            Log.e(TAG, "could not set zen rule state", t)
        }
    }
}
