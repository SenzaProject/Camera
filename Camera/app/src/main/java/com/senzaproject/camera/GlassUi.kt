package com.senzaproject.camera

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.TextureView
import android.view.View
import kotlin.math.*

object C {
    val YELLOW = 0xFFFFD60A.toInt()
    val GREEN = 0xFF34C759.toInt()
    val RED = 0xFFFF3B30.toInt()
    val CARD = 0xFF1C1C1E.toInt()
    val SEP = 0xFF38383A.toInt()
    val GRAY = 0xFF8E8E93.toInt()
    val TRACK_OFF = 0xFF39393D.toInt()
}

fun Context.dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

/**
 * Sumber blur latar untuk kaca cair: cuplikan kecil pratinjau kamera yang diburamkan dan diperbarui berkala.
 * Setiap GlassDrawable menggambar potongan cuplikan yang berada tepat di belakang dirinya.
 */
object Backdrop {
    var bitmap: Bitmap? = null
    var frame: View? = null
    var mirror = false
    var enabled = true
    private val views = java.util.WeakHashMap<View, Boolean>()
    private var px = IntArray(0)
    private var tmp = IntArray(0)

    fun register(v: View) { views[v] = true }

    fun invalidateAll() {
        for (v in views.keys.toList()) v.invalidate()
    }

    fun update(tex: TextureView) {
        if (!enabled || !tex.isAvailable) return
        try {
            var bm = bitmap
            if (bm == null) {
                bm = Bitmap.createBitmap(54, 96, Bitmap.Config.ARGB_8888)
                bitmap = bm
                px = IntArray(bm.width * bm.height)
                tmp = IntArray(bm.width * bm.height)
            }
            tex.getBitmap(bm)
            val w = bm.width
            val h = bm.height
            bm.getPixels(px, 0, w, 0, 0, w, h)
            boxBlur(px, tmp, w, h, 2)
            boxBlur(px, tmp, w, h, 2)
            bm.setPixels(px, 0, w, 0, 0, w, h)
            invalidateAll()
        } catch (_: Exception) {
        }
    }

    private fun boxBlur(a: IntArray, t: IntArray, w: Int, h: Int, r: Int) {
        for (y in 0 until h) for (x in 0 until w) {
            var sr = 0; var sg = 0; var sb = 0
            for (k in -r..r) {
                val c = a[y * w + (x + k).coerceIn(0, w - 1)]
                sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255
            }
            val n = 2 * r + 1
            t[y * w + x] = (0xFF shl 24) or ((sr / n) shl 16) or ((sg / n) shl 8) or (sb / n)
        }
        for (y in 0 until h) for (x in 0 until w) {
            var sr = 0; var sg = 0; var sb = 0
            for (k in -r..r) {
                val c = t[(y + k).coerceIn(0, h - 1) * w + x]
                sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255
            }
            val n = 2 * r + 1
            a[y * w + x] = (0xFF shl 24) or ((sr / n) shl 16) or ((sg / n) shl 8) or (sb / n)
        }
    }
}

