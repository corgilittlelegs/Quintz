package com.quintz.wifi.radar

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.*
import kotlin.math.max
import kotlin.math.roundToInt

data class RadarState(
    val currentHeading: Float = 0f,           // 0..359° (Device azimuth)
    val targetBearing: Float = 0f,            // 0..359° (Estimated router direction)
    val relativeAngle: Float = 0f,            // Relative angle from device heading to target
    val distanceMeters: Float = 0f,
    val proximityZone: String = "SCANNING",
    val calibrationPercent: Int = 0,
    val isCalibrated: Boolean = false,
    val targetSsid: String = "",
    val targetBssid: String = "",
    val rssi: Int = 0
)

/**
 * Sensor-fusion engine calculating estimated router distance and direction.
 */
class RadarEngine(private val context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private var rotationVectorSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private var accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var magnetometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val accelerometerReading = FloatArray(3)
    private val magnetometerReading = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    // 36 angular sectors (10° each) to record peak signal per direction
    private val sectorPeakRssi = FloatArray(36) { -120f }
    private val visitedSectors = mutableSetOf<Int>()

    var radarState by mutableStateOf(RadarState())
        private set

    private var smoothedHeading = 0f
    private var lastRssi = 0
    private var lastFrequency = 5745
    private var currentSsid = ""
    private var currentBssid = ""

    fun start() {
        if (rotationVectorSensor != null) {
            sensorManager.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_UI)
        } else {
            accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
            magnetometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    fun resetCalibration() {
        for (i in sectorPeakRssi.indices) sectorPeakRssi[i] = -120f
        visitedSectors.clear()
        updateState()
    }

    fun updateWifiMetrics(ssid: String, bssid: String, rssi: Int, frequencyMhz: Int) {
        if (ssid != currentSsid || bssid != currentBssid) {
            currentSsid = ssid
            currentBssid = bssid
            resetCalibration()
        }
        lastRssi = rssi
        if (frequencyMhz > 0) lastFrequency = frequencyMhz

        if (rssi != 0 && smoothedHeading >= 0f) {
            val sector = (smoothedHeading / 10f).toInt().coerceIn(0, 35)
            sectorPeakRssi[sector] = max(sectorPeakRssi[sector], rssi.toFloat())
            visitedSectors.add(sector)
        }
        updateState()
    }

    override fun onSensorChanged(event: SensorEvent) {
        var azimuthDegrees: Float? = null

        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            val rad = orientationAngles[0]
            azimuthDegrees = ((Math.toDegrees(rad.toDouble()) + 360) % 360).toFloat()
        } else {
            if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                System.arraycopy(event.values, 0, accelerometerReading, 0, 3)
            } else if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
                System.arraycopy(event.values, 0, magnetometerReading, 0, 3)
            }
            if (SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerReading, magnetometerReading)) {
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                val rad = orientationAngles[0]
                azimuthDegrees = ((Math.toDegrees(rad.toDouble()) + 360) % 360).toFloat()
            }
        }

        azimuthDegrees?.let { rawHeading ->
            // Low-pass filter to smooth heading
            smoothedHeading = lowPassAngle(smoothedHeading, rawHeading, 0.15f)

            if (lastRssi != 0) {
                val sector = (smoothedHeading / 10f).toInt().coerceIn(0, 35)
                sectorPeakRssi[sector] = max(sectorPeakRssi[sector], lastRssi.toFloat())
                visitedSectors.add(sector)
            }
            updateState()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun updateState() {
        val distance = calculateDistanceMeters(lastRssi, lastFrequency)
        val zone = getProximityZone(distance)

        // Find sector with strongest RSSI
        var bestSector = 0
        var maxSignal = -120f
        for (i in sectorPeakRssi.indices) {
            if (sectorPeakRssi[i] > maxSignal) {
                maxSignal = sectorPeakRssi[i]
                bestSector = i
            }
        }

        val bearing = (bestSector * 10f + 5f) % 360f
        val relAngle = ((bearing - smoothedHeading) + 360f) % 360f
        val calibPercent = ((visitedSectors.size / 24f) * 100f).toInt().coerceIn(0, 100)
        val isCalibrated = visitedSectors.size >= 16

        radarState = RadarState(
            currentHeading = smoothedHeading,
            targetBearing = bearing,
            relativeAngle = relAngle,
            distanceMeters = distance,
            proximityZone = zone,
            calibrationPercent = calibPercent,
            isCalibrated = isCalibrated,
            targetSsid = currentSsid,
            targetBssid = currentBssid,
            rssi = lastRssi
        )
    }

    companion object {
        fun calculateDistanceMeters(rssi: Int, frequencyMhz: Int): Float {
            if (rssi >= 0 || rssi <= -105) return 0f
            val is5G = frequencyMhz > 4900
            val txPower = if (is5G) -38f else -40f // Reference dBm at 1 meter
            val pathLossExponent = if (is5G) 2.85f else 2.6f
            val exp = (txPower - rssi) / (10f * pathLossExponent)
            val d = Math.pow(10.0, exp.toDouble()).toFloat()
            return ((d * 10).roundToInt() / 10f).coerceIn(0.5f, 35.0f)
        }

        fun getProximityZone(distance: Float): String = when {
            distance <= 0f -> "UNKNOWN"
            distance < 2.0f -> "ZONE 0: IMMEDIATE"
            distance < 5.5f -> "ZONE 1: NEAR"
            distance < 12.0f -> "ZONE 2: MID-RANGE"
            else -> "ZONE 3: DISTANT"
        }

        fun formatBearingCompass(degrees: Float): String {
            val deg = (degrees % 360).roundToInt()
            val cardinal = when (deg) {
                in 338..360, in 0..22 -> "N"
                in 23..67 -> "NE"
                in 68..112 -> "E"
                in 113..157 -> "SE"
                in 158..202 -> "S"
                in 203..247 -> "SW"
                in 248..292 -> "W"
                else -> "NW"
            }
            return String.format("%03d° %s", deg, cardinal)
        }

        private fun lowPassAngle(current: Float, target: Float, alpha: Float): Float {
            var diff = (target - current) % 360f
            if (diff > 180f) diff -= 360f
            if (diff < -180f) diff += 360f
            val result = (current + alpha * diff) % 360f
            return if (result < 0) result + 360f else result
        }
    }
}
