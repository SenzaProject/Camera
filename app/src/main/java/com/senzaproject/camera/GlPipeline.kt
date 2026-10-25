package com.senzaproject.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Renderer OpenGL: menerima 1 atau 2 aliran kamera, menggambarnya ke pratinjau dan (opsional) ke
 * surface MediaRecorder. Mendukung tata letak atas-bawah / gambar-dalam-gambar dan filter beauty.
 */
class GlPipeline(private val appCtx: Context, private val previewSt: SurfaceTexture, private val inputs: Int, private val driver: Int) {

    private val thread = HandlerThread("senza-gl").apply { start() }
    private val h = Handler(thread.looper)

    @Volatile var beauty = 0f
    @Volatile var bright = 0f
    @Volatile var layout = 0          // 0 = atas-bawah, 1 = gambar-dalam-gambar
    @Volatile var swap = false
    @Volatile var outW = 1080
    @Volatile var outH = 1920
    @Volatile private var released = false

    val aspect = FloatArray(inputs) { 9f / 16f }      // lebar/tinggi gambar tegak
    val mirror = BooleanArray(inputs)
    val sensorOri = IntArray(inputs) { 90 }

    // ---- Stabilizer gyro (EIS Plus)
    private var gyro: GyroTracker? = null
    private val stab = BooleanArray(inputs)
    private val fpx = FloatArray(inputs) { 1500f }
    private val upW = FloatArray(inputs) { 1080f }
    private val upH = FloatArray(inputs) { 1920f }
    private val tsRealtime = BooleanArray(inputs)
    private val lp = Array(inputs) { FloatArray(3) }
    private val lpInit = BooleanArray(inputs)
    private val lastTs = LongArray(inputs)
    private val tsOff = LongArray(inputs)
    private val tsOffSet = BooleanArray(inputs)
    private val shiftA = FloatArray(inputs)
    private val shiftB = FloatArray(inputs)
    private val rollRad = FloatArray(inputs)
    private val ang = FloatArray(3)
    private val tmp = FloatArray(2)
    @Volatile private var stabZoom = 1.10f
    @Volatile private var tau = 0.45f

    /** Level 0 = ringan, 1 = normal, 2 = kuat. Mengembalikan false bila perangkat tidak punya gyroscope. */
    fun enableStab(i: Int, fpxPx: Float, upWidthPx: Float, upHeightPx: Float, realtimeClock: Boolean, level: Int): Boolean {
        val g = gyro ?: GyroTracker(appCtx).also { gyro = it }
        if (!g.available) return false
        fpx[i] = fpxPx; upW[i] = upWidthPx; upH[i] = upHeightPx; tsRealtime[i] = realtimeClock
        when (level) {
            0 -> { stabZoom = 1.06f; tau = 0.25f }
            2 -> { stabZoom = 1.16f; tau = 0.80f }
            else -> { stabZoom = 1.10f; tau = 0.45f }
        }
        if (!stab.any { it }) {
            if (!g.start()) return false
        }
        stab[i] = true
        return true
    }

    val stabActive: Boolean get() = stab.any { it }

    private fun computeStab(i: Int, ts: Long) {
        val g = gyro ?: return
        if (!tsOffSet[i]) {
            tsOff[i] = if (tsRealtime[i]) 0L else (SystemClock.elapsedRealtimeNanos() - ts - 30_000_000L)
            tsOffSet[i] = true
            CamLog.add("EIS+ input $i: sumber waktu realtime=${tsRealtime[i]} offset=${tsOff[i] / 1_000_000} ms fpx=${fpx[i]}")
        }
        if (!g.angleAt(ts + tsOff[i], ang)) return
        val l = lp[i]
        if (!lpInit[i]) {
            l[0] = ang[0]; l[1] = ang[1]; l[2] = ang[2]
            lpInit[i] = true
            lastTs[i] = ts
            return
        }
        var dt = (ts - lastTs[i]) / 1e9f
        lastTs[i] = ts
        if (dt < 0.004f) dt = 0.004f
        if (dt > 0.1f) dt = 0.1f
        val a = dt / (tau + dt)
        val margin = (1f - 1f / stabZoom) / 2f * 0.55f
        val capYaw = margin * upW[i] / fpx[i]
        val capPitch = margin * upH[i] / fpx[i]
        val capRoll = 0.035f
        for (k in 0..2) l[k] += a * (ang[k] - l[k])
        val ex = (ang[0] - l[0]).coerceIn(-capPitch, capPitch); l[0] = ang[0] - ex
        val ey = (ang[1] - l[1]).coerceIn(-capYaw, capYaw); l[1] = ang[1] - ey
        val ez = (ang[2] - l[2]).coerceIn(-capRoll, capRoll); l[2] = ang[2] - ez
        shiftA[i] = fpx[i] * ey / upW[i]
        shiftB[i] = -fpx[i] * ex / upH[i]
        rollRad[i] = ez
    }