/** Latar "liquid glass": blur latar nyata dari pratinjau, pembesaran tipis ala lensa, kilau, dan tepi bercahaya. */
class GlassDrawable(
    ctx: Context,
    private val radius: Float = -1f,
    private val fillColor: Int = 0x59303034,
    private val useBackdrop: Boolean = true
) : Drawable() {
    private val density = ctx.resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hi = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        val cm = ColorMatrix()
        cm.setSaturation(1.5f)
        cm.postConcat(ColorMatrix(floatArrayOf(
            1.08f, 0f, 0f, 0f, 8f,
            0f, 1.08f, 0f, 0f, 8f,
            0f, 0f, 1.08f, 0f, 8f,
            0f, 0f, 0f, 1f, 0f)))
        colorFilter = ColorMatrixColorFilter(cm)
    }
    private val rect = RectF()
    private val path = Path()
    private val loc = IntArray(2)
    private val floc = IntArray(2)

    override fun draw(c: Canvas) {
        val b = bounds
        rect.set(b.left + 0.5f, b.top + 0.5f, b.right - 0.5f, b.bottom - 0.5f)
        val r = if (radius < 0f) min(rect.width(), rect.height()) / 2f else radius
        val bmp = Backdrop.bitmap
        val fr = Backdrop.frame
        val view = callback as? View
        var backdrop = false
        if (useBackdrop && Backdrop.enabled && bmp != null && fr != null && view != null && fr.width > 0 && fr.height > 0) {
            view.getLocationInWindow(loc)
            fr.getLocationInWindow(floc)
            Backdrop.register(view)
            c.save()
            path.reset()
            path.addRoundRect(rect, r, r, Path.Direction.CW)
            c.clipPath(path)
            c.scale(1.05f, 1.05f, rect.centerX(), rect.centerY())
            c.translate((floc[0] - loc[0]).toFloat(), (floc[1] - loc[1]).toFloat())
            c.scale(fr.width.toFloat() / bmp.width, fr.height.toFloat() / bmp.height)
            if (Backdrop.mirror) {
                c.translate(bmp.width.toFloat(), 0f)
                c.scale(-1f, 1f)
            }
            c.drawBitmap(bmp, 0f, 0f, bgPaint)
            c.restore()
            backdrop = true
        }
        fill.color = if (backdrop) 0x40202024 else fillColor
        c.drawRoundRect(rect, r, r, fill)
        hi.shader = LinearGradient(0f, rect.top, 0f, rect.bottom,
            intArrayOf(0x4AFFFFFF, 0x0DFFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, r, r, hi)
        stroke.strokeWidth = density * 1.2f
        stroke.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
            intArrayOf(0xCCFFFFFF.toInt(), 0x1AFFFFFF, 0x80FFFFFF.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, r, r, stroke)
        val ins = density * 1.6f
        if (rect.width() > ins * 4 && rect.height() > ins * 4) {
            val r2 = RectF(rect.left + ins, rect.top + ins, rect.right - ins, rect.bottom - ins)
            inner.strokeWidth = density
            inner.shader = LinearGradient(r2.left, r2.bottom, r2.right, r2.top,
                intArrayOf(0x30FFFFFF, 0x00FFFFFF, 0x24FFFFFF), null, Shader.TileMode.CLAMP)
            c.drawRoundRect(r2, max(0f, r - ins), max(0f, r - ins), inner)
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

enum class Ic {
    FLASH_OFF, FLASH_AUTO, FLASH_ON, CHEVRON, FLIP, GEAR, TIMER, RATIO, GRID, LEVEL, BACK, CLOSE,
    PRO, MACRO, TIMELAPSE, HD, PANO, DOC, SLOWMO, DUAL, AIGC, SKY, WATERMARK,
    STAB, FACE, BEAUTY, SWAP, LAYOUT
}

object Icons {
    private val path = Path()
    private val rect = RectF()

    private fun text(c: Canvas, p: Paint, t: String, x: Float, y: Float, size: Float) {
        val oldStyle = p.style
        p.style = Paint.Style.FILL
        p.textSize = size
        p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.DEFAULT_BOLD
        c.drawText(t, x, y, p)
        p.style = oldStyle
        p.textAlign = Paint.Align.LEFT
    }

    private fun arrow(c: Canvas, p: Paint, deg: Float, r: Float) {
        val a = Math.toRadians(deg.toDouble())
        val px = (r * cos(a)).toFloat(); val py = (r * sin(a)).toFloat()
        val tx = (-sin(a)).toFloat(); val ty = cos(a).toFloat()
        val nx = cos(a).toFloat(); val ny = sin(a).toFloat()
        path.reset()
        path.moveTo(px + tx * 0.34f, py + ty * 0.34f)
        path.lineTo(px + nx * 0.22f, py + ny * 0.22f)
        path.lineTo(px - nx * 0.22f, py - ny * 0.22f)
        path.close()
        val old = p.style
        p.style = Paint.Style.FILL
        c.drawPath(path, p)
        p.style = old
    }

    private fun bolt(dx: Float) {
        path.reset()
        path.moveTo(0.3f + dx, -1f)
        path.lineTo(-0.4f + dx, 0.1f)
        path.lineTo(0.0f + dx, 0.1f)
        path.lineTo(-0.3f + dx, 1f)
        path.lineTo(0.4f + dx, -0.15f)
        path.lineTo(0.0f + dx, -0.15f)
        path.close()
    }

    fun draw(c: Canvas, ic: Ic, cx: Float, cy: Float, s: Float, p: Paint, strokePx: Float, label: String = "") {
        c.save()
        c.translate(cx, cy)
        c.scale(s, s)
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        p.strokeWidth = strokePx / s
        when (ic) {
            Ic.FLASH_OFF, Ic.FLASH_ON, Ic.FLASH_AUTO -> {
                bolt(if (ic == Ic.FLASH_AUTO) -0.25f else 0f)
                if (ic == Ic.FLASH_ON) p.style = Paint.Style.FILL_AND_STROKE
                c.drawPath(path, p)
                p.style = Paint.Style.STROKE
                if (ic == Ic.FLASH_OFF) c.drawLine(-0.85f, -0.85f, 0.85f, 0.85f, p)
                if (ic == Ic.FLASH_AUTO) text(c, p, "A", 0.62f, 0.8f, 0.8f)
            }
            Ic.CHEVRON -> {
                path.reset(); path.moveTo(-0.7f, 0.3f); path.lineTo(0f, -0.3f); path.lineTo(0.7f, 0.3f)
                c.drawPath(path, p)
            }
            Ic.BACK -> {
                path.reset(); path.moveTo(0.3f, -0.8f); path.lineTo(-0.4f, 0f); path.lineTo(0.3f, 0.8f)
                c.drawPath(path, p)
            }
            Ic.CLOSE -> {
                c.drawLine(-0.7f, -0.7f, 0.7f, 0.7f, p); c.drawLine(0.7f, -0.7f, -0.7f, 0.7f, p)
            }
            Ic.FLIP -> {
                rect.set(-0.72f, -0.72f, 0.72f, 0.72f)
                c.drawArc(rect, 200f, 130f, false, p)
                c.drawArc(rect, 20f, 130f, false, p)
                arrow(c, p, 330f, 0.72f)
                arrow(c, p, 150f, 0.72f)
            }
            Ic.GEAR -> {
                c.drawCircle(0f, 0f, 0.3f, p)
                c.drawCircle(0f, 0f, 0.6f, p)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    c.drawLine((0.62 * cos(a)).toFloat(), (0.62 * sin(a)).toFloat(),
                        (0.9 * cos(a)).toFloat(), (0.9 * sin(a)).toFloat(), p)
                }
            }
            Ic.TIMER -> {
                c.drawCircle(0f, 0.12f, 0.72f, p)
                c.drawLine(-0.2f, -0.88f, 0.2f, -0.88f, p)
                c.drawLine(0f, 0.12f, 0f, -0.35f, p)
                c.drawLine(0f, 0.12f, 0.3f, 0.28f, p)
            }
            Ic.RATIO -> {
                rect.set(-0.9f, -0.6f, 0.9f, 0.6f)
                c.drawRoundRect(rect, 0.18f, 0.18f, p)
                if (label.isNotEmpty()) text(c, p, label, 0f, 0.24f, 0.62f)
            }
            Ic.GRID -> {
                rect.set(-0.8f, -0.8f, 0.8f, 0.8f)
                c.drawRoundRect(rect, 0.18f, 0.18f, p)
                c.drawLine(-0.27f, -0.8f, -0.27f, 0.8f, p); c.drawLine(0.27f, -0.8f, 0.27f, 0.8f, p)
                c.drawLine(-0.8f, -0.27f, 0.8f, -0.27f, p); c.drawLine(-0.8f, 0.27f, 0.8f, 0.27f, p)
            }
            Ic.LEVEL -> {
                c.drawLine(-0.95f, 0f, -0.4f, 0f, p)
                c.drawLine(0.4f, 0f, 0.95f, 0f, p)
                c.drawLine(-0.22f, 0f, 0.22f, 0f, p)
                c.drawLine(0f, -0.4f, 0f, -0.2f, p)
            }
            Ic.PRO -> {
                c.drawCircle(0f, 0f, 0.8f, p)
                c.drawCircle(0f, 0f, 0.3f, p)
                for (i in 0 until 3) {
                    val a = Math.toRadians(90.0 + i * 120.0)
                    c.drawLine((0.3 * cos(a)).toFloat(), (0.3 * sin(a)).toFloat(),
                        (0.8 * cos(a + 0.5)).toFloat(), (0.8 * sin(a + 0.5)).toFloat(), p)
                }
            }
            Ic.MACRO -> {
                for (i in 0 until 5) {
                    val a = Math.toRadians(-90.0 + i * 72.0)
                    c.drawCircle((0.5 * cos(a)).toFloat(), (0.5 * sin(a)).toFloat(), 0.3f, p)
                }
                c.drawCircle(0f, 0f, 0.16f, p)
            }
            Ic.TIMELAPSE -> {
                c.drawCircle(0f, 0f, 0.8f, p)
                c.drawLine(0f, 0f, 0f, -0.5f, p)
                c.drawLine(0f, 0f, 0.38f, 0.22f, p)
            }
            Ic.HD -> {
                rect.set(-0.9f, -0.62f, 0.9f, 0.62f)
                c.drawRoundRect(rect, 0.18f, 0.18f, p)
                text(c, p, "HD", 0f, 0.26f, 0.72f)
            }
            Ic.PANO -> {
                rect.set(-0.9f, -0.62f, 0.9f, 0.62f)
                c.drawRoundRect(rect, 0.18f, 0.18f, p)
                path.reset(); path.moveTo(-0.9f, 0.4f); path.lineTo(-0.3f, -0.15f); path.lineTo(0.05f, 0.25f)
                path.lineTo(0.35f, -0.05f); path.lineTo(0.9f, 0.4f)
                c.drawPath(path, p)
            }
            Ic.DOC -> {
                rect.set(-0.58f, -0.88f, 0.58f, 0.88f)
                c.drawRoundRect(rect, 0.14f, 0.14f, p)
                c.drawLine(-0.3f, -0.35f, 0.3f, -0.35f, p)
                c.drawLine(-0.3f, 0f, 0.3f, 0f, p)
                c.drawLine(-0.3f, 0.35f, 0.1f, 0.35f, p)
            }
            Ic.SLOWMO -> {
                p.pathEffect = DashPathEffect(floatArrayOf(0.26f, 0.2f), 0f)
                c.drawCircle(0f, 0f, 0.82f, p)
                p.pathEffect = null
                c.drawCircle(0f, 0f, 0.22f, p)
            }
            Ic.DUAL -> {
                c.drawCircle(-0.38f, 0f, 0.52f, p)
                c.drawCircle(0.38f, 0f, 0.52f, p)
            }
            Ic.AIGC -> {
                path.reset()
                path.moveTo(0f, -0.95f); path.lineTo(0.2f, -0.2f); path.lineTo(0.95f, 0f); path.lineTo(0.2f, 0.2f)
                path.lineTo(0f, 0.95f); path.lineTo(-0.2f, 0.2f); path.lineTo(-0.95f, 0f); path.lineTo(-0.2f, -0.2f)
                path.close()
                c.drawPath(path, p)
            }
            Ic.WATERMARK -> {
                rect.set(-0.85f, -0.7f, 0.85f, 0.7f)
                c.drawRoundRect(rect, 0.16f, 0.16f, p)
                c.drawLine(-0.45f, 0.36f, 0.45f, 0.36f, p)
                c.drawCircle(0f, -0.1f, 0.22f, p)
            }
            Ic.STAB -> {
                rect.set(-0.42f, -0.85f, 0.42f, 0.85f)
                c.drawRoundRect(rect, 0.14f, 0.14f, p)
                rect.set(-1.15f, -0.5f, -0.25f, 0.5f)
                c.drawArc(rect, 135f, 90f, false, p)
                rect.set(0.25f, -0.5f, 1.15f, 0.5f)
                c.drawArc(rect, -45f, 90f, false, p)
                if (label == "off") c.drawLine(-0.9f, -0.9f, 0.9f, 0.9f, p)
            }
            Ic.FACE -> {
                val k = 0.34f
                for (sx in floatArrayOf(-1f, 1f)) for (sy in floatArrayOf(-1f, 1f)) {
                    c.drawLine(sx * 0.9f, sy * 0.9f, sx * 0.9f, sy * (0.9f - k), p)
                    c.drawLine(sx * 0.9f, sy * 0.9f, sx * (0.9f - k), sy * 0.9f, p)
                }
                c.drawCircle(-0.28f, -0.15f, 0.05f, p)
                c.drawCircle(0.28f, -0.15f, 0.05f, p)
                rect.set(-0.32f, -0.05f, 0.32f, 0.5f)
                c.drawArc(rect, 20f, 140f, false, p)
            }
            Ic.BEAUTY -> {
                path.reset()
                path.moveTo(-0.2f, -0.85f); path.lineTo(-0.05f, -0.2f); path.lineTo(0.55f, -0.05f); path.lineTo(-0.05f, 0.1f)
                path.lineTo(-0.2f, 0.75f); path.lineTo(-0.35f, 0.1f); path.lineTo(-0.95f, -0.05f); path.lineTo(-0.35f, -0.2f)
                path.close()
                c.drawPath(path, p)
                c.drawLine(0.6f, -0.75f, 0.6f, -0.35f, p)
                c.drawLine(0.4f, -0.55f, 0.8f, -0.55f, p)
                c.drawLine(0.55f, 0.35f, 0.55f, 0.65f, p)
                c.drawLine(0.4f, 0.5f, 0.7f, 0.5f, p)
            }
            Ic.SWAP -> {
                rect.set(-0.95f, -0.95f, -0.15f, -0.15f)
                c.drawRoundRect(rect, 0.12f, 0.12f, p)
                rect.set(0.15f, 0.15f, 0.95f, 0.95f)
                c.drawRoundRect(rect, 0.12f, 0.12f, p)
                rect.set(-0.1f, -0.95f, 0.95f, 0.1f)
                c.drawArc(rect, 270f, 90f, false, p)
                c.drawLine(0.95f, -0.45f, 0.75f, -0.62f, p)
                c.drawLine(0.95f, -0.45f, 1.0f, -0.7f, p)
            }
            Ic.LAYOUT -> {
                rect.set(-0.8f, -0.9f, 0.8f, 0.9f)
                c.drawRoundRect(rect, 0.12f, 0.12f, p)
                c.drawLine(-0.8f, 0f, 0.8f, 0f, p)
            }
            Ic.SKY -> {
                for (r in floatArrayOf(0.3f, 0.58f, 0.86f)) {
                    rect.set(-r, 0.45f - r, r, 0.45f + r)
                    c.drawArc(rect, 180f, 180f, false, p)
                }
                c.drawLine(-0.95f, 0.45f, 0.95f, 0.45f, p)
            }
        }
        c.restore()
    }
}

class IconView(ctx: Context, var ic: Ic, var color: Int = Color.WHITE, var strokeDp: Float = 1.8f) : View(ctx) {
    var label: String = ""
        set(v) { field = v; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setIcon(i: Ic) { ic = i; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val w = width - paddingLeft - paddingRight
        val h = height - paddingTop - paddingBottom
        if (w <= 0 || h <= 0) return
        paint.color = color
        Icons.draw(canvas, ic, paddingLeft + w / 2f, paddingTop + h / 2f, min(w, h) / 2f,
            paint, context.dpf(strokeDp), label)
    }
}

class ShutterView(ctx: Context) : View(ctx) {
    var video = false
        set(v) { field = v; invalidate() }
    var recording = false
        set(v) { field = v; invalidate() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG)

    init { isClickable = true }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f
        ring.strokeWidth = context.dpf(4f)
        c.drawCircle(cx, cy, r - ring.strokeWidth / 2f, ring)
        val ir = r - ring.strokeWidth - context.dpf(5f)
        val k = if (isPressed) 0.88f else 1f
        if (recording) {
            inner.color = C.RED
            val q = ir * 0.55f
            c.drawRoundRect(cx - q, cy - q, cx + q, cy + q, q * 0.32f, q * 0.32f, inner)
        } else {
            inner.color = if (video) C.RED else Color.WHITE
            c.drawCircle(cx, cy, ir * k, inner)
        }
    }
}

class GridOverlay(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x70FFFFFF; strokeWidth = ctx.dpf(1f) }
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        for (i in 1..2) {
            c.drawLine(w * i / 3f, 0f, w * i / 3f, h, p)
            c.drawLine(0f, h * i / 3f, w, h * i / 3f, p)
        }
    }
}

