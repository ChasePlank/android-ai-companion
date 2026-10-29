package com.chase.companion

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
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.tan

/** Process-scoped avatar-only overlay; settings live in the app. */
object FloatingAvatarOverlay {
    private val main = Handler(Looper.getMainLooper())
    private var session: Session? = null

    @JvmStatic fun show(context: Context) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { show(context.applicationContext) }; return }
        if (session != null) return
        val app = context.applicationContext
        if (!Settings.canDrawOverlays(app)) {
            Toast.makeText(app, "Grant display-over-apps permission first", Toast.LENGTH_LONG).show(); return
        }
        val candidate = Session(app)
        session = candidate
        try { candidate.show() } catch (error: Exception) { candidate.close(); Toast.makeText(app, "Overlay failed: ${error.message}", Toast.LENGTH_LONG).show() }
    }

    @JvmStatic fun hide() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { hide() }; return }
        session?.close()
    }

    @JvmStatic fun refreshSettings() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { refreshSettings() }; return }
        session?.refreshSettings()
    }

    @JvmStatic fun diagnostics(): String = session?.diagnosticReport() ?: "Overlay is hidden. Show floating Roxanne to collect a report."

    private class Session(private val context: Context) {
        private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        private val metrics get() = context.resources.displayMetrics
        private fun dp(value: Int) = (value * metrics.density).roundToInt()
        private val width = minOf(dp(120), metrics.widthPixels)
        private val height = minOf(dp(242), metrics.heightPixels)
        private val position = WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = minOf(dp(16), max(0, metrics.widthPixels - width))
            y = minOf(dp(100), max(0, metrics.heightPixels - height))
        }
        private val root = object : FrameLayout(context) {
            override fun onInterceptTouchEvent(event: MotionEvent): Boolean = true
            override fun onTouchEvent(event: MotionEvent): Boolean = handleTouch(event)
        }
        private val surface = SurfaceView(context).apply { setZOrderOnTop(true); holder.setFormat(PixelFormat.TRANSLUCENT) }
        private var viewer: ModelViewer? = null
        private var idle: IdleFacialAnimator? = null
        private var ambient: IndirectLight? = null
        private var source: ByteBuffer? = null
        private var added = false
        private var closed = false
        private var loaded = false
        private var ready = false
        private var chest = true
        private var zoom = 2.0
        private var verticalStep = 0
        private var horizontalStep = 0
        private var idleEnabled = true
        private var debugBackground = false
        private var normalizedHeight = 2.0
        private var normalizedWidth = 2.0
        private var normalizedDepth = 2.0
        private data class Anchor(val name: String, val x: Double, val y: Double, val z: Double)
        private var spine = Anchor("Spine missing; normalized-center fallback", 0.0, 0.0, -4.0)
        private var neck = Anchor("Neck missing; normalized-center fallback", 0.0, 0.0, -4.0)
        private var attempts = 0L
        private var rendered = 0L
        private var rigNote = "Loading"
        private var cameraNote = "Camera pending"
        private var touching = false
        private var dragging = false
        private var startX = 0f
        private var startY = 0f
        private var oldX = 0
        private var oldY = 0
        private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        private val holdToRemove = Runnable {
            if (touching && !dragging && !closed) {
                touching = false
                root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                close()
            }
        }
        private val frame = object : Choreographer.FrameCallback {
            override fun doFrame(time: Long) {
                if (closed) return
                try {
                    val v = viewer
                    if (v != null && power.isInteractive && surface.display?.state == Display.STATE_ON && surface.holder.surface.isValid && frameCamera(v)) {
                        if (ready) { idle?.update(time); v.animator?.updateBoneMatrices() }
                        attempts++
                        if (v.render(time)) rendered++
                        if (loaded && !ready && v.progress >= 1f) {
                            val jawSet = applyRestingJaw(v)
                            val facial = IdleFacialAnimator(v)
                            idle = facial
                            facial.setIdleEnabled(idleEnabled)
                            v.animator?.updateBoneMatrices()
                            disableAvatarCulling(v)
                            // Cache anatomical pivots after normalization and calibrated body rest.
                            spine = findAnchor(v, "Spine", "Spine_jnt_03")
                            neck = findAnchor(v, "Neck", "Neck_jnt_05")
                            ready = true
                            rigNote = when {
                                !jawSet -> "Jaw missing"
                                facial.missingJoints.isNotEmpty() -> "Missing joints: ${facial.missingJoints.joinToString()}"
                                else -> "Rig ready"
                            }
                        }
                    }
                } catch (error: Exception) { fail(error); return }
                Choreographer.getInstance().postFrameCallback(this)
            }
        }

        fun refreshSettings() {
            val p = OverlaySettings.preferences(context)
            chest = p.getBoolean("chest", true)
            zoom = p.getInt("zoom_tenths", 20).coerceIn(8, 60) / 10.0
            verticalStep = p.getInt("vertical_step", 0).coerceIn(-18, 18)
            horizontalStep = p.getInt("horizontal_step", 0).coerceIn(-18, 18)
            idleEnabled = p.getBoolean("idle", true)
            debugBackground = p.getBoolean("debug_background", false)
            idle?.let { if (it.enabled != idleEnabled) { it.setIdleEnabled(idleEnabled); viewer?.animator?.updateBoneMatrices() } }
            applyBackground()
        }

        private fun handleTouch(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touching = true; dragging = false
                    startX = event.rawX; startY = event.rawY; oldX = position.x; oldY = position.y
                    main.removeCallbacks(holdToRemove)
                    main.postDelayed(holdToRemove, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> if (touching) {
                    val dx = event.rawX - startX; val dy = event.rawY - startY
                    if (!dragging && dx * dx + dy * dy > slop * slop) { dragging = true; main.removeCallbacks(holdToRemove) }
                    if (dragging) {
                        position.x = (oldX + dx.roundToInt()).coerceIn(0, max(0, metrics.widthPixels - width))
                        position.y = (oldY + dy.roundToInt()).coerceIn(0, max(0, metrics.heightPixels - height))
                        try { if (added && !closed) wm.updateViewLayout(root, position) } catch (_: RuntimeException) { close() }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(holdToRemove)
                    if (touching && !dragging) root.performClick()
                    touching = false
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> { main.removeCallbacks(holdToRemove); touching = false }
            }
            return true
        }

        fun show() {
            refreshSettings()
            root.contentDescription = "Floating Roxanne. Drag anywhere to move; hold still to remove. Settings are inside the app."
            root.addView(surface, FrameLayout.LayoutParams(-1, -1))
            // Register before ModelViewer's later engine-destroying detach listener.
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
                                    val extent = 2f * maxOf(half[0], half[1], half[2])
                                    require(extent.isFinite() && extent > 0f) { "Invalid model bounds" }
                                    normalizedWidth = (4f * half[0] / extent).toDouble()
                                    normalizedHeight = (4f * half[1] / extent).toDouble()
                                    normalizedDepth = (4f * half[2] / extent).toDouble()
                                    require(normalizedWidth.isFinite() && normalizedWidth >= 0.0 && normalizedHeight.isFinite() && normalizedHeight > 0.0 && normalizedDepth.isFinite() && normalizedDepth >= 0.0) { "Invalid normalized bounds" }
                                    v.transformToUnitCube()
                                    disableAvatarCulling(v)
                                    loaded = true; rigNote = "Geometry loaded; resources pending"
                                } catch (error: Exception) { fail(error) }
                            }
                        }
                    } catch (error: Exception) { main.post { if (!closed) fail(error) } }
                }, "read-floating-roxanne").start()
            } catch (error: Exception) { fail(error) }
        }

        private fun disableAvatarCulling(v: ModelViewer) {
            val asset = v.asset ?: return
            val rm = v.engine.renderableManager
            for (entity in asset.entities) {
                val instance = rm.getInstance(entity)
                // Bypass suspect skinning bounds only; leave materials, layers and shadows alone.
                if (instance != 0) rm.setCulling(instance, false)
            }
        }

        private fun renderableBoundsReport(v: ModelViewer): String = buildString {
            val asset = v.asset ?: return@buildString
            val rm = v.engine.renderableManager
            val tm = v.engine.transformManager
            var count = 0
            var disabled = 0
            val details = StringBuilder()
            for (entity in asset.entities) {
                val instance = rm.getInstance(entity)
                if (instance == 0) continue
                count++
                val culling = rm.isCullingEnabled(instance)
                if (!culling) disabled++
                val box = rm.getAxisAlignedBoundingBox(instance, null)
                val center = box.center
                val half = box.halfExtent
                details.append("${asset.getName(entity) ?: "Unnamed"} [entity $entity]: frustumCulling=$culling; primitives=${rm.getPrimitiveCount(instance)}\n")
                details.append("  local bounds center=${center.contentToString()}; halfExtent=${half.contentToString()}\n")
                val transform = tm.getInstance(entity)
                if (transform != 0) {
                    val world = FloatArray(16).also { tm.getWorldTransform(transform, it) }
                    val worldCenter = FloatArray(3) { row -> world[row] * center[0] + world[4 + row] * center[1] + world[8 + row] * center[2] + world[12 + row] }
                    val worldHalf = FloatArray(3) { row -> abs(world[row]) * half[0] + abs(world[4 + row]) * half[1] + abs(world[8 + row]) * half[2] }
                    details.append("  world bounds center=${worldCenter.contentToString()}; halfExtent=${worldHalf.contentToString()}\n")
                } else details.append("  World transform unavailable\n")
            }
            append("Frustum culling disabled=$disabled / $count renderables (queried flags)\n")
            append("Bounds below are stored CPU boxes, not measured skinned vertex positions.\n")
            append(details)
        }

        private fun findAnchor(v: ModelViewer, base: String, preferred: String): Anchor {
            val fallback = Anchor("$base missing; normalized-center fallback", 0.0, 0.0, -4.0)
            val asset = v.asset ?: return fallback
            val entity = asset.entities.firstOrNull { asset.getName(it) == preferred }
                ?: asset.entities.firstOrNull {
                    val name = asset.getName(it) ?: ""
                    name.equals(base, true) || name.startsWith("${base}_jnt_", true)
                } ?: return fallback
            val tm = v.engine.transformManager
            val instance = tm.getInstance(entity)
            if (instance == 0) return fallback
            val world = FloatArray(16).also { tm.getWorldTransform(instance, it) }
            if (!world[12].isFinite() || !world[13].isFinite() || !world[14].isFinite()) return fallback
            return Anchor(asset.getName(entity) ?: base, world[12].toDouble(), world[13].toDouble(), world[14].toDouble())
        }

        private fun frameCamera(v: ModelViewer): Boolean {
            val nativeFrame = surface.holder.surfaceFrame
            val w = nativeFrame.width(); val h = nativeFrame.height()
            if (w <= 0 || h <= 0) return false
            val viewport = v.view.viewport
            if (viewport.width != w || viewport.height != h) v.view.viewport = Viewport(0, 0, w, h)
            val aspect = w.toDouble() / h.toDouble()
            val tangent = tan(Math.toRadians(35.0 / 2.0))
            val halfHeight = normalizedHeight * if (chest) 0.20 else 0.5
            val halfWidth = if (chest) normalizedHeight * 0.14 else normalizedWidth * 0.5
            val baseDistance = max(halfHeight / tangent, halfWidth / (aspect * tangent)) * (if (chest) 1.08 else 1.15) + normalizedDepth * 0.5
            // Scale the entire distance: no unscaled depth offset that defeats close zoom.
            val distance = (baseDistance / if (chest) zoom else 1.0).coerceAtLeast(0.2)
            val anchor = if (chest) neck else spine
            val x = anchor.x - if (chest) normalizedHeight * horizontalStep * 0.025 else 0.0
            val y = anchor.y - if (chest) normalizedHeight * verticalStep * 0.025 else 0.0
            val z = anchor.z
            v.camera.setProjection(35.0, aspect, 0.05, 1000.0, Camera.Fov.VERTICAL)
            v.camera.lookAt(x, y, z + distance, x, y, z, 0.0, 1.0, 0.0)
            cameraNote = String.format(Locale.US, "Camera target=(%.3f, %.3f, %.3f); distance=%.3f; aspect=%.3f", x, y, z, distance, aspect)
            return true
        }

        private fun applyBackground() {
            viewer?.renderer?.clearOptions = Renderer.ClearOptions().apply {
                clear = true; discard = true
                clearColor = if (debugBackground) doubleArrayOf(0.12, 0.025, 0.16, 1.0) else doubleArrayOf(0.0, 0.0, 0.0, 0.0)
            }
        }

        fun diagnosticReport(): String {
            val v = viewer
            val viewport = v?.view?.viewport
            val nativeFrame = surface.holder.surfaceFrame
            var components = 0
            v?.asset?.let { asset -> for (entity in asset.entities) if (v.engine.renderableManager.getInstance(entity) != 0) components++ }
            val boundsReport = v?.let { renderableBoundsReport(it) } ?: "Renderer unavailable\n"
            return "OVERLAY DIAGNOSTICS v4\nAvatar-only window=120 x 242 dp\nRenderer target=SurfaceView; transparent surface; on-top stacking\nLoaded=$loaded; resourceReady=$ready\n$rigNote\nRender calls: $rendered successful / $attempts attempted\nA successful call does not prove visible model pixels.\nSurfaceView: ${surface.width} x ${surface.height}; nativeValid=${surface.holder.surface.isValid}; attached=${surface.isAttachedToWindow}\nNative surface frame: ${nativeFrame.width()} x ${nativeFrame.height()}\nFilament viewport: ${viewport?.width} x ${viewport?.height}\nDisplay=${surface.display?.state}; interactive=${power.isInteractive}\nRenderable components=$components; loadProgress=${v?.progress}\nMode=${if (chest) "chest / neck" else "Fit / spine"}\nSpine anchor=$spine\nNeck anchor=$neck\nNormalized size=$normalizedWidth, $normalizedHeight, $normalizedDepth\n$cameraNote\nChest zoom=$zoom; horizontalStep=$horizontalStep; verticalStep=$verticalStep\nIdle=$idleEnabled; diagnostic background=$debugBackground\n$boundsReport\nDrag anywhere; hold still to remove. Settings are in the app.\n"
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

        private fun fail(error: Exception) { Toast.makeText(context, "Avatar overlay failed: ${error.message}", Toast.LENGTH_LONG).show(); close() }

        private fun releaseCustomResources() {
            main.removeCallbacks(holdToRemove); touching = false
            Choreographer.getInstance().removeFrameCallback(frame)
            idle = null
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
