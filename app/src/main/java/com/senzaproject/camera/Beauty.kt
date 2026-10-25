package com.senzaproject.camera

import android.graphics.Bitmap
import android.media.FaceDetector
import kotlin.math.max
import kotlin.math.min

/** Deteksi wajah sederhana (android.media.FaceDetector) pada bitmap tegak. Koordinat dalam piksel bitmap asli. */
object FaceFind {
    class F(val cx: Float, val cy: Float, val ed: Float)

    fun detect(bmp: Bitmap): List<F> {
        val w = bmp.width
        val h = bmp.height
        val ds = 640f / max(w, h)
        val dw = (w * ds).toInt() and 1.inv()
        val dh = (h * ds).toInt()
        if (dw < 32 || dh < 32) return emptyList()
        val small = Bitmap.createScaledBitmap(bmp, dw, dh, true)
        val det = small.copy(Bitmap.Config.RGB_565, false)
        if (small !== bmp) small.recycle()
        val faces = arrayOfNulls<FaceDetector.Face>(4)
        val n = try {
            FaceDetector(dw, dh, 4).findFaces(det, faces)
        } finally {
            det.recycle()
        }
        val out = ArrayList<F>()
        val pt = android.graphics.PointF()
        for (i in 0 until n) {
            val f = faces[i] ?: continue
            f.getMidPoint(pt)
            out.add(F(pt.x / ds, pt.y / ds, f.eyesDistance() / ds))
        }
        return out
    }
}

/** Preset beauty. Foto: haluskan kulit di sekitar wajah + cerahkan. Video: parameter shader di GlPipeline. */
object Beauty {
    val NAMES = arrayOf("Mati", "Alami", "Halus", "Cerah", "Maks")
    val SMOOTH = floatArrayOf(0f, 0.35f, 0.60f, 0.50f, 0.85f)
    val BRIGHT = floatArrayOf(0f, 0.10f, 0.15f, 0.40f, 0.45f)

    fun name(p: Int): String = NAMES[p.coerceIn(0, NAMES.size - 1)]

    /** Mengubah [bmp] langsung. false = tidak ada wajah / preset mati. */
    fun applyPhoto(bmp: Bitmap, preset: Int): Boolean {
        if (preset <= 0 || preset >= NAMES.size) return false
        val faces = FaceFind.detect(bmp)
        if (faces.isEmpty()) return false
        val sm = SMOOTH[preset]
        val br = BRIGHT[preset]
        for (f in faces) {
            val x0 = max(0, (f.cx - f.ed * 2.4f).toInt())
            val x1 = min(bmp.width, (f.cx + f.ed * 2.4f).toInt())
            val y0 = max(0, (f.cy - f.ed * 2.4f).toInt())
            val y1 = min(bmp.height, (f.cy + f.ed * 3.4f).toInt())
            val rw = x1 - x0
            val rh = y1 - y0
            if (rw < 16 || rh < 16) continue
            val reg = Bitmap.createBitmap(bmp, x0, y0, rw, rh)
            val small = Bitmap.createScaledBitmap(reg, max(1, rw / 6), max(1, rh / 6), true)
            val blur = Bitmap.createScaledBitmap(small, rw, rh, true)
            val px = IntArray(rw * rh)
            val bp = IntArray(rw * rh)
            reg.getPixels(px, 0, rw, 0, 0, rw, rh)
            blur.getPixels(bp, 0, rw, 0, 0, rw, rh)
            val ecx = f.cx - x0
            val ecy = f.cy + f.ed * 0.5f - y0
            val rx = f.ed * 2.2f
            val ry = f.ed * 2.9f
            for (y in 0 until rh) {
                val dy = (y - ecy) / ry
                for (x in 0 until rw) {
                    val dx = (x - ecx) / rx
                    val d = dx * dx + dy * dy
                    if (d >= 1f) continue
                    val i = y * rw + x
                    val c = px[i]
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    val cb = 128f - 0.168736f * r - 0.331264f * g + 0.5f * b
                    val cr = 128f + 0.5f * r - 0.418688f * g - 0.081312f * b
                    if (cb < 77f || cb > 127f || cr < 133f || cr > 173f) continue
                    val fall = 1f - d
                    val w = sm * fall
                    val q = bp[i]
                    var nr = r + (((q shr 16) and 0xFF) - r) * w
                    var ng = g + (((q shr 8) and 0xFF) - g) * w
                    var nb = b + ((q and 0xFF) - b) * w
                    val gain = 1f + br * 0.22f * fall
                    val lift = br * 14f * fall
                    nr = min(255f, nr * gain + lift)
                    ng = min(255f, ng * gain + lift)
                    nb = min(255f, nb * gain + lift)
                    px[i] = (0xFF shl 24) or (nr.toInt() shl 16) or (ng.toInt() shl 8) or nb.toInt()
                }
            }
            bmp.setPixels(px, 0, rw, x0, y0, rw, rh)
            reg.recycle(); small.recycle(); blur.recycle()
        }
        return true
    }
}
