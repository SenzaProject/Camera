package com.senzaproject.camera

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

class MainActivity : Activity(), CameraController.Callback, SensorEventListener {

    private lateinit var prefs: Prefs
    private lateinit var ctrl: CameraController
    private val ui = Handler(Looper.getMainLooper())

    private var front = false
    private var mode = Mode.AI_CAM
    private var moreMode: Mode? = null
    private var recording = false
    private var counting = false
    private var topInset = 0
    private var bottomInset = 0
    private var prevW = 1440
    private var prevH = 1080
    private var zoom = 1f
    private var caps: Caps? = null
    private var lastUri: Uri? = null
    private var recStart = 0L
    private var optionsOpen = false
    private var oriListener: OrientationEventListener? = null

    private lateinit var root: FrameLayout
    private lateinit var previewFrame: FrameLayout
    private lateinit var texture: TextureView
    private lateinit var grid: GridOverlay
    private lateinit var level: LevelOverlay
    private lateinit var focusBox: View
    private lateinit var shotFlash: View
    private lateinit var topBar: FrameLayout
    private lateinit var flashBtn: IconView
    private lateinit var chevron: IconView
    private lateinit var infoPill: TextView
    private lateinit var optionsRow: LinearLayout
    private lateinit var ratioIcon: IconView
    private lateinit var ratioCell: View
    private lateinit var recPill: TextView
    private lateinit var wmPreview: TextView
    private lateinit var bottomPanel: LinearLayout
    private lateinit var zoomRow: LinearLayout
    private lateinit var modeScroll: HorizontalScrollView
    private lateinit var modeRow: LinearLayout
    private lateinit var shutter: ShutterView
    private lateinit var thumb: ImageView
    private lateinit var countdown: TextView
    private lateinit var msgPill: TextView
    private lateinit var proPanel: LinearLayout
    private var moreOverlay: FrameLayout? = null

    private val optRefresh = ArrayList<() -> Unit>()
    private val modeViews = ArrayList<TextView>()
    private val carousel: List<Mode?> = listOf(Mode.VLOG, Mode.VIDEO, Mode.AI_CAM, Mode.PORTRAIT, Mode.NIGHT, null)

    // pro sliders
    private var proEv: SeekBar? = null
    private var proIso: SeekBar? = null
    private var proExp: SeekBar? = null
    private var proFocus: SeekBar? = null
    private val proValues = ArrayList<TextView>()

    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector
    private var sensorManager: SensorManager? = null

