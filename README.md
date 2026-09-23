# Flip

Put the phone face down on a table and it goes quiet. Pick it up and the sound comes back.

<p align="center">
  <a href="https://play.google.com/store/apps/details?id=app.flipsilence">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset=".github/download-dark.svg">
      <img src=".github/download-light.svg" alt="Get it on Google Play" width="198" height="48">
    </picture>
  </a>
  <br>
  <sub>On Google Play · Android 14 or newer</sub>
</p>

This is Pixel's **Flip to Shhh** rebuilt for a Samsung phone, which does not ship it — One UI's
Modes and Routines has no orientation or face-down condition, Good Lock's Routines+ adds only
fingerprint, S Pen and button triggers, and "Mute with gestures" only silences a call or alarm that
is already ringing. So it is an app.

Two quick haptic ticks confirm each transition, because the screen is against the table and there is
nothing else to tell you it worked. They can be turned off in Settings.

## Why it watches the accelerometer and not the proximity sensor

The obvious implementation — "screen covered plus face down" — is not available. **Samsung does not
expose the real proximity sensor to third-party apps.** The sensor that is exposed is the palm
sensor, and on this device it was measured reporting both *near* and *far* in the same face-down
position, so it cannot be trusted to tell a desk from a pocket.

What is left is "flat and still": gravity Z at or below a threshold *and* non-gravity acceleration
below a threshold, both held continuously for a debounce window. A phone in a worn pocket is
essentially never both within a few degrees of horizontal and motionless for over a second, so that
pair is the pocket guard. Release uses a separate, shallower threshold, so a phone that jiggles on
the table does not chatter between states.

Flat and still are not enough on their own, though, because a *hand* holding the phone face down
passes both: gripping something steadily produces almost no linear acceleration. What a hand cannot
do is hold an **angle**. So a third condition anchors the gravity direction when the countdown
starts and restarts the countdown from the current angle whenever the phone leans further than the
profile allows. A table wanders about a fifth of a degree; a wrist wanders several.

That drift is measured as `atan2(|a × b|, a · b)` between the anchored and the current gravity
vector. The magnitudes cancel, so accelerometer gain error cannot leak in; and unlike an `acos` of
normalised dot products it keeps its precision at the one or two degrees this actually operates on,
where `acos` has almost none. It also catches a lean in any direction rather than only a change in
how flat the phone is, so a wrist rocking one way and back does not average itself out into looking
motionless.

The thresholds are not invented. Real placements measured on the device landed at gravity Z between
−9.53 and −9.73 with motion between 0.002 and 0.072; Balanced keeps roughly 3× headroom over the
worst of those while still tolerating an imperfect table.

| Profile  | Face down at | Released past | Still below | Max drift | Hold  |
| -------- | ------------ | ------------- | ----------- | --------- | ----- |
| Strict   | z ≤ −9.5     | z > −7.5      | 0.12        | 1.5°      | 2.0 s |
| Balanced | z ≤ −9.0     | z > −7.0      | 0.25        | 2.5°      | 1.5 s |
| Relaxed  | z ≤ −8.3     | z > −6.3      | 0.45        | 4°        | 1.0 s |

Balanced is the default. Strict rejects a surface that is not properly flat; Relaxed accepts a
slope, a cushion or a quick set-down, and is the most likely to fire in a pocket.

## How the silencing works

Since Android 15 an app cannot set the device's global Do Not Disturb state — `setInterruptionFilter`
creates an implicit rule instead, and an app may only clear a rule it owns. So Flip owns exactly one
`AutomaticZenRule` and drives both edges of it. Owning the *off* transition as well as the *on* one
is what makes face-up reliably un-silence.

The rule carries its own `ZenPolicy` for calls only: starred contacts ring if you want them to,
and so do repeat callers, per the two switches in settings. Alarms are allowed explicitly, since
Flip's haptics and its alerts for allowed apps go out as alarms. Everything else the policy leaves
unset is inherited from your own Do Not Disturb settings.

Two things keep the phone from getting stranded in Do Not Disturb:

- **A screen-on backstop.** Picking the phone up almost always turns the screen on, which wakes the
  service even if the CPU had suspended and accelerometer samples were missed. If no sample
  arrives within 1.5 s of `ACTION_SCREEN_ON` while engaged, it forces a release. A live sensor
  decides for itself, so a call that lights the screen of a phone still lying face down does not
  un-silence it.
- **Reconciliation.** On every service start and every screen-on, the zen rule is driven back to
  whatever the detector currently believes, which bounds how long any drift — a process kill
  mid-engage, a stale rule from a previous install — can survive to "until you next look at your
  phone".