    /** Koreksi guncangan: zoom (crop), putar (roll), lalu geser (yaw/pitch) pada koordinat gambar tegak. */
    private fun tf(i: Int, a: Float, b: Float) {
        if (!stab[i] || !lpInit[i]) { tmp[0] = a; tmp[1] = b; return }
        val x = (a - 0.5f) / stabZoom * upW[i]
        val y = (b - 0.5f) / stabZoom * upH[i]
        val c = cos(rollRad[i])
        val s = sin(rollRad[i])
        val xr = x * c + y * s
        val yr = -x * s + y * c
        tmp[0] = 0.5f + xr / upW[i] + shiftA[i]
        tmp[1] = 0.5f + yr / upH[i] + shiftB[i]
    }

    private var dpy: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var ctx: EGLContext = EGL14.EGL_NO_CONTEXT
    private var cfg: EGLConfig? = null
    private var pbuf: EGLSurface = EGL14.EGL_NO_SURFACE
    private var previewSurf: EGLSurface = EGL14.EGL_NO_SURFACE
    private var recSurf: EGLSurface = EGL14.EGL_NO_SURFACE

    private var prog = 0
    private var aPos = 0
    private var aTex = 0
    private var uMat = 0
    private var uTex = 0
    private var uBeauty = 0
    private var uBright = 0
    private var uTexel = 0

    private val texIds = IntArray(inputs)
    private val sts = arrayOfNulls<SurfaceTexture>(inputs)
    private val surfaces = ArrayList<Surface>()
    private val mats = Array(inputs) { FloatArray(16) }
    private val have = BooleanArray(inputs)
    private val fresh = BooleanArray(inputs)
    private val needRot = BooleanArray(inputs)
    private val checked = BooleanArray(inputs)
    private val vb = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val sz = IntArray(1)

    fun start(sizes: List<Size>): List<Surface> {
        val latch = CountDownLatch(1)
        var err: Throwable? = null
        h.post {
            try { init(sizes) } catch (t: Throwable) { err = t }
            latch.countDown()
        }
        if (!latch.await(5, TimeUnit.SECONDS)) throw RuntimeException("GL init timeout")
        val e = err
        if (e != null) throw RuntimeException("GL init gagal: ${e.message}")
        return surfaces
    }

