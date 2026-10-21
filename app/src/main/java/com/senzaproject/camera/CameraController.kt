package com.senzaproject.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
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
    PRO("PRO", false),
    MACRO("SUPER MAKRO", false),
    TIMELAPSE("SELANG WAKTU", true),
    HIRES("PIKSEL TINGGI", false)
}

data class Caps(
    val zoomMin: Float, val zoomMax: Float,
    val evMin: Int, val evMax: Int, val evStep: Float,
    val isoMin: Int, val isoMax: Int, val expMin: Long, val expMax: Long,
    val focusMax: Float, val manual: Boolean, val hasFlash: Boolean
)

class CameraController(private val ctx: Context, private val prefs: Prefs, private val cb: Callback) {

    interface Callback {
        fun onPreviewSize(w: Int, h: Int)
        fun onCaps(c: Caps)
        fun onSaved(uri: Uri, video: Boolean)
        fun onMessage(msg: String)
        fun onVideoStatus(height: Int, fps: Int, eis: Boolean, measured: Boolean)
        fun onRecording(on: Boolean)
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
    private var videoHeight = 1080
    private var videoFps = 30
    private var videoEis = false

    private var fpsWindowStart = 0L
    private var fpsFrames = 0

    // ---------------------------------------------------------------- API

    fun setSurface(st: SurfaceTexture) {
        handler.post {
            surfaceTexture = st
            if (device != null) configure()
        }
    }

    fun detachSurface(st: SurfaceTexture) {
        handler.post {
            closeSession()
            if (surfaceTexture === st) surfaceTexture = null
            try { st.release() } catch (_: Exception) {}
        }
    }

