package com.verdictzero.boat

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Tracks the attitude of the device (and therefore the boat it is mounted on) using the
 * rotation-vector sensor, plus vertical/horizontal linear acceleration, and keeps a sliding
 * window of samples from which rocking statistics (peak-to-peak, RMS, period) are derived.
 *
 * All methods are expected to be called from the main thread; sensor events are delivered on
 * the main thread as well, so no locking is needed.
 */
class MotionTracker(context: Context) : SensorEventListener {

    /** One sample of the five tracked channels. Angles in degrees, accelerations in m/s². */
    class Sample(
        val tNs: Long,
        val roll: Float,
        val pitch: Float,
        val yaw: Float,
        val heave: Float,
        val horiz: Float,
    )

    /** Statistics for one channel over the current window. [periodS] is NaN when unknown. */
    class AxisStats(val now: Float, val min: Float, val max: Float, val rms: Float, val periodS: Float) {
        val peakToPeak: Float get() = max - min
        companion object {
            val EMPTY = AxisStats(Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN)
        }
    }

    class Snapshot(
        val roll: AxisStats,
        val pitch: AxisStats,
        val yaw: AxisStats,
        val heave: AxisStats,
        val horiz: AxisStats,
        /** Angle between the zeroed "level" attitude and the current one, in degrees. */
        val tiltDeg: Float,
        /** Magnetic compass heading of the device, 0..360, NaN if unavailable. */
        val compassDeg: Float,
        val sampleCount: Int,
        val windowSeconds: Float,
        val zeroed: Boolean,
        val hasRotationSensor: Boolean,
    )

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val linearAccSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val magnetic = rotationSensor?.type == Sensor.TYPE_ROTATION_VECTOR

    /** Length of the sliding statistics window. */
    var windowMs: Long = 30_000L

    /** Swap roll and pitch for a phone mounted in landscape. */
    var swapRollPitch: Boolean = false

    // Rotation matrices (device -> world), row-major 3x3.
    private val rCur = FloatArray(9)
    private val rRef = FloatArray(9).also { it[0] = 1f; it[4] = 1f; it[8] = 1f }
    private val rRel = FloatArray(9)
    private val rRemap = FloatArray(9)
    private val orientation = FloatArray(3)
    private var hasAttitude = false
    private var zeroed = false

    private val worldAcc = FloatArray(3)
    private var haveLinearAcc = false

    private val samples = ArrayDeque<Sample>()

    private var compassDeg = Float.NaN

