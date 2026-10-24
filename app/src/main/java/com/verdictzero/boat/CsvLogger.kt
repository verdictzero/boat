package com.verdictzero.boat

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Writes one CSV row per second to the app's external files directory. */
class CsvLogger(private val context: Context) {

    private var writer: BufferedWriter? = null
    var file: File? = null
        private set

    val isLogging: Boolean get() = writer != null

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
    private val utcFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val nameFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    @Throws(IOException::class)
    fun start(): File {
        stop()
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "logs")
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Cannot create ${dir.absolutePath}")
        val f = File(dir, "boat-${nameFormat.format(Date())}.csv")
        val w = BufferedWriter(FileWriter(f))
        w.write(HEADER)
        w.newLine()
        w.flush()
        writer = w
        file = f
        return f
    }

    fun stop(): File? {
        val f = file
        try {
            writer?.flush()
            writer?.close()
        } catch (_: IOException) {
        }
        writer = null
        file = null
        return f
    }

    fun write(motion: MotionTracker.Snapshot, fix: GpsTracker.Fix?, fixFresh: Boolean) {
        val w = writer ?: return
        val sb = StringBuilder(256)
        sb.append(isoFormat.format(Date())).append(',')
        if (fix != null && fixFresh) {
            sb.append(utcFormat.format(Date(fix.gpsTimeMs))).append(',')
            sb.append(String.format(Locale.US, "%.6f", fix.latitude)).append(',')
            sb.append(String.format(Locale.US, "%.6f", fix.longitude)).append(',')
            sb.append(if (fix.hasSpeed) fmt(fix.speedMps * GpsTracker.MPS_TO_KNOTS) else "").append(',')
            sb.append(if (fix.hasBearing) fmt(fix.bearingDeg) else "").append(',')
            sb.append(if (fix.hasAltitude) fmt(fix.altitudeM.toFloat()) else "").append(',')
            sb.append(if (fix.hasAccuracy) fmt(fix.accuracyM) else "").append(',')
        } else {
            sb.append(",,,,,,,")
        }
        sb.append(fmt(motion.compassDeg)).append(',')
        appendAxis(sb, motion.roll)
        appendAxis(sb, motion.pitch)
        appendAxis(sb, motion.yaw)
        appendAxis(sb, motion.heave)
        appendAxis(sb, motion.horiz)
        sb.append(fmt(motion.tiltDeg))
        try {
            w.write(sb.toString())
            w.newLine()
            w.flush()
        } catch (_: IOException) {
            stop()
        }
    }

    private fun appendAxis(sb: StringBuilder, a: MotionTracker.AxisStats) {
        sb.append(fmt(a.now)).append(',')
            .append(fmt(a.peakToPeak)).append(',')
            .append(fmt(a.rms)).append(',')
            .append(fmt(a.periodS)).append(',')
    }

    private fun fmt(v: Float): String = if (v.isNaN()) "" else String.format(Locale.US, "%.2f", v)

    companion object {
        private val HEADER = listOf(
            "device_time", "gps_time_utc", "lat", "lon", "speed_kn", "course_deg", "altitude_m", "accuracy_m",
            "compass_deg",
            "roll_deg", "roll_p2p_deg", "roll_rms_deg", "roll_period_s",
            "pitch_deg", "pitch_p2p_deg", "pitch_rms_deg", "pitch_period_s",
            "yaw_deg", "yaw_p2p_deg", "yaw_rms_deg", "yaw_period_s",
            "heave_mps2", "heave_p2p_mps2", "heave_rms_mps2", "heave_period_s",
            "horiz_mps2", "horiz_p2p_mps2", "horiz_rms_mps2", "horiz_period_s",
            "tilt_deg",
        ).joinToString(",")
    }
}