The service samples at ~10 Hz on a wake-up accelerometer where the device exposes one, and takes a
short, self-timing-out partial wake lock only while a candidate placement is finishing its debounce.

The S26 Ultra does not expose one — its only accelerometer is `lsm6dsv_0 Accelerometer Non-wakeup` —
and that appears to have cost whole nights. Pressing the power button and *then* laying the phone
down is how most people go to bed, and in between the CPU can suspend: no samples arrive, the hold
never completes, and the phone lies face down until morning without silencing. The recorded history
fits that on six nights out of nine: the screen dark from about half past one, and a silence that
only began when something woke the CPU at 07:22. So when the screen goes off, the service keeps the
CPU up for a further 15 seconds, which is long enough to reach over and set the phone down and still
hold for Strict. Silencing releases it early.

## The app

The main screen is a tilt gauge that fills as the hold completes, the on/off pill, today's silences
laid on a midnight-to-midnight strip, last night on an evening-to-noon one, and the notification
switch. Everything set once and then
left alone lives on a separate Settings page: the three sensitivity profiles, the haptic ticks
(on by default), Do Not Disturb access and the battery setting with their current state, and a
collapsible diagnostics readout (service state, sensor name, whether it is a wake-up sensor, sample
rate, live gravity Z / motion / drift / held-ms against the current thresholds, and the raw
proximity reading for the record).

Android requires a notification for every foreground service, so it can never be absent. It can be
unobtrusive: it is dismissible by swipe, and turning it off in the app strips it of text and defers
it out of the way.

**Permissions:** Do Not Disturb access (`ACCESS_NOTIFICATION_POLICY`), notifications, and — for the
service to survive idle — Battery → Unrestricted. The app links straight to each settings screen.

## Last night

People put the phone face down to sleep, so Flip already knows roughly when you went to bed and when
you got up. The main screen shows it: last night's span, how long it lasted, how much of it the phone
spent silenced, and how many times it was picked up in between.

It does not measure sleep, only the phone being left alone, and it reads that from two things:
**the screen being dark**, recorded by the service whenever it is running, and **the face-down
silences**. Neither is enough alone. The screen going dark is exact to the second, while a silence
can start hours after the phone was put down (see the non-wakeup accelerometer above); a phone set
face down with its screen still on has been left alone but is not dark yet.

Those intervals are merged, and the night is found in them like this:

- The screen coming on for **2 minutes or less** does not break a stretch: checking the time,
  snoozing an alarm, a notification lighting it up.
- A stretch shorter than **2 hours** is not sleep, and is never joined onto a night — that is what
  keeps an evening with the phone on the charger from being counted as an early night.
- The night is the longest such stretch **centred after midnight**, joined to any other one no more
  than **an hour** away. Each gap joined over counts as a wake-up and is left out of the total.

Measured on the device the night before this was written: the screen went dark at 01:30:06 and stayed
dark, apart from two three-second wakes at 07:22 and 08:51, until 09:47:47, giving 01:30 – 09:47,
8 h 17 m. The silences alone would have said 07:22 – 09:47.

The window runs from 18:00 to noon. Until 06:00 the night in progress has not been slept yet, so
"last night" is still the one before. Dark-screen stretches of a minute or more are kept for three
days, on the device only.

## Build

Requires JDK 17 (AGP will not run on the JDK 23 that is likely your default) and an Android SDK with
API 36.

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :app:installDebug     # or :app:assembleDebug for just the APK
```

`local.properties` is not committed; point `sdk.dir` at your SDK, or let Android Studio write it.

Minimum SDK 34, target and compile SDK 36. No dependencies beyond the Android platform — the UI is
plain views and hand-drawn `Canvas`, and there is no AndroidX, no Compose, no Kotlin plugin (AGP 9
registers the `kotlin` extension itself).

The type is Plus Jakarta Sans throughout (semibold for display, regular and medium for body), under
the SIL Open Font License 1.1 (the licence text travels inside each font's name table). The four
weights in `app/src/main/res/font` are subset to Latin with fontTools: about 60 KB each instead of
130 KB.

### Handy while tuning

```sh
adb shell am start -n app.flipsilence/.MainActivity --ez enable true   # start the service
adb shell am start -n app.flipsilence/.MainActivity --es force on      # force the zen rule on/off
adb logcat -s Flip                                              # transitions + 15 s heartbeat
```

The two `am start` hooks work in debug builds only.

The heartbeat line is the only way to know whether the sensor keeps delivering once the screen is
off and the device idles.