    // ------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        prefs = Prefs(this)
        CamLog.init(prefs)
        ctrl = CameraController(this, prefs, this)
        mode = try { Mode.valueOf(prefs.lastMode) } catch (_: Exception) { Mode.AI_CAM }
        if (mode !in carousel) moreMode = mode
        buildUi()
        setContentView(root)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        oriListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                ctrl.orientation = ((o + 45) / 90 * 90) % 360
            }
        }
        refreshModeLabels()
        updateModeUi()
        refreshFlashIcon()
        onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
            if (moreOverlay?.visibility == View.VISIBLE) hideMore() else finish()
        }
    }

    override fun onResume() {
        super.onResume()
        grid.visibility = if (prefs.grid) View.VISIBLE else View.GONE
        level.visibility = if (prefs.level) View.VISIBLE else View.GONE
        zoom = 1f
        updateZoomUi()
        refreshOptions()
        ui.postDelayed({ centerSelected() }, 200)
        Thread {
            try { DeviceInfo.get(this, prefs) } catch (_: Exception) {}
            runOnUiThread { refreshWatermarkPreview() }
        }.start()
        oriListener?.enable()
        if (prefs.level) {
            val acc = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (acc != null) sensorManager?.registerListener(this, acc, SensorManager.SENSOR_DELAY_UI)
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            texture.scaleX = if (front) -1f else 1f
            ctrl.setMode(mode)
            ctrl.open(front)
            loadThumb()
        } else {
            requestPermissions(arrayOf(
                Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO), 1)
        }
    }

    override fun onPause() {
        super.onPause()
        oriListener?.disable()
        sensorManager?.unregisterListener(this)
        ui.removeCallbacksAndMessages(null)
        counting = false
        countdown.visibility = View.GONE
        ctrl.release()
    }

    override fun onDestroy() {
        super.onDestroy()
        ctrl.destroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            ctrl.setMode(mode)
            ctrl.open(front)
            loadThumb()
        } else {
            showMsg("Izin kamera diperlukan")
        }
    }


    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            when (prefs.volumeAction) {
                0 -> { if (event.repeatCount == 0) onShutter(); return true }
                1 -> {
                    val c = caps
                    val step = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 0.3f else -0.3f
                    zoom = (zoom + step).coerceIn(c?.zoomMin ?: 1f, c?.zoomMax ?: 1f)
                    ctrl.setZoom(zoom); updateZoomUi()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ------------------------------------------------------------------ UI

    private fun tv(text: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.BOLD) else Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
        }

    private fun iconBtn(ic: Ic, size: Float = 44f, click: () -> Unit): IconView {
        val v = IconView(this, ic)
        v.background = GlassDrawable(this)
        val p = dp(size * 0.27f)
        v.setPadding(p, p, p, p)
        v.setOnClickListener { click() }
        return v
    }

    private fun buildUi() {
        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        // ---- pratinjau
        previewFrame = FrameLayout(this)
        previewFrame.clipChildren = true
        texture = TextureView(this)
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { ctrl.setSurface(st) }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { ctrl.refreshBufferSize(st) }
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { ctrl.detachSurface(st); return false }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        previewFrame.addView(texture, FrameLayout.LayoutParams(-1, -1))
        grid = GridOverlay(this)
        previewFrame.addView(grid, FrameLayout.LayoutParams(-1, -1))
        level = LevelOverlay(this)
        previewFrame.addView(level, FrameLayout.LayoutParams(-1, -1))
        focusBox = View(this)
        focusBox.background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT); setStroke(dp(2f), C.YELLOW); cornerRadius = dpf(6f)
        }
        focusBox.visibility = View.GONE
        previewFrame.addView(focusBox, FrameLayout.LayoutParams(dp(72f), dp(72f)))
        wmPreview = TextView(this)
        wmPreview.setTextColor(0xF2FFFFFF.toInt())
        wmPreview.textSize = 11f
        wmPreview.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        wmPreview.setShadowLayer(dpf(3f), 0f, dpf(1f), 0xAA000000.toInt())
        wmPreview.visibility = View.GONE
        previewFrame.addView(wmPreview, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(16f); rightMargin = dp(16f); bottomMargin = dp(14f)
        })
        shotFlash = View(this)
        shotFlash.setBackgroundColor(Color.WHITE)
        shotFlash.alpha = 0f
        previewFrame.addView(shotFlash, FrameLayout.LayoutParams(-1, -1))
        root.addView(previewFrame, FrameLayout.LayoutParams(-1, dp(400f), Gravity.TOP))
        setupGestures()

        // ---- bilah atas
        topBar = FrameLayout(this)
        flashBtn = iconBtn(Ic.FLASH_OFF) { cycleFlash() }
        topBar.addView(flashBtn, FrameLayout.LayoutParams(dp(44f), dp(44f), Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = dp(16f) })
        chevron = IconView(this, Ic.CHEVRON)
        chevron.background = GlassDrawable(this)
        chevron.setPadding(dp(18f), dp(9f), dp(18f), dp(9f))
        chevron.setOnClickListener { toggleOptions() }
        topBar.addView(chevron, FrameLayout.LayoutParams(dp(64f), dp(34f), Gravity.CENTER))
        val right = LinearLayout(this)
        right.orientation = LinearLayout.HORIZONTAL
        right.gravity = Gravity.CENTER_VERTICAL
        infoPill = tv("", 12f)
        infoPill.gravity = Gravity.CENTER
        infoPill.setPadding(dp(12f), 0, dp(12f), 0)
        infoPill.background = GlassDrawable(this)
        infoPill.visibility = View.GONE
        infoPill.setOnClickListener { toggleUnlock() }
        right.addView(iconBtn(Ic.GEAR) { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) },
            LinearLayout.LayoutParams(dp(44f), dp(44f)))
        topBar.addView(right, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(16f) })
        root.addView(topBar, FrameLayout.LayoutParams(-1, dp(56f), Gravity.TOP))
        root.addView(infoPill, FrameLayout.LayoutParams(-2, dp(30f), Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        recPill = tv("00:00", 14f, Color.WHITE, true)
        recPill.gravity = Gravity.CENTER
        recPill.setPadding(dp(14f), 0, dp(14f), 0)
        recPill.background = GradientDrawable().apply { setColor(C.RED); cornerRadius = dpf(17f) }
        recPill.visibility = View.GONE
        root.addView(recPill, FrameLayout.LayoutParams(-2, dp(34f), Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        // ---- baris opsi (rantai chevron)
        optionsRow = LinearLayout(this)
        optionsRow.orientation = LinearLayout.HORIZONTAL
        optionsRow.gravity = Gravity.CENTER
        optionsRow.background = GlassDrawable(this, dpf(28f))
        optionsRow.setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
        optionsRow.visibility = View.GONE
        optionsRow.addView(optCell(Ic.TIMER, { when (prefs.timer) { 3 -> "3 dtk"; 10 -> "10 dtk"; else -> "Mati" } }) { cycleTimer() }.first)
        val ratio = optCell(Ic.RATIO, { if (prefs.photoRatio == 1) "16:9" else "4:3" }) {
            prefs.photoRatio = 1 - prefs.photoRatio
            ctrl.reconfigure()
        }
        ratioCell = ratio.first
        ratioIcon = ratio.second
        optionsRow.addView(ratioCell)
        optionsRow.addView(optCell(Ic.GRID, { if (prefs.grid) "Aktif" else "Mati" }) {
            prefs.grid = !prefs.grid
            grid.visibility = if (prefs.grid) View.VISIBLE else View.GONE
        }.first)
        optionsRow.addView(optCell(Ic.LEVEL, { if (prefs.level) "Aktif" else "Mati" }) {
            prefs.level = !prefs.level
            level.visibility = if (prefs.level) View.VISIBLE else View.GONE
            sensorManager?.unregisterListener(this)
            if (prefs.level) {
                val acc = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                if (acc != null) sensorManager?.registerListener(this, acc, SensorManager.SENSOR_DELAY_UI)
            }
        }.first)
        optionsRow.addView(optCell(Ic.WATERMARK, { if (prefs.watermark) "Aktif" else "Mati" }) {
            prefs.watermark = !prefs.watermark
        }.first)
        root.addView(optionsRow, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        // ---- panel bawah
        bottomPanel = LinearLayout(this)
        bottomPanel.orientation = LinearLayout.VERTICAL
        bottomPanel.gravity = Gravity.CENTER_HORIZONTAL
        bottomPanel.background = GradientDrawable().apply {
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            setColors(intArrayOf(0x00000000, 0xE6000000.toInt(), 0xFF000000.toInt()), floatArrayOf(0f, 0.3f, 0.55f))
        }

        buildProPanel()
        bottomPanel.addView(proPanel, LinearLayout.LayoutParams(-1, -2).apply {
            leftMargin = dp(16f); rightMargin = dp(16f); bottomMargin = dp(8f)
        })

        zoomRow = LinearLayout(this)
        zoomRow.orientation = LinearLayout.HORIZONTAL
        zoomRow.gravity = Gravity.CENTER
        zoomRow.background = GlassDrawable(this)
        zoomRow.setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
        bottomPanel.addView(zoomRow, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(10f) })

        modeScroll = HorizontalScrollView(this)
        modeScroll.isHorizontalScrollBarEnabled = false
        modeRow = LinearLayout(this)
        modeRow.orientation = LinearLayout.HORIZONTAL
        modeRow.gravity = Gravity.CENTER_VERTICAL
        val half = resources.displayMetrics.widthPixels / 2
        modeRow.addView(View(this), LinearLayout.LayoutParams(half, 1))
        for (i in carousel.indices) {
            val t = tv("", 13f)
            t.gravity = Gravity.CENTER
            t.setPadding(dp(15f), dp(10f), dp(15f), dp(10f))
            t.setOnClickListener { onModeTap(i) }
            modeViews.add(t)
            modeRow.addView(t, LinearLayout.LayoutParams(-2, -2))
        }
        modeRow.addView(View(this), LinearLayout.LayoutParams(half, 1))
        modeScroll.addView(modeRow, FrameLayout.LayoutParams(-2, -2))
        bottomPanel.addView(modeScroll, LinearLayout.LayoutParams(-1, dp(44f)))

        val shutterRow = FrameLayout(this)
        thumb = ImageView(this)
        thumb.scaleType = ImageView.ScaleType.CENTER_CROP
        thumb.background = GradientDrawable().apply { setColor(0xFF2C2C2E.toInt()); cornerRadius = dpf(12f) }
        thumb.clipToOutline = true
        thumb.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dpf(12f)) }
        }
        thumb.setOnClickListener { openLast() }
        shutterRow.addView(thumb, FrameLayout.LayoutParams(dp(54f), dp(54f), Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = dp(34f) })
        shutter = ShutterView(this)
        shutter.setOnClickListener { onShutter() }
        shutterRow.addView(shutter, FrameLayout.LayoutParams(dp(78f), dp(78f), Gravity.CENTER))
        shutterRow.addView(iconBtn(Ic.FLIP, 54f) { flipCamera() },
            FrameLayout.LayoutParams(dp(54f), dp(54f), Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(34f) })
        bottomPanel.addView(shutterRow, LinearLayout.LayoutParams(-1, dp(92f)))
        root.addView(bottomPanel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        // ---- hitung mundur dan pesan
        countdown = tv("", 96f, Color.WHITE, true)
        countdown.gravity = Gravity.CENTER
        countdown.visibility = View.GONE
        root.addView(countdown, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        msgPill = tv("", 13f)
        msgPill.gravity = Gravity.CENTER
        msgPill.setPadding(dp(16f), dp(9f), dp(16f), dp(9f))
        msgPill.background = GlassDrawable(this, dpf(18f), 0xB3202022.toInt())
        msgPill.visibility = View.GONE
        root.addView(msgPill, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        root.setOnApplyWindowInsetsListener { _, ins ->
            val i = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            topInset = i.top
            bottomInset = i.bottom
            applyInsets()
            WindowInsets.CONSUMED
        }
        applyInsets()
        rebuildZoom()
    }

    private fun applyInsets() {
        (topBar.layoutParams as FrameLayout.LayoutParams).topMargin = topInset + dp(4f)
        (recPill.layoutParams as FrameLayout.LayoutParams).topMargin = topInset + dp(10f)
        (infoPill.layoutParams as FrameLayout.LayoutParams).topMargin = topInset + dp(66f)
        (optionsRow.layoutParams as FrameLayout.LayoutParams).topMargin = topInset + dp(102f)
        (msgPill.layoutParams as FrameLayout.LayoutParams).topMargin = topInset + dp(160f)
        bottomPanel.setPadding(0, 0, 0, bottomInset + dp(8f))
        layoutPreview()
        root.requestLayout()
    }

    private fun layoutPreview() {
        val w = resources.displayMetrics.widthPixels
        val h = (w * prevW / prevH.toFloat()).toInt()
        val lp = previewFrame.layoutParams as FrameLayout.LayoutParams
        lp.width = w
        lp.height = h
        lp.topMargin = topInset + dp(60f)
        lp.gravity = Gravity.TOP
        previewFrame.layoutParams = lp
    }

    private fun optCell(ic: Ic, caption: () -> String, click: () -> Unit): Pair<View, IconView> {
        val cell = LinearLayout(this)
        cell.orientation = LinearLayout.VERTICAL
        cell.gravity = Gravity.CENTER
        cell.setPadding(dp(10f), dp(2f), dp(10f), dp(2f))
        val icon = IconView(this, ic)
        cell.addView(icon, LinearLayout.LayoutParams(dp(28f), dp(28f)))
        val cap = tv(caption(), 10f, 0xCCFFFFFF.toInt())
        cap.gravity = Gravity.CENTER
        cell.addView(cap, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3f) })
        cell.setOnClickListener { click(); refreshOptions() }
        optRefresh.add {
            cap.text = caption()
            if (ic == Ic.RATIO) icon.label = if (prefs.photoRatio == 1) "16:9" else "4:3"
        }
        return Pair(cell, icon)
    }

    private fun refreshOptions() {
        optRefresh.forEach { it() }
        ratioCell.visibility = if (!mode.video && mode != Mode.HIRES) View.VISIBLE else View.GONE
        refreshWatermarkPreview()
    }

    private fun refreshWatermarkPreview() {
        val show = prefs.watermark && !mode.video
        wmPreview.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        val lp = wmPreview.layoutParams as FrameLayout.LayoutParams
        lp.gravity = Gravity.BOTTOM or when (prefs.wmPos) { 1 -> Gravity.CENTER_HORIZONTAL; 2 -> Gravity.END; else -> Gravity.START }
        wmPreview.layoutParams = lp
        wmPreview.text = if (prefs.wmText.isBlank() && prefs.deviceName.isBlank()) "…" else Watermark.text(this, prefs)
    }

    private fun toggleOptions() {
        optionsOpen = !optionsOpen
        optionsRow.visibility = if (optionsOpen) View.VISIBLE else View.GONE
        chevron.animate().rotation(if (optionsOpen) 180f else 0f).setDuration(180).start()
        refreshOptions()
    }

    private fun cycleTimer() {
        prefs.timer = when (prefs.timer) { 0 -> 3; 3 -> 10; else -> 0 }
    }

    private fun cycleFlash() {
        if (caps?.hasFlash == false) { showMsg("Flash tidak tersedia di kamera ini"); return }
        prefs.flash = (prefs.flash + 1) % 3
        ctrl.setFlash(prefs.flash)
        refreshFlashIcon()
    }

    private fun refreshFlashIcon() {
        flashBtn.setIcon(when (prefs.flash) { 1 -> Ic.FLASH_AUTO; 2 -> Ic.FLASH_ON; else -> Ic.FLASH_OFF })
        flashBtn.color = if (prefs.flash == 0) Color.WHITE else C.YELLOW
        flashBtn.alpha = if (caps?.hasFlash == false) 0.4f else 1f
        flashBtn.invalidate()
    }

    private fun toggleUnlock() {
        prefs.unlockEis = !prefs.unlockEis
        showMsg(if (prefs.unlockEis) "Unlock EIS 1080p 60 fps: AKTIF" else "Unlock EIS 1080p 60 fps: MATI")
        ctrl.reconfigure()
    }

    // --------------------------------------------------------- zoom & mode

    private fun rebuildZoom() {
        zoomRow.removeAllViews()
        val c = caps
        val presets = ArrayList<Float>()
        if (c != null && c.zoomMin <= 0.6f) presets.add(0.5f)
        presets.add(1f)
        if (c == null || c.zoomMax >= 2f) presets.add(2f)
        for (p in presets) {
            val t = tv("", 13f, Color.WHITE, true)
            t.gravity = Gravity.CENTER
            t.tag = p
            t.setOnClickListener { zoom = p; ctrl.setZoom(p); updateZoomUi() }
            zoomRow.addView(t, LinearLayout.LayoutParams(dp(40f), dp(40f)).apply { leftMargin = dp(2f); rightMargin = dp(2f) })
        }
        updateZoomUi()
    }

    private fun fmtZoom(z: Float): String {
        val s = if (abs(z - z.roundToInt()) < 0.05f) z.roundToInt().toString() else String.format("%.1f", z)
        return s
    }

    private fun updateZoomUi() {
        var best: TextView? = null
        var bestD = Float.MAX_VALUE
        for (i in 0 until zoomRow.childCount) {
            val t = zoomRow.getChildAt(i) as TextView
            val p = t.tag as Float
            if (abs(p - zoom) < bestD) { bestD = abs(p - zoom); best = t }
        }
        for (i in 0 until zoomRow.childCount) {
            val t = zoomRow.getChildAt(i) as TextView
            val p = t.tag as Float
            if (t === best) {
                t.text = fmtZoom(zoom) + "×"
                t.setTextColor(C.YELLOW)
                t.background = GradientDrawable().apply { setColor(0x99000000.toInt()); shape = GradientDrawable.OVAL }
            } else {
                t.text = if (p < 1f) ".5" else fmtZoom(p)
                t.setTextColor(Color.WHITE)
                t.background = null
            }
        }
    }

    private fun refreshModeLabels() {
        for (i in carousel.indices) {
            val m = carousel[i]
            val label = if (m != null) m.label else (moreMode?.label ?: "LAINNYA")
            val selected = if (m != null) m == mode else (moreMode != null && moreMode == mode)
            modeViews[i].text = label
            modeViews[i].setTextColor(if (selected) C.YELLOW else 0xE6FFFFFF.toInt())
        }
    }

    private fun centerSelected() {
        val idx = carousel.indices.firstOrNull { i ->
            val m = carousel[i]
            if (m != null) m == mode else moreMode == mode
        } ?: return
        modeScroll.post {
            val v = modeViews[idx]
            modeScroll.smoothScrollTo(v.left + v.width / 2 - modeScroll.width / 2, 0)
        }
    }

    private fun onModeTap(i: Int) {
        if (recording || counting) return
        val m = carousel[i]
        if (m == null) showMore() else selectMode(m, true)
    }

    private fun selectMode(m: Mode, fromCarousel: Boolean) {
        if (recording || counting) return
        if (fromCarousel) moreMode = null else moreMode = m
        mode = m
        prefs.lastMode = m.name
        refreshModeLabels()
        centerSelected()
        updateModeUi()
        ctrl.setMode(m)
    }

    private fun stepMode(dir: Int) {
        if (recording || counting) return
        val idx = carousel.indices.firstOrNull { i ->
            val m = carousel[i]
            if (m != null) m == mode else moreMode == mode
        } ?: 2
        val n = idx + dir
        if (n < 0 || n >= carousel.size) return
        val m = carousel[n]
        if (m == null) showMore() else selectMode(m, true)
    }

    private fun updateModeUi() {
        shutter.video = mode.video
        infoPill.visibility = if (mode == Mode.VLOG || mode == Mode.VIDEO) View.VISIBLE else View.GONE
        proPanel.visibility = if (mode == Mode.PRO) View.VISIBLE else View.GONE
        if (mode != Mode.PRO) resetPro()
        refreshOptions()
    }

    private fun resetPro() {
        val c = caps
        proEv?.progress = (-(c?.evMin ?: 0)).coerceAtLeast(0)
        proIso?.progress = 0
        proExp?.progress = 0
        proFocus?.progress = 0
        if (proValues.size >= 4) updateProLabels()
    }

    // ---------------------------------------------------------- Lainnya

    private fun showMore() {
        var ov = moreOverlay
        if (ov == null) {
            ov = FrameLayout(this)
            ov.setBackgroundColor(0xF2000000.toInt())
            ov.isClickable = true
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.gravity = Gravity.CENTER
            val items = listOf(
                Triple("PANORAMA", Ic.PANO, null as Mode?), Triple("PRO", Ic.PRO, Mode.PRO), Triple("SKY SHOP", Ic.SKY, null as Mode?),
                Triple("DOKUMEN", Ic.DOC, null as Mode?), Triple("SUPER MAKRO", Ic.MACRO, Mode.MACRO), Triple("GERAK LAMBAT", Ic.SLOWMO, null as Mode?),
                Triple("SELANG WAKTU", Ic.TIMELAPSE, Mode.TIMELAPSE), Triple("VIDEO GANDA", Ic.DUAL, null as Mode?), Triple("POTRET AIGC", Ic.AIGC, null as Mode?),
                Triple("PIKSEL TINGGI", Ic.HD, Mode.HIRES)
            )
            var row: LinearLayout? = null
            for ((i, it) in items.withIndex()) {
                if (i % 3 == 0) {
                    row = LinearLayout(this)
                    row.orientation = LinearLayout.HORIZONTAL
                    col.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(34f) })
                }
                val cell = LinearLayout(this)
                cell.orientation = LinearLayout.VERTICAL
                cell.gravity = Gravity.CENTER
                cell.addView(IconView(this, it.second), LinearLayout.LayoutParams(dp(30f), dp(30f)))
                val t = tv(it.first, 11f)
                t.gravity = Gravity.CENTER
                cell.addView(t, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8f) })
                val target = it.third
                cell.alpha = if (target != null) 1f else 0.35f
                cell.setOnClickListener {
                    if (target != null) { hideMore(); selectMode(target, false) }
                    else showMsg("Belum tersedia di versi 1.0")
                }
                row!!.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
            }
            ov.addView(col, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { leftMargin = dp(20f); rightMargin = dp(20f) })
            val close = iconBtn(Ic.CLOSE) { hideMore() }
            ov.addView(close, FrameLayout.LayoutParams(dp(44f), dp(44f), Gravity.TOP or Gravity.END).apply {
                topMargin = topInset + dp(8f); rightMargin = dp(16f)
            })
            root.addView(ov, FrameLayout.LayoutParams(-1, -1))
            moreOverlay = ov
        }
        ov.visibility = View.VISIBLE
    }

    private fun hideMore() { moreOverlay?.visibility = View.GONE }

    // --------------------------------------------------------- panel Pro

    private fun buildProPanel() {
        proPanel = LinearLayout(this)
        proPanel.orientation = LinearLayout.VERTICAL
        proPanel.background = GlassDrawable(this, dpf(20f))
        proPanel.setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
        proPanel.visibility = View.GONE
        proEv = proRow("EV")
        proIso = proRow("ISO")
        proExp = proRow("S")
        proFocus = proRow("AF")
        val l = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) applyPro() else updateProLabels() }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        }
        proEv?.setOnSeekBarChangeListener(l); proIso?.setOnSeekBarChangeListener(l)
        proExp?.setOnSeekBarChangeListener(l); proFocus?.setOnSeekBarChangeListener(l)
        proEv?.max = 12; proEv?.progress = 6
        proIso?.max = 100; proExp?.max = 100; proFocus?.max = 100
        updateProLabels()
    }

    private fun proRow(name: String): SeekBar {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val n = tv(name, 12f, Color.WHITE, true)
        row.addView(n, LinearLayout.LayoutParams(dp(30f), -2))
        val sb = SeekBar(this)
        sb.progressTintList = android.content.res.ColorStateList.valueOf(C.YELLOW)
        sb.thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        row.addView(sb, LinearLayout.LayoutParams(0, dp(34f), 1f))
        val v = tv("", 12f, Color.WHITE)
        v.gravity = Gravity.END
        proValues.add(v)
        row.addView(v, LinearLayout.LayoutParams(dp(62f), -2))
        proPanel.addView(row)
        return sb
    }

    private fun proIsoValue(): Int {
        val c = caps ?: return 0
        val p = proIso?.progress ?: 0
        if (p == 0 || !c.manual) return 0
        val lo = ln(c.isoMin.toDouble()); val hi = ln(c.isoMax.toDouble())
        return exp(lo + (hi - lo) * p / 100.0).roundToInt()
    }

    private fun proExpValue(): Long {
        val c = caps ?: return 0L
        val p = proExp?.progress ?: 0
        if (p == 0 || !c.manual) return 0L
        val lo = ln(maxOf(c.expMin, 125_000L).toDouble())
        val hi = ln(minOf(c.expMax, 1_000_000_000L).toDouble())
        return exp(lo + (hi - lo) * p / 100.0).toLong()
    }

    private fun proFocusValue(): Float {
        val c = caps ?: return -1f
        val p = proFocus?.progress ?: 0
        if (p == 0 || c.focusMax <= 0f) return -1f
        return c.focusMax * (1f - (p - 1) / 99f)
    }

    private fun updateProLabels() {
        val c = caps
        val ev = ((proEv?.progress ?: 6) - 6) * (c?.evStep ?: 0.33f)
        proValues[0].text = (if (ev > 0) "+" else "") + String.format("%.1f", ev)
        val iso = proIsoValue()
        proValues[1].text = if (iso == 0) "AUTO" else iso.toString()
        val ex = proExpValue()
        proValues[2].text = when {
            ex == 0L -> "AUTO"
            ex >= 500_000_000L -> String.format("%.1fs", ex / 1e9)
            else -> "1/" + (1e9 / ex).roundToInt()
        }
        val f = proFocusValue()
        proValues[3].text = if (f < 0f) "AUTO" else if (f < 0.05f) "∞" else String.format("%.0fcm", 100f / f)
    }

    private fun applyPro() {
        updateProLabels()
        val c = caps
        val idx = (proEv?.progress ?: 6) - 6
        val clamped = idx.coerceIn(c?.evMin ?: 0, c?.evMax ?: 0)
        ctrl.setEv(clamped)
        ctrl.setManual(proIsoValue(), proExpValue(), proFocusValue())
    }

    // ------------------------------------------------------ gesture kamera

    private fun setupGestures() {
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val c = caps ?: return true
                zoom = (zoom * d.scaleFactor).coerceIn(c.zoomMin, c.zoomMax)
                ctrl.setZoom(zoom)
                updateZoomUi()
                return true
            }
        })
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val w = previewFrame.width.toFloat(); val h = previewFrame.height.toFloat()
                if (w > 0 && h > 0) {
                    ctrl.focusAt(e.x / w, e.y / h)
                    showFocus(e.x, e.y)
                }
                return true
            }
        })
        var downX = 0f
        var downY = 0f
        var multi = false
        previewFrame.setOnTouchListener { _, e ->
            scaleDetector.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; multi = false }
                MotionEvent.ACTION_POINTER_DOWN -> multi = true
                MotionEvent.ACTION_UP -> {
                    val dx = e.x - downX
                    val dy = e.y - downY
                    if (!multi && abs(dx) > dp(70f) && abs(dx) > abs(dy) * 1.5f) {
                        stepMode(if (dx < 0) 1 else -1)
                        return@setOnTouchListener true
                    }
                }
            }
            if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(e)
            true
        }
    }

    private fun showFocus(x: Float, y: Float) {
        val s = dp(72f)
        focusBox.translationX = x - s / 2f
        focusBox.translationY = y - s / 2f
        focusBox.visibility = View.VISIBLE
        focusBox.alpha = 1f
        focusBox.scaleX = 1.4f
        focusBox.scaleY = 1.4f
        focusBox.animate().scaleX(1f).scaleY(1f).setDuration(180).withEndAction {
            focusBox.animate().alpha(0f).setStartDelay(900).setDuration(250).withEndAction {
                focusBox.visibility = View.GONE
            }.start()
        }.start()
    }

    // -------------------------------------------------------- rana / video

    private fun onShutter() {
        if (counting) return
        if (recording) { ctrl.stopRecording(); return }
        val t = prefs.timer
        if (t > 0) startCountdown(t) else doShutter()
    }

    private fun startCountdown(sec: Int) {
        counting = true
        countdown.visibility = View.VISIBLE
        var left = sec
        val tick = object : Runnable {
            override fun run() {
                if (!counting) return
                if (left <= 0) {
                    counting = false
                    countdown.visibility = View.GONE
                    doShutter()
                } else {
                    countdown.text = left.toString()
                    left--
                    ui.postDelayed(this, 1000)
                }
            }
        }
        tick.run()
    }

    private fun doShutter() {
        if (mode.video) {
            ctrl.startRecording()
        } else {
            ctrl.capturePhoto()
            shotFlash.animate().cancel()
            shotFlash.alpha = 0.55f
            shotFlash.animate().alpha(0f).setDuration(160).start()
        }
    }

    private fun flipCamera() {
        if (recording || counting) return
        front = !front
        texture.scaleX = if (front) -1f else 1f
        zoom = 1f
        ctrl.open(front)
        updateZoomUi()
    }

    private val recTick = object : Runnable {
        override fun run() {
            if (!recording) return
            val s = ((android.os.SystemClock.elapsedRealtime() - recStart) / 1000).toInt()
            recPill.text = String.format("%02d:%02d", s / 60, s % 60)
            ui.postDelayed(this, 500)
        }
    }

    // ---------------------------------------------------------- Callback

    override fun onPreviewSize(w: Int, h: Int) {
        prevW = w
        prevH = h
        layoutPreview()
    }

    override fun onCaps(c: Caps) {
        caps = c
        zoom = zoom.coerceIn(c.zoomMin, c.zoomMax)
        rebuildZoom()
        refreshFlashIcon()
        proEv?.max = (c.evMax - c.evMin).coerceAtLeast(0)
        proEv?.progress = (-c.evMin).coerceAtLeast(0)
        val manualOk = c.manual
        proIso?.isEnabled = manualOk; proExp?.isEnabled = manualOk
        proFocus?.isEnabled = c.focusMax > 0f
        updateProLabels()
    }

    override fun onSaved(uri: Uri, video: Boolean) {
        lastUri = uri
        loadThumb()
        if (video) showMsg("Video tersimpan")
    }

    override fun onMessage(msg: String) { showMsg(msg) }

    override fun onVideoStatus(height: Int, fps: Int, eis: Boolean, measured: Boolean) {
        val res = if (height >= 2160) "4K" else height.toString()
        infoPill.text = res + " · " + fps + if (eis) " · EIS" else ""
        infoPill.setTextColor(
            if (measured && eis && fps >= 55) C.YELLOW
            else if (measured) Color.WHITE
            else 0xAAFFFFFF.toInt()
        )
    }

    override fun onRecording(on: Boolean) {
        recording = on
        shutter.recording = on
        recPill.visibility = if (on) View.VISIBLE else View.GONE
        chevron.visibility = if (on) View.INVISIBLE else View.VISIBLE
        if (on) {
            recStart = android.os.SystemClock.elapsedRealtime()
            recPill.text = "00:00"
            ui.post(recTick)
        }
    }

    // ------------------------------------------------------------- misc

    private fun showMsg(text: String) {
        msgPill.text = text
        msgPill.visibility = View.VISIBLE
        msgPill.alpha = 1f
        msgPill.animate().cancel()
        msgPill.animate().alpha(0f).setStartDelay(1800).setDuration(300).withEndAction {
            msgPill.visibility = View.GONE
        }.start()
    }

    private fun loadThumb() {
        Thread {
            try {
                val uri = latestMedia() ?: return@Thread
                val bmp = contentResolver.loadThumbnail(uri, Size(256, 256), null)
                runOnUiThread {
                    lastUri = uri
                    thumb.setImageBitmap(bmp)
                }
            } catch (_: Exception) {}
        }.start()
    }

    private fun latestMedia(): Uri? {
        val q = MediaStore.Files.getContentUri("external")
        val proj = arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE)
        val sel = "(" + MediaStore.Files.FileColumns.MEDIA_TYPE + "=? OR " + MediaStore.Files.FileColumns.MEDIA_TYPE + "=?) AND " +
            MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?"
        val args = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(), "DCIM/Camera%")
        contentResolver.query(q, proj, sel, args, MediaStore.MediaColumns.DATE_ADDED + " DESC")?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(0)
                val type = c.getInt(1)
                val base = if (type == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                return android.content.ContentUris.withAppendedId(base, id)
            }
        }
        return null
    }

    private fun openLast() {
        val u = lastUri ?: return
        try {
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(u, contentResolver.getType(u) ?: "image/*")
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(i)
        } catch (_: Exception) {
            showMsg("Tidak ada aplikasi untuk membuka file")
        }
    }

    override fun onSensorChanged(e: SensorEvent) {
        val ax = e.values[0]
        val ay = e.values[1]
        level.angle = Math.toDegrees(atan2(-ax.toDouble(), ay.toDouble())).toFloat()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
