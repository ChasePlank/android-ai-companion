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
import android.view.MotionEvent
import android.view.TextureView
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
import com.google.android.filament.android.UiHelper
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
        private val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        private val texture = TextureView(context).apply { isOpaque = false }
        private val label = TextView(context).apply {
            text = "Loading Roxanne… · drag here"
            setTextColor(0xffffffff.toInt()); textSize = 12f; gravity = Gravity.CENTER_VERTICAL
        }
        private val metrics get() = context.resources.displayMetrics
        private fun dp(value: Int) = (value * metrics.density).roundToInt()
        private val width = minOf(dp(240), metrics.widthPixels)
        private val height = minOf(dp(340), metrics.heightPixels)
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
        private var ambient: IndirectLight? = null
        private var source: ByteBuffer? = null
        private var added = false
        private var closed = false
        private var loaded = false
        private var ready = false
        private var lastFrame = 0L
        private var normalizedHeight = 2.0
        private val frame = object : Choreographer.FrameCallback {
            override fun doFrame(time: Long) {
                if (closed) return
                try {
                    val v = viewer
                    if (v != null && power.isInteractive && texture.display?.state == Display.STATE_ON &&
                        (lastFrame == 0L || time - lastFrame >= 33_000_000L)) {
                        lastFrame = time
                        if (texture.width > 0 && texture.height > 0) frameCamera(v)
                        if (ready) v.animator?.updateBoneMatrices()
                        v.render(time)
                        if (loaded && !ready && v.progress >= 1f) {
                            ready = true
                            val jawSet = applyRestingJaw(v)
                            v.animator?.updateBoneMatrices()
                            label.text = if (jawSet) "Roxanne · drag here" else "Jaw missing · drag here"
                        }
                    }
                } catch (error: Exception) {
                    Toast.makeText(context, "Avatar render failed: ${error.message}", Toast.LENGTH_LONG).show()
                    close()
                    return
                }
                Choreographer.getInstance().postFrameCallback(this)
            }
        }

        fun show() {
            val bar = LinearLayout(context).apply { setBackgroundColor(0xcc20242c.toInt()); gravity = Gravity.CENTER_VERTICAL }
            bar.addView(label, LinearLayout.LayoutParams(0, dp(40), 1f))
            bar.addView(Button(context).apply {
                text = "×"; contentDescription = "Close floating Roxanne"
                setOnClickListener { close() }
            }, LinearLayout.LayoutParams(dp(48), dp(40)))
            root.addView(bar, LinearLayout.LayoutParams(-1, dp(40)))
            root.addView(FrameLayout(context).apply {
                clipChildren = true
                addView(texture, FrameLayout.LayoutParams(-1, -1))
            }, LinearLayout.LayoutParams(-1, 0, 1f))
            label.setOnTouchListener(object : View.OnTouchListener {
                private var startX = 0f; private var startY = 0f
                private var oldX = 0; private var oldY = 0
                override fun onTouch(view: View, event: MotionEvent): Boolean {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            startX = event.rawX; startY = event.rawY; oldX = position.x; oldY = position.y
                            return true
                        }
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
            // Run our cleanup listener before ModelViewer's engine-destroying detach listener.
            texture.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {}
                override fun onViewDetachedFromWindow(view: View) {
                    releaseCustomResources()
                    closed = true; added = false
                    if (session === this@Session) session = null
                }
            })
            wm.addView(root, position)
            added = true
            texture.post { if (!closed && texture.isAttachedToWindow) initializeRenderer() }
        }

        private fun initializeRenderer() {
            try {
                Utils.init()
                val helper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK).apply { setOpaque(false) }
                val v = ModelViewer(textureView = texture, uiHelper = helper, manipulator = null)
                viewer = v
                v.autoPlayAnimations = false
                v.view.blendMode = com.google.android.filament.View.BlendMode.TRANSLUCENT
                v.renderer.clearOptions = Renderer.ClearOptions().apply {
                    clear = true; discard = true; clearColor = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
                }
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
                                    source = buffer
                                    v.loadModelGlb(buffer)
                                    val asset = v.asset ?: error("Filament could not load bundled Roxanne")
                                    val half = asset.boundingBox.halfExtent
                                    val maxExtent = 2f * maxOf(half[0], half[1], half[2])
                                    require(maxExtent.isFinite() && maxExtent > 0f) { "Invalid model bounds" }
                                    normalizedHeight = (4f * half[1] / maxExtent).toDouble()
                                    require(normalizedHeight.isFinite() && normalizedHeight > 0.0) { "Invalid model height" }
                                    v.transformToUnitCube()
                                    loaded = true
                                    label.text = "Loading textures… · drag here"
                                } catch (error: Exception) { fail(error) }
                            }
                        }
                    } catch (error: Exception) { main.post { if (!closed) fail(error) } }
                }, "read-floating-roxanne").start()
            } catch (error: Exception) { fail(error) }
        }

        private fun frameCamera(v: ModelViewer) {
            val aspect = texture.width.toDouble() / texture.height.toDouble()
            // Initial chest-up crop from normalized full-model bounds; phone tuning pending.
            val targetY = normalizedHeight * 0.31
            val halfHeight = normalizedHeight * 0.24
            val halfWidth = normalizedHeight * 0.25
            val tangent = tan(Math.toRadians(35.0 / 2.0))
            val distance = max(halfHeight / tangent, halfWidth / (aspect * tangent)) * 1.08
            v.camera.setProjection(35.0, aspect, 0.05, 1000.0, Camera.Fov.VERTICAL)
            v.camera.lookAt(0.0, targetY, -4.0 + distance, 0.0, targetY, -4.0, 0.0, 1.0, 0.0)
        }

        private fun applyRestingJaw(v: ModelViewer): Boolean {
            val asset = v.asset ?: return false
            val tm = v.engine.transformManager
            val jaw = asset.entities.firstOrNull { asset.getName(it) == FacialCalibration.JAW } ?: return false
            val instance = tm.getInstance(jaw)
            if (instance == 0) return false
            val base = FloatArray(16).also { tm.getTransform(instance, it) }
            val rotation = FloatArray(16)
            val result = FloatArray(16)
            Matrix.setRotateM(rotation, 0, FacialCalibration.JAW_CLOSED, 0f, 1f, 0f)
            Matrix.multiplyMM(result, 0, base, 0, rotation, 0)
            tm.setTransform(instance, result)
            return true
        }

        private fun fail(error: Exception) {
            Toast.makeText(context, "Avatar overlay failed: ${error.message}", Toast.LENGTH_LONG).show()
            close()
        }

        private fun releaseCustomResources() {
            Choreographer.getInstance().removeFrameCallback(frame)
            viewer?.let { v ->
                v.scene.indirectLight = null
                ambient?.let { v.engine.destroyIndirectLight(it) }
            }
            ambient = null; source = null; viewer = null; loaded = false; ready = false
        }

        fun close() {
            if (closed) return
            closed = true
            releaseCustomResources()
            if (added) {
                added = false
                try { wm.removeViewImmediate(root) } catch (_: IllegalArgumentException) {}
            }
            if (session === this) session = null
        }
    }
}
