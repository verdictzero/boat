# Boat Motion

A small Android app that shows how much a boat is moving and, when a GPS fix is
available, its heading, speed and the receiver's UTC clock.

## What it shows

**Motion (from the phone's rotation-vector and linear-acceleration sensors)**

| Channel | Meaning |
| --- | --- |
| Roll | Rotation about the phone's long axis (side-to-side rocking when the phone is mounted fore-and-aft) |
| Pitch | Rotation about the phone's short axis |
| Yaw | Rotation about the vertical axis relative to the zeroed heading |
| Heave accel | Vertical linear acceleration (gravity removed), m/s² |
| Horiz. accel | Horizontal linear acceleration magnitude, m/s² |

For every channel the table lists the current value, the peak-to-peak swing,
the RMS and an estimated period over a sliding 30-second window. A trace of
roll (blue) and pitch (orange) over the same window is drawn above the table,
and the total tilt from the zeroed attitude plus a magnetic compass heading are
shown underneath.

* **Zero (level)** makes the current attitude the reference, so the phone can
  be mounted at any angle. Tap it once the phone is fixed in its holder.
* **Reset stats** clears the sliding window.
* **Phone mounted sideways** swaps roll and pitch for a landscape mount.

**GPS (platform `LocationManager`, GPS provider only)**

* Status: permission / provider / fix freshness. Values are blanked when the
  last fix is more than 5 seconds old.
* GPS time and date in UTC, taken from the receiver's fix time (the "RTC") and
  advanced by the fix age.
* Speed in knots and km/h.
* Course over ground (true), shown only when moving faster than 0.5 m/s.
* Position, altitude, horizontal accuracy and satellites used / visible.

**CSV log**

*Start CSV log* writes one row per second to
`Android/data/com.verdictzero.boat/files/logs/boat-YYYYMMDD-HHMMSS.csv` on the
phone's shared storage (readable over USB or with a file manager, no extra
permission needed). Columns: device time, GPS UTC time, position, speed, course,
altitude, accuracy, compass heading, then now / peak-to-peak / RMS / period for
each motion channel, and total tilt.

The screen stays on while the app is in the foreground.

## Building

Requirements: JDK 17+, Android SDK with platform 34 and build-tools 34.0.0.
Point Gradle at the SDK with `ANDROID_HOME` or a `local.properties` file
containing `sdk.dir=/path/to/sdk`, then:

```
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`. Install it with
`adb install -r app/build/outputs/apk/debug/app-debug.apk`, or open the project
in Android Studio and press Run.

Minimum Android version is 8.0 (API 26). The app needs a rotation-vector sensor
(present on practically every phone) and, for the GPS card, the location
permission it asks for on first launch.

## Project layout

```
app/src/main/java/com/verdictzero/boat/
  MainActivity.kt      UI wiring, permission request, 5 Hz refresh, logging cadence
  MotionTracker.kt     Sensor fusion, zeroing, sliding window, p2p / RMS / period
  GpsTracker.kt        GPS fixes, satellite status, fix freshness
  MotionTraceView.kt   Roll / pitch trace drawing
  CsvLogger.kt         CSV writer
app/src/main/res/layout/activity_main.xml
```
