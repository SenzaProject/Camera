package com.senzaproject.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
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
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * Video ganda: kamera depan (atas) + belakang (bawah) direkam bersamaan ke satu file.
 * Dua aliran kamera digabung oleh GlPipeline. Butuh dukungan HAL untuk membuka dua kamera sekaligus.
 */
class DualCamera(private val ctx: Context, private val prefs: Prefs, private val cb: CameraController.Callback) {

    private val thread = HandlerThread("senza-dual").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executor { r -> handler.post(r) }
    private val manager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val sound = MediaActionSound().apply {
        load(MediaActionSound.START_VIDEO_RECORDING)
        load(MediaActionSound.STOP_VIDEO_RECORDING)
    }

    private var st: SurfaceTexture? = null
    private var pipeline: GlPipeline? = null
    private var backId: String? = null
    private var frontId: String? = null
    private var backDev: CameraDevice? = null
    private var frontDev: CameraDevice? = null
    private var backSes: CameraCaptureSession? = null
    private var frontSes: CameraCaptureSession? = null
    private var recorder: MediaRecorder? = null
    private var pendingUri: Uri? = null
    private var pendingPfd: ParcelFileDescriptor? = null
    @Volatile var recording = false
        private set

    private var attempt = 0
    private var gen = 0
    private var ready = 0
    private var outW = 1080
    private var outH = 1920
    private var fps = 60
    private var resH = 1080
    private var eisOn = false
    private var fpsStart = 0L
    private var fpsFrames = 0

    // ---------------------------------------------------------------- API

    fun start(surface: SurfaceTexture) {
        handler.post {
            st = surface
            attempt = 0
            closeAll()
            openAll()
        }
    }

    fun release(done: (() -> Unit)? = null) {
        handler.post {
            closeAll()
            st = null
            if (done != null) main.post(done)
        }
    }

    fun destroy() {
        handler.post {
            closeAll()
            thread.quitSafely()
        }
    }

    fun reconfigure() {
        handler.post {
            attempt = 0
            if (st != null) {
                closeAll()
                openAll()
            }
        }
    }

    fun setLayout(layout: Int, swap: Boolean) {
        pipeline?.layout = layout
        pipeline?.swap = swap
    }

    fun startRecording() {
        handler.post {
            val r = recorder
            val pl = pipeline
            if (r == null || pl == null || ready < 2) {
                msg("Kamera belum siap")
                return@post
            }
            try {
                if (!pl.attachRecorder(r.surface, outW, outH))
                    throw IllegalStateException("renderer GL tidak bisa tersambung ke perekam")
                r.start()
                recording = true
                if (prefs.shutterSound) sound.play(MediaActionSound.START_VIDEO_RECORDING)
                main.post { cb.onRecording(true) }
            } catch (e: Exception) {
                pl.detachRecorder()
                msg("Gagal mulai merekam: ${e.message}")
            }
        }
    }

    fun stopRecording() {
        handler.post {
            stopRecordingInternal()
            try { prepareRecorder() } catch (e: Exception) { msg("Gagal menyiapkan perekam: ${e.message}") }
        }
    }

    // ------------------------------------------------------------ internals

