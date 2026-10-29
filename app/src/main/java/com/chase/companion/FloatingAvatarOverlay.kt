package com.chase.companion

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Choreographer
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.filament.Camera
import com.google.android.filament.IndirectLight
import com.google.android.filament.Renderer
import com.google.android.filament.Viewport
import com.google.android.filament.android.UiHelper
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.tan

/** Process-scoped diagnostic overlay; not an always-running background service. */
object FloatingAvatarOverlay {
    private val main = Handler(Looper.getMainLooper())
    private var session: Session? = null

    @JvmStatic fun show(context: Context) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { show(context.applicationContext) }; return }
        if (session != null) return
        val app = context.applicationContext
        if (!Settings.canDrawOverlays(app)) {
            Toast.makeText(app, "Grant display-over-apps permission first", Toast.LENGTH_LONG).show()
            return
        }
        val candidate = Session(app)
        session = candidate
        try { candidate.show() } catch (error: Exception) {
            candidate.close()
            Toast.makeText(app, "Overlay failed: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    @JvmStatic fun hide() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { hide() }; return }
        session?.close()
    }

    private class Session(private val context: Context) {
        private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        private val framing = context.getSharedPreferences("overlay_framing", Context.MODE_PRIVATE)
        private var verticalStep = framing.getInt("vertical_step", 0).coerceIn(-18, 18)
        private var zoomStep = framing.getInt("zoom_step", 0).coerceIn(-4, 10)
        private val zoom get() = 1.0 + zoomStep * 0.1
        // Every new session starts fitted, deliberately bypassing saved crop settings.
        private var fullFit = true
        private var debugBackground = false
        private var diagnosticDialog: AlertDialog? = null
        private val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        private val surface = SurfaceView(context).apply {
            setZOrderOnTop(true)
            holder.setFormat(PixelFormat.TRANSLUCENT)
        }
        private val label = TextView(context).apply {
            text = "Loading Roxanne… · drag here"
            setTextColor(0xffffffff.toInt()); textSize = 11f; gravity = Gravity.CENTER_VERTICAL
        }
        private val idleButton = Button(context).apply {
            text = "Ⅱ"; contentDescription = "Pause idle facial movement"; isEnabled = false
            setOnClickListener {
                idle?.let { animator ->
                    animator.setIdleEnabled(!animator.enabled)
                    viewer?.animator?.updateBoneMatrices()
                    text = if (animator.enabled) "Ⅱ" else "▶"
                    contentDescription = if (animator.enabled) "Pause idle facial movement" else "Resume idle facial movement"
                }
            }
        }
        private val metrics get() = context.resources.displayMetrics
        private fun dp(value: Int) = (value * metrics.density).roundToInt()
        private val width = minOf(dp(240), metrics.widthPixels)
        private val height = minOf(dp(484), metrics.heightPixels)
        private val position = WindowManager.LayoutParams(
            width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = minOf(dp(16), max(0, metrics.widthPixels - width))
            y = minOf(dp(100), max(0, metrics.heightPixels - height))
        }
        private var viewer: ModelViewer? = null
        private var idle: IdleFacialAnimator? = null
        private var ambient: IndirectLight? = null
        private var source: ByteBuffer? = null
        private var added = false
        private var closed = false
        private var loaded = false
        private var ready = false
        private var normalizedHeight = 2.0
        private var normalizedWidth = 2.0
        private var normalizedDepth = 2.0
        private var torsoX = 0.0
        private var torsoY = 0.0
        private var torsoZ = -4.0
        private var headAnchor = false
        private var attempts = 0L
        private var rendered = 0L
        private var lastLabelTime = 0L
        private var rigNote = "Loading"
        private var cameraNote = "Camera pending"
        private val frame = object : Choreographer.FrameCallback {
            override fun doFrame(time: Long) {
                if (closed) return
                try {
                    val v = viewer
                    // Let ModelViewer's own FramePacer control frame acceptance, not a second 30 Hz throttle.
                    if (v != null && power.isInteractive && surface.display?.state == Display.STATE_ON && surface.holder.surface.isValid) {
                        frameCamera(v)
                        if (ready) { idle?.update(time); v.animator?.updateBoneMatrices() }
                        attempts++
                        if (v.render(time)) rendered++
                        if (loaded && !ready && v.progress >= 1f) {
                            val jawSet = applyRestingJaw(v)
                            val facial = IdleFacialAnimator(v)
                            idle = facial
                            idleButton.isEnabled = true
                            v.animator?.updateBoneMatrices()
                            ready = true
                            rigNote = when {
                                !jawSet -> "Jaw missing"
                                facial.missingJoints.isNotEmpty() -> "Missing joints: ${facial.missingJoints.joinToString()}"
                                else -> "Rig ready"
                            }
                        }
                    }
                    if (lastLabelTime == 0L || time - lastLabelTime >= 1_000_000_000L) {
                        lastLabelTime = time
                        label.text = if (ready) "Roxanne · frames $rendered · drag" else if (loaded) "Textures ${((viewer?.progress ?: 0f) * 100).toInt()}% · frames $rendered" else "Loading model · frames $rendered"
                        label.contentDescription = "$rigNote. Successful render calls $rendered of $attempts. Drag to move overlay."
                    }
                } catch (error: Exception) {
                    Toast.makeText(context, "Avatar render failed: ${error.message}", Toast.LENGTH_LONG).show()
                    close()
                    return
                }
                Choreographer.getInstance().postFrameCallback(this)
            }
        }

        private fun applyBackground() {
            viewer?.renderer?.clearOptions = Renderer.ClearOptions().apply {
                clear = true; discard = true
                clearColor = if (debugBackground) doubleArrayOf(0.12, 0.025, 0.16, 1.0) else doubleArrayOf(0.0, 0.0, 0.0, 0.0)
            }
        }

        private fun diagnosticReport(): String {
            val v = viewer
            var components = 0
            v?.asset?.let { asset ->
                val rm = v.engine.renderableManager
                for (entity in asset.entities) if (rm.getInstance(entity) != 0) components++
            }
            val viewport = v?.view?.viewport
            val nativeFrame = surface.holder.surfaceFrame
            return "OVERLAY DIAGNOSTICS v2\nRenderer target=SurfaceView; transparent surface; on-top stacking\nLoaded=$loaded; resourceReady=$ready\n$rigNote\nRender calls: $rendered successful / $attempts attempted\nA successful call does not prove visible model pixels.\nSurfaceView: ${surface.width} x ${surface.height}; nativeValid=${surface.holder.surface.isValid}; attached=${surface.isAttachedToWindow}\nNative surface frame: ${nativeFrame.width()} x ${nativeFrame.height()}\nFilament viewport: ${viewport?.width} x ${viewport?.height}\nDisplay=${surface.display?.state}; interactive=${power.isInteractive}\nRenderable components=$components; loadProgress=${v?.progress}\nMode=${if (fullFit) "whole-model fit" else "chest"}; headAnchor=$headAnchor\nNormalized size=$normalizedWidth, $normalizedHeight, $normalizedDepth\nTorso target=$torsoX, $torsoY, $torsoZ\n$cameraNote\nSaved verticalStep=$verticalStep; zoom=$zoom; ignored in Fit mode\nDiagnostic background=$debugBackground\n"
        }

        private fun showInfo() {
            if (closed) return
            diagnosticDialog?.dismiss()
            val report = diagnosticReport()
            val dialog = AlertDialog.Builder(context)
                .setTitle("Floating avatar diagnostics")
                .setMessage(report)
                .setPositiveButton("Copy report") { _, _ ->
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Overlay diagnostics", report))
                    Toast.makeText(context, "Overlay report copied", Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton(if (debugBackground) "Transparent bg" else "Test background") { _, _ ->
                    if (!closed) { debugBackground = !debugBackground; applyBackground() }
                }
                .setNegativeButton("Close", null).create()
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            diagnosticDialog = dialog
            dialog.show()
        }

        private fun framingControls(): LinearLayout {
            val panel = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0xcc20242c.toInt())
            }
            val zoomLabel = TextView(context).apply { gravity = Gravity.CENTER; setTextColor(0xffffffff.toInt()); textSize = 14f }
            fun refresh() {
                zoomLabel.text = if (fullFit) "Fit" else String.format(Locale.US, "%.1f×", zoom)
                zoomLabel.contentDescription = if (fullFit) "Whole model fitted; saved crop ignored" else String.format(Locale.US, "Zoom %.1f times; avatar vertical offset %d steps", zoom, verticalStep)
            }
            fun save() {
                framing.edit().putInt("vertical_step", verticalStep).putInt("zoom_step", zoomStep).apply()
                refresh()
            }
            fun button(text: String, description: String, action: () -> Unit) = Button(context).apply {
                this.text = text; contentDescription = description; minWidth = 0; setPadding(0, 0, 0, 0)
                setOnClickListener { if (!closed) { action(); save() } }
            }
            val modes = LinearLayout(context)
            modes.addView(button("Fit", "Fit entire original model bounds in view") { fullFit = true }, LinearLayout.LayoutParams(0, dp(48), 1f))
            modes.addView(button("Chest", "Frame upper torso using head joint") { fullFit = false }, LinearLayout.LayoutParams(0, dp(48), 1f))
            modes.addView(button("Info", "Render diagnostics and test background") { showInfo() }, LinearLayout.LayoutParams(0, dp(48), 1f))
            val vertical = LinearLayout(context)
            vertical.addView(button("↑", "Move avatar up within chest frame") { fullFit = false; verticalStep = (verticalStep + 1).coerceAtMost(18) }, LinearLayout.LayoutParams(0, dp(48), 1f))
            vertical.addView(button("↓", "Move avatar down within chest frame") { fullFit = false; verticalStep = (verticalStep - 1).coerceAtLeast(-18) }, LinearLayout.LayoutParams(0, dp(48), 1f))
            vertical.addView(button("Reset", "Reset crop settings and fit whole model") { verticalStep = 0; zoomStep = 0; fullFit = true }, LinearLayout.LayoutParams(0, dp(48), 1f))
            val magnification = LinearLayout(context)
            magnification.addView(button("−", "Zoom out in chest mode") { fullFit = false; zoomStep = (zoomStep - 1).coerceAtLeast(-4) }, LinearLayout.LayoutParams(0, dp(48), 1f))
            magnification.addView(zoomLabel, LinearLayout.LayoutParams(0, dp(48), 1f))
            magnification.addView(button("+", "Zoom in in chest mode") { fullFit = false; zoomStep = (zoomStep + 1).coerceAtMost(10) }, LinearLayout.LayoutParams(0, dp(48), 1f))
            panel.addView(modes, LinearLayout.LayoutParams(-1, dp(48)))
            panel.addView(vertical, LinearLayout.LayoutParams(-1, dp(48)))
            panel.addView(magnification, LinearLayout.LayoutParams(-1, dp(48)))
            refresh()
            return panel
        }

        fun show() {
            val bar = LinearLayout(context).apply { setBackgroundColor(0xcc20242c.toInt()); gravity = Gravity.CENTER_VERTICAL }
            bar.addView(label, LinearLayout.LayoutParams(0, dp(40), 1f))
            bar.addView(idleButton, LinearLayout.LayoutParams(dp(48), dp(40)))
            bar.addView(Button(context).apply {
                text = "×"; contentDescription = "Close floating Roxanne"; setOnClickListener { close() }
            }, LinearLayout.LayoutParams(dp(48), dp(40)))
            root.addView(bar, LinearLayout.LayoutParams(-1, dp(40)))
            root.addView(FrameLayout(context).apply {
                clipChildren = true; addView(surface, FrameLayout.LayoutParams(-1, -1))
            }, LinearLayout.LayoutParams(-1, 0, 1f))
            root.addView(framingControls(), LinearLayout.LayoutParams(-1, dp(144)))
            label.setOnTouchListener(object : View.OnTouchListener {
                private var startX = 0f; private var startY = 0f
                private var oldX = 0; private var oldY = 0
                override fun onTouch(view: View, event: MotionEvent): Boolean {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { startX = event.rawX; startY = event.rawY; oldX = position.x; oldY = position.y; return true }
                        MotionEvent.ACTION_MOVE -> {
                            position.x = (oldX + (event.rawX - startX).roundToInt()).coerceIn(0, max(0, metrics.widthPixels - width))
                            position.y = (oldY + (event.rawY - startY).roundToInt()).coerceIn(0, max(0, metrics.heightPixels - height))
                            try { if (added && !closed) wm.updateViewLayout(root, position) } catch (_: RuntimeException) { close() }
                            return true
                        }
                        MotionEvent.ACTION_UP -> { view.performClick(); return true }
                        MotionEvent.ACTION_CANCEL -> return true
                    }
                    return false
                }
            })
            // Cleanup listener precedes ModelViewer's engine-destroying detach listener.
            surface.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {}
                override fun onViewDetachedFromWindow(view: View) {
                    releaseCustomResources(); closed = true; added = false
                    if (session === this@Session) session = null
                }
            })
            wm.addView(root, position); added = true
            surface.post { if (!closed && surface.isAttachedToWindow) initializeRenderer() }
        }

        private fun initializeRenderer() {
            try {
                Utils.init()
                // UiHelper sets the holder format and on-top stacking before attaching.
                val helper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK).apply { setOpaque(false); setMediaOverlay(false) }
                val v = ModelViewer(surfaceView = surface, uiHelper = helper, manipulator = null)
                viewer = v
                v.autoPlayAnimations = false
                v.view.blendMode = com.google.android.filament.View.BlendMode.TRANSLUCENT
                applyBackground()
                val lights = v.engine.lightManager
                lights.setDirection(lights.getInstance(v.light), -0.4f, -0.7f, -1f)
                ambient = IndirectLight.Builder().irradiance(1, floatArrayOf(0.7f, 0.7f, 0.7f)).intensity(30000f).build(v.engine)
                v.scene.indirectLight = ambient
                Choreographer.getInstance().postFrameCallback(frame)
                Thread({
                    try {
                        val bytes = context.assets.open("roxanne_wolf_security_breach.glb").use { it.readBytes() }
                        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
                        buffer.put(bytes).flip()
                        main.post {
                            if (!closed && viewer === v) {
                                try {
                                    source = buffer; v.loadModelGlb(buffer)
                                    val asset = v.asset ?: error("Filament could not load bundled Roxanne")
                                    val half = asset.boundingBox.halfExtent
                                    val maxExtent = 2f * maxOf(half[0], half[1], half[2])
                                    require(maxExtent.isFinite() && maxExtent > 0f) { "Invalid model bounds" }
                                    normalizedWidth = (4f * half[0] / maxExtent).toDouble()
                                    normalizedHeight = (4f * half[1] / maxExtent).toDouble()
                                    normalizedDepth = (4f * half[2] / maxExtent).toDouble()
                                    require(normalizedWidth.isFinite() && normalizedWidth >= 0.0 && normalizedHeight.isFinite() && normalizedHeight > 0.0 && normalizedDepth.isFinite() && normalizedDepth >= 0.0) { "Invalid normalized bounds" }
                                    v.transformToUnitCube()
                                    findTorsoTarget(v)
                                    loaded = true; rigNote = "Geometry loaded; resources pending"
                                } catch (error: Exception) { fail(error) }
                            }
                        }
                    } catch (error: Exception) { main.post { if (!closed) fail(error) } }
                }, "read-floating-roxanne").start()
            } catch (error: Exception) { fail(error) }
        }

        private fun findTorsoTarget(v: ModelViewer) {
            torsoX = 0.0; torsoY = normalizedHeight * 0.25; torsoZ = -4.0; headAnchor = false
            val asset = v.asset ?: return
            val head = asset.entities.firstOrNull { asset.getName(it) == "Head_jnt_06" } ?: return
            val tm = v.engine.transformManager
            val instance = tm.getInstance(head)
            if (instance == 0) return
            val world = FloatArray(16).also { tm.getWorldTransform(instance, it) }
            if (!world[12].isFinite() || !world[13].isFinite() || !world[14].isFinite()) return
            torsoX = world[12].toDouble(); torsoY = world[13].toDouble() - normalizedHeight * 0.12; torsoZ = world[14].toDouble(); headAnchor = true
        }

        private fun frameCamera(v: ModelViewer) {
            val nativeFrame = surface.holder.surfaceFrame
            val renderWidth = nativeFrame.width()
            val renderHeight = nativeFrame.height()
            if (renderWidth <= 0 || renderHeight <= 0) return
            val viewport = v.view.viewport
            if (viewport.width != renderWidth || viewport.height != renderHeight) {
                v.view.viewport = Viewport(0, 0, renderWidth, renderHeight)
            }
            val aspect = renderWidth.toDouble() / renderHeight.toDouble()
            val tangent = tan(Math.toRadians(35.0 / 2.0))
            val halfHeight = if (fullFit) normalizedHeight * 0.5 else normalizedHeight * 0.24
            val halfWidth = if (fullFit) normalizedWidth * 0.5 else normalizedHeight * 0.25
            val distance = (max(halfHeight / tangent, halfWidth / (aspect * tangent)) * (if (fullFit) 1.15 else 1.08) / (if (fullFit) 1.0 else zoom) + normalizedDepth * 0.5).coerceAtLeast(0.2)
            val x = if (fullFit) 0.0 else torsoX
            val y = if (fullFit) 0.0 else torsoY - normalizedHeight * verticalStep * 0.025
            val z = if (fullFit) -4.0 else torsoZ
            v.camera.setProjection(35.0, aspect, 0.05, 1000.0, Camera.Fov.VERTICAL)
            v.camera.lookAt(x, y, z + distance, x, y, z, 0.0, 1.0, 0.0)
            cameraNote = String.format(Locale.US, "Camera target=(%.3f, %.3f, %.3f); distance=%.3f; aspect=%.3f", x, y, z, distance, aspect)
        }

        private fun applyRestingJaw(v: ModelViewer): Boolean {
            val asset = v.asset ?: return false
            val tm = v.engine.transformManager
            val jaw = asset.entities.firstOrNull { asset.getName(it) == FacialCalibration.JAW } ?: return false
            val instance = tm.getInstance(jaw)
            if (instance == 0) return false
            val base = FloatArray(16).also { tm.getTransform(instance, it) }
            val rotation = FloatArray(16); val result = FloatArray(16)
            Matrix.setRotateM(rotation, 0, FacialCalibration.JAW_CLOSED, 0f, 1f, 0f)
            Matrix.multiplyMM(result, 0, base, 0, rotation, 0); tm.setTransform(instance, result)
            return true
        }

        private fun fail(error: Exception) {
            Toast.makeText(context, "Avatar overlay failed: ${error.message}", Toast.LENGTH_LONG).show(); close()
        }

        private fun releaseCustomResources() {
            Choreographer.getInstance().removeFrameCallback(frame)
            diagnosticDialog?.dismiss(); diagnosticDialog = null; idle = null
            viewer?.let { v -> v.scene.indirectLight = null; ambient?.let { v.engine.destroyIndirectLight(it) } }
            ambient = null; source = null; viewer = null; loaded = false; ready = false
        }

        fun close() {
            if (closed) return
            closed = true; releaseCustomResources()
            if (added) { added = false; try { wm.removeViewImmediate(root) } catch (_: IllegalArgumentException) {} }
            if (session === this) session = null
        }
    }
}
