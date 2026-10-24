package com.verdictzero.boat

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat

/**
 * Thin wrapper over the platform [LocationManager] that keeps the latest GPS fix, the satellite
 * count and whether the GPS provider is enabled. Uses the GPS provider only (no fused/network
 * location) so the speed, course and time really come from the GNSS receiver.
 */
class GpsTracker(private val context: Context) {

    class Fix(
        val latitude: Double,
        val longitude: Double,
        val hasSpeed: Boolean,
        val speedMps: Float,
        val hasBearing: Boolean,
        val bearingDeg: Float,
        val hasAccuracy: Boolean,
        val accuracyM: Float,
        val hasAltitude: Boolean,
        val altitudeM: Double,
        /** GPS (UTC) time of the fix in epoch milliseconds, as reported by the receiver. */
        val gpsTimeMs: Long,
        /** Device uptime (ms) when the fix arrived, for computing fix age. */
        val receivedAtMs: Long,
    )

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val handler = Handler(Looper.getMainLooper())

    var lastFix: Fix? = null
        private set
    var satellitesInFix: Int = 0
        private set
    var satellitesVisible: Int = 0
        private set
    var running: Boolean = false
        private set

    val providerEnabled: Boolean
        get() = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)

    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Age of the latest fix in whole seconds, or null if no fix yet. */
    fun fixAgeSeconds(): Long? = lastFix?.let { (SystemClock.elapsedRealtime() - it.receivedAtMs) / 1000 }

    fun isFixFresh(): Boolean = fixAgeSeconds()?.let { it <= FRESH_SECONDS } ?: false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastFix = Fix(
                latitude = location.latitude,
                longitude = location.longitude,
                hasSpeed = location.hasSpeed(),
                speedMps = location.speed,
                hasBearing = location.hasBearing(),
                bearingDeg = location.bearing,
                hasAccuracy = location.hasAccuracy(),
                accuracyM = location.accuracy,
                hasAltitude = location.hasAltitude(),
                altitudeM = location.altitude,
                gpsTimeMs = location.time,
                receivedAtMs = SystemClock.elapsedRealtime(),
            )
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) {
                if (status.usedInFix(i)) used++
            }
            satellitesInFix = used
            satellitesVisible = status.satelliteCount
        }
    }

    /** Returns false if location permission has not been granted. */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun start(): Boolean {
        if (running) return true
        if (!hasPermission) return false
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER, UPDATE_INTERVAL_MS, 0f, listener, Looper.getMainLooper(),
        )
        locationManager.registerGnssStatusCallback(gnssCallback, handler)
        running = true
        return true
    }

    fun stop() {
        if (!running) return
        locationManager.removeUpdates(listener)
        locationManager.unregisterGnssStatusCallback(gnssCallback)
        running = false
    }

    companion object {
        const val UPDATE_INTERVAL_MS = 1000L
        const val FRESH_SECONDS = 5L
        const val MPS_TO_KNOTS = 1.943844f
        const val MPS_TO_KMH = 3.6f
    }
}