    fun start() {
        rotationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        linearAccSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    /** Make the current attitude the "level, straight-ahead" reference. */
    fun zero() {
        if (!hasAttitude) return
        System.arraycopy(rCur, 0, rRef, 0, 9)
        zeroed = true
        samples.clear()
    }

    /** Discard the sliding window so statistics start fresh. */
    fun clearStats() {
        samples.clear()
    }

    val isZeroed: Boolean get() = zeroed

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                if (hasAttitude) {
                    rotateToWorld(rCur, event.values, worldAcc)
                    haveLinearAcc = true
                }
            }
            Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rCur, event.values)
                hasAttitude = true
                updateCompass()
                addSample(event.timestamp)
            }
        }
    }

    private fun addSample(tNs: Long) {
        // Relative rotation: reference-device frame <- current-device frame.
        multiplyTransposeA(rRef, rCur, rRel)
        SensorManager.getOrientation(rRel, orientation)
        var roll = Math.toDegrees(orientation[2].toDouble()).toFloat()
        var pitch = Math.toDegrees(orientation[1].toDouble()).toFloat()
        val yaw = Math.toDegrees(orientation[0].toDouble()).toFloat()
        if (swapRollPitch) {
            val t = roll; roll = pitch; pitch = t
        }
        val heave = if (haveLinearAcc) worldAcc[2] else 0f
        val horiz = if (haveLinearAcc) sqrt(worldAcc[0] * worldAcc[0] + worldAcc[1] * worldAcc[1]) else 0f

        samples.addLast(Sample(tNs, roll, pitch, yaw, heave, horiz))
        val cutoff = tNs - windowMs * 1_000_000L
        while (samples.isNotEmpty() && samples.first().tNs < cutoff) samples.removeFirst()
    }

    private fun updateCompass() {
        if (!magnetic) { compassDeg = Float.NaN; return }
        // rCur[8] is the device-Z component of world "up": ~1 when the phone lies flat.
        val heading = if (abs(rCur[8]) > 0.7f) {
            SensorManager.getOrientation(rCur, orientation)
            orientation[0]
        } else {
            // Phone held upright: use the direction the back of the phone faces.
            SensorManager.remapCoordinateSystem(rCur, SensorManager.AXIS_X, SensorManager.AXIS_Z, rRemap)
            SensorManager.getOrientation(rRemap, orientation)
            orientation[0]
        }
        var deg = Math.toDegrees(heading.toDouble()).toFloat()
        if (deg < 0f) deg += 360f
        compassDeg = deg
    }

    fun snapshot(): Snapshot {
        val n = samples.size
        if (n == 0) {
            return Snapshot(
                AxisStats.EMPTY, AxisStats.EMPTY, AxisStats.EMPTY, AxisStats.EMPTY, AxisStats.EMPTY,
                currentTilt(), compassDeg, 0, 0f, zeroed, rotationSensor != null,
            )
        }
        val times = LongArray(n)
        val roll = FloatArray(n)
        val pitch = FloatArray(n)
        val yaw = FloatArray(n)
        val heave = FloatArray(n)
        val horiz = FloatArray(n)
        var i = 0
        for (s in samples) {
            times[i] = s.tNs; roll[i] = s.roll; pitch[i] = s.pitch; yaw[i] = s.yaw
            heave[i] = s.heave; horiz[i] = s.horiz
            i++
        }
        val windowS = (times[n - 1] - times[0]) / 1e9f
        return Snapshot(
            stats(roll, times, ANGLE_THRESHOLD_DEG),
            stats(pitch, times, ANGLE_THRESHOLD_DEG),
            stats(yaw, times, ANGLE_THRESHOLD_DEG),
            stats(heave, times, ACC_THRESHOLD),
            stats(horiz, times, ACC_THRESHOLD),
            currentTilt(), compassDeg, n, windowS, zeroed, rotationSensor != null,
        )
    }

    /**
     * Returns down-sampled (secondsAgo, roll, pitch) arrays for drawing a trace.
     * secondsAgo values are <= 0, with 0 being the newest sample.
     */
    fun trace(maxPoints: Int): Array<FloatArray> {
        val n = samples.size
        if (n == 0) return arrayOf(FloatArray(0), FloatArray(0), FloatArray(0))
        val step = maxOf(1, n / maxPoints)
        val count = (n + step - 1) / step
        val t = FloatArray(count)
        val r = FloatArray(count)
        val p = FloatArray(count)
        val newest = samples.last().tNs
        var i = 0
        var j = 0
        for (s in samples) {
            if (i % step == 0 && j < count) {
                t[j] = (s.tNs - newest) / 1e9f
                r[j] = s.roll
                p[j] = s.pitch
                j++
            }
            i++
        }
        return arrayOf(t.copyOf(j), r.copyOf(j), p.copyOf(j))
    }

    private fun currentTilt(): Float {
        if (!hasAttitude) return Float.NaN
        multiplyTransposeA(rRef, rCur, rRel)
        val c = rRel[8].coerceIn(-1f, 1f)
        return Math.toDegrees(acos(c.toDouble())).toFloat()
    }

    private fun stats(v: FloatArray, t: LongArray, threshold: Float): AxisStats {
        val n = v.size
        var min = Float.POSITIVE_INFINITY
        var max = Float.NEGATIVE_INFINITY
        var sum = 0.0
        for (x in v) {
            if (x < min) min = x
            if (x > max) max = x
            sum += x
        }
        val mean = (sum / n).toFloat()
        var sq = 0.0
        for (x in v) {
            val d = x - mean
            sq += d * d
        }
        val rms = sqrt(sq / n).toFloat()
        return AxisStats(v[n - 1], min, max, rms, estimatePeriod(v, t, mean, threshold))
    }

    /** Period from mean-crossings with hysteresis; NaN unless at least two crossings exist. */
    private fun estimatePeriod(v: FloatArray, t: LongArray, mean: Float, threshold: Float): Float {
        var state = 0
        var flips = 0
        var firstFlip = 0L
        var lastFlip = 0L
        for (i in v.indices) {
            val d = v[i] - mean
            val s = if (d > threshold) 1 else if (d < -threshold) -1 else 0
            if (s != 0 && s != state) {
                if (state != 0) {
                    flips++
                    if (flips == 1) firstFlip = t[i]
                    lastFlip = t[i]
                }
                state = s
            }
        }
        if (flips < 2) return Float.NaN
        return 2f * (lastFlip - firstFlip) / 1e9f / (flips - 1)
    }

    companion object {
        private const val ANGLE_THRESHOLD_DEG = 0.3f
        private const val ACC_THRESHOLD = 0.15f

        /** out = transpose(a) * b, all 3x3 row-major. */
        private fun multiplyTransposeA(a: FloatArray, b: FloatArray, out: FloatArray) {
            for (r in 0 until 3) {
                for (c in 0 until 3) {
                    out[r * 3 + c] = a[r] * b[c] + a[3 + r] * b[3 + c] + a[6 + r] * b[6 + c]
                }
            }
        }

        /** out = R * v (device -> world). */
        private fun rotateToWorld(r: FloatArray, v: FloatArray, out: FloatArray) {
            out[0] = r[0] * v[0] + r[1] * v[1] + r[2] * v[2]
            out[1] = r[3] * v[0] + r[4] * v[1] + r[5] * v[2]
            out[2] = r[6] * v[0] + r[7] * v[1] + r[8] * v[2]
        }
    }
}