class LevelOverlay(ctx: Context) : View(ctx) {
    var angle = 0f
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = ctx.dpf(2f); strokeCap = Paint.Cap.ROUND
    }
    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val half = ctx().dpf(46f)
        val level = abs(angle) < 1.0f
        p.color = if (level) C.YELLOW else Color.WHITE
        c.save()
        c.rotate(-angle, cx, cy)
        c.drawLine(cx - half * 2.4f, cy, cx - half * 1.4f, cy, p)
        c.drawLine(cx + half * 1.4f, cy, cx + half * 2.4f, cy, p)
        c.drawLine(cx - half * 0.5f, cy, cx + half * 0.5f, cy, p)
        c.restore()
        p.color = 0xAAFFFFFF.toInt()
        c.drawLine(cx - half * 2.4f, cy, cx - half * 2.4f + ctx().dpf(10f), cy, p)
        c.drawLine(cx + half * 2.4f - ctx().dpf(10f), cy, cx + half * 2.4f, cy, p)
    }
    private fun ctx(): Context = context
}

class IosSwitch(ctx: Context) : View(ctx) {
    var checked = false
        private set
    var onChange: ((Boolean) -> Unit)? = null
    private var t = 0f
    private val d = ctx.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init { setOnClickListener { toggle() } }

    fun setCheckedSilently(v: Boolean) { checked = v; t = if (v) 1f else 0f; invalidate() }