    private fun init(sizes: List<Size>) {
        dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2)
        if (!EGL14.eglInitialize(dpy, v, 0, v, 1)) throw RuntimeException("eglInitialize")
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, 0x3142, 1, EGL14.EGL_NONE
        )
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        if (!EGL14.eglChooseConfig(dpy, attrs, 0, cfgs, 0, 1, n, 0) || n[0] == 0) throw RuntimeException("eglChooseConfig")
        cfg = cfgs[0]
        ctx = EGL14.eglCreateContext(dpy, cfg, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        pbuf = EGL14.eglCreatePbufferSurface(dpy, cfg, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        if (!EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctx)) throw RuntimeException("eglMakeCurrent")

        val vs = compile(GLES20.GL_VERTEX_SHADER, VERT)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAG)
        prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("link: " + GLES20.glGetProgramInfoLog(prog))
        aPos = GLES20.glGetAttribLocation(prog, "aPos")
        aTex = GLES20.glGetAttribLocation(prog, "aTex")
        uMat = GLES20.glGetUniformLocation(prog, "uTexMatrix")
        uTex = GLES20.glGetUniformLocation(prog, "uTex")
        uBeauty = GLES20.glGetUniformLocation(prog, "uBeauty")
        uBright = GLES20.glGetUniformLocation(prog, "uBright")
        uTexel = GLES20.glGetUniformLocation(prog, "uTexel")

        GLES20.glGenTextures(inputs, texIds, 0)
        for (i in 0 until inputs) {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texIds[i])
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            val st = SurfaceTexture(texIds[i])
            st.setDefaultBufferSize(sizes[i].width, sizes[i].height)
            val idx = i
            st.setOnFrameAvailableListener(SurfaceTexture.OnFrameAvailableListener {
                fresh[idx] = true
                if (idx == driver) drawFrame()
            }, h)
            sts[i] = st
            surfaces.add(Surface(st))
        }
        previewSurf = EGL14.eglCreateWindowSurface(dpy, cfg, previewSt, intArrayOf(EGL14.EGL_NONE), 0)
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val st = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, st, 0)
        if (st[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            throw RuntimeException("shader: $log")
        }
        return s
    }

    fun attachRecorder(s: Surface, w: Int, hh: Int) {
        val latch = CountDownLatch(1)
        h.post {
            try {
                outW = w
                outH = hh
                recSurf = EGL14.eglCreateWindowSurface(dpy, cfg, s, intArrayOf(EGL14.EGL_NONE), 0)
            } catch (t: Throwable) {
                CamLog.add("GL attach recorder: ${t.message}")
            }
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
    }

    fun detachRecorder() {
        val latch = CountDownLatch(1)
        h.post {
            try {
                if (recSurf != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctx)
                    EGL14.eglDestroySurface(dpy, recSurf)
                    recSurf = EGL14.EGL_NO_SURFACE
                }
            } catch (t: Throwable) {
                CamLog.add("GL detach recorder: ${t.message}")
            }
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
    }

    fun release() {
        if (released) return
        released = true
        gyro?.stop()
        gyro = null
        val latch = CountDownLatch(1)
        h.post {
            try {
                EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctx)
                if (recSurf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, recSurf)
                if (previewSurf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, previewSurf)
                for (s in surfaces) try { s.release() } catch (_: Exception) {}
                for (st in sts) try { st?.release() } catch (_: Exception) {}
                if (prog != 0) GLES20.glDeleteProgram(prog)
                GLES20.glDeleteTextures(inputs, texIds, 0)
                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(dpy, pbuf)
                EGL14.eglDestroyContext(dpy, ctx)
                EGL14.eglTerminate(dpy)
            } catch (t: Throwable) {
                CamLog.add("GL release: ${t.message}")
            }
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
        thread.quitSafely()
    }

    private fun drawFrame() {
        if (released) return
        try {
            EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctx)
            for (i in 0 until inputs) {
                if (fresh[i]) {
                    val st = sts[i] ?: continue
                    st.updateTexImage()
                    st.getTransformMatrix(mats[i])
                    fresh[i] = false
                    have[i] = true
                    if (stab[i]) computeStab(i, st.timestamp)
                    if (!checked[i]) {
                        checked[i] = true
                        val m = mats[i]
                        val rotIncluded = abs(m[1]) > 0.5f || abs(m[4]) > 0.5f
                        needRot[i] = !rotIncluded && (sensorOri[i] == 90 || sensorOri[i] == 270)
                        CamLog.add("GL input $i matriks=[${m[0]},${m[1]},${m[4]},${m[5]}] rotasiDiMatriks=$rotIncluded perluRotasi=${needRot[i]} sensor=${sensorOri[i]}")
                    }
                }
            }
            if (previewSurf != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(dpy, previewSurf, previewSurf, ctx)
                EGL14.eglQuerySurface(dpy, previewSurf, EGL14.EGL_WIDTH, sz, 0)
                val pw = sz[0]
                EGL14.eglQuerySurface(dpy, previewSurf, EGL14.EGL_HEIGHT, sz, 0)
                val ph = sz[0]
                GLES20.glViewport(0, 0, pw, ph)
                drawScene()
                EGL14.eglSwapBuffers(dpy, previewSurf)
            }
            if (recSurf != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(dpy, recSurf, recSurf, ctx)
                GLES20.glViewport(0, 0, outW, outH)
                drawScene()
                EGLExt.eglPresentationTimeANDROID(dpy, recSurf, System.nanoTime())
                EGL14.eglSwapBuffers(dpy, recSurf)
            }
        } catch (t: Throwable) {
            CamLog.add("GL draw: ${t.javaClass.simpleName} ${t.message}")
        }
    }

    private fun drawScene() {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(prog)
        GLES20.glUniform1f(uBeauty, beauty)
        GLES20.glUniform1f(uBright, bright)
        GLES20.glUniform2f(uTexel, 1f / 1920f, 1f / 1080f)
        val sw = swap
        if (inputs == 1) {
            drawInput(0, -1f, -1f, 1f, 1f)
        } else if (layout == 0) {
            val top = if (sw) 1 else 0
            val bot = 1 - top
            drawInput(top, -1f, 0f, 1f, 1f)
            drawInput(bot, -1f, -1f, 1f, 0f)
        } else {
            val main = if (sw) 0 else 1      // utama = kamera belakang
            val small = 1 - main
            drawInput(main, -1f, -1f, 1f, 1f)
            drawInput(small, 0.22f, 0.38f, 0.94f, 0.92f)
        }
    }

    private fun drawInput(i: Int, x0: Float, y0: Float, x1: Float, y1: Float) {
        if (!have[i]) return
        val ra = ((x1 - x0) * outW) / ((y1 - y0) * outH)
        val sa = aspect[i]
        var a0 = 0f; var a1 = 1f; var b0 = 0f; var b1 = 1f
        if (sa > ra) {
            val f = ra / sa
            a0 = 0.5f - f / 2f; a1 = 0.5f + f / 2f
        } else {
            val f = sa / ra
            b0 = 0.5f - f / 2f; b1 = 0.5f + f / 2f
        }
        if (mirror[i]) { val t = a0; a0 = a1; a1 = t }
        val rot = if (needRot[i]) sensorOri[i] else 0
        vb.clear()
        put(i, x0, y0, a0, b0, rot)
        put(i, x1, y0, a1, b0, rot)
        put(i, x0, y1, a0, b1, rot)
        put(i, x1, y1, a1, b1, rot)
        GLES20.glUniformMatrix4fv(uMat, 1, false, mats[i], 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texIds[i])
        GLES20.glUniform1i(uTex, 0)
        vb.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, vb)
        GLES20.glEnableVertexAttribArray(aPos)
        vb.position(2)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, vb)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        vb.position(0)
    }

    /** (a,b) = koordinat gambar tegak; dikonversi ke koordinat tekstur mentah bila rotasi belum ada di matriks. */
    private fun put(i: Int, x: Float, y: Float, a0: Float, b0: Float, rot: Int) {
        tf(i, a0, b0)
        val a = tmp[0]
        val b = tmp[1]
        val s: Float
        val t: Float
        when (rot) {
            90 -> { s = 1f - b; t = a }
            270 -> { s = b; t = 1f - a }
            else -> { s = a; t = b }
        }
        vb.put(x); vb.put(y); vb.put(s); vb.put(t)
    }

    companion object {
        private const val VERT = """
            uniform mat4 uTexMatrix;
            attribute vec4 aPos;
            attribute vec4 aTex;
            varying vec2 vTex;
            void main() {
                gl_Position = aPos;
                vTex = (uTexMatrix * aTex).xy;
            }
        """

        private const val FRAG = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES uTex;
            uniform float uBeauty;
            uniform float uBright;
            uniform vec2 uTexel;
            void main() {
                vec3 c = texture2D(uTex, vTex).rgb;
                if (uBeauty > 0.001 || uBright > 0.001) {
                    vec3 acc = c;
                    float ws = 1.0;
                    for (int i = 0; i < 8; i++) {
                        float a = float(i) * 0.785398;
                        vec2 o = vec2(cos(a), sin(a)) * uTexel * 2.5;
                        vec3 s = texture2D(uTex, vTex + o).rgb;
                        vec3 d = s - c;
                        float w = exp(-dot(d, d) * 30.0);
                        acc += s * w;
                        ws += w;
                    }
                    vec3 sm = acc / ws;
                    float cb = -0.168736 * c.r - 0.331264 * c.g + 0.5 * c.b + 0.5;
                    float cr = 0.5 * c.r - 0.418688 * c.g - 0.081312 * c.b + 0.5;
                    float skin = smoothstep(0.30, 0.34, cb) * (1.0 - smoothstep(0.49, 0.53, cb))
                               * smoothstep(0.51, 0.54, cr) * (1.0 - smoothstep(0.66, 0.70, cr));
                    c = mix(c, sm, uBeauty * skin);
                    c = c + (1.0 - c) * uBright * 0.25 * skin;
                }
                gl_FragColor = vec4(c, 1.0);
            }
        """
    }
}
