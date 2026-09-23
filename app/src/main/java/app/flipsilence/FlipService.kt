package app.flipsilence

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

class FlipService : Service() {

    private lateinit var sensorManager: SensorManager
    private lateinit var powerManager: PowerManager
    private var accelerometer: Sensor? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockUntil = 0L
    private var detector: FlipDetector? = null

    private var eventCount = 0
    private var lastHeartbeat = 0L
    private var engagedAtElapsed = 0L
    private var engagedAtWall = 0L
    private var rateWindowStart = 0L

    /** When the screen last went dark, or 0 while it is on. Each dark stretch feeds [Sleep]. */
    private var darkSinceElapsed = 0L
    private var darkSinceWall = 0L

    /**
     * When the next alarm is set to ring, as last read. The system moves its "next alarm" on as soon
     * as one goes off, so a change that finds this already due is that alarm ringing.
     */
    private var nextAlarmMs = 0L

    /** Sequences the second heavy click, and runs the screen-on backstop once samples had a chance. */
    private val main = Handler(Looper.getMainLooper())

    /** When the last accelerometer sample arrived, and when the screen last came on, elapsed ms. */
    private var lastSampleElapsed = 0L
    private var screenOnElapsed = 0L
    private val backstop = Runnable { runBackstop() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> onScreenOff()
                Intent.ACTION_SCREEN_ON -> onScreenOn()
                Intent.ACTION_USER_PRESENT -> if (Prefs(context).sleepMonitoring) {
                    Sleep.addMoment(context, Sleep.Moment.UNLOCK, System.currentTimeMillis())
                }
                AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED -> onNextAlarmChanged()
            }
        }
    }

    /**
     * Pressing the power button and *then* laying the phone down is how most people go to bed, and
     * on a device without a wake-up accelerometer the CPU can suspend in between: no samples arrive,
     * the hold never completes, and the phone lies face down all night without silencing. So the
     * CPU is kept up for a short grace period after the screen goes dark, long enough for the hold
     * to finish. Silencing releases it early.
     */
    private fun onScreenOff() {
        markDark()
        if (detector?.engaged == false) {
            acquireBriefWakeLock(SCREEN_OFF_GRACE_MS)
            Log.i(TAG, "screen off - awake ${SCREEN_OFF_GRACE_MS}ms for a set-down")
        }
    }

    /**
     * Reads the next alarm afresh, and records the previous one as having rung if its time has come.
     * A snoozed alarm ringing again is recorded the same way, as one more alarm.
     */
    private fun onNextAlarmChanged() {
        val now = System.currentTimeMillis()
        val due = nextAlarmMs
        nextAlarmMs = readNextAlarm()
        if (due != 0L && due <= now + ALARM_EARLY_MS && due > now - ALARM_LATE_MS && Prefs(this).sleepMonitoring) {
            Sleep.addMoment(this, Sleep.Moment.ALARM, due)
            Log.i(TAG, "alarm rang at $due")
        }
    }

    private fun readNextAlarm(): Long =
        (getSystemService(Context.ALARM_SERVICE) as AlarmManager).nextAlarmClock?.triggerTime ?: 0L

    private fun markDark() {
        if (darkSinceElapsed != 0L) return
        darkSinceElapsed = SystemClock.elapsedRealtime()
        darkSinceWall = System.currentTimeMillis()
    }

    /**
     * Picking the phone up almost always turns the screen on, which wakes us even if the CPU had
     * suspended and we missed accelerometer samples. That makes SCREEN_ON the backstop that stops
     * the phone from getting stuck in Do Not Disturb.
     *
     * But the screen also comes on by itself while the phone lies still: a starred contact calling,
     * an alarm. So the backstop does not release at once. It gives the accelerometer a moment to
     * report, and steps in only if it stays quiet; a live sensor already releases on a real pickup.
     */
    private fun onScreenOn() {
        if (darkSinceElapsed != 0L) {
            if (Prefs(this).sleepMonitoring) {
                Sleep.addRest(this, darkSinceWall, SystemClock.elapsedRealtime() - darkSinceElapsed)
            }
            darkSinceElapsed = 0L
            darkSinceWall = 0L
        }

        val d = detector ?: return
        if (d.engaged) {
            screenOnElapsed = SystemClock.elapsedRealtime()
            main.removeCallbacks(backstop)
            main.postDelayed(backstop, BACKSTOP_WAIT_MS)
        }
        reconcile()
    }

    private fun runBackstop() {
        val d = detector ?: return
        if (!d.engaged) return
        if (lastSampleElapsed >= screenOnElapsed) return
        Log.i(TAG, "screen on while engaged, no samples since - releasing")
        d.forceDisengage()
        if (engagedAtElapsed > 0L) {
            History.add(this, engagedAtWall, SystemClock.elapsedRealtime() - engagedAtElapsed)
            engagedAtElapsed = 0L
            engagedAtWall = 0L
        }
        vibrate()
        pushNotification()
        // The last sample still says engaged, and the UI and ExceptionListener both read it.
        FlipState.update { it.copy(sample = it.sample?.copy(engaged = false, changed = false)) }
        reconcile()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Every start that came through startForegroundService owes a startForeground, reload
        // included: a reload can be what creates the service, if the process was killed.
        startForeground(
            NOTIFICATION_ID,
            buildNotification(detector?.engaged == true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )

        val d = detector
        if (d == null) {
            // A fresh start reads every setting anyway, so there is nothing left to reload.
            start()
        } else if (intent?.action == ACTION_RELOAD) {
            d.sensitivity = Prefs(this).sensitivity
            Log.i(TAG, "reloaded: sensitivity=${d.sensitivity.name}")
        }
        return START_STICKY
    }

    private fun start() {
        DndController.syncPolicy(this)

        // A wake-up accelerometer keeps delivering while the CPU is suspended. Samsung flagships
        // usually expose one; if this device does not, the SCREEN_ON backstop carries the release.
        val wake = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true)
        val normal = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        accelerometer = wake ?: normal

        val sensor = accelerometer
        if (sensor == null) {
            Log.e(TAG, "no accelerometer")
            stopSelf()
            return
        }

        val d = FlipDetector(Prefs(this).sensitivity, ::onSample)
        detector = d
        sensorManager.registerListener(d, sensor, SAMPLE_PERIOD_US)

        // Observed only for now. If it turns out to report near against a desk it is a wake-up,
        // on-change sensor, which would be both a better pocket guard and a free screen-off path.
        val proximity = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY, true)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        if (proximity != null) {
            d.proximityMaxRange = proximity.maximumRange
            sensorManager.registerListener(d, proximity, SensorManager.SENSOR_DELAY_NORMAL)
            Log.i(TAG, "proximity: ${proximity.name} maxRange=${proximity.maximumRange} wakeUp=${proximity.isWakeUpSensor}")
        } else {
            Log.i(TAG, "proximity: none exposed")
        }

        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
            },
        )
        nextAlarmMs = readNextAlarm()
        // Started in the dark (at boot, or restarted by the system): the stretch began at least now.
        if (!powerManager.isInteractive) markDark()

        // Start from a known-clean state: a fresh detector is never engaged, so neither is the rule.
        reconcile()

        rateWindowStart = SystemClock.elapsedRealtime()
        FlipState.update {
            it.copy(
                running = true,
                wakeUpSensor = wake != null,
                sensorName = sensor.name,
            )
        }
        Log.i(TAG, "started on ${sensor.name} (wakeUp=${wake != null})")
    }

    private fun onSample(s: FlipDetector.Snapshot) {
        lastSampleElapsed = SystemClock.elapsedRealtime()
        // Hold the CPU just long enough for a candidate to finish its debounce, so putting the
        // phone down right as the device idles still registers. Times out on its own.
        if (!s.engaged && s.heldMs > 0) acquireBriefWakeLock()

        if (s.changed) {
            vibrate()
            if (s.engaged) Exceptions.rememberSound(this)
            DndController.setEngaged(this, s.engaged)
            pushNotification()
            releaseWakeLock()
            Log.i(
                TAG,
                "${if (s.engaged) "ENGAGE" else "RELEASE"} gz=${s.gravityZ} motion=${s.motion} " +
                    "prox=${s.proximity} near=${s.proximityNear}",
            )
        }

        eventCount++
        val now = SystemClock.elapsedRealtime()

        // Heartbeat: the only way to know whether a non-wake-up sensor keeps delivering once the
        // screen is off and the device idles is to watch it do so.
        if (now - lastHeartbeat > HEARTBEAT_MS) {
            val since = if (lastHeartbeat == 0L) 0 else now - lastHeartbeat
            Log.i(TAG, "heartbeat gz=${s.gravityZ} prox=${s.proximity} engaged=${s.engaged} gapMs=$since")
            lastHeartbeat = now
        }

        val elapsed = now - rateWindowStart
        val rate = if (elapsed > 0) eventCount * 1000f / elapsed else 0f
        if (elapsed > 5_000) {
            eventCount = 0
            rateWindowStart = now
        }

        FlipState.update {
            it.copy(
                sample = s,
                eventsPerSec = rate,
                lastSilencedAtMs = if (s.changed && s.engaged) {
                    System.currentTimeMillis()
                } else it.lastSilencedAtMs,
            )
        }

        if (s.changed && !s.engaged && engagedAtElapsed > 0L) {
            History.add(this, engagedAtWall, now - engagedAtElapsed)
        }
        if (s.changed) {
            engagedAtElapsed = if (s.engaged) now else 0L
            engagedAtWall = if (s.engaged) System.currentTimeMillis() else 0L
        }
    }

    /**
     * Drives the zen rule back to whatever the detector currently believes.
     *
     * Without this, any drift between the two -- a process kill mid-engage, a crash, a stale rule
     * left over from a previous install -- strands the phone in Do Not Disturb with nothing to ever
     * clear it. Run on every service start and every screen-on, which bounds how long any such drift
     * can survive to "until you next look at your phone".
     */
    private fun reconcile() {
        val shouldBeEngaged = detector?.engaged == true
        DndController.setEngaged(this, shouldBeEngaged)
    }

    /**
     * Keeps the CPU up for [timeoutMs]. A hold with at least half that still to run is left alone,
     * so calling this on every sample re-arms it every couple of seconds rather than ten times a
     * second, and a longer hold already running is never cut short.
     */
    private fun acquireBriefWakeLock(timeoutMs: Long = WAKELOCK_TIMEOUT_MS) {
        val now = SystemClock.elapsedRealtime()
        val existing = wakeLock
        if (existing != null && existing.isHeld && wakeLockUntil - now >= timeoutMs / 2) return
        // Re-acquiring a non-reference-counted lock re-arms its timeout rather than stacking.
        val wl = existing ?: powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "flip:debounce")
            .apply { setReferenceCounted(false) }
        wl.acquire(timeoutMs)
        wakeLock = wl
        wakeLockUntil = now + timeoutMs
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wakeLockUntil = 0L
    }

    /**
     * The same two quick ticks for both transitions. Which one happened is unambiguous from what
     * you just did -- you either set the phone down or picked it up -- so the buzz only needs to
     * confirm that Flip noticed, not encode which way it went.
     */
    private fun vibrate() {
        if (!Prefs(this).haptics) return
        val vibrator =
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        if (!vibrator.hasVibrator()) return

        // Do Not Disturb is switching in the same breath, and a notification-usage vibration would
        // be swallowed by it. The zen policy allows alarms, so alarm usage is what reaches your hand.
        val attrs = VibrationAttributes.Builder()
            .setUsage(VibrationAttributes.USAGE_ALARM)
            .build()

        val heavySupported = vibrator
            .areEffectsSupported(VibrationEffect.EFFECT_HEAVY_CLICK)
            .firstOrNull() == Vibrator.VIBRATION_EFFECT_SUPPORT_YES

        if (heavySupported) {
            // VibrationEffect.Composition only accepts primitives, never predefined effects, so the
            // second click has to be posted rather than sequenced. The gap is comfortably longer
            // than one click, so the two never collide.
            val heavy = VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
            vibrator.vibrate(heavy, attrs)
            main.postDelayed({ vibrator.vibrate(heavy, attrs) }, HEAVY_CLICK_GAP_MS)
        } else {
            vibrator.vibrate(
                VibrationEffect.createWaveform(
                    CONFIRM_PATTERN_MS,
                    intArrayOf(0, MAX_AMPLITUDE, 0, MAX_AMPLITUDE),
                    -1,
                ),
                attrs,
            )
        }
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = getString(R.string.channel_description)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
        // Left over from hiding the notification through a blocked channel, which Android undoes
        // for foreground services by quietly unblocking the channel.
        nm.deleteNotificationChannel("flip_service_hidden")
    }

    /**
     * Android requires a notification for every foreground service, so this can never be absent.
     * What it can be is unobtrusive: dismissible by swipe, and gone from the shade entirely when
     * notifications are off, since Android then shows foreground services only in its task manager.
     */
    private fun buildNotification(engaged: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_flip)
            .setContentTitle(getString(if (engaged) R.string.silenced else R.string.app_name))
            .setContentText(
                getString(if (engaged) R.string.notification_engaged else R.string.notification_watching)
            )
            .setContentIntent(open)
            // Not ongoing: on Android 13+ this is what lets you swipe it away.
            .setOngoing(false)
            .setShowWhen(false)
            .build()
    }

    private fun pushNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(detector?.engaged == true))
    }

    override fun onDestroy() {
        detector?.let {
            sensorManager.unregisterListener(it)
            if (it.engaged) DndController.setEngaged(this, false)
        }
        detector = null
        main.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(screenReceiver) }
        releaseWakeLock()
        FlipState.update { FlipState.Info() }
        Log.i(TAG, "stopped")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Flip"
        const val CHANNEL_ID = "flip_service"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "app.flipsilence.STOP"
        const val ACTION_RELOAD = "app.flipsilence.RELOAD"

        /** ~10 Hz. Fast enough for a 1.5 s debounce, slow enough to be cheap. */
        private const val SAMPLE_PERIOD_US = 100_000

        private const val WAKELOCK_TIMEOUT_MS = 4_000L

        /** Long enough to press power, reach over and lay the phone down, and hold for Strict. */
        private const val SCREEN_OFF_GRACE_MS = 15_000L

        /** Full-scale amplitude; this is the only confirmation you get with the screen face down. */
        private const val MAX_AMPLITUDE = 255

        /** Two quick ticks: wait, buzz, gap, buzz. Used for both silencing and releasing. */
        private val CONFIRM_PATTERN_MS = longArrayOf(0, 55, 70, 55)

        /** Longer than one heavy click, so the pair reads as two distinct hits. */
        private const val HEAVY_CLICK_GAP_MS = 150L
        private const val HEARTBEAT_MS = 15_000L

        /** How long a screen-on waits for the accelerometer to speak before releasing anyway. */
        private const val BACKSTOP_WAIT_MS = 1_500L

        /** The next alarm moves on as one rings; allow for that landing a little either side. */
        private const val ALARM_EARLY_MS = 30_000L
        private const val ALARM_LATE_MS = 5 * 60_000L

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, FlipService::class.java))
        }

        /** Applies a settings change to an already-running service. */
        fun reload(ctx: Context) {
            if (!Prefs(ctx).enabled) return
            ctx.startForegroundService(
                Intent(ctx, FlipService::class.java).setAction(ACTION_RELOAD)
            )
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, FlipService::class.java).setAction(ACTION_STOP))
        }
    }
}
