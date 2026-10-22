package com.senzaproject.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.content.Context
import android.graphics.Typeface
import android.media.ExifInterface
import android.os.Build
import android.provider.Settings
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/** Log kecil yang disimpan di preferensi supaya bisa dibaca dari layar Pengaturan. */
object CamLog {
    private var prefs: Prefs? = null
    fun init(p: Prefs) { prefs = p }

    @Synchronized
    fun add(s: String) {
        val p = prefs ?: return
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val lines = (p.log.split("\n").filter { it.isNotBlank() } + "$t $s").takeLast(40)
        p.log = lines.joinToString("\n")
    }

    fun dump(): String = prefs?.log ?: ""
    fun clear() { prefs?.log = "" }
}

/** Membaca nama perangkat pengguna: marketname dari ROM dulu, lalu model bawaan sistem. */
object DeviceInfo {
    private val PROPS = listOf(
        "ro.product.marketname",
        "ro.product.vendor.marketname",
        "ro.product.odm.marketname",
        "ro.product.system.marketname",
        "ro.product.product.marketname",
        "ro.config.marketing_name",
        "ro.tran.market.name",
        "ro.oppo.market.name",
        "ro.vivo.market.name",
        "ro.vendor.product.display"
    )

    // Kode model yang sudah pasti diketahui
    private val KNOWN = mapOf("X6882" to "Infinix HOT 50")

    private fun getprop(k: String): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("getprop", k))
        val s = p.inputStream.bufferedReader().use { it.readText() }.trim()
        p.waitFor()
        s
    } catch (e: Exception) {
        ""
    }

    fun resolve(ctx: Context): String {
        val brandRaw = Build.MANUFACTURER.ifBlank { Build.BRAND }.trim()
        val brand = brandRaw.lowercase().replaceFirstChar { it.uppercase() }
        var name = ""
        for (k in PROPS) {
            val v = getprop(k)
            if (v.isNotBlank()) { name = v; break }
        }
        if (name.isBlank()) {
            val m = Build.MODEL.uppercase()
            name = KNOWN.entries.firstOrNull { m.contains(it.key) }?.value ?: ""
        }
        if (name.isBlank()) {
            // Nama perangkat bawaan sistem, dipakai hanya kalau tampak seperti nama produk (bukan nama pribadi)
            val dn = try { Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME) } catch (_: Exception) { null }
            if (!dn.isNullOrBlank() && dn.startsWith(brand, true) && dn.length <= 32 && !dn.equals(Build.MODEL, true)) name = dn
        }
        if (name.isBlank()) name = Build.MODEL
        name = name.trim()
        if (!name.startsWith(brand, true) && !name.startsWith(Build.BRAND, true)) name = "$brand $name"
        return name
    }

    /** Hasil di-cache; dihitung ulang kalau build ROM berubah. */
    fun get(ctx: Context, p: Prefs): String {
        if (p.deviceName.isBlank() || p.deviceFp != Build.FINGERPRINT) {
            p.deviceName = resolve(ctx)
            p.deviceFp = Build.FINGERPRINT
            CamLog.add("device: ${p.deviceName} (model=${Build.MODEL})")
        }
        return p.deviceName
    }

    fun refresh(ctx: Context, p: Prefs): String {
        p.deviceName = ""
        return get(ctx, p)
    }
}

object Watermark {
    fun text(ctx: Context, p: Prefs): String {
        val base = if (p.wmText.isNotBlank()) p.wmText.trim() else DeviceInfo.get(ctx, p)
        return if (p.wmStyle == 0) "Shot on $base" else base
    }

    private val TAGS = arrayOf(
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_ISO_SPEED_RATINGS,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_FLASH,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP, ExifInterface.TAG_GPS_PROCESSING_METHOD
    )

    /** Menggambar teks ke piksel foto (sudah diputar tegak) lalu mengembalikan JPEG baru. */
    fun apply(src: ByteArray, text: String, pos: Int, quality: Int): ByteArray {
        val exif = ExifInterface(ByteArrayInputStream(src))
        val ori = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(src, 0, src.size, bounds)
        val px = bounds.outWidth.toLong() * bounds.outHeight
        val opts = BitmapFactory.Options().apply {
            inSampleSize = if (px > 20_000_000L) 2 else 1
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(src, 0, src.size, opts)
            ?: throw IllegalStateException("decode JPEG gagal")

        val m = Matrix()
        when (ori) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        }
        var bmp = decoded
        if (!m.isIdentity) {
            bmp = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
            if (bmp !== decoded) decoded.recycle()
        }
        if (!bmp.isMutable) {
            val c = bmp.copy(Bitmap.Config.ARGB_8888, true)
            bmp.recycle()
            bmp = c
        }

        val w = bmp.width
        val h = bmp.height
        val shortSide = min(w, h).toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = 0xF2FFFFFF.toInt()
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        paint.textSize = shortSide * 0.03f
        while (paint.measureText(text) > w * 0.9f && paint.textSize > 8f) paint.textSize *= 0.92f
        paint.setShadowLayer(paint.textSize * 0.15f, 0f, paint.textSize * 0.05f, 0xAA000000.toInt())
        val margin = shortSide * 0.045f
        val x: Float
        when (pos) {
            1 -> { paint.textAlign = Paint.Align.CENTER; x = w / 2f }
            2 -> { paint.textAlign = Paint.Align.RIGHT; x = w - margin }
            else -> { paint.textAlign = Paint.Align.LEFT; x = margin }
        }
        Canvas(bmp).drawText(text, x, h - margin, paint)

        val out = ByteArrayOutputStream(src.size + 65536)
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        bmp.recycle()
        return out.toByteArray()
    }

    /** Salin EXIF penting (waktu, GPS, model) dari JPEG asli ke file hasil watermark. */
    fun copyExif(original: ByteArray, target: FileDescriptor) {
        val s = ExifInterface(ByteArrayInputStream(original))
        val d = ExifInterface(target)
        for (t in TAGS) {
            val v = s.getAttribute(t)
            if (v != null) d.setAttribute(t, v)
        }
        d.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        d.saveAttributes()
    }
}
