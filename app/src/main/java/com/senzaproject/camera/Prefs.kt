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
}
