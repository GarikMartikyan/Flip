package app.flipsilence

import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Who and what still gets through while the phone is face down: the people whose calls ring, and
 * the apps Flip still sounds for. Reached from the strip under the gauge.
 */
class ExceptionsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var peopleList: LinearLayout
    private lateinit var appsList: LinearLayout
    private lateinit var favouritesSwitch: Switch
    private lateinit var repeatSwitch: Switch
    private lateinit var listenerStatus: TextView

    /** Set while notification settings are open on the way to adding an app. */
    private var addAppAfterAccess = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_exceptions)
        prefs = Prefs(this)
        addAppAfterAccess = savedInstanceState?.getBoolean(STATE_ADD_APP) ?: false

        applySystemBarInsets(findViewById(R.id.scroll), findViewById(R.id.content))

        peopleList = findViewById(R.id.people_list)
        appsList = findViewById(R.id.apps_list)
        favouritesSwitch = findViewById(R.id.favourites_switch)
        repeatSwitch = findViewById(R.id.repeat_switch)
        listenerStatus = findViewById(R.id.listener_status)

        findViewById<View>(R.id.back).setOnClickListener { finish() }

        favouritesSwitch.isChecked = prefs.favouritesRing
        favouritesSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.favouritesRing = checked
            DndController.syncPolicy(this)
            renderPeople()
        }

        repeatSwitch.isChecked = prefs.repeatCallers
        repeatSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.repeatCallers = checked
            DndController.syncPolicy(this)
        }

        findViewById<View>(R.id.add_person).setOnClickListener { addPerson() }
        findViewById<View>(R.id.add_app).setOnClickListener { addApp() }
        findViewById<View>(R.id.listener_row).setOnClickListener { openListenerSettings() }
    }

    override fun onResume() {
        super.onResume()
        // Contacts, the apps list and notification access can all change on the screens we open.
        renderPeople()
        renderApps()
        if (addAppAfterAccess) {
            addAppAfterAccess = false
            if (Exceptions.hasListenerAccess(this)) startActivity(Intent(this, AppPickerActivity::class.java))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Android may drop this screen while its settings page is in front.
        outState.putBoolean(STATE_ADD_APP, addAppAfterAccess)
    }

    private fun renderPeople() {
        peopleList.removeAllViews()
        // Switched off, the list stays readable but reads as not in force.
        peopleList.alpha = if (prefs.favouritesRing) 1f else DIMMED
        if (!Exceptions.hasContacts(this)) {
            peopleList.addRowNote(getString(R.string.people_need_contacts))
            return
        }
        val people = Exceptions.people(this)
        if (people.isEmpty()) {
            peopleList.addRowNote(getString(R.string.people_empty))
            return
        }
        people.forEachIndexed { i, p ->
            if (i > 0) peopleList.addRowDivider()
            peopleList.addMarkRow(avatar(p.name, p.photo, ROW_MARK_DP), p.name) {
                Exceptions.setStarred(this, p.id, false)
                renderPeople()
            }
        }
    }

    private fun renderApps() {
        appsList.removeAllViews()
        val apps = Exceptions.apps(this)
        if (apps.isEmpty()) {
            appsList.addRowNote(getString(R.string.apps_empty))
        } else {
            apps.forEachIndexed { i, a ->
                if (i > 0) appsList.addRowDivider()
                appsList.addMarkRow(appIcon(a.icon, ROW_MARK_DP), a.label) {
                    Exceptions.setAllowed(this, a.pkg, false)
                    renderApps()
                }
            }
        }

        val granted = Exceptions.hasListenerAccess(this)
        listenerStatus.setText(if (granted) R.string.dnd_granted else R.string.listener_missing)
        // Only worth raising when it is holding something back.
        listenerStatus.setTextColor(getColor(if (!granted && apps.isNotEmpty()) R.color.ink else R.color.ink_dim))
    }

    private fun addPerson() {
        if (!Exceptions.hasContacts(this)) {
            requestOrOpenSettings(
                Exceptions.CONTACTS_PERMISSIONS,
                REQUEST_CONTACTS,
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.fromParts("package", packageName, null)),
            )
            return
        }
        // The phone-number picker, so only people who can actually call are offered.
        startActivityForResult(
            Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI),
            REQUEST_PICK,
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CONTACTS) return
        notePermissionResult(permissions, grantResults)
        // Refused or dismissed, the answer stands; the next tap goes to settings if Android stopped asking.
        if (Exceptions.hasContacts(this)) {
            renderPeople()
            addPerson()
        }
    }

    @Deprecated("Activity result APIs need AndroidX, which Flip does not use")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val id = Exceptions.contactIdOfPhone(this, uri) ?: return
        Exceptions.setStarred(this, id, true)
        renderPeople()
    }

    /**
     * Without notification access an added app would stay silent, so access comes first: a word on
     * why, then its settings, and the picker opens once it is granted.
     */
    private fun addApp() {
        if (Exceptions.hasListenerAccess(this)) {
            startActivity(Intent(this, AppPickerActivity::class.java))
        } else {
            askForListenerAccess()
        }
    }

    private fun askForListenerAccess() {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_listener_access)
        dialog.window?.apply {
            // The card draws its own rounded ground; the window only sizes and dims.
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val margin = (DIALOG_MARGIN_DP * resources.displayMetrics.density).roundToInt()
            setLayout(
                minOf(resources.displayMetrics.widthPixels - 2 * margin, (DIALOG_MAX_DP * resources.displayMetrics.density).roundToInt()),
                WindowManager.LayoutParams.WRAP_CONTENT,
            )
        }
        dialog.findViewById<View>(R.id.not_now).setOnClickListener { dialog.dismiss() }
        dialog.findViewById<View>(R.id.open_settings).setOnClickListener {
            dialog.dismiss()
            addAppAfterAccess = true
            openListenerSettings()
        }
        dialog.show()
    }

    private fun openListenerSettings() {
        // The detail page goes straight to Flip's switch; fall back to the list where it is not.
        try {
            startActivity(Exceptions.listenerSettingsIntent(this))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    private companion object {
        const val REQUEST_CONTACTS = 1
        const val REQUEST_PICK = 2
        const val ROW_MARK_DP = 36f
        const val DIMMED = 0.4f
        const val DIALOG_MARGIN_DP = 24f
        const val DIALOG_MAX_DP = 420f
        const val STATE_ADD_APP = "add_app_after_access"
    }
}
