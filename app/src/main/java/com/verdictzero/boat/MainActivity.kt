package com.verdictzero.boat

import android.Manifest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : AppCompatActivity() {

    private lateinit var motion: MotionTracker
    private lateinit var gps: GpsTracker
    private lateinit var logger: CsvLogger

    private val handler = Handler(Looper.getMainLooper())
    private var lastLogWriteMs = 0L
    private var permissionDenied = false

    private lateinit var traceView: MotionTraceView
    private lateinit var axisViews: Map<String, List<TextView>>
    private lateinit var tiltNow: TextView
    private lateinit var compassNow: TextView
    private lateinit var windowInfo: TextView
    private lateinit var motionHint: TextView
    private lateinit var gpsStatus: TextView
    private lateinit var gpsTime: TextView
    private lateinit var gpsDate: TextView
    private lateinit var gpsSpeed: TextView
    private lateinit var gpsCourse: TextView
    private lateinit var gpsPosition: TextView
    private lateinit var gpsAltitude: TextView
    private lateinit var gpsAccuracy: TextView
    private lateinit var gpsSats: TextView
    private lateinit var btnLog: Button
    private lateinit var logStatus: TextView

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    private val requestLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        permissionDenied = !granted
        if (granted) gps.start()
    }

    private val refresh = object : Runnable {
        override fun run() {
            updateUi()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        motion = MotionTracker(this)
        gps = GpsTracker(this)
        logger = CsvLogger(this)

        bindViews()

        findViewById<Button>(R.id.btnZero).setOnClickListener { motion.zero() }
        findViewById<Button>(R.id.btnReset).setOnClickListener { motion.clearStats() }
        findViewById<MaterialSwitch>(R.id.switchSideways).setOnCheckedChangeListener { _, checked ->
            motion.swapRollPitch = checked
            motion.clearStats()
        }
        btnLog.setOnClickListener { toggleLogging() }

        if (!gps.hasPermission) {
            requestLocation.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            )
        }
    }

    private fun bindViews() {
        traceView = findViewById(R.id.traceView)
        axisViews = mapOf(
            "roll" to listOf(R.id.rollNow, R.id.rollP2p, R.id.rollRms, R.id.rollPeriod),
            "pitch" to listOf(R.id.pitchNow, R.id.pitchP2p, R.id.pitchRms, R.id.pitchPeriod),
            "yaw" to listOf(R.id.yawNow, R.id.yawP2p, R.id.yawRms, R.id.yawPeriod),
            "heave" to listOf(R.id.heaveNow, R.id.heaveP2p, R.id.heaveRms, R.id.heavePeriod),
            "horiz" to listOf(R.id.horizNow, R.id.horizP2p, R.id.horizRms, R.id.horizPeriod),
        ).mapValues { (_, ids) -> ids.map { findViewById<TextView>(it) } }
        tiltNow = findViewById(R.id.tiltNow)
        compassNow = findViewById(R.id.compassNow)
        windowInfo = findViewById(R.id.windowInfo)
        motionHint = findViewById(R.id.motionHint)
        gpsStatus = findViewById(R.id.gpsStatus)
        gpsTime = findViewById(R.id.gpsTime)
        gpsDate = findViewById(R.id.gpsDate)
        gpsSpeed = findViewById(R.id.gpsSpeed)
        gpsCourse = findViewById(R.id.gpsCourse)
        gpsPosition = findViewById(R.id.gpsPosition)
        gpsAltitude = findViewById(R.id.gpsAltitude)
        gpsAccuracy = findViewById(R.id.gpsAccuracy)
        gpsSats = findViewById(R.id.gpsSats)
        btnLog = findViewById(R.id.btnLog)
        logStatus = findViewById(R.id.logStatus)
    }

    override fun onResume() {
        super.onResume()
        motion.start()
        if (gps.hasPermission) gps.start()
        handler.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresh)
        motion.stop()
        gps.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        logger.stop()
    }

    private fun toggleLogging() {
        if (logger.isLogging) {
            val f = logger.stop()
            btnLog.setText(R.string.btn_log_start)
            logStatus.text = getString(R.string.log_stopped, f?.absolutePath ?: "")
        } else {
            try {
                val f = logger.start()
                btnLog.setText(R.string.btn_log_stop)
                logStatus.text = getString(R.string.log_started, f.absolutePath)
                lastLogWriteMs = 0L
            } catch (e: IOException) {
                val msg = getString(R.string.log_failed, e.message ?: "I/O error")
                logStatus.text = msg
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateUi() {
        val snap = motion.snapshot()
        updateMotion(snap)
        val fix = gps.lastFix
        val fresh = gps.isFixFresh()
        updateGps(fix, fresh)

        if (logger.isLogging) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastLogWriteMs >= LOG_INTERVAL_MS) {
                lastLogWriteMs = now
                logger.write(snap, fix, fresh)
            }
        }
    }

    private fun updateMotion(s: MotionTracker.Snapshot) {
        setAxis("roll", s.roll, "°")
        setAxis("pitch", s.pitch, "°")
        setAxis("yaw", s.yaw, "°")
        setAxis("heave", s.heave, " m/s²")
        setAxis("horiz", s.horiz, " m/s²")
        tiltNow.text = fmt(s.tiltDeg, 1, "°")
        compassNow.text = if (s.compassDeg.isNaN()) "–" else String.format(Locale.US, "%03.0f° M", s.compassDeg)
        windowInfo.text = String.format(Locale.US, "%.0f s / %d samples%s", s.windowSeconds, s.sampleCount,
            if (s.zeroed) "" else " · not zeroed")
        motionHint.text = if (!s.hasRotationSensor) {
            "This device has no rotation-vector sensor; motion data is unavailable."
        } else {
            getString(R.string.motion_hint)
        }

        val trace = motion.trace(TRACE_POINTS)
        traceView.setData(trace[0], trace[1], trace[2], motion.windowMs / 1000f)
    }

    private fun setAxis(key: String, a: MotionTracker.AxisStats, unit: String) {
        val views = axisViews[key] ?: return
        views[0].text = fmt(a.now, 1, unit)
        views[1].text = fmt(a.peakToPeak, 1, unit)
        views[2].text = fmt(a.rms, 2, unit)
        views[3].text = fmt(a.periodS, 1, " s")
    }

    private fun updateGps(fix: GpsTracker.Fix?, fresh: Boolean) {
        gpsStatus.text = when {
            permissionDenied || !gps.hasPermission -> getString(R.string.gps_no_permission)
            !gps.providerEnabled -> getString(R.string.gps_disabled)
            fix == null -> getString(R.string.gps_waiting)
            !fresh -> getString(R.string.gps_stale, gps.fixAgeSeconds() ?: 0L)
            else -> getString(R.string.gps_ok, gps.fixAgeSeconds() ?: 0L)
        }

        if (fix == null || !fresh) {
            gpsTime.text = "–"
            gpsDate.text = "–"
            gpsSpeed.text = "–"
            gpsCourse.text = "–"
            if (fix == null) {
                gpsPosition.text = "–"
                gpsAltitude.text = "–"
                gpsAccuracy.text = "–"
            }
        } else {
            // "RTC": the receiver's own UTC time, advanced by the age of the fix.
            val ageMs = SystemClock.elapsedRealtime() - fix.receivedAtMs
            val d = Date(fix.gpsTimeMs + ageMs)
            gpsTime.text = timeFormat.format(d)
            gpsDate.text = dateFormat.format(d)
            gpsSpeed.text = if (fix.hasSpeed) {
                String.format(Locale.US, "%.1f kn  (%.1f km/h)", fix.speedMps * GpsTracker.MPS_TO_KNOTS,
                    fix.speedMps * GpsTracker.MPS_TO_KMH)
            } else "–"
            gpsCourse.text = if (fix.hasBearing && fix.hasSpeed && fix.speedMps >= MIN_COURSE_SPEED_MPS) {
                String.format(Locale.US, "%03.0f° T", fix.bearingDeg)
            } else "–"
        }
        if (fix != null) {
            gpsPosition.text = String.format(Locale.US, "%.5f, %.5f", fix.latitude, fix.longitude)
            gpsAltitude.text = if (fix.hasAltitude) String.format(Locale.US, "%.0f m", fix.altitudeM) else "–"
            gpsAccuracy.text = if (fix.hasAccuracy) String.format(Locale.US, "±%.0f m", fix.accuracyM) else "–"
        }
        gpsSats.text = String.format(Locale.US, "%d used / %d visible", gps.satellitesInFix, gps.satellitesVisible)
    }

    private fun fmt(v: Float, decimals: Int, unit: String): String =
        if (v.isNaN()) "–" else String.format(Locale.US, "%.${decimals}f%s", v, unit)

    companion object {
        private const val REFRESH_MS = 200L
        private const val LOG_INTERVAL_MS = 1000L
        private const val TRACE_POINTS = 300
        private const val MIN_COURSE_SPEED_MPS = 0.5f
    }
}
