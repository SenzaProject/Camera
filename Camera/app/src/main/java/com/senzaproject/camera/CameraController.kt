package com.senzaproject.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.StreamConfigurationMap
import android.location.Location
import android.location.LocationManager
import android.media.ImageReader
import android.media.MediaActionSound
import android.media.MediaRecorder
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.Surface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

enum class Mode(val label: String, val video: Boolean) {
    VLOG("VLOG", true),
    VIDEO("VIDEO", true),
    AI_CAM("AI CAM", false),
    PORTRAIT("POTRET", false),
    NIGHT("SUPER MALAM", false),
    PRO("MANUAL", false),
    MACRO("SUPER MAKRO", false),
    TIMELAPSE("SELANG WAKTU", true),
    HIRES("PIKSEL TINGGI", false),
    FACE("DETEKSI WAJAH", false),
    DUAL("VIDEO GANDA", true)
}

data class Caps(
    val zoomMin: Float, val zoomMax: Float,
    val evMin: Int, val evMax: Int, val evStep: Float,
    val isoMin: Int, val isoMax: Int, val expMin: Long, val expMax: Long,
    val focusMax: Float, val manual: Boolean, val hasFlash: Boolean,
    val halZoomMax: Float = 1f
)

class CameraController(private val ctx: Context, private val prefs: Prefs, private val cb: Callback) {

    interface Callback {
        fun onPreviewSize(w: Int, h: Int)
        fun onCaps(c: Caps)
        fun onSaved(uri: Uri, video: Boolean)
        fun onMessage(msg: String)
        fun onVideoStatus(height: Int, fps: Int, eis: Boolean, measured: Boolean)
        fun onRecording(on: Boolean)
        fun onFaces(rects: List<android.graphics.RectF>)
        fun onZoom(z: Float, extra: Float)
        fun onViewMirror(on: Boolean)
        fun onDualFailed(reason: String)
    }

    @Volatile var orientation = 0
    @Volatile var recording = false
        private set

