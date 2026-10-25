package com.senzaproject.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread

/** Mengintegrasikan gyroscope menjadi sudut (rad) bercap waktu, supaya bisa dicocokkan dengan waktu frame video. */
class GyroTracker(ctx: Context) : SensorEventListener {
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var thread: HandlerThread? = null

    private val n = 4096
    private val ts = LongArray(n)
    private val ax = FloatArray(n)
    private val ay = FloatArray(n)
    private val az = FloatArray(n)
    private var head = 0
    private var count = 0
    private var tx = 0f
    private var ty = 0f
    private var tz = 0f
    private var lastT = 0L

    val available: Boolean get() = sensor != null

    fun start(): Boolean {
        val s = sensor ?: return false
        val t = HandlerThread("senza-gyro")
        t.start()
        thread = t
        return sm.registerListener(this, s, 5000, Handler(t.looper))
    }

    fun stop() {
        try { sm.unregisterListener(this) } catch (_: Exception) {}
        thread?.quitSafely()
        thread = null
    }

    override fun onSensorChanged(e: SensorEvent) {
        val t = e.timestamp
        if (lastT != 0L) {
            val dt = (t - lastT) / 1e9f
            if (dt > 0f && dt < 0.1f) {
                tx += e.values[0] * dt
                ty += e.values[1] * dt
                tz += e.values[2] * dt
            }
        }
        lastT = t
        synchronized(this) {
            ts[head] = t; ax[head] = tx; ay[head] = ty; az[head] = tz
            head = (head + 1) % n
            if (count < n) count++
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** Sudut terintegrasi (x, y, z) pada waktu [t] (ns, basis elapsedRealtime). */
    fun angleAt(t: Long, out: FloatArray): Boolean {
        synchronized(this) {
            if (count < 2) return false
            var i = (head - 1 + n) % n
            if (t >= ts[i]) {
                out[0] = ax[i]; out[1] = ay[i]; out[2] = az[i]
                return true
            }
            var steps = 0
            while (steps < count - 1) {
                val p = (i - 1 + n) % n
                if (ts[p] <= t) {
                    val span = (ts[i] - ts[p]).toFloat()
                    val f = if (span > 0f) (t - ts[p]) / span else 1f
                    out[0] = ax[p] + (ax[i] - ax[p]) * f
                    out[1] = ay[p] + (ay[i] - ay[p]) * f
                    out[2] = az[p] + (az[i] - az[p]) * f
                    return true
                }
                i = p
                steps++
            }
            out[0] = ax[i]; out[1] = ay[i]; out[2] = az[i]
            return true
        }
    }
}