    fun refreshBufferSize(st: SurfaceTexture) {
        handler.post {
            if (surfaceTexture === st) {
                try { st.setDefaultBufferSize(previewSize.width, previewSize.height) } catch (_: Exception) {}
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun open(frontFacing: Boolean) {
        handler.post {
            closeInternal()
            front = frontFacing
            attempt = 0
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
            mode = m
            attempt = 0
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
            zoom = clampZoom(z)
            builder?.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
            startRepeating()
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
            if (!front) { sx = v; sy = 1f - u } else { sx = 1f - v; sy = 1f - u }
            sx = 0.5f + (sx - 0.5f) / zoom
            sy = 0.5f + (sy - 0.5f) / zoom
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
                r.start()
                recording = true
                if (prefs.shutterSound) sound.play(MediaActionSound.START_VIDEO_RECORDING)
                main.post { cb.onRecording(true) }
            } catch (e: Exception) {
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

    fun release() {
        handler.post { closeInternal() }
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
        val caps = Caps(
            z?.lower ?: 1f, z?.upper ?: 1f,
            ev?.lower ?: 0, ev?.upper ?: 0, step,
            isoR?.lower ?: 100, isoR?.upper ?: 100,
            expR?.lower ?: 1_000_000L, expR?.upper ?: 1_000_000L,
            minFocus, manual,
            ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
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

    private fun closeSession() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        discardRecorder()
        builder = null
    }

    private fun closeInternal() {
        if (recording) stopRecordingInternal()
        closeSession()
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
        val dev = device ?: return
        val st = surfaceTexture ?: return
        val ch = chars ?: return
        closeSession()
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        fpsWindowStart = 0L
        fpsFrames = 0
        try {
            if (mode.video) configureVideo(dev, st, ch, map) else configurePhoto(dev, st, ch, map)
        } catch (e: Exception) {
            if (mode.video && attempt < 2) {
                attempt++
                configure()
            } else {
                msg("Gagal konfigurasi kamera: ${e.message}")
            }
        }
    }

    private fun configurePhoto(dev: CameraDevice, st: SurfaceTexture, ch: CameraCharacteristics, map: StreamConfigurationMap) {
        val wantRatio = if (mode == Mode.HIRES) 4f / 3f else if (prefs.photoRatio == 1) 16f / 9f else 4f / 3f
        val jpegSizes = map.getOutputSizes(ImageFormat.JPEG)
        val jpeg = if (mode == Mode.HIRES && jpegSizes != null && jpegSizes.isNotEmpty())
            jpegSizes.maxByOrNull { it.width.toLong() * it.height }!!
        else pickSize(jpegSizes, wantRatio, 13_500_000L)
        val ratio = jpeg.width.toFloat() / jpeg.height
        val prev = pickSize(map.getOutputSizes(SurfaceTexture::class.java), ratio, 2_100_000L)
        previewSize = prev
        main.post { cb.onPreviewSize(prev.width, prev.height) }
        st.setDefaultBufferSize(prev.width, prev.height)
        val ps = Surface(st)
        val rd = ImageReader.newInstance(jpeg.width, jpeg.height, ImageFormat.JPEG, 2)
        rd.setOnImageAvailableListener({ r -> onImage(r) }, handler)
        reader = rd
        val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        b.addTarget(ps)
        builder = b
        applyCommon(b, false)
        createSession(dev, listOf(ps, rd.surface), b)
    }

    private fun configureVideo(dev: CameraDevice, st: SurfaceTexture, ch: CameraCharacteristics, map: StreamConfigurationMap) {
        val tl = mode == Mode.TIMELAPSE
        val unlock = prefs.unlockEis && !tl
        val h = if (tl) 1080 else if (unlock || mode == Mode.VLOG) 1080 else prefs.videoHeight
        var fps = if (tl) 30 else if (unlock) 60 else if (mode == Mode.VLOG) 30 else prefs.videoFps
        var eis = !tl && (unlock || mode == Mode.VLOG || prefs.eis)
        if (attempt >= 1) fps = min(fps, 30)
        if (attempt >= 2) eis = false
        videoHeight = h
        videoFps = fps
        videoEis = eis

        val sizes = map.getOutputSizes(MediaRecorder::class.java)
        val list = sizes?.toList() ?: emptyList()
        val wide = list.filter { abs(it.width.toFloat() / it.height - 16f / 9f) < 0.03f }
        val vs = wide.filter { it.height == h }.maxByOrNull { it.width }
            ?: wide.filter { it.height <= h }.maxByOrNull { it.width }
            ?: list.maxByOrNull { it.width.toLong() * it.height }
            ?: Size(1920, 1080)

        val prev = pickSize(map.getOutputSizes(SurfaceTexture::class.java), vs.width.toFloat() / vs.height, 2_100_000L)
        previewSize = prev
        main.post { cb.onPreviewSize(prev.width, prev.height) }
        st.setDefaultBufferSize(prev.width, prev.height)
        val ps = Surface(st)
        val rs = prepareRecorder(vs, fps, tl)

        val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        b.addTarget(ps)
        b.addTarget(rs)
        builder = b
        applyCommon(b, true)
        pickFpsRange(ch, fps, unlock && attempt == 0)?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        applyStabilization(b, eis, unlock && attempt == 0)
        val resLabel = vs.height
        main.post { cb.onVideoStatus(resLabel, fps, eis, false) }
        createSession(dev, listOf(ps, rs), b)
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

        if (ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) != null)
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, clampZoom(zoom))

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

    private fun createSession(dev: CameraDevice, surfaces: List<Surface>, b: CaptureRequest.Builder) {
        val outs = surfaces.map { OutputConfiguration(it) }
        val sc = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outs, executor, sessionCb)
        sc.sessionParameters = b.build()
        dev.createCaptureSession(sc)
    }

    private val sessionCb = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(s: CameraCaptureSession) {
            if (device == null) {
                s.close()
                return
            }
            session = s
            startRepeating()
        }

        override fun onConfigureFailed(s: CameraCaptureSession) {
            onSessionFailed()
        }
    }

    private fun onSessionFailed() {
        if (mode.video && attempt < 2) {
            attempt++
            msg(if (attempt == 1) "Perangkat menolak 60 fps + EIS, mencoba 30 fps…" else "Mencoba tanpa EIS…")
            configure()
        } else {
            msg("Gagal membuat sesi kamera")
        }
    }

    private val repeatCb = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { lastIso = it }
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastExp = it }
            if (mode.video) trackFps(result)
        }
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
            val eisOn = st != null && st != CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            val h = videoHeight
            fpsWindowStart = ts
            fpsFrames = 0
            main.post { cb.onVideoStatus(h, fps, eisOn, true) }
        }
    }

    private fun startRepeating() {
        val s = session ?: return
        val b = builder ?: return
        try {
            s.setRepeatingRequest(b.build(), repeatCb, handler)
        } catch (e: Exception) {
            onSessionFailed()
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

    private fun savePhoto(bytes: ByteArray) {
        try {
            val resolver = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_${stamp()}.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            main.post { cb.onSaved(uri, false) }
        } catch (e: Exception) {
            msg("Gagal menyimpan foto: ${e.message}")
        }
    }

    // --------------------------------------------------------------- video

    private fun prepareRecorder(size: Size, fps: Int, timelapse: Boolean): Surface {
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
        r.setOrientationHint(jpegOrientation())
        r.prepare()
        return r.surface
    }

    private fun stopRecordingInternal() {
        val r = recorder ?: return
        val uri = pendingUri
        val pfd = pendingPfd
        var ok = true
        try { session?.stopRepeating(); session?.abortCaptures() } catch (_: Exception) {}
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