    private val thread = HandlerThread("senza-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executor { r -> handler.post(r) }
    private val io = Executors.newSingleThreadExecutor()
    private val manager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val sound = MediaActionSound().apply {
        load(MediaActionSound.SHUTTER_CLICK)
        load(MediaActionSound.START_VIDEO_RECORDING)
        load(MediaActionSound.STOP_VIDEO_RECORDING)
    }

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var chars: CameraCharacteristics? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var builder: CaptureRequest.Builder? = null
    private var reader: ImageReader? = null
    private var recorder: MediaRecorder? = null
    private var pendingUri: Uri? = null
    private var pendingPfd: ParcelFileDescriptor? = null
    private var previewSize = Size(1440, 1080)
    private var bufSize = Size(1440, 1080)

    private var front = false
    private var mode = Mode.AI_CAM
    private var zoom = 1f
    private var flash = 0
    private var evIndex = 0
    private var iso = 0
    private var expNs = 0L
    private var focus = -1f
    private var lastIso = 100
    private var lastExp = 16_000_000L
    private var attempt = 0
    private var gen = 0
    private var closing: CameraCaptureSession? = null
    private var configuring = false
    private var pendingNext: (() -> Unit)? = null
    private var jpegW = 0
    private var jpegH = 0
    private class Shot(val mode: Mode, val extra: Float, val mirror: Boolean)
    private val shotModes = java.util.concurrent.ConcurrentLinkedQueue<Shot>()
    private var halMin = 1f
    private var halMax = 1f
    private var extraZoom = 1f
    private var awb = CaptureRequest.CONTROL_AWB_MODE_AUTO
    private var pipeline: GlPipeline? = null
    private var glOut = Size(1080, 1920)
    private var glDisabled = false
    private var faceTick = 0
    private var lastFaceCount = 0
    @Volatile var stabKind = 0          // 0 = mati, 1 = EIS bawaan (HAL), 2 = EIS Plus (gyro)
        private set
    private var videoHeight = 1080
    private var videoFps = 30
    private var videoEis = false

    private var fpsWindowStart = 0L
    private var fpsFrames = 0
    private var fpsReports = 0

    // Pemantau frame pertama: HAL bisa menerima sesi tetapi tidak pernah mengirim frame (pratinjau hitam, tanpa error)
    private var readyAt = 0L
    private var resultCount = 0L
    private var errLogged = 0

    private companion object {
        const val FIRST_FRAME_TIMEOUT_MS = 3000L
    }

    // ---------------------------------------------------------------- API

    fun setSurface(st: SurfaceTexture) {
        handler.post {
            surfaceTexture = st
            if (device != null) configure()
        }
    }

    fun detachSurface(st: SurfaceTexture) {
        handler.post {
            if (surfaceTexture === st) surfaceTexture = null
            closeSessionAsync { try { st.release() } catch (_: Exception) {} }
        }
    }

    fun refreshBufferSize(st: SurfaceTexture) {
        handler.post {
            if (surfaceTexture === st) {
                try { st.setDefaultBufferSize(bufSize.width, bufSize.height) } catch (_: Exception) {}
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun open(frontFacing: Boolean) {
        handler.post {
            closeInternal()
            front = frontFacing
            attempt = 0
            zoom = 1f
            extraZoom = 1f
            glDisabled = false
            main.post { cb.onZoom(1f, 1f) }
            CamLog.add("buka kamera ${if (frontFacing) "depan" else "belakang"}")
            try {
                val id = pickCameraId(frontFacing)
                if (id == null) {
                    msg("Kamera tidak ditemukan")
                    return@post
                }
                chars = manager.getCameraCharacteristics(id)
                reportCaps()
                manager.openCamera(id, stateCb, handler)
            } catch (e: Exception) {
                msg("Gagal membuka kamera: ${e.message}")
            }
        }
    }

    fun setMode(m: Mode) {
        handler.post {
            if (m != Mode.PRO) { evIndex = 0; iso = 0; expNs = 0L; focus = -1f; awb = CaptureRequest.CONTROL_AWB_MODE_AUTO }
            mode = m
            attempt = 0
            glDisabled = false
            applyUserZoom(zoom)
            if (device != null) configure()
        }
    }

    fun reconfigure() {
        handler.post {
            attempt = 0
            if (device != null) configure()
        }
    }

    fun setZoom(z: Float) {
        handler.post {
            applyUserZoom(z)
            builder?.set(CaptureRequest.CONTROL_ZOOM_RATIO, halZoom())
            startRepeating()
        }
    }

    private fun userMaxZoom(): Float = if (mode.video) halMax else maxOf(halMax, 12f)

    private fun halZoom(): Float = zoom.coerceIn(halMin, halMax)

    /** Zoom pengguna bisa melebihi batas HAL: sisanya zoom digital (pratinjau di-scale, foto di-crop). */
    private fun applyUserZoom(z: Float) {
        zoom = z.coerceIn(halMin, userMaxZoom())
        val hz = halZoom()
        extraZoom = if (hz > 0f) zoom / hz else 1f
        val zz = zoom
        val ee = extraZoom
        main.post { cb.onZoom(zz, ee) }
    }

    fun setAwb(m: Int) {
        handler.post {
            awb = m
            builder?.let { applyCommon(it, mode.video) }
            startRepeating()
        }
    }

    private fun wantFaces(): Boolean = prefs.faceDetect || mode == Mode.FACE

    fun updateFaces() {
        handler.post {
            builder?.let { applyCommon(it, mode.video) }
            startRepeating()
        }
    }

    fun onBeautyChanged() {
        handler.post {
            val pl = pipeline
            if (mode.video && !recording) {
                attempt = 0
                if (device != null) configure()
            } else if (pl != null) {
                pl.beauty = Beauty.SMOOTH[prefs.beauty.coerceIn(0, 4)]
                pl.bright = Beauty.BRIGHT[prefs.beauty.coerceIn(0, 4)]
            }
        }
    }

    fun setFlash(m: Int) {
        handler.post {
            flash = m
            builder?.let { applyCommon(it, mode.video) }
            startRepeating()
        }
    }

    fun setEv(index: Int) {
        handler.post {
            evIndex = index
            builder?.let { applyCommon(it, mode.video) }
            startRepeating()
        }
    }

    /** iso 0 = otomatis, expNs 0 = otomatis, focus < 0 = otomatis (dioptri). */
    fun setManual(isoV: Int, expV: Long, focusV: Float) {
        handler.post {
            iso = isoV; expNs = expV; focus = focusV
            builder?.let { applyCommon(it, mode.video) }
            startRepeating()
        }
    }

    fun focusAt(u: Float, v: Float) {
        handler.post {
            val ch = chars ?: return@post
            val b = builder ?: return@post
            val s = session ?: return@post
            if (mode == Mode.PRO && focus >= 0f) return@post
            val r = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return@post
            var sx: Float
            var sy: Float
            val e = extraZoom
            val u0 = 0.5f + (u - 0.5f) / e
            val v0 = 0.5f + (v - 0.5f) / e
            val mir = front && prefs.mirrorFront
            if (!front) { sx = v0; sy = 1f - u0 } else { sx = 1f - v0; sy = if (mir) 1f - u0 else u0 }
            val hz = halZoom()
            sx = 0.5f + (sx - 0.5f) / hz
            sy = 0.5f + (sy - 0.5f) / hz
            val cx = (r.left + sx * r.width()).toInt()
            val cy = (r.top + sy * r.height()).toInt()
            val half = (min(r.width(), r.height()) * 0.07f).toInt()
            val rect = Rect(
                maxOf(r.left, cx - half), maxOf(r.top, cy - half),
                minOf(r.right - 1, cx + half), minOf(r.bottom - 1, cy + half)
            )
            val m = MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX - 1)
            try {
                if ((ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0)
                    b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(m))
                if ((ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0)
                    b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(m))
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                s.capture(b.build(), null, handler)
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                startRepeating()
            } catch (_: Exception) {}
        }
    }

    fun capturePhoto() {
        handler.post {
            val s = session
            val ch = chars
            if (s == null || ch == null || reader == null || recording) {
                msg("Kamera belum siap")
                return@post
            }
            val hasFlash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            if (hasFlash && flash != 0 && !manualAe()) {
                try {
                    val pb = builder
                    if (pb != null) {
                        pb.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                            CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
                        s.capture(pb.build(), null, handler)
                        pb.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                            CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE)
                    }
                } catch (_: Exception) {}
                handler.postDelayed({ shoot() }, 600)
            } else {
                shoot()
            }
        }
    }

    fun startRecording() {
        handler.post {
            val r = recorder
            if (r == null || session == null) {
                msg("Kamera belum siap")
                return@post
            }
            try {
                if (pipeline?.attachRecorder(r.surface, glOut.width, glOut.height) == false)
                    throw IllegalStateException("renderer GL tidak bisa tersambung ke perekam")
                r.start()
                recording = true
                if (prefs.shutterSound) sound.play(MediaActionSound.START_VIDEO_RECORDING)
                main.post { cb.onRecording(true) }
            } catch (e: Exception) {
                pipeline?.detachRecorder()
                msg("Gagal mulai merekam: ${e.message}")
                if (attempt < 2) {
                    attempt++
                    configure()
                }
            }
        }
    }

    fun stopRecording() {
        handler.post {
            stopRecordingInternal()
            configure()
        }
    }

    fun release(done: (() -> Unit)? = null) {
        handler.post {
            closeInternal()
            if (done != null) main.post(done)
        }
    }

    fun destroy() {
        handler.post {
            closeInternal()
            thread.quitSafely()
        }
        io.shutdown()
    }

    // ------------------------------------------------------------ internals

    private fun msg(s: String) { main.post { cb.onMessage(s) } }

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    private fun pickCameraId(frontFacing: Boolean): String? {
        val want = if (frontFacing) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        return manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want
        }
    }

    private fun has(key: CameraCharacteristics.Key<IntArray>, v: Int): Boolean =
        chars?.get(key)?.contains(v) == true

    private fun clampZoom(z: Float): Float {
        val r = chars?.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) ?: return 1f
        return z.coerceIn(r.lower, r.upper)
    }