    private fun toggle() {
        checked = !checked
        ValueAnimator.ofFloat(t, if (checked) 1f else 0f).apply {
            duration = 180
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
            start()
        }
        onChange?.invoke(checked)
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((51 * d).toInt(), (31 * d).toInt())

    private fun mix(a: Int, b: Int, k: Float): Int {
        val r = (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt()
        val g = (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt()
        val bl = (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt()
        return Color.rgb(r, g, bl)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        paint.color = mix(C.TRACK_OFF, C.GREEN, t)
        rect.set(0f, 0f, w, h)
        c.drawRoundRect(rect, h / 2f, h / 2f, paint)
        val pad = 2f * d
        val r = h / 2f - pad
        val x = pad + r + (w - 2 * pad - 2 * r) * t
        paint.color = 0x33000000
        c.drawCircle(x, h / 2f + d, r, paint)
        paint.color = Color.WHITE
        c.drawCircle(x, h / 2f, r, paint)
    }
}


/** Cincin fokus + slider cahaya bergambar matahari di sampingnya. */
class FocusView(ctx: Context) : View(ctx) {
    var cx = 0f
    var cy = 0f
    var ev = 0f
        set(v) { field = v; invalidate() }
    var active = false
        set(v) { field = v; invalidate() }
    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    fun sliderX(): Float = if (cx + 62 * d > width - 24 * d) cx - 62 * d else cx + 62 * d
    fun halfLen(): Float = 76 * d
    fun hitSlider(x: Float, y: Float): Boolean =
        active && abs(x - sliderX()) < 36 * d && abs(y - cy) < halfLen() + 24 * d
    fun evFromY(y: Float): Float = ((cy - y) / halfLen()).coerceIn(-1f, 1f)

    override fun onDraw(c: Canvas) {
        if (!active) return
        p.color = C.YELLOW
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = 1.6f * d
        c.drawCircle(cx, cy, 36 * d, p)
        r.set(cx - 7 * d, cy - 7 * d, cx + 7 * d, cy + 7 * d)
        c.drawArc(r, 20f, 300f, false, p)
        val sx = sliderX()
        val sy = cy - ev * halfLen()
        p.strokeWidth = 1.8f * d
        val top = cy - halfLen()
        val bot = cy + halfLen()
        if (sy - 16 * d > top) c.drawLine(sx, top, sx, sy - 16 * d, p)
        if (sy + 16 * d < bot) c.drawLine(sx, sy + 16 * d, sx, bot, p)
        p.style = Paint.Style.FILL
        c.drawCircle(sx, sy, 6.5f * d, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.5f * d
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            c.drawLine(sx + (10 * d * cos(a)).toFloat(), sy + (10 * d * sin(a)).toFloat(),
                sx + (13.5f * d * cos(a)).toFloat(), sy + (13.5f * d * sin(a)).toFloat(), p)
        }
    }
}

/** Kotak deteksi wajah (koordinat ternormalisasi 0..1). */
class FaceOverlay(ctx: Context) : View(ctx) {
    var faces: List<RectF> = emptyList()
        set(v) { field = v; invalidate() }
    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = C.YELLOW
        strokeWidth = 2f * d
    }
    private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f * d
        setShadowLayer(3f * d, 0f, d, 0xAA000000.toInt())
    }

    override fun onDraw(c: Canvas) {
        if (faces.isEmpty()) return
        val w = width.toFloat()
        val h = height.toFloat()
        for (f in faces) {
            val l = f.left * w; val tp = f.top * h; val r = f.right * w; val b = f.bottom * h
            val k = min(r - l, b - tp) * 0.28f
            c.drawLine(l, tp, l + k, tp, p); c.drawLine(l, tp, l, tp + k, p)
            c.drawLine(r, tp, r - k, tp, p); c.drawLine(r, tp, r, tp + k, p)
            c.drawLine(l, b, l + k, b, p); c.drawLine(l, b, l, b - k, p)
            c.drawLine(r, b, r - k, b, p); c.drawLine(r, b, r, b - k, p)
        }
        c.drawText("${faces.size} wajah", 10f * d, 22f * d, t)
    }
}


/** Latar gelap berpendar lembut supaya panel kaca di layar Pengaturan terlihat. */
class AuroraDrawable : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(c: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        c.drawColor(0xFF05060A.toInt())
        blob(c, w * 0.15f, h * 0.12f, w * 0.85f, 0x663A6BFF)
        blob(c, w * 0.95f, h * 0.42f, w * 0.95f, 0x558A4DFF)
        blob(c, w * 0.2f, h * 0.88f, w * 0.95f, 0x4400C2A8)
    }

    private fun blob(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        p.shader = RadialGradient(cx, cy, r, color, color and 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, p)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
