package com.senzaproject.camera

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var content: LinearLayout
    private lateinit var header: FrameLayout
    private var locSwitch: IosSwitch? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        prefs = Prefs(this)
        CamLog.init(prefs)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.background = AuroraDrawable()

        header = FrameLayout(this)
        val back = IconView(this, Ic.BACK)
        back.setPadding(dp(11f), dp(11f), dp(11f), dp(11f))
        back.setOnClickListener { finish() }
        header.addView(back, FrameLayout.LayoutParams(dp(44f), dp(44f), Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = dp(10f) })
        val title = tv("Pengaturan", 20f, Color.WHITE, true)
        header.addView(title, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL).apply { leftMargin = dp(62f) })
        root.addView(header, LinearLayout.LayoutParams(-1, dp(56f)))

        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(16f), dp(4f), dp(16f), dp(40f))
        scroll.addView(content, FrameLayout.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        root.setOnApplyWindowInsetsListener { _, ins ->
            val i = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            root.setPadding(0, i.top, 0, i.bottom)
            WindowInsets.CONSUMED
        }

        build()
    }

    private fun tv(text: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            typeface = Typeface.create("sans-serif-medium", if (bold) Typeface.BOLD else Typeface.NORMAL)
        }

    private fun section(title: String) {
        val t = tv(title.uppercase(), 12f, C.GRAY)
        t.setPadding(dp(16f), dp(22f), 0, dp(8f))
        content.addView(t)
    }

    private fun card(vararg rows: View) {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = GlassDrawable(this, dpf(18f), 0x4A2C2C34, false)
        for ((i, r) in rows.withIndex()) {
            if (i > 0) {
                val sep = View(this)
                sep.setBackgroundColor(C.SEP)
                c.addView(sep, LinearLayout.LayoutParams(-1, 1).apply { leftMargin = dp(16f) })
            }
            c.addView(r, LinearLayout.LayoutParams(-1, -2))
        }
        content.addView(c, LinearLayout.LayoutParams(-1, -2))
    }

    private fun rowBase(title: String, sub: String?): Pair<LinearLayout, LinearLayout> {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
        row.minimumHeight = dp(54f)
        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        left.addView(tv(title, 16f))
        if (sub != null) {
            val s = tv(sub, 12f, C.GRAY)
            s.setPadding(0, dp(3f), 0, 0)
            left.addView(s)
        }
        row.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
        return Pair(row, left)
    }

    private fun switchRow(title: String, sub: String?, get: () -> Boolean, set: (Boolean) -> Unit): Pair<View, IosSwitch> {
        val (row, _) = rowBase(title, sub)
        val sw = IosSwitch(this)
        sw.setCheckedSilently(get())
        sw.onChange = { set(it) }
        row.addView(sw, LinearLayout.LayoutParams(-2, -2))
        row.setOnClickListener { sw.performClick() }
        return Pair(row, sw)
    }

    private fun valueRow(title: String, sub: String?, value: () -> String, click: () -> Unit): View =
        valueRowRef(title, sub, value, click).first

    private fun valueRowRef(title: String, sub: String?, value: () -> String, click: () -> Unit): Pair<View, TextView> {
        val (row, _) = rowBase(title, sub)
        val v = tv(value(), 15f, C.GRAY)
        v.setPadding(dp(8f), 0, 0, 0)
        row.addView(v)
        val chev = IconView(this, Ic.BACK, C.GRAY, 1.6f)
        chev.rotation = 180f
        row.addView(chev, LinearLayout.LayoutParams(dp(18f), dp(18f)).apply { leftMargin = dp(4f) })
        row.setOnClickListener { click(); v.text = value() }
        return Pair(row, v)
    }

    private fun infoRow(title: String, text: String): View {
        val (row, left) = rowBase(title, null)
        val t = tv(text, 12f, C.GRAY)
        t.setPadding(0, dp(6f), 0, 0)
        t.typeface = Typeface.MONOSPACE
        left.addView(t)
        return row
    }

    private fun <T> cycle(list: List<T>, cur: T): T = list[(list.indexOf(cur) + 1).mod(list.size)]

    private fun build() {
        section("Umum")
        val loc = switchRow("Lokasi", "Simpan koordinat GPS di EXIF foto", { prefs.location }) { on ->
            if (on) {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 7)
                }
            }
            prefs.location = on
        }
        locSwitch = loc.second
        card(
            switchRow("Suara rana", null, { prefs.shutterSound }) { prefs.shutterSound = it }.first,
            loc.first,
            switchRow("Level horizontal", "Garis kemiringan di pratinjau", { prefs.level }) { prefs.level = it }.first,
            switchRow("Garis grid", null, { prefs.grid }) { prefs.grid = it }.first,
            valueRow("Tombol volume sebagai", null, {
                when (prefs.volumeAction) { 0 -> "Rana"; 1 -> "Zoom"; else -> "Nonaktif" }
            }) { prefs.volumeAction = (prefs.volumeAction + 1) % 3 },
            switchRow("Koreksi distorsi lensa", "Jika didukung oleh kamera", { prefs.lensCorrection }) { prefs.lensCorrection = it }.first
        )

        section("Video")
        card(
            switchRow("Unlock EIS 1080p 60 fps", "Paksa 1080p 60 fps + stabilisasi di mode VIDEO dan VLOG. Jika HAL menolak, otomatis turun ke 30 fps",
                { prefs.unlockEis }) { prefs.unlockEis = it }.first,
            valueRow("Resolusi video", "Dipakai saat Unlock EIS mati", {
                if (prefs.videoHeight >= 2160) "4K" else prefs.videoHeight.toString() + "p"
            }) { prefs.videoHeight = cycle(listOf(720, 1080, 2160), prefs.videoHeight) },
            valueRow("Frame rate", "Dipakai saat Unlock EIS mati", { prefs.videoFps.toString() + " fps" }) {
                prefs.videoFps = cycle(listOf(30, 60), prefs.videoFps)
            },
            switchRow("Stabilisasi EIS", "Dipakai saat Unlock EIS mati", { prefs.eis }) { prefs.eis = it }.first,
            valueRow("Interval selang waktu", null, { prefs.tlInterval.toString() + " dtk" }) {
                prefs.tlInterval = cycle(listOf(1, 2, 5), prefs.tlInterval)
            }
        )

        section("Foto")
        card(
            valueRow("Kualitas JPEG", null, {
                when (prefs.jpegQuality) { 90 -> "Standar"; 100 -> "Maksimal"; else -> "Tinggi" }
            }) { prefs.jpegQuality = cycle(listOf(90, 95, 100), prefs.jpegQuality) },
            valueRow("Rasio foto", null, { if (prefs.photoRatio == 1) "16:9" else "4:3" }) {
                prefs.photoRatio = 1 - prefs.photoRatio
            }
        )

        section("Stabilizer")
        card(
            switchRow("Peredam guncangan", "Sama dengan tombol di kanan atas mode video", { prefs.eis }) { prefs.eis = it }.first,
            switchRow("EIS Plus (gyroscope)",
                "Stabilizer perangkat lunak berbasis gyro. Tetap bekerja di 1080p 60 fps walau EIS bawaan ditolak HAL. Hanya kamera belakang",
                { prefs.stabGyro }) { prefs.stabGyro = it }.first,
            valueRow("Kekuatan", "Makin kuat, makin banyak tepi gambar terpotong", {
                when (prefs.stabLevel) { 0 -> "Ringan"; 2 -> "Kuat"; else -> "Normal" }
            }) { prefs.stabLevel = cycle(listOf(0, 1, 2), prefs.stabLevel) }
        )

        section("Beauty dan wajah")
        card(
            valueRow("Preset beauty", "Foto dan video (belum untuk video ganda)", { Beauty.name(prefs.beauty) }) {
                prefs.beauty = (prefs.beauty + 1) % Beauty.NAMES.size
            },
            switchRow("Deteksi wajah", "Kotak wajah di pratinjau", { prefs.faceDetect }) { prefs.faceDetect = it }.first
        )

        section("Tampilan")
        card(
            switchRow("Kaca cair (blur nyata)", "Latar panel diburamkan dari pratinjau kamera. Matikan untuk hemat baterai",
                { prefs.glass }) { prefs.glass = it }.first,
            switchRow("Cermin pratinjau kamera depan", null, { prefs.mirrorFront }) { prefs.mirrorFront = it }.first,
            valueRow("Tata letak video ganda", null, { if (prefs.dualLayout == 0) "Atas-bawah" else "Gambar dalam gambar" }) {
                prefs.dualLayout = 1 - prefs.dualLayout
            }
        )

        section("Watermark")
        val devRef = valueRowRef("Nama perangkat terdeteksi", "Ketuk untuk deteksi ulang", { prefs.deviceName.ifBlank { "…" } }) {}
        devRef.first.setOnClickListener {
            devRef.second.text = "…"
            Thread {
                val n = try { DeviceInfo.refresh(this, prefs) } catch (_: Exception) { "?" }
                runOnUiThread { devRef.second.text = n }
            }.start()
        }
        Thread {
            val n = try { DeviceInfo.get(this, prefs) } catch (_: Exception) { "?" }
            runOnUiThread { devRef.second.text = n }
        }.start()
        val textRef = valueRowRef("Teks watermark", "Kosong = otomatis memakai nama perangkat",
            { if (prefs.wmText.isBlank()) "Otomatis" else prefs.wmText }) {}
        textRef.first.setOnClickListener { editWatermark { textRef.second.text = if (prefs.wmText.isBlank()) "Otomatis" else prefs.wmText } }
        card(
            switchRow("Watermark", "Tulis teks di pojok foto (hanya foto, video belum)", { prefs.watermark }) { prefs.watermark = it }.first,
            devRef.first,
            textRef.first,
            valueRow("Gaya", null, { if (prefs.wmStyle == 0) "Shot on …" else "Nama saja" }) { prefs.wmStyle = 1 - prefs.wmStyle },
            valueRow("Posisi", null, { when (prefs.wmPos) { 1 -> "Tengah bawah"; 2 -> "Kanan bawah"; else -> "Kiri bawah" } }) {
                prefs.wmPos = (prefs.wmPos + 1) % 3
            }
        )

        section("Penyimpanan")
        card(infoRow("Jalur penyimpanan", "Penyimpanan internal\nDCIM/Camera"))

        section("Diagnostik kamera")
        card(infoRow("Kemampuan yang dilaporkan HAL", diag()))

        section("Log kamera")
        card(
            infoRow("Peristiwa terakhir", CamLog.dump().ifBlank { "(kosong)" }),
            valueRow("Salin log", "Kirim ke pengembang kalau ada masalah", { "" }) {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("camera-log", CamLog.dump() + "\n\n" + diag()))
                Toast.makeText(this, "Log disalin", Toast.LENGTH_SHORT).show()
            },
            valueRow("Hapus log", null, { "" }) { CamLog.clear(); recreate() }
        )

        section("Tentang")
        card(infoRow("Camera 1.0 Beta", "SenzaProject"))
    }

    private fun editWatermark(done: () -> Unit) {
        val et = EditText(this)
        et.setText(prefs.wmText)
        et.hint = "Kosongkan = nama perangkat"
        et.setSingleLine()
        et.setTextColor(Color.WHITE)
        et.setHintTextColor(C.GRAY)
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Teks watermark")
            .setView(et)
            .setPositiveButton("Simpan") { _, _ -> prefs.wmText = et.text.toString().trim(); done() }
            .setNegativeButton("Batal", null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            prefs.location = false
            locSwitch?.setCheckedSilently(false)
        }
    }

    private fun diag(): String = try {
        val cm = getSystemService(CAMERA_SERVICE) as CameraManager
        val sb = StringBuilder()
        for (id in cm.cameraIdList) {
            val ch = cm.getCameraCharacteristics(id)
            val facing = when (ch.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_BACK -> "belakang"
                CameraCharacteristics.LENS_FACING_FRONT -> "depan"
                else -> "lain"
            }
            val st = ch.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.joinToString(",") {
                when (it) { 0 -> "OFF"; 1 -> "ON"; 2 -> "PREVIEW"; else -> it.toString() }
            } ?: "-"
            val ois = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.contains(1) == true
            val fr = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.joinToString(" ") { "${it.lower}-${it.upper}" } ?: "-"
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val d = try { map?.getOutputMinFrameDuration(MediaRecorder::class.java, Size(1920, 1080)) ?: 0L } catch (_: Exception) { 0L }
            val max = if (d > 0) (1e9 / d).roundToInt() else 0
            sb.append("ID $id ($facing)\n")
            sb.append("  EIS: $st | OIS: ${if (ois) "ya" else "tidak"}\n")
            sb.append("  FPS: $fr\n")
            sb.append("  1080p maks: $max fps\n")
        }
        sb.toString().trim()
    } catch (e: Exception) {
        "Tidak bisa membaca info kamera: ${e.message}"
    }
}