    private fun manualAe(): Boolean = mode == Mode.PRO && (iso > 0 || expNs > 0)

    private fun reportCaps() {
        val ch = chars ?: return
        val z = ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        val ev = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val step = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0.333f
        val isoR = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val expR = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val minFocus = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val capList = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList() ?: emptyList()
        val manual = capList.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) &&
            isoR != null && expR != null
        halMin = z?.lower ?: 1f
        halMax = z?.upper ?: 1f
        val caps = Caps(
            halMin, maxOf(halMax, 12f),
            ev?.lower ?: 0, ev?.upper ?: 0, step,
            isoR?.lower ?: 100, isoR?.upper ?: 100,
            expR?.lower ?: 1_000_000L, expR?.upper ?: 1_000_000L,
            minFocus, manual,
            ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
            halMax
        )
        main.post { cb.onCaps(caps) }
    }

    private val stateCb = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            device = camera
            if (surfaceTexture != null) configure()
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            if (device === camera) device = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            if (device === camera) device = null
            msg("Kamera error ($error)")
        }
    }

    /**
     * Menutup sesi lama dan MENUNGGU onClosed sebelum membuat sesi baru. Tanpa menunggu,
     * stream lama masih terhubung ke SurfaceTexture yang sama sehingga sesi baru gagal (onConfigureFailed).
     */
    private fun closeSessionAsync(next: () -> Unit) {
        gen++
        val old = session
        session = null
        if (old != null) {
            closing = old
            try { old.close() } catch (_: Exception) {}
            handler.postDelayed({ if (closing === old) onSessionClosed(old) }, 800)
        }
        if (closing != null || configuring) {
            pendingNext = next
            return
        }
        cleanupOutputs()
        next()
    }

    private fun runPending() {
        if (closing != null || configuring) return
        cleanupOutputs()
        val n = pendingNext
        pendingNext = null
        n?.invoke()
    }

    private fun onSessionClosed(s: CameraCaptureSession) {
        if (closing !== s) return
        closing = null
        runPending()
    }

    private fun cleanupOutputs() {
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        discardRecorder()
        pipeline?.release()
        pipeline = null
        builder = null
    }

    private fun closeInternal() {
        if (recording) stopRecordingInternal()
        gen++
        pendingNext = null
        closing = null
        configuring = false
        try { session?.close() } catch (_: Exception) {}
        session = null
        cleanupOutputs()
        try { device?.close() } catch (_: Exception) {}
        device = null
    }

    private fun discardRecorder() {
        val r = recorder
        recorder = null
        if (r != null) {
            try { r.reset() } catch (_: Exception) {}
            try { r.release() } catch (_: Exception) {}
        }
        try { pendingPfd?.close() } catch (_: Exception) {}
        pendingPfd = null
        val u = pendingUri
        pendingUri = null
        if (u != null) {
            try { ctx.contentResolver.delete(u, null, null) } catch (_: Exception) {}
        }
    }

    private fun pickSize(sizes: Array<Size>?, ratio: Float, maxPixels: Long): Size {
        val all = sizes?.toList() ?: emptyList()
        if (all.isEmpty()) return Size(1280, 720)
        val ok = all.filter { abs(it.width.toFloat() / it.height - ratio) < 0.02f && it.width.toLong() * it.height <= maxPixels }
        if (ok.isNotEmpty()) return ok.maxByOrNull { it.width.toLong() * it.height }!!
        val under = all.filter { it.width.toLong() * it.height <= maxPixels }
        if (under.isNotEmpty()) return under.maxByOrNull { it.width.toLong() * it.height }!!
        return all.minByOrNull { it.width.toLong() * it.height }!!
    }

    private fun configure() {
        if (device == null || surfaceTexture == null || chars == null) return
        if (recording) return
        closeSessionAsync { doConfigure() }
    }

    private fun doConfigure() {
        val dev = device ?: return
        val st = surfaceTexture ?: return
        val ch = chars ?: return
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        fpsWindowStart = 0L
        fpsFrames = 0
        fpsReports = 0
        resultCount = 0L
        errLogged = 0
        readyAt = 0L
        if (front) CamLog.add("cermin kamera depan: pratinjau=${prefs.mirrorFront} simpan=${prefs.mirrorFrontSave}")
        try {
            if (mode.video) configureVideo(dev, st, ch, map) else configurePhoto(dev, st, ch, map)
        } catch (e: Exception) {
            onSessionFailed("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun configurePhoto(dev: CameraDevice, st: SurfaceTexture, ch: CameraCharacteristics, map: StreamConfigurationMap) {
        val wantRatio = if (mode == Mode.HIRES) 4f / 3f else if (prefs.photoRatio == 1) 16f / 9f else 4f / 3f
        val jpegSizes = map.getOutputSizes(ImageFormat.JPEG)
        val jpegCap = when (attempt) { 0 -> 13_500_000L; 1 -> 8_500_000L; else -> 3_200_000L }
        val jpeg = if (mode == Mode.HIRES && attempt == 0 && jpegSizes != null && jpegSizes.isNotEmpty())
            jpegSizes.maxByOrNull { it.width.toLong() * it.height }!!
        else pickSize(jpegSizes, wantRatio, jpegCap)
        jpegW = jpeg.width
        jpegH = jpeg.height
        val ratio = jpeg.width.toFloat() / jpeg.height
        val prev = pickSize(map.getOutputSizes(SurfaceTexture::class.java), ratio, if (attempt >= 2) 1_300_000L else 2_100_000L)
        CamLog.add("foto ${mode.name} attempt=$attempt jpeg=${jpeg.width}x${jpeg.height} preview=${prev.width}x${prev.height}")
        previewSize = prev
        bufSize = prev
        main.post { cb.onPreviewSize(prev.width, prev.height); cb.onViewMirror(front && prefs.mirrorFront) }
        st.setDefaultBufferSize(prev.width, prev.height)
        val ps = Surface(st)
        val rd = ImageReader.newInstance(jpeg.width, jpeg.height, ImageFormat.JPEG, 2)
        rd.setOnImageAvailableListener({ r -> onImage(r) }, handler)
        reader = rd
        val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        b.addTarget(ps)
        builder = b
        applyCommon(b, false)
        createSession(dev, listOf(ps, rd.surface), b, attempt < 2)
    }

    private fun configureVideo(dev: CameraDevice, st: SurfaceTexture, ch: CameraCharacteristics, map: StreamConfigurationMap) {
        val tl = mode == Mode.TIMELAPSE
        val unlock = prefs.unlockEis && !tl
        val h = if (tl) 1080 else if (unlock || mode == Mode.VLOG) 1080 else prefs.videoHeight
        var fps = if (tl) 30 else if (unlock) 60 else if (mode == Mode.VLOG) 30 else prefs.videoFps
        val stabWanted = !tl && prefs.eis
        if (attempt >= 1) fps = min(fps, 30)
        videoHeight = h
        videoFps = fps

        val sizes = map.getOutputSizes(MediaRecorder::class.java)
        val list = sizes?.toList() ?: emptyList()
        val wide = list.filter { abs(it.width.toFloat() / it.height - 16f / 9f) < 0.03f }
        val vs = wide.filter { it.height == h }.maxByOrNull { it.width }
            ?: wide.filter { it.height <= h }.maxByOrNull { it.width }
            ?: list.maxByOrNull { it.width.toLong() * it.height }
            ?: Size(1920, 1080)

        // Jalur GL dipakai bila beauty video atau EIS Plus (gyro) aktif
        val gyroWanted = !front && stabWanted && prefs.stabGyro
        // MediaRecorder menerima frame sensor apa adanya, jadi cermin hasil video hanya bisa lewat renderer GL
        val mirrorSave = front && prefs.mirrorFrontSave && !tl
        val wantGl = !tl && !glDisabled && attempt < 2 && h <= 1080 && (prefs.beauty > 0 || gyroWanted || mirrorSave)
        if (wantGl && tryGlVideo(dev, st, ch, vs, fps, unlock, stabWanted)) return
        if (mirrorSave) CamLog.add("cermin hasil video tidak aktif: jalur GL tidak dipakai (attempt=$attempt tinggi=$h glDisabled=$glDisabled)")

        var eis = stabWanted
        if (attempt >= 2) eis = false
        videoEis = eis
        stabKind = if (eis) 1 else 0
        val prev = pickSize(map.getOutputSizes(SurfaceTexture::class.java), vs.width.toFloat() / vs.height, 2_100_000L)
        previewSize = prev
        bufSize = prev
        main.post { cb.onPreviewSize(prev.width, prev.height); cb.onViewMirror(front && prefs.mirrorFront) }
        st.setDefaultBufferSize(prev.width, prev.height)
        val ps = Surface(st)
        val rs = prepareRecorder(vs, fps, tl)

        val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        b.addTarget(ps)
        b.addTarget(rs)
        builder = b
        applyCommon(b, true)
        val rng = pickFpsRange(ch, fps, unlock && attempt == 0)
        rng?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        applyStabilization(b, eis, unlock && attempt == 0 && eis)
        val resLabel = vs.height
        CamLog.add("video ${mode.name} attempt=$attempt ${vs.width}x${vs.height}@$fps eis=$eis unlock=$unlock rng=$rng${rangeNote(ch, rng)}")
        main.post { cb.onVideoStatus(resLabel, fps, eis, false) }
        createSession(dev, listOf(ps, rs), b, attempt < 2)
    }

    /** Video lewat renderer GL (beauty dan/atau EIS Plus). false = GL gagal, lanjut ke jalur biasa. */
    private fun tryGlVideo(dev: CameraDevice, st: SurfaceTexture, ch: CameraCharacteristics, vs: Size, fps: Int, unlock: Boolean, stab: Boolean): Boolean {
        val sensorOri = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val portrait = sensorOri % 180 == 90
        val upW = if (portrait) vs.height else vs.width
        val upH = if (portrait) vs.width else vs.height
        val pl = GlPipeline(ctx, st, 1, 0)
        pl.aspect[0] = upW.toFloat() / upH
        pl.sensorOri[0] = sensorOri
        pl.mirror[0] = front && prefs.mirrorFront                 // pratinjau
        pl.recMirror[0] = front && prefs.mirrorFrontSave          // hasil rekaman, terpisah dari pratinjau
        pl.separateRecMirror = true
        val bi = prefs.beauty.coerceIn(0, 4)
        pl.beauty = Beauty.SMOOTH[bi]
        pl.bright = Beauty.BRIGHT[bi]
        pl.outW = upW
        pl.outH = upH
        var gyroUsed = false
        if (stab && prefs.stabGyro && !front) {
            val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
            val phys = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val fpx = if (focal > 0f && phys != null && phys.width > 0f) focal / phys.width * vs.width
            else (vs.width / 2f) / Math.tan(Math.toRadians(35.0)).toFloat()
            val realtime = ch.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
            gyroUsed = pl.enableStab(0, fpx, upW.toFloat(), upH.toFloat(), realtime, prefs.stabLevel)
            if (!gyroUsed) msg("Gyroscope tidak tersedia, memakai EIS bawaan")
        }
        val surfaces: List<Surface>
        try {
            st.setDefaultBufferSize(upW, upH)
            bufSize = Size(upW, upH)
            surfaces = pl.start(listOf(vs))
        } catch (t: Throwable) {
            CamLog.add("GL gagal: ${t.message}")
            try { pl.release() } catch (_: Throwable) {}
            glDisabled = true
            msg("Renderer GL gagal, memakai jalur video biasa")
            return false
        }
        pipeline = pl
        glOut = Size(upW, upH)
        previewSize = vs
        main.post { cb.onPreviewSize(vs.width, vs.height); cb.onViewMirror(false) }
        // Bingkai rekaman sudah tegak menurut orientasi sensor. Tanpa cermin, kamera depan perlu putar -rot. Bila bingkai
        // dicermin horizontal di bingkai itu, putarnya harus +rot supaya cermin tetap kiri-kanan di layar saat ponsel miring
        // (kalau tidak, video landscape malah terbalik atas-bawah).
        val hint = if (front && prefs.mirrorFrontSave) ((orientation + 45) / 90 * 90) % 360
        else ((jpegOrientation() - sensorOri) % 360 + 360) % 360
        prepareRecorder(Size(upW, upH), fps, false, hint)

        val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        b.addTarget(surfaces[0])
        builder = b
        applyCommon(b, true)
        val rng = pickFpsRange(ch, fps, unlock && attempt == 0)
        rng?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        val halEis = stab && !gyroUsed
        applyStabilization(b, halEis, unlock && attempt == 0 && halEis)
        videoEis = stab
        stabKind = if (gyroUsed) 2 else if (halEis) 1 else 0
        CamLog.add("video GL ${mode.name} attempt=$attempt ${vs.width}x${vs.height}@$fps keluar=${upW}x$upH gyro=$gyroUsed halEis=$halEis beauty=$bi rng=$rng${rangeNote(ch, rng)}")
        main.post { cb.onVideoStatus(vs.height, fps, stab, false) }
        createSession(dev, listOf(surfaces[0]), b, attempt < 2)
        return true
    }

    /** Penanda di log bila rentang fps yang diminta tidak ada di daftar HAL (dipaksa oleh Unlock). */
    private fun rangeNote(ch: CameraCharacteristics, r: Range<Int>?): String {
        if (r == null) return ""
        val ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return ""
        return if (ranges.contains(r)) "" else " (di luar daftar HAL)"
    }

    private fun pickFpsRange(ch: CameraCharacteristics, fps: Int, force: Boolean): Range<Int>? {
        val ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList()
        if (ranges.isNullOrEmpty()) return if (force) Range(fps, fps) else null
        ranges.firstOrNull { it.lower == fps && it.upper == fps }?.let { return it }
        ranges.filter { it.upper == fps }.minByOrNull { it.lower }?.let { return it }
        if (force) return Range(fps, fps)
        return ranges.filter { it.upper <= fps }.maxByOrNull { it.upper }
    }

    private fun applyStabilization(b: CaptureRequest.Builder, want: Boolean, force: Boolean) {
        val key = CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES
        if (!want) {
            if (has(key, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF))
                b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            return
        }
        val v = when {
            has(key, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) -> CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
            has(key, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION) ->
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
            force -> CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
            else -> -1
        }
        if (v >= 0) b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, v)
        if (has(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON))
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
    }

    private fun applyCommon(b: CaptureRequest.Builder, video: Boolean) {
        val ch = chars ?: return
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)

        val afModes = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList() ?: emptyList()
        val af: Int? = when {
            mode == Mode.PRO && focus >= 0f -> CaptureRequest.CONTROL_AF_MODE_OFF
            mode == Mode.MACRO && afModes.contains(CaptureRequest.CONTROL_AF_MODE_MACRO) -> CaptureRequest.CONTROL_AF_MODE_MACRO
            video && afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) -> CaptureRequest.CONTROL_AF_MODE_AUTO
            else -> null
        }
        if (af != null) b.set(CaptureRequest.CONTROL_AF_MODE, af)
        if (af == CaptureRequest.CONTROL_AF_MODE_OFF) b.set(CaptureRequest.LENS_FOCUS_DISTANCE, focus)

        if (manualAe()) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, if (iso > 0) iso else lastIso)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, if (expNs > 0) expNs else lastExp)
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evIndex)
        }

        val hasFlash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        if (hasFlash) {
            val torch = video && flash != 0
            b.set(CaptureRequest.FLASH_MODE, if (torch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
        }

        if (wantFaces() && has(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES, CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE))
            b.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE)
        if (mode == Mode.PRO && awb != CaptureRequest.CONTROL_AWB_MODE_AUTO && has(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES, awb))
            b.set(CaptureRequest.CONTROL_AWB_MODE, awb)
        else if (has(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES, CaptureRequest.CONTROL_AWB_MODE_AUTO))
            b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)

        if (attempt >= 2) return

        if (ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) != null)
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, halZoom())

        if (attempt >= 1) return

        if (prefs.lensCorrection &&
            has(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES, CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY))
            b.set(CaptureRequest.DISTORTION_CORRECTION_MODE, CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY)

        if (!video) {
            val scenes = ch.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)?.toList() ?: emptyList()
            when (mode) {
                Mode.PORTRAIT -> if (scenes.contains(CaptureRequest.CONTROL_SCENE_MODE_PORTRAIT)) {
                    b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
                    b.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_PORTRAIT)
                }
                Mode.FACE -> if (scenes.contains(CaptureRequest.CONTROL_SCENE_MODE_FACE_PRIORITY)) {
                    b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
                    b.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_FACE_PRIORITY)
                }
                Mode.NIGHT -> if (scenes.contains(CaptureRequest.CONTROL_SCENE_MODE_NIGHT)) {
                    b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
                    b.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_NIGHT)
                } else {
                    val evMax = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.upper ?: 0
                    b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, maxOf(evIndex, evMax / 2))
                    val low = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                        ?.filter { it.upper >= 24 }?.minByOrNull { it.lower }
                    if (low != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, low)
                }
                else -> {}
            }
        }
    }

    private fun createSession(dev: CameraDevice, surfaces: List<Surface>, b: CaptureRequest.Builder, useParams: Boolean) {
        val myGen = ++gen
        val outs = surfaces.map { OutputConfiguration(it) }
        val stateCallback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                configuring = false
                if (myGen != gen || device == null) {
                    // Sesi usang: tutup dulu, lalu lanjutkan konfigurasi yang menunggu
                    closing = s
                    try { s.close() } catch (_: Exception) {}
                    handler.postDelayed({ if (closing === s) onSessionClosed(s) }, 800)
                    return
                }
                session = s
                readyAt = SystemClock.elapsedRealtime()
                CamLog.add("sesi siap (${mode.name}, attempt=$attempt)")
                startRepeating(true)
                announceMode()
                armFrameWatchdog(s, myGen)
            }

            override fun onClosed(s: CameraCaptureSession) {
                onSessionClosed(s)
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                configuring = false
                if (myGen != gen) {
                    runPending()
                    return
                }
                onSessionFailed("onConfigureFailed")
            }
        }
        val sc = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outs, executor, stateCallback)
        if (useParams) sc.sessionParameters = b.build()
        configuring = true
        try {
            dev.createCaptureSession(sc)
        } catch (e: Exception) {
            configuring = false
            throw e
        }
    }

    /**
     * HAL kadang menerima sesi (onConfigured) dan permintaan berulang tanpa error, tetapi tidak pernah mengirim frame,
     * misalnya setelah pindah dari sesi foto ke video 60 fps. Hasilnya pratinjau hitam dan tidak ada callback gagal,
     * jadi tangga fallback (30 fps, lalu jalur biasa tanpa EIS) tidak pernah jalan. Pemantau ini menganggap
     * "tidak ada frame" sebagai kegagalan sesi supaya tangga itu dijalankan.
     */
    private fun armFrameWatchdog(s: CameraCaptureSession, g: Int) {
        if (g != gen) return
        handler.postDelayed({
            if (g != gen || session !== s || recording) return@postDelayed
            val gotFrame = pipeline?.let { it.frames > 0L } ?: (resultCount > 0L)
            if (!gotFrame) {
                CamLog.add("tidak ada frame ${FIRST_FRAME_TIMEOUT_MS} ms setelah sesi siap (${mode.name}, attempt=$attempt)")
                onSessionFailed("tidak ada frame")
            }
        }, FIRST_FRAME_TIMEOUT_MS)
    }

    private fun announceMode() {
        if (attempt >= 1) return
        when (mode) {
            Mode.FACE -> if (!has(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES, CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE))
                msg("Perangkat tidak mendukung deteksi wajah")
            Mode.PORTRAIT -> msg("Potret: blur latar diterapkan setelah foto (wajah harus terlihat)")
            Mode.NIGHT -> msg(
                if (has(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES, CaptureRequest.CONTROL_SCENE_MODE_NIGHT))
                    "Super Malam: scene malam aktif" else "Super Malam: exposure dinaikkan (perangkat tanpa scene malam)"
            )
            Mode.MACRO -> msg(
                if (has(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES, CaptureRequest.CONTROL_AF_MODE_MACRO))
                    "Super Makro: fokus dekat aktif" else "Perangkat tanpa mode makro, memakai fokus biasa"
            )
            Mode.HIRES -> msg("Piksel Tinggi: $jpegW×$jpegH (${(jpegW.toLong() * jpegH / 1_000_000L)} MP)")
            Mode.PRO -> if (chars?.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.contains(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) != true) msg("Perangkat tidak mendukung kontrol manual penuh")
            else -> {}
        }
    }

    private fun onSessionFailed(reason: String) {
        CamLog.add("GAGAL ${mode.name} attempt=$attempt: $reason")
        if (attempt < 2) {
            attempt++
            msg(
                if (mode.video) {
                    if (attempt == 1) "60 fps + EIS ditolak perangkat, mencoba 30 fps…" else "Mencoba tanpa EIS…"
                } else {
                    "Mode ${mode.label} dibatasi perangkat, memakai pengaturan aman…"
                }
            )
            configure()
        } else {
            msg("Gagal membuat sesi kamera: $reason")
        }
    }

    private val repeatCb = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            if (resultCount++ == 0L && readyAt != 0L) {
                CamLog.add("hasil kamera pertama +${SystemClock.elapsedRealtime() - readyAt} ms")
            }
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { lastIso = it }
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastExp = it }
            if (mode.video) trackFps(result)
            if (wantFaces()) {
                if ((++faceTick) % 2 == 0) processFaces(result)
            } else if (lastFaceCount != 0) {
                lastFaceCount = 0
                main.post { cb.onFaces(emptyList()) }
            }
        }

        override fun onCaptureFailed(s: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            // REASON_FLUSHED (1) wajar: permintaan yang masih antre dibuang saat rekaman dihentikan atau sesi ditutup.
            if (failure.reason == CaptureFailure.REASON_FLUSHED) return
            // REASON_ERROR (0) bisa membanjir pada 60 fps, jadi dibatasi per sesi supaya log tidak penuh.
            if (errLogged++ < 5) CamLog.add("capture gagal reason=ERROR frame=${failure.frameNumber}")
        }
    }

    private fun processFaces(result: TotalCaptureResult) {
        val faces = result.get(CaptureResult.STATISTICS_FACES) ?: return
        val r = chars?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val hz = halZoom()
        val e = extraZoom
        val mir = front && prefs.mirrorFront
        val out = ArrayList<android.graphics.RectF>()
        for (f in faces) {
            if (f.score < 40) continue
            val bd = f.bounds
            val xs = floatArrayOf((bd.left - r.left) / r.width().toFloat(), (bd.right - r.left) / r.width().toFloat())
            val ys = floatArrayOf((bd.top - r.top) / r.height().toFloat(), (bd.bottom - r.top) / r.height().toFloat())
            var uMin = 9f; var vMin = 9f; var uMax = -9f; var vMax = -9f
            for (sx in xs) for (sy in ys) {
                val sx0 = 0.5f + (sx - 0.5f) * hz
                val sy0 = 0.5f + (sy - 0.5f) * hz
                var u: Float
                var v: Float
                if (!front) { u = 1f - sy0; v = sx0 } else { u = if (mir) 1f - sy0 else sy0; v = 1f - sx0 }
                u = 0.5f + (u - 0.5f) * e
                v = 0.5f + (v - 0.5f) * e
                if (u < uMin) uMin = u; if (u > uMax) uMax = u
                if (v < vMin) vMin = v; if (v > vMax) vMax = v
            }
            out.add(android.graphics.RectF(uMin, vMin, uMax, vMax))
        }
        lastFaceCount = out.size
        main.post { cb.onFaces(out) }
    }

    private fun trackFps(r: TotalCaptureResult) {
        val ts = r.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
        if (fpsWindowStart == 0L) {
            fpsWindowStart = ts
            fpsFrames = 0
            return
        }
        fpsFrames++
        val dt = ts - fpsWindowStart
        if (dt >= 1_000_000_000L) {
            val fps = (fpsFrames * 1e9 / dt).roundToInt()
            val st = r.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE)
            val eisOn = (st != null && st != CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE_OFF) || stabKind == 2
            val h = videoHeight
            fpsWindowStart = ts
            fpsFrames = 0
            // Dua jendela pertama dicatat supaya kelihatan apakah 60 fps benar-benar tercapai atau HAL membatasi ke 30
            if (fpsReports < 2) {
                fpsReports++
                CamLog.add("fps terukur=$fps diminta=$videoFps")
            }
            main.post { cb.onVideoStatus(h, fps, eisOn, true) }
        }
    }

    private fun startRepeating(initial: Boolean = false) {
        val s = session ?: return
        val b = builder ?: return
        try {
            s.setRepeatingRequest(b.build(), repeatCb, handler)
        } catch (e: Exception) {
            if (initial) onSessionFailed("repeat: ${e.javaClass.simpleName} ${e.message}")
            else CamLog.add("repeat error: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- foto

    private fun jpegOrientation(): Int {
        val s = chars?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val rot = ((orientation + 45) / 90 * 90) % 360
        return if (front) (s - rot + 360) % 360 else (s + rot) % 360
    }

    @SuppressLint("MissingPermission")
    private fun lastLocation(): Location? {
        if (!prefs.location) return null
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } }
            .maxByOrNull { it.time }
    }

    private fun shoot() {
        val dev = device ?: return
        val s = session ?: return
        val rd = reader ?: return
        val ch = chars ?: return
        try {
            val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            b.addTarget(rd.surface)
            applyCommon(b, false)
            b.set(CaptureRequest.JPEG_QUALITY, prefs.jpegQuality.toByte())
            b.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation())
            lastLocation()?.let { b.set(CaptureRequest.JPEG_GPS_LOCATION, it) }
            val hasFlash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            if (hasFlash && flash != 0 && !manualAe()) {
                b.set(CaptureRequest.CONTROL_AE_MODE,
                    if (flash == 2) CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH else CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
            }
            if (prefs.shutterSound) sound.play(MediaActionSound.SHUTTER_CLICK)
            shotModes.add(Shot(mode, extraZoom, front && prefs.mirrorFrontSave))
            s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    msg("Gagal mengambil foto")
                }
            }, handler)
        } catch (e: Exception) {
            msg("Gagal mengambil foto: ${e.message}")
        }
    }

    private fun onImage(r: ImageReader) {
        val img = try { r.acquireNextImage() } catch (e: Exception) { null } ?: return
        val bytes = try {
            val buf = img.planes[0].buffer
            val arr = ByteArray(buf.remaining())
            buf.get(arr)
            arr
        } finally {
            img.close()
        }
        io.execute { savePhoto(bytes) }
    }

    private fun savePhoto(raw: ByteArray) {
        try {
            val shot = shotModes.poll()
            val shotMode = shot?.mode ?: Mode.AI_CAM
            val extra = shot?.extra ?: 1f
            val mirror = shot?.mirror ?: false
            var bytes = raw
            var processed = false
            val wantPortrait = shotMode == Mode.PORTRAIT
            val wantBeauty = prefs.beauty > 0
            if (wantPortrait || wantBeauty || prefs.watermark || extra > 1.02f || mirror) {
                try {
                    var bmp = Watermark.loadUpright(raw)
                    var changed = false
                    if (extra > 1.02f) {
                        val cw = (bmp.width / extra).toInt().coerceAtLeast(16)
                        val chh = (bmp.height / extra).toInt().coerceAtLeast(16)
                        val c = Bitmap.createBitmap(bmp, (bmp.width - cw) / 2, (bmp.height - chh) / 2, cw, chh)
                        if (c !== bmp) bmp.recycle()
                        bmp = if (c.isMutable) c else c.copy(Bitmap.Config.ARGB_8888, true).also { c.recycle() }
                        changed = true
                    }
                    // Cermin dulu, baru potret/beauty/watermark: teks watermark tidak boleh ikut terbalik
                    if (mirror) {
                        Watermark.mirrorInPlace(bmp)
                        changed = true
                    }
                    if (wantPortrait) {
                        if (Portrait.apply(bmp)) changed = true
                        else msg("Wajah tidak terdeteksi, efek potret tidak diterapkan")
                    }
                    if (wantBeauty) {
                        if (Beauty.applyPhoto(bmp, prefs.beauty)) changed = true
                        else msg("Wajah tidak terdeteksi, beauty tidak diterapkan")
                    }
                    if (prefs.watermark) {
                        Watermark.draw(bmp, Watermark.text(ctx, prefs), prefs.wmPos)
                        changed = true
                    }
                    if (changed) {
                        bytes = Watermark.encode(bmp, prefs.jpegQuality)
                        processed = true
                    }
                    bmp.recycle()
                } catch (t: Throwable) {
                    CamLog.add("olah foto gagal: ${t.javaClass.simpleName} ${t.message}")
                    msg("Pengolahan foto gagal, disimpan apa adanya")
                }
            }
            val resolver = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_${stamp()}.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
            if (processed) {
                try {
                    resolver.openFileDescriptor(uri, "rw")?.use { Watermark.copyExif(raw, it.fileDescriptor) }
                } catch (e: Exception) {
                    CamLog.add("salin EXIF gagal: ${e.message}")
                }
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            main.post { cb.onSaved(uri, false) }
        } catch (e: Exception) {
            msg("Gagal menyimpan foto: ${e.message}")
        }
    }

    // --------------------------------------------------------------- video

    private fun prepareRecorder(size: Size, fps: Int, timelapse: Boolean, hint: Int = -1): Surface {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "VID_${stamp()}.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore menolak membuat file video")
        pendingUri = uri
        val pfd = resolver.openFileDescriptor(uri, "rw") ?: throw IllegalStateException("Tidak bisa membuka file video")
        pendingPfd = pfd

        val r = MediaRecorder(ctx)
        recorder = r
        val audio = !timelapse &&
            ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (audio) r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        if (timelapse) r.setCaptureRate(1.0 / prefs.tlInterval)
        r.setOutputFile(pfd.fileDescriptor)
        val mbps = when {
            size.height >= 2160 -> if (fps > 30) 80 else 50
            size.height >= 1080 -> if (fps > 30) 24 else 16
            else -> 8
        }
        r.setVideoEncodingBitRate(mbps * 1_000_000)
        r.setVideoFrameRate(fps)
        r.setVideoSize(size.width, size.height)
        r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        if (audio) {
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(128_000)
            r.setAudioSamplingRate(44_100)
        }
        r.setOrientationHint(if (hint >= 0) hint else jpegOrientation())
        r.prepare()
        return r.surface
    }

    private fun stopRecordingInternal() {
        val r = recorder ?: return
        val uri = pendingUri
        val pfd = pendingPfd
        var ok = true
        try { session?.stopRepeating(); session?.abortCaptures() } catch (_: Exception) {}
        pipeline?.detachRecorder()
        try { r.stop() } catch (_: RuntimeException) { ok = false }
        try { r.release() } catch (_: Exception) {}
        try { pfd?.close() } catch (_: Exception) {}
        recorder = null
        pendingUri = null
        pendingPfd = null
        recording = false
        if (uri != null) {
            if (ok) {
                try {
                    val v = ContentValues()
                    v.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    ctx.contentResolver.update(uri, v, null, null)
                    main.post { cb.onSaved(uri, true) }
                } catch (_: Exception) {}
            } else {
                try { ctx.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
                msg("Rekaman terlalu pendek, tidak disimpan")
            }
        }
        if (prefs.shutterSound) sound.play(MediaActionSound.STOP_VIDEO_RECORDING)
        main.post { cb.onRecording(false) }
    }
}
