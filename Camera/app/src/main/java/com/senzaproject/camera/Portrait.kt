package com.senzaproject.camera

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.media.FaceDetector
import kotlin.math.max

/**
 * Efek potret perangkat lunak: mendeteksi wajah (android.media.FaceDetector), lalu memburamkan
 * latar dengan peralihan lembut di sekitar kepala dan tubuh. Ini bukan bokeh berbasis kedalaman.
 */
object Portrait {

    /** Mengubah [bmp] langsung. Mengembalikan false (tanpa mengubah apa pun) kalau tidak ada wajah. */
    fun apply(bmp: Bitmap): Boolean {
        val w = bmp.width
        val h = bmp.height
        val ds = 640f / max(w, h)
        val dw = (w * ds).toInt() and 1.inv()   // lebar harus genap
        val dh = (h * ds).toInt()
        if (dw < 32 || dh < 32) return false

        val small = Bitmap.createScaledBitmap(bmp, dw, dh, true)
        val det = small.copy(Bitmap.Config.RGB_565, false)
        if (small !== bmp) small.recycle()
        val faces = arrayOfNulls<FaceDetector.Face>(4)
        val n = try {
            FaceDetector(dw, dh, 4).findFaces(det, faces)
        } finally {
            det.recycle()
        }
        if (n <= 0) return false

        // Mask resolusi rendah (1/8): alpha tinggi = bagian yang tetap tajam
        val mw = max(1, w / 8)
        val mh = max(1, h / 8)
        val mask = Bitmap.createBitmap(mw, mh, Bitmap.Config.ALPHA_8)
        val mc = Canvas(mask)
        val mp = Paint(Paint.ANTI_ALIAS_FLAG)
        val k = mw.toFloat() / dw
        val pt = PointF()
        for (i in 0 until n) {
            val f = faces[i] ?: continue
            f.getMidPoint(pt)
            val ed = f.eyesDistance()
            blob(mc, mp, pt.x * k, (pt.y + ed * 0.9f) * k, ed * 2.5f * k, ed * 3.3f * k, 1f)   // kepala
            blob(mc, mp, pt.x * k, (pt.y + ed * 4.8f) * k, ed * 4.2f * k, ed * 5.2f * k, 0.9f) // bahu dan tubuh
        }

        // Lapisan buram dibuat dari versi kecil yang dibesarkan bertahap
        val sm = Bitmap.createScaledBitmap(bmp, max(1, w / 16), max(1, h / 16), true)
        val mid = Bitmap.createScaledBitmap(sm, max(1, w / 4), max(1, h / 4), true)
        val sharp = bmp.copy(Bitmap.Config.ARGB_8888, false)

        val canvas = Canvas(bmp)
        val fp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(mid, null, Rect(0, 0, w, h), fp)

        val maskShader = BitmapShader(mask, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val mm = Matrix()
        mm.setScale(w.toFloat() / mw, h.toFloat() / mh)
        maskShader.setLocalMatrix(mm)
        val sharpShader = BitmapShader(sharp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val sp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        sp.shader = ComposeShader(sharpShader, maskShader, PorterDuff.Mode.DST_IN)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), sp)

        sm.recycle(); mid.recycle(); sharp.recycle(); mask.recycle()
        return true
    }

    private fun blob(c: Canvas, p: Paint, cx: Float, cy: Float, rx: Float, ry: Float, a: Float) {
        val alpha = (255 * a).toInt().coerceIn(0, 255)
        c.save()
        c.translate(cx, cy)
        c.scale(1f, ry / rx)
        p.shader = RadialGradient(
            0f, 0f, rx,
            intArrayOf(Color.argb(alpha, 0, 0, 0), Color.argb(alpha, 0, 0, 0), Color.argb(0, 0, 0, 0)),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP
        )
        c.drawCircle(0f, 0f, rx, p)
        c.restore()
        p.shader = null
    }
}
