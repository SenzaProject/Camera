package com.senzaproject.camera

import android.content.Context
import android.content.SharedPreferences
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("senza_camera", Context.MODE_PRIVATE)

    private fun bool(k: String, d: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean = sp.getBoolean(k, d)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
            sp.edit().putBoolean(k, value).apply()
        }
    }

    private fun int(k: String, d: Int) = object : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Int = sp.getInt(k, d)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
            sp.edit().putInt(k, value).apply()
        }
    }

    private fun str(k: String, d: String) = object : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): String = sp.getString(k, d) ?: d
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
            sp.edit().putString(k, value).apply()
        }
    }

    var shutterSound by bool("shutter_sound", true)
    var location by bool("location", false)
    var level by bool("level", false)
    var grid by bool("grid", false)
    var lensCorrection by bool("lens_correction", true)
    var unlockEis by bool("unlock_eis", true)
    var eis by bool("eis", true)
    var volumeAction by int("volume_action", 0)   // 0 rana, 1 zoom, 2 nonaktif
    var videoHeight by int("video_height", 1080)
    var videoFps by int("video_fps", 30)
    var tlInterval by int("tl_interval", 2)
    var jpegQuality by int("jpeg_quality", 95)
    var photoRatio by int("photo_ratio", 0)       // 0 = 4:3, 1 = 16:9
    var timer by int("timer", 0)
    var flash by int("flash", 0)
    var lastMode by str("last_mode", "AI_CAM")
    var watermark by bool("watermark", true)
    var wmText by str("wm_text", "")
    var wmStyle by int("wm_style", 0)             // 0 = "Shot on X", 1 = "X"
    var wmPos by int("wm_pos", 0)                 // 0 kiri, 1 tengah, 2 kanan
    var deviceName by str("device_name", "")
    var deviceFp by str("device_fp", "")
    var log by str("log", "")
    var faceDetect by bool("face_detect", false)
    var beauty by int("beauty", 0)                // 0 mati, 1 alami, 2 halus, 3 cerah, 4 maks
    var glass by bool("glass", true)
    var dualLayout by int("dual_layout", 0)       // 0 atas-bawah, 1 gambar-dalam-gambar
    var dualSwap by bool("dual_swap", false)
    var mirrorFront by bool("mirror_front", true)
    var stabGyro by bool("stab_gyro", true)       // EIS Plus berbasis gyroscope
    var stabLevel by int("stab_level", 1)         // 0 ringan, 1 normal, 2 kuat
}