    private fun msg(s: String) { main.post { cb.onMessage(s) } }

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    @SuppressLint("MissingPermission")
    private fun openAll() {
        val myGen = ++gen
        ready = 0
        try {
            backId = null
            frontId = null
            for (id in manager.cameraIdList) {
                val f = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
                if (f == CameraCharacteristics.LENS_FACING_BACK && backId == null) backId = id
                if (f == CameraCharacteristics.LENS_FACING_FRONT && frontId == null) frontId = id
            }
            val b = backId
            val f = frontId
            if (b == null || f == null) {
                fail("Perangkat tidak punya kamera depan dan belakang")
                return
            }
            val conc = try {
                manager.concurrentCameraIds.any { it.contains(b) && it.contains(f) }
            } catch (_: Throwable) {
                false
            }
            CamLog.add("dual: belakang=$b depan=$f concurrentDidukung=$conc attempt=$attempt")
            manager.openCamera(b, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) {
                    if (myGen != gen) { c.close(); return }
                    backDev = c
                    openFront(myGen, f)
                }

                override fun onDisconnected(c: CameraDevice) {
                    c.close()
                    if (myGen == gen) fail("Kamera belakang terputus")
                }

                override fun onError(c: CameraDevice, e: Int) {
                    c.close()
                    if (myGen == gen) fail("Kamera belakang error ($e)")
                }
            }, handler)
        } catch (e: Exception) {
            fail("Gagal membuka kamera: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun openFront(myGen: Int, id: String) {
        try {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) {
                    if (myGen != gen) { c.close(); return }
                    frontDev = c
                    configure(myGen)
                }

                override fun onDisconnected(c: CameraDevice) {
                    c.close()
                    if (myGen == gen) fail("Kamera terputus saat dua kamera aktif")
                }

                override fun onError(c: CameraDevice, e: Int) {
                    c.close()
                    if (myGen == gen) fail("Kamera depan tidak bisa dibuka bersamaan dengan kamera belakang (error $e)")
                }
            }, handler)
        } catch (e: Exception) {
            fail("Gagal membuka kamera depan: ${e.message}")
        }
    }

    private fun pickSize(ch: CameraCharacteristics, h: Int): Size {
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(SurfaceTexture::class.java)?.toList() ?: emptyList()
        val wide = sizes.filter { abs(it.width.toFloat() / it.height - 16f / 9f) < 0.03f }
        return wide.filter { it.height == h }.maxByOrNull { it.width }
            ?: wide.filter { it.height <= h }.maxByOrNull { it.width }
            ?: Size(1280, 720)
    }

    private fun upright(size: Size, ch: CameraCharacteristics): Float {
        val s = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        return if (s % 180 == 90) size.height.toFloat() / size.width else size.width.toFloat() / size.height
    }

    private fun request(dev: CameraDevice, ch: CameraCharacteristics, target: Surface, fpsWanted: Int, force: Boolean, eis: Boolean): CaptureRequest.Builder {
        val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        b.addTarget(target)
        val af = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
        if (af?.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) == true)
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        val ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList()
        val r: Range<Int>? = ranges?.firstOrNull { it.lower == fpsWanted && it.upper == fpsWanted }
            ?: ranges?.filter { it.upper == fpsWanted }?.minByOrNull { it.lower }
            ?: (if (force) Range(fpsWanted, fpsWanted) else ranges?.filter { it.upper <= fpsWanted }?.maxByOrNull { it.upper })
        if (r != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, r)
        if (eis) {
            val modes = ch.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
            if (modes?.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true)
                b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
        }
        return b
    }

    private fun configure(myGen: Int) {
        val surf = st ?: return
        val bd = backDev ?: return
        val fd = frontDev ?: return
        try {
            val bc = manager.getCameraCharacteristics(backId!!)
            val fc = manager.getCameraCharacteristics(frontId!!)
            val h = if (attempt >= 2) 720 else 1080
            fps = if (attempt >= 1) 30 else 60
            resH = h
            outW = if (h == 1080) 1080 else 720
            outH = if (h == 1080) 1920 else 1280
            val bs = pickSize(bc, h)
            val fs = pickSize(fc, h)

            val pl = GlPipeline(ctx, surf, 2, 1)
            pipeline = pl
            pl.aspect[0] = upright(fs, fc)
            pl.aspect[1] = upright(bs, bc)
            pl.sensorOri[0] = fc.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 270
            pl.sensorOri[1] = bc.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            pl.mirror[0] = prefs.mirrorFront
            pl.mirror[1] = false
            pl.layout = prefs.dualLayout
            pl.swap = prefs.dualSwap
            pl.outW = outW
            pl.outH = outH
            surf.setDefaultBufferSize(outW, outH)
            val surfaces = pl.start(listOf(fs, bs))
            main.post { cb.onPreviewSize(1920, 1080); cb.onViewMirror(false) }

            eisOn = prefs.eis
            CamLog.add("dual ${outW}x$outH @$fps belakang=${bs.width}x${bs.height} depan=${fs.width}x${fs.height} attempt=$attempt")
            prepareRecorder()

            val bb = request(bd, bc, surfaces[1], fps, attempt == 0, eisOn)
            val fb = request(fd, fc, surfaces[0], fps, false, false)
            startSession(bd, surfaces[1], bb, true, myGen)
            startSession(fd, surfaces[0], fb, false, myGen)
        } catch (e: Exception) {
            retry("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private val backCb = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
            if (fpsStart == 0L) { fpsStart = ts; fpsFrames = 0; return }
            fpsFrames++
            val dt = ts - fpsStart
            if (dt >= 1_000_000_000L) {
                val f = (fpsFrames * 1e9 / dt).roundToInt()
                val st = result.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE)
                val eis = st != null && st != CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                fpsStart = ts
                fpsFrames = 0
                main.post { cb.onVideoStatus(resH, f, eis, true) }
            }
        }
    }

    private fun startSession(dev: CameraDevice, surface: Surface, req: CaptureRequest.Builder, isBack: Boolean, myGen: Int) {
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (myGen != gen) {
                    try { s.close() } catch (_: Exception) {}
                    return
                }
                if (isBack) backSes = s else frontSes = s
                try {
                    s.setRepeatingRequest(req.build(), if (isBack) backCb else null, handler)
                } catch (e: Exception) {
                    retry("repeat: ${e.message}")
                    return
                }
                ready++
                if (ready >= 2) {
                    CamLog.add("dual siap")
                    main.post { cb.onVideoStatus(resH, fps, eisOn, false) }
                }
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                if (myGen == gen) retry("sesi ${if (isBack) "belakang" else "depan"} gagal")
            }
        }
        val sc = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(OutputConfiguration(surface)), executor, callback)
        sc.sessionParameters = req.build()
        dev.createCaptureSession(sc)
    }

    private fun retry(reason: String) {
        CamLog.add("dual gagal attempt=$attempt: $reason")
        if (attempt < 2) {
            attempt++
            msg(if (attempt == 1) "Dua kamera 60 fps ditolak, mencoba 30 fps…" else "Mencoba 720p…")
            closeAll()
            handler.postDelayed({ if (st != null) openAll() }, 700)
        } else {
            fail(reason)
        }
    }

    private fun fail(reason: String) {
        CamLog.add("dual TIDAK BISA: $reason")
        closeAll()
        main.post { cb.onDualFailed(reason) }
    }

    private fun closeAll() {
        gen++
        if (recording) stopRecordingInternal()
        try { backSes?.close() } catch (_: Exception) {}
        try { frontSes?.close() } catch (_: Exception) {}
        backSes = null
        frontSes = null
        try { backDev?.close() } catch (_: Exception) {}
        try { frontDev?.close() } catch (_: Exception) {}
        backDev = null
        frontDev = null
        discardRecorder()
        pipeline?.release()
        pipeline = null
        ready = 0
        fpsStart = 0L
    }

    // ------------------------------------------------------------- rekaman

    private fun prepareRecorder() {
        discardRecorder()
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
        val audio = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (audio) r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setOutputFile(pfd.fileDescriptor)
        r.setVideoEncodingBitRate(if (fps > 30) 24_000_000 else 16_000_000)
        r.setVideoFrameRate(fps)
        r.setVideoSize(outW, outH)
        r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        if (audio) {
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(128_000)
            r.setAudioSamplingRate(44_100)
        }
        r.setOrientationHint(0)
        r.prepare()
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

    private fun stopRecordingInternal() {
        val r = recorder ?: return
        val uri = pendingUri
        val pfd = pendingPfd
        var ok = true
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
