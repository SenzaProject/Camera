package com.senzaproject.camera

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
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

/** Latar "liquid glass": isi translusen, kilau atas, dan garis tepi bergradasi. */
class GlassDrawable(ctx: Context, private val radius: Float = -1f, private val fillColor: Int = 0x59303034) : Drawable() {
    private val density = ctx.resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hi = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()

    override fun draw(c: Canvas) {
        val b = bounds
        rect.set(b.left + 0.5f, b.top + 0.5f, b.right - 0.5f, b.bottom - 0.5f)
        val r = if (radius < 0f) min(rect.width(), rect.height()) / 2f else radius
        fill.color = fillColor
        c.drawRoundRect(rect, r, r, fill)
        hi.shader = LinearGradient(0f, rect.top, 0f, rect.bottom,
            intArrayOf(0x38FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.65f), Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, r, r, hi)
        stroke.strokeWidth = density
        stroke.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
            intArrayOf(0x99FFFFFF.toInt(), 0x14FFFFFF, 0x66FFFFFF), null, Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, r, r, stroke)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

enum class Ic {
    FLASH_OFF, FLASH_AUTO, FLASH_ON, CHEVRON, FLIP, GEAR, TIMER, RATIO, GRID, LEVEL, BACK, CLOSE,
    PRO, MACRO, TIMELAPSE, HD, PANO, DOC, SLOWMO, DUAL, AIGC, SKY, WATERMARK
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
