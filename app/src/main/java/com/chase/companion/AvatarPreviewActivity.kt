package com.chase.companion

import android.app.Activity
import android.content.Intent
import android.opengl.Matrix
import android.os.Bundle
import android.view.Choreographer
import android.view.SurfaceView
import android.view.View
import android.widget.*
import com.google.android.filament.IndirectLight
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AvatarPreviewActivity : Activity() {
    private var viewer: ModelViewer? = null
    private var ambient: IndirectLight? = null
    private lateinit var status: TextView
    private lateinit var joints: Spinner
    private lateinit var axis: Spinner
    private lateinit var slider: SeekBar
    private val entities = mutableMapOf<String, Int>()
    private val original = mutableMapOf<Int, FloatArray>()
    private var resumed = false
    private var closed = false
    private var loaded = false
    private var readyReported = false
    private val jointNames = listOf("Jaw_jnt_07", "Head_jnt_06", "L_UpperEyelid_jnt_010", "L_LowerEyelid_jnt_011", "R_UpperEyelid_jnt_014", "R_LowerEyelid_jnt_015", "L_Eye_jnt_09", "R_Eye_jnt_013")
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(time: Long) {
            if (!resumed || closed) return
            try {
                viewer?.let { v ->
                    if (loaded) v.animator?.updateBoneMatrices()
                    v.render(time)
                    if (loaded && !readyReported && v.progress >= 1f) {
                        readyReported = true
                        status.text = "Resources loaded. Check appearance; drag to orbit, pinch to zoom. Bone axes are uncalibrated."
                    }
                }
            } catch (error: Exception) {
                status.text = "Render error: ${error.message}"
                resumed = false
                return
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 48, 16, 24) }
        root.addView(TextView(this).apply { text = "Roxanne — 3D preview"; textSize = 22f })
        val navigation = LinearLayout(this)
        navigation.addView(Button(this).apply { text = "Qwen chat"; setOnClickListener { startActivity(Intent(this@AvatarPreviewActivity, LocalChatActivity::class.java)) } }, LinearLayout.LayoutParams(0, -2, 1f))
        navigation.addView(Button(this).apply { text = "Overlay controls"; setOnClickListener { startActivity(Intent(this@AvatarPreviewActivity, MainActivity::class.java)) } }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(navigation)
        status = TextView(this).apply { text = "Loading bundled Roxanne model…" }
        root.addView(status)
        val surface = SurfaceView(this)
        root.addView(surface, LinearLayout.LayoutParams(-1, 0, 1f))
        joints = Spinner(this).apply { adapter = ArrayAdapter(this@AvatarPreviewActivity, android.R.layout.simple_spinner_dropdown_item, jointNames) }
        root.addView(joints)
        axis = Spinner(this).apply { adapter = ArrayAdapter(this@AvatarPreviewActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Local X axis", "Local Y axis", "Local Z axis")) }
        root.addView(axis)
        root.addView(TextView(this).apply { text = "Bone test: −20° to +20°; center is original pose. Select bone/axis, then move slider." })
        slider = SeekBar(this).apply { max = 40; progress = 20 }
        root.addView(slider)
        root.addView(Button(this).apply { text = "Reset pose and camera"; setOnClickListener { reset() } })
        root.addView(TextView(this).apply { text = "Model upload: JetteBoy / Sketchfab · CC-BY-4.0 label. Source and rights notes: AVATAR_PREVIEW.md."; textSize = 11f })
        setContentView(root)

        surface.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {}
            override fun onViewDetachedFromWindow(view: View) {
                resumed = false
                Choreographer.getInstance().removeFrameCallback(frame)
                viewer?.let { v ->
                    v.scene.indirectLight = null
                    ambient?.let { v.engine.destroyIndirectLight(it) }
                }
                ambient = null
                viewer = null
                // ModelViewer's later detach listener owns engine/resource destruction.
            }
        })
        try {
            Utils.init()
            val v = ModelViewer(surface)
            viewer = v
            v.autoPlayAnimations = false
            surface.setOnTouchListener(v)
            val lightManager = v.engine.lightManager
            lightManager.setDirection(lightManager.getInstance(v.light), -0.4f, -0.7f, -1f)
            ambient = IndirectLight.Builder().irradiance(1, floatArrayOf(0.7f, 0.7f, 0.7f)).intensity(30000f).build(v.engine)
            v.scene.indirectLight = ambient
            Thread({
                try {
                    val bytes = assets.open("roxanne_wolf_security_breach.glb").use { it.readBytes() }
                    val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
                    buffer.put(bytes).flip()
                    runOnUiThread {
                        if (!closed && viewer === v) {
                            try {
                                v.loadModelGlb(buffer)
                                val asset = v.asset ?: error("Filament could not load the GLB")
                                v.transformToUnitCube()
                                val tm = v.engine.transformManager
                                for (entity in asset.entities) {
                                    val name = asset.getName(entity) ?: continue
                                    if (name in jointNames) {
                                        val instance = tm.getInstance(entity)
                                        if (instance != 0) {
                                            entities[name] = entity
                                            val base = FloatArray(16)
                                            tm.getTransform(instance, base)
                                            original[entity] = base
                                        }
                                    }
                                }
                                loaded = true
                                status.text = "Geometry loaded; textures loading. Found ${entities.size}/${jointNames.size} test joints."
                            } catch (error: Exception) { status.text = "Model load error: ${error.message}" }
                        }
                    }
                } catch (error: Exception) { runOnUiThread { if (!closed) status.text = "Asset read error: ${error.message}" } }
            }, "read-roxanne-asset").start()
        } catch (error: Exception) { status.text = "Renderer setup error: ${error.message}" }
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) { if (fromUser) pose((value - 20).toFloat()) }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })
    }

    private fun pose(degrees: Float) {
        val v = viewer ?: return
        val name = joints.selectedItem as? String ?: return
        val entity = entities[name] ?: run { status.text = "Joint not available: $name"; return }
        val base = original[entity] ?: return
        val rotation = FloatArray(16)
        val result = FloatArray(16)
        Matrix.setRotateM(rotation, 0, degrees, if (axis.selectedItemPosition == 0) 1f else 0f, if (axis.selectedItemPosition == 1) 1f else 0f, if (axis.selectedItemPosition == 2) 1f else 0f)
        Matrix.multiplyMM(result, 0, base, 0, rotation, 0)
        val tm = v.engine.transformManager
        tm.setTransform(tm.getInstance(entity), result)
        v.animator?.updateBoneMatrices()
        status.text = "$name: $degrees degrees. Reset if movement looks wrong."
    }

    private fun reset() {
        val v = viewer ?: return
        val tm = v.engine.transformManager
        for ((entity, base) in original) tm.setTransform(tm.getInstance(entity), base)
        slider.progress = 20
        v.resetToDefaultState()
        status.text = "Original pose and camera restored"
    }
    override fun onResume() { super.onResume(); resumed = true; Choreographer.getInstance().removeFrameCallback(frame); Choreographer.getInstance().postFrameCallback(frame) }
    override fun onPause() { resumed = false; Choreographer.getInstance().removeFrameCallback(frame); super.onPause() }
    override fun onDestroy() { closed = true; resumed = false; Choreographer.getInstance().removeFrameCallback(frame); super.onDestroy() }
}
