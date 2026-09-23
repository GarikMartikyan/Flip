package app.flipsilence

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.ContactsContract
import android.provider.Settings
import android.util.Log

/**
 * What still gets through while the phone is face down.
 *
 * People are the phone's starred contacts: that is the only list of callers Do Not Disturb can let
 * through, and only as a whole. Adding someone here stars them and removing them unstars them;
 * silencing them all while keeping them starred is a switch. Apps are Flip's own
 * list, because Android offers no way for an app to choose which other apps Do Not Disturb lets
 * through. Their notifications are still posted, only muted, so [ExceptionListener] sees them and
 * Flip sounds for them itself.
 */
object Exceptions {

    private const val TAG = "Flip"

    data class Person(val id: Long, val name: String, val photo: Uri?)

    data class App(val pkg: String, val label: String, val icon: Drawable)

    fun hasContacts(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED

    val CONTACTS_PERMISSIONS = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)

    /** Starred contacts that can actually call, by name. Empty without contacts access. */
    fun people(ctx: Context): List<Person> {
        if (!hasContacts(ctx)) return emptyList()
        val c = ContactsContract.Contacts._ID
        val name = ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
        val photo = ContactsContract.Contacts.PHOTO_THUMBNAIL_URI
        return runCatching {
            ctx.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(c, name, photo),
                "${ContactsContract.Contacts.STARRED} = 1 AND ${ContactsContract.Contacts.HAS_PHONE_NUMBER} = 1",
                null,
                "$name COLLATE LOCALIZED ASC",
            )?.use { cur ->
                buildList {
                    while (cur.moveToNext()) {
                        add(
                            Person(
                                id = cur.getLong(0),
                                name = cur.getString(1) ?: "",
                                photo = cur.getString(2)?.let(Uri::parse),
                            )
                        )
                    }
                }
            } ?: emptyList()
        }.getOrElse {
            Log.e(TAG, "could not read starred contacts", it)
            emptyList()
        }
    }

    fun setStarred(ctx: Context, contactId: Long, starred: Boolean) {
        runCatching {
            ctx.contentResolver.update(
                ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId),
                ContentValues().apply { put(ContactsContract.Contacts.STARRED, if (starred) 1 else 0) },
                null,
                null,
            )
        }.onFailure { Log.e(TAG, "could not ${if (starred) "star" else "unstar"} $contactId", it) }
    }

    /** The contact behind a row picked from the phone-number picker. */
    fun contactIdOfPhone(ctx: Context, phoneUri: Uri): Long? = runCatching {
        ctx.contentResolver.query(
            phoneUri,
            arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID),
            null,
            null,
            null,
        )?.use { if (it.moveToFirst()) it.getLong(0) else null }
    }.getOrNull()

    /** The allowed apps that are still installed, by name. */
    fun apps(ctx: Context): List<App> {
        val pm = ctx.packageManager
        return Prefs(ctx).allowedApps.mapNotNull { pkg ->
            runCatching {
                val info = pm.getApplicationInfo(pkg, 0)
                App(pkg, pm.getApplicationLabel(info).toString(), pm.getApplicationIcon(info))
            }.getOrNull()
        }.sortedBy { it.label.lowercase() }
    }

    /** Every app with a launcher icon but Flip, by name: what the picker offers. */
    fun launchable(ctx: Context): List<App> {
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != ctx.packageName }
            .map { App(it.packageName, pm.getApplicationLabel(it).toString(), pm.getApplicationIcon(it)) }
            .sortedBy { it.label.lowercase() }
    }

    fun setAllowed(ctx: Context, pkg: String, allowed: Boolean) {
        val prefs = Prefs(ctx)
        prefs.allowedApps = if (allowed) prefs.allowedApps + pkg else prefs.allowedApps - pkg
    }

    private fun listenerComponent(ctx: Context) = ComponentName(ctx, ExceptionListener::class.java)

    fun hasListenerAccess(ctx: Context): Boolean =
        ctx.getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(listenerComponent(ctx))

    fun listenerSettingsIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                listenerComponent(ctx).flattenToString(),
            )

    /**
     * Called the moment before silencing, while the ringer still says what the user chose. Once
     * Do Not Disturb is on, the notification stream reads as muted whatever it was.
     */
    fun rememberSound(ctx: Context) {
        val audio = ctx.getSystemService(AudioManager::class.java)
        val prefs = Prefs(ctx)
        prefs.ringerBefore = audio.ringerMode
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION)
        prefs.notificationVolumeBefore =
            if (max > 0) audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION).toFloat() / max else 0f
    }

    /**
     * Alerts for an allowed app the way the phone was set before it went quiet: sound and a buzz,
     * a buzz alone, or nothing. Both go out as alarms, the one usage the rule lets through, and the
     * sound is scaled so the alarm stream plays it about as loud as a notification would have been.
     */
    fun alert(ctx: Context) {
        val prefs = Prefs(ctx)
        val mode = prefs.ringerBefore
        if (mode == AudioManager.RINGER_MODE_SILENT) return

        val vibrator = ctx.getSystemService(VibratorManager::class.java).defaultVibrator
        if (vibrator.hasVibrator()) {
            vibrator.vibrate(
                VibrationEffect.createWaveform(ALERT_PATTERN_MS, -1),
                VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_ALARM).build(),
            )
        }

        if (mode != AudioManager.RINGER_MODE_NORMAL) return
        val audio = ctx.getSystemService(AudioManager::class.java)
        val alarmMax = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val alarmLevel = if (alarmMax > 0) audio.getStreamVolume(AudioManager.STREAM_ALARM).toFloat() / alarmMax else 0f
        if (alarmLevel <= 0f || prefs.notificationVolumeBefore <= 0f) return

        runCatching {
            val tone = RingtoneManager.getRingtone(ctx, Settings.System.DEFAULT_NOTIFICATION_URI) ?: return
            tone.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            tone.volume = (prefs.notificationVolumeBefore / alarmLevel).coerceIn(0f, 1f)
            tone.play()
        }.onFailure { Log.e(TAG, "could not play alert", it) }
    }

    /** Close to a stock notification buzz: two short pulses. */
    private val ALERT_PATTERN_MS = longArrayOf(0, 180, 120, 180)
}
