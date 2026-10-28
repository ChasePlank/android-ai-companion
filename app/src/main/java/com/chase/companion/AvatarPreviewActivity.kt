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
import com.google.android.filament.Renderer
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

class AvatarPreviewActivity : Activity() {
    private val jawName = "Jaw_jnt_07"
    private val jawClosedDegrees = -8f
    private val jawOpenDegrees = 20f
    private var viewer: ModelViewer? = null
    private var ambient: IndirectLight? = null
    private var source: ByteBuffer? = null
    private lateinit var status: TextView
    private lateinit var inspection: TextView
    private lateinit var clips: Spinner
    private lateinit var play: Button
    private lateinit var stop: Button
    private lateinit var joints: Spinner
    private lateinit var axis: Spinner
    private lateinit var slider: SeekBar
    private val entities = mutableMapOf<String, Int>()
    private val original = mutableMapOf<Int, FloatArray>()
    private var resumed = false
    private var closed = false
    private var loaded = false
    private var readyReported = false
    private var playing = false
    private var activeClip = -1
    private var elapsed = 0.0
    private var lastFrame = 0L
    private var modelReport = "Inspection pending"
    private val jointNames = listOf("Jaw_jnt_07", "Head_jnt_06", "L_UpperEyelid_jnt_010", "L_LowerEyelid_jnt_011", "R_UpperEyelid_jnt_014", "R_LowerEyelid_jnt_015", "L_Eye_jnt_09", "R_Eye_jnt_013")
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(time: Long) {
            if (!resumed || closed) return
            try {
                viewer?.let { v ->
                    if (loaded && readyReported) {
                        val animator = v.animator
                        if (playing && animator != null && activeClip in 0 until animator.animationCount) {
                            if (lastFrame != 0L) elapsed += (time - lastFrame).toDouble() / 1_000_000_000.0
                            lastFrame = time
                            val duration = animator.getAnimationDuration(activeClip)
                            val seconds = if (duration.isFinite() && duration > 0f) (elapsed % duration.toDouble()).toFloat() else 0f
                            animator.applyAnimation(activeClip, seconds)
                        } else lastFrame = 0L
                        animator?.updateBoneMatrices()
                    }
                    v.render(time)
                    if (loaded && !readyReported && v.progress >= 1f) {
                        readyReported = true
                        val count = v.animator?.animationCount ?: 0
                        clips.isEnabled = count > 0
                        play.isEnabled = count > 0
                        stop.isEnabled = true
                        slider.isEnabled = true
                        applyJawOpenness(0f)
                        v.animator?.updateBoneMatrices()
                        status.text = if (count > 0) "Ready. Jaw resting at -8° local Y. Select a clip and tap Play; drag to orbit, pinch to zoom." else "Ready. Jaw resting at -8° local Y. No embedded animation clips; manual joint tests remain available."
                    }
                }
            } catch (error: Exception) {
                playing = false
                play.text = "Play"
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
        root.addView(TextView(this).apply { text = "Roxanne — animation inspection"; textSize = 22f })
        val navigation = LinearLayout(this)
        navigation.addView(Button(this).apply { text = "Qwen chat"; setOnClickListener { startActivity(Intent(this@AvatarPreviewActivity, LocalChatActivity::class.java)) } }, LinearLayout.LayoutParams(0, -2, 1f))
        navigation.addView(Button(this).apply { text = "Overlay controls"; setOnClickListener { startActivity(Intent(this@AvatarPreviewActivity, MainActivity::class.java)) } }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(navigation)
        status = TextView(this).apply { text = "Loading bundled Roxanne model…" }
        root.addView(status)
        val surface = SurfaceView(this)
        root.addView(surface, LinearLayout.LayoutParams(-1, 0, 1f))
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        inspection = TextView(this).apply { text = "Inspecting model…"; setTextIsSelectable(true) }
        panel.addView(inspection)
        clips = Spinner(this).apply { isEnabled = false }
        panel.addView(clips)
        val playback = LinearLayout(this)
        play = Button(this).apply { text = "Play"; isEnabled = false; setOnClickListener { togglePlayback() } }
        stop = Button(this).apply { text = "Stop / calibrated rest"; isEnabled = false; setOnClickListener { viewer?.let { loadModel(it) } } }
        playback.addView(play, LinearLayout.LayoutParams(0, -2, 1f))
        playback.addView(stop, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(playback)
        joints = Spinner(this).apply { adapter = ArrayAdapter(this@AvatarPreviewActivity, android.R.layout.simple_spinner_dropdown_item, jointNames) }
        panel.addView(joints)
        axis = Spinner(this).apply { adapter = ArrayAdapter(this@AvatarPreviewActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Local X axis", "Local Y axis", "Local Z axis")); setSelection(1) }
        panel.addView(axis)
        panel.addView(TextView(this).apply { text = "Manual bone test: −90° to +90° relative to original pose. Jaw calibration: local Y −8° closed, +20° open. Stop restores calibrated rest. Speech animation is not connected yet." })
        slider = SeekBar(this).apply { max = 180; progress = 82; isEnabled = false }
        panel.addView(slider)
        val jawControls = LinearLayout(this)
        jawControls.addView(Button(this).apply { text = "Jaw closed (−8°)"; setOnClickListener { selectJawEndpoint(0f) } }, LinearLayout.LayoutParams(0, -2, 1f))
        jawControls.addView(Button(this).apply { text = "Jaw open (+20°)"; setOnClickListener { selectJawEndpoint(1f) } }, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(jawControls)
        panel.addView(Button(this).apply { text = "Reset pose and camera"; setOnClickListener { viewer?.let { it.resetToDefaultState(); loadModel(it) } } })
        panel.addView(TextView(this).apply { text = "Model upload: JetteBoy / Sketchfab · CC-BY-4.0 label. Source and rights notes: AVATAR_PREVIEW.md."; textSize = 11f })
        root.addView(ScrollView(this).apply { addView(panel) }, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.34f).toInt()))
        setContentView(root)
        surface.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {}
            override fun onViewDetachedFromWindow(view: View) {
                resumed = false
                playing = false
                Choreographer.getInstance().removeFrameCallback(frame)
                viewer?.let { v -> v.scene.indirectLight = null; ambient?.let { v.engine.destroyIndirectLight(it) } }
                ambient = null
                viewer = null
                source = null
                // ModelViewer's later detach listener owns engine/resource destruction.
            }
        })
        try {
            Utils.init()
            val v = ModelViewer(surface)
            viewer = v
            v.renderer.clearOptions = Renderer.ClearOptions().apply {
                clear = true
                discard = true
                clearColor = doubleArrayOf(0.0, 0.0, 0.0, 1.0)
            }
            v.autoPlayAnimations = false
            surface.setOnTouchListener(v)
            val lights = v.engine.lightManager
            lights.setDirection(lights.getInstance(v.light), -0.4f, -0.7f, -1f)
            ambient = IndirectLight.Builder().irradiance(1, floatArrayOf(0.7f, 0.7f, 0.7f)).intensity(30000f).build(v.engine)
            v.scene.indirectLight = ambient
            Thread({
                try {
                    val bytes = assets.open("roxanne_wolf_security_breach.glb").use { it.readBytes() }
                    val report = runCatching { inspectGlb(bytes) }.getOrElse { "GLB inspection unavailable: ${it.message}" }
                    val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
                    buffer.put(bytes).flip()
                    runOnUiThread {
                        if (!closed && viewer === v) {
                            source = buffer
                            modelReport = report
                            loadModel(v)
                        }
                    }
                } catch (error: Exception) { runOnUiThread { if (!closed) status.text = "Asset read error: ${error.message}" } }
            }, "read-roxanne-asset").start()
        } catch (error: Exception) { status.text = "Renderer setup error: ${error.message}" }
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) { if (fromUser) pose((value - 90).toFloat()) }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })
    }

    private fun loadModel(v: ModelViewer) {
        val buffer = source ?: return
        playing = false
        activeClip = -1
        elapsed = 0.0
        lastFrame = 0L
        loaded = false
        readyReported = false
        play.text = "Play"
        play.isEnabled = false
        stop.isEnabled = false
        clips.isEnabled = false
        slider.isEnabled = false
        joints.setSelection(0)
        axis.setSelection(1)
        slider.progress = 82
        entities.clear()
        original.clear()
        try {
            v.loadModelGlb(buffer.duplicate().apply { rewind() })
            val asset = v.asset ?: error("Filament could not load the GLB")
            v.transformToUnitCube()
            val tm = v.engine.transformManager
            for (entity in asset.entities) {
                val name = asset.getName(entity) ?: continue
                if (name in jointNames && tm.getInstance(entity) != 0) {
                    entities[name] = entity
                    original[entity] = FloatArray(16).also { tm.getTransform(tm.getInstance(entity), it) }
                }
            }
            val animator = v.animator
            val count = animator?.animationCount ?: 0
            val labels = (0 until count).map { index ->
                val name = animator!!.getAnimationName(index).ifBlank { "Unnamed clip ${index + 1}" }
                "${index + 1}. $name — ${String.format(Locale.US, "%.2f", animator.getAnimationDuration(index))} s"
            }
            clips.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, if (labels.isEmpty()) listOf("No embedded animation clips") else labels)
            inspection.text = "$modelReport\nFilament clips: $count\n${labels.joinToString("\n")}\nTest joints found: ${entities.size}/${jointNames.size}\nMorph targets are not automatically classified as facial controls."
            loaded = true
            status.text = "Geometry loaded; textures loading. Calibrated jaw rest will apply when ready."
        } catch (error: Exception) { status.text = "Model load error: ${error.message}" }
    }

    private fun togglePlayback() {
        val v = viewer ?: return
        val animator = v.animator ?: return
        val index = clips.selectedItemPosition
        if (!readyReported || index !in 0 until animator.animationCount) return
        if (activeClip != index && activeClip >= 0) {
            loadModel(v)
            clips.setSelection(index)
            status.text = "Calibrated rest restored for clip change. Wait for loading, then tap Play."
            return
        }
        if (activeClip != index) { activeClip = index; elapsed = 0.0 }
        playing = !playing
        lastFrame = 0L
        play.text = if (playing) "Pause" else "Play"
        status.text = if (playing) "Looping clip ${index + 1}. Stop restores calibrated rest." else "Animation paused at its current pose."
    }

    private fun applyJointRotation(name: String, degrees: Float, axisIndex: Int): Boolean {
        val v = viewer ?: return false
        val entity = entities[name] ?: return false
        val base = original[entity] ?: return false
        val rotation = FloatArray(16)
        val result = FloatArray(16)
        Matrix.setRotateM(rotation, 0, degrees, if (axisIndex == 0) 1f else 0f, if (axisIndex == 1) 1f else 0f, if (axisIndex == 2) 1f else 0f)
        Matrix.multiplyMM(result, 0, base, 0, rotation, 0)
        v.engine.transformManager.let { it.setTransform(it.getInstance(entity), result) }
        return true
    }

    private fun applyJawOpenness(openness: Float): Boolean {
        if (!openness.isFinite()) return false
        val degrees = jawClosedDegrees + (jawOpenDegrees - jawClosedDegrees) * openness.coerceIn(0f, 1f)
        return applyJointRotation(jawName, degrees, 1)
    }

    private fun selectJawEndpoint(openness: Float) {
        if (!readyReported) return
        playing = false
        lastFrame = 0L
        play.text = "Play"
        joints.setSelection(0)
        axis.setSelection(1)
        val degrees = if (openness == 0f) jawClosedDegrees else jawOpenDegrees
        slider.progress = degrees.toInt() + 90
        if (applyJawOpenness(openness)) {
            viewer?.animator?.updateBoneMatrices()
            status.text = "$jawName: $degrees degrees on local Y. Speech animation is not connected yet."
        } else status.text = "Joint not available: $jawName"
    }

    private fun pose(degrees: Float) {
        if (!readyReported) return
        playing = false
        lastFrame = 0L
        play.text = "Play"
        val name = joints.selectedItem as? String ?: return
        if (!applyJointRotation(name, degrees, axis.selectedItemPosition)) {
            status.text = "Joint not available: $name"
            return
        }
        viewer?.animator?.updateBoneMatrices()
        status.text = "$name: $degrees degrees. Playback paused; Stop restores calibrated rest."
    }

    private fun inspectGlb(bytes: ByteArray): String {
        require(bytes.size >= 20) { "GLB header too short" }
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(header.int == 0x46546C67 && header.int == 2) { "Expected GLB version 2" }
        require(header.int == bytes.size) { "GLB length mismatch" }
        val length = header.int
        require(header.int == 0x4E4F534A && length >= 0 && length <= header.remaining()) { "Invalid GLB JSON chunk" }
        val json = JSONObject(String(bytes, 20, length, Charsets.UTF_8).trimEnd('\u0000', ' '))
        val nodes = json.optJSONArray("nodes")
        val skins = json.optJSONArray("skins")
        val skinJoints = mutableSetOf<Int>()
        for (i in 0 until (skins?.length() ?: 0)) {
            val list = skins!!.getJSONObject(i).optJSONArray("joints") ?: continue
            for (j in 0 until list.length()) skinJoints.add(list.getInt(j))
        }
        val meshes = json.optJSONArray("meshes")
        var morphMeshes = 0
        var targetSlots = 0
        val targetNames = mutableListOf<String>()
        for (i in 0 until (meshes?.length() ?: 0)) {
            val mesh = meshes!!.getJSONObject(i)
            val primitives = mesh.optJSONArray("primitives")
            var count = 0
            for (p in 0 until (primitives?.length() ?: 0)) count = maxOf(count, primitives!!.getJSONObject(p).optJSONArray("targets")?.length() ?: 0)
            if (count > 0) { morphMeshes++; targetSlots += count }
            val names = mesh.optJSONObject("extras")?.optJSONArray("targetNames")
            for (n in 0 until (names?.length() ?: 0)) targetNames.add(names!!.optString(n))
        }
        val sampleJoints = skinJoints.take(12).map { nodes?.optJSONObject(it)?.optString("name")?.ifBlank { "Node $it" } ?: "Node $it" }
        return "GLB nodes: ${nodes?.length() ?: 0}\nSkins: ${skins?.length() ?: 0}; distinct skin joints: ${skinJoints.size}\nJoint sample: ${sampleJoints.joinToString(", ")}\nMeshes with morph targets: $morphMeshes; target slots summed per mesh: $targetSlots\nMorph-name sample: ${targetNames.distinct().take(16).joinToString(", ").ifBlank { "No names supplied" }}"
    }

    override fun onResume() { super.onResume(); resumed = true; lastFrame = 0L; Choreographer.getInstance().removeFrameCallback(frame); Choreographer.getInstance().postFrameCallback(frame) }
    override fun onPause() { resumed = false; lastFrame = 0L; Choreographer.getInstance().removeFrameCallback(frame); super.onPause() }
    override fun onDestroy() { closed = true; resumed = false; playing = false; source = null; Choreographer.getInstance().removeFrameCallback(frame); super.onDestroy() }
}
