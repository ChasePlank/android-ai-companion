package com.chase.companion

import android.app.Activity
import android.content.Intent
import android.opengl.Matrix
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Choreographer
import android.view.SurfaceView
import android.view.View
import android.widget.*
import com.google.android.filament.IndirectLight
import com.google.android.filament.Renderer
import com.google.android.filament.utils.Manipulator
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
    private var keytarEntity: Int? = null
    private var keytarVisible = false
    private lateinit var keytarButton: Button
    private var cameraControls: Manipulator? = null
    private var centerNote = "Upper-torso focus pending."
    private var ambient: IndirectLight? = null
    private var source: ByteBuffer? = null
    private lateinit var status: TextView
    private lateinit var inspection: TextView
    private lateinit var clips: Spinner
    private lateinit var play: Button
    private lateinit var stop: Button
    private lateinit var joints: Spinner
    private lateinit var search: EditText
    private lateinit var matches: TextView
    private lateinit var axis: Spinner
    private lateinit var slider: SeekBar
    private lateinit var angles: TextView
    private val entities = mutableMapOf<String, Int>()
    private val original = mutableMapOf<Int, FloatArray>()
    private val offsets = mutableMapOf<Int, FloatArray>()
    private val jointNames = mutableListOf<String>()
    private val visibleJointNames = mutableListOf<String>()
    private var jawLabel: String? = null
    private var resumed = false
    private var closed = false
    private var loaded = false
    private var readyReported = false
    private var playing = false
    private var activeClip = -1
    private var elapsed = 0.0
    private var lastFrame = 0L
    private var modelReport = "Inspection pending"
    private val faceNames = listOf("Jaw_jnt_07", "Head_jnt_06", "L_UpperEyelid_jnt_010", "L_LowerEyelid_jnt_011", "R_UpperEyelid_jnt_014", "R_LowerEyelid_jnt_015", "L_Eye_jnt_09", "R_Eye_jnt_013")
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
                        search.isEnabled = true
                        keytarButton.isEnabled = applyKeytarVisibility(v)
                        updateNodeFilter()
                        val jawSet = applyJawOpenness(0f)
                        v.animator?.updateBoneMatrices()
                        syncControls()
                        val keytarNote = if (keytarEntity == null) "Keytar mesh unavailable; visibility unchanged." else if (keytarVisible) "Keytar shown." else "Keytar hidden."
                        status.text = "Ready. ${jointNames.size} transform nodes. $centerNote $keytarNote Shoulders rest at -72° X; TailB at -90° X. " + if (jawSet) "Jaw resting at -8° local Y. Drag: orbit; two fingers: pan; pinch: zoom." else "Jaw not found. Drag: orbit; two fingers: pan; pinch: zoom."
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
        keytarVisible = getSharedPreferences("preview_options", MODE_PRIVATE).getBoolean("keytar_visible", false)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 48, 16, 24) }
        root.addView(TextView(this).apply { text = "Roxanne — full rig inspection"; textSize = 22f })
        val navigation = LinearLayout(this)
        navigation.addView(Button(this).apply { text = "Qwen chat"; setOnClickListener { startActivity(Intent(this@AvatarPreviewActivity, LocalChatActivity::class.java)) } }, LinearLayout.LayoutParams(0, -2, 1f))
        navigation.addView(Button(this).apply { text = "Overlay controls"; setOnClickListener { startActivity(Intent(this@AvatarPreviewActivity, MainActivity::class.java)) } }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(navigation)
        status = TextView(this).apply { text = "Loading bundled Roxanne model…" }
        root.addView(status)
        val surface = SurfaceView(this)
        root.addView(surface, LinearLayout.LayoutParams(-1, 0, 1f))
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val diagnostics = LinearLayout(this)
        diagnostics.addView(Button(this).apply { text = "Voice test"; setOnClickListener { startActivity(Intent(this@AvatarPreviewActivity, VoiceTestActivity::class.java)) } }, LinearLayout.LayoutParams(0, -2, 1f))
        diagnostics.addView(Button(this).apply { text = "Keytar inspector"; setOnClickListener { startActivity(Intent(this@AvatarPreviewActivity, KeytarInspectionActivity::class.java)) } }, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(diagnostics)
        keytarButton = Button(this).apply {
            text = if (keytarVisible) "Hide keytar" else "Show keytar"
            isEnabled = false
            setOnClickListener {
                val v = viewer
                if (v != null && readyReported && !closed) {
                    keytarVisible = !keytarVisible
                    if (applyKeytarVisibility(v)) {
                        getSharedPreferences("preview_options", MODE_PRIVATE).edit().putBoolean("keytar_visible", keytarVisible).apply()
                        status.text = if (keytarVisible) "Keytar shown. Poses and camera unchanged." else "Keytar hidden. Poses and camera unchanged."
                    } else {
                        keytarVisible = !keytarVisible
                        isEnabled = false
                        status.text = "Keytar renderable unavailable; nothing else was hidden."
                    }
                }
            }
        }
        panel.addView(keytarButton)
        panel.addView(Button(this).apply {
            text = "Recenter view (keep pose)"
            setOnClickListener {
                cameraControls?.let { it.jumpToBookmark(it.homeBookmark) }
                status.text = "Camera returned to starting view. $centerNote Joint offsets and clip playback were not changed."
            }
        })
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
        val searchRow = LinearLayout(this)
        search = EditText(this).apply {
            hint = "Filter nodes: arm, hair, spine…"
            contentDescription = "Filter transform nodes by name; spaces match all terms"
            setSingleLine(true)
            isEnabled = false
        }
        searchRow.addView(search, LinearLayout.LayoutParams(0, -2, 1f))
        searchRow.addView(Button(this).apply { text = "Clear"; setOnClickListener { search.setText("") } }, LinearLayout.LayoutParams(-2, -2))
        panel.addView(searchRow)
        matches = TextView(this).apply { text = "Transform nodes loading…" }
        panel.addView(matches)
        joints = Spinner(this).apply { isEnabled = false; adapter = ArrayAdapter(this@AvatarPreviewActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Loading transform nodes…")) }
        panel.addView(joints)
        axis = Spinner(this).apply { isEnabled = false; adapter = ArrayAdapter(this@AvatarPreviewActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Local X axis", "Local Y axis", "Local Z axis")); setSelection(1) }
        panel.addView(axis)
        panel.addView(TextView(this).apply { text = "View: one-finger orbit, two-finger pan, pinch zoom. Recenter view restores the camera without resetting poses. Upper-torso pivot is estimated below the head joint and needs visual checking. All transform nodes are searchable: bones, hair controls and possible mesh/group nodes. Search ignores case and matches every space-separated term. Names are not proof of function. Filtering never resets poses. Manual offsets: −90° to +90° per local axis; XYZ offsets combine relative to the original transform. Start small on untested nodes. Stop reloads the full rig." })
        angles = TextView(this).apply { text = "Selected node: loading"; setTextIsSelectable(true) }
        panel.addView(angles)
        slider = SeekBar(this).apply { max = 180; progress = 90; isEnabled = false }
        panel.addView(slider)
        panel.addView(Button(this).apply { text = "Reset selected node"; setOnClickListener { resetSelected() } })
        val jawControls = LinearLayout(this)
        jawControls.addView(Button(this).apply { text = "Jaw closed (−8°)"; setOnClickListener { selectJawEndpoint(0f) } }, LinearLayout.LayoutParams(0, -2, 1f))
        jawControls.addView(Button(this).apply { text = "Jaw open (+20°)"; setOnClickListener { selectJawEndpoint(1f) } }, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(jawControls)
        panel.addView(Button(this).apply { text = "Reset pose and camera"; setOnClickListener { viewer?.let { it.resetToDefaultState(); loadModel(it) } } })
        panel.addView(TextView(this).apply { text = "Model upload: JetteBoy / Sketchfab · CC-BY-4.0 label. Source and rights notes: AVATAR_PREVIEW.md."; textSize = 11f })
        root.addView(ScrollView(this).apply { addView(panel) }, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.34f).toInt()))
        setContentView(root)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updateNodeFilter() }
            override fun afterTextChanged(s: Editable?) {}
        })
        val selectionListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { syncControls() }
            override fun onNothingSelected(parent: AdapterView<*>?) { syncControls() }
        }
        joints.onItemSelectedListener = selectionListener
        axis.onItemSelectedListener = selectionListener
        surface.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {}
            override fun onViewDetachedFromWindow(view: View) {
                resumed = false
                playing = false
                Choreographer.getInstance().removeFrameCallback(frame)
                viewer?.let { v -> v.scene.indirectLight = null; ambient?.let { v.engine.destroyIndirectLight(it) } }
                ambient = null
                viewer = null
                keytarEntity = null
                keytarButton.isEnabled = false
                cameraControls = null
                source = null
                // ModelViewer's later detach listener owns engine/resource destruction.
            }
        })
        try {
            Utils.init()
            val orbit = Manipulator.Builder()
                .targetPosition(0f, 0f, -4f)
                .orbitHomePosition(0f, 0f, -2f)
                .viewport(surface.width.coerceAtLeast(1), surface.height.coerceAtLeast(1))
                .build(Manipulator.Mode.ORBIT)
            cameraControls = orbit
            val v = ModelViewer(surfaceView = surface, manipulator = orbit)
            viewer = v
            v.renderer.clearOptions = Renderer.ClearOptions().apply { clear = true; discard = true; clearColor = doubleArrayOf(0.0, 0.0, 0.0, 1.0) }
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
                    runOnUiThread { if (!closed && viewer === v) { source = buffer; modelReport = report; loadModel(v) } }
                } catch (error: Exception) { runOnUiThread { if (!closed) status.text = "Asset read error: ${error.message}" } }
            }, "read-roxanne-asset").start()
        } catch (error: Exception) { status.text = "Renderer setup error: ${error.message}" }
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) { if (fromUser) pose((value - 90).toFloat()) }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })
    }

    private fun bindKeytar(v: ModelViewer) {
        keytarEntity = null
        val asset = v.asset ?: return
        val rm = v.engine.renderableManager
        var matches = 0
        for (entity in asset.entities) {
            if (asset.getName(entity) == "Object_115" && rm.getInstance(entity) != 0) {
                keytarEntity = entity
                matches++
            }
        }
        if (matches != 1 || asset.entities.none { asset.getName(it) == "World_Prop_jnt_073" }) keytarEntity = null
        applyKeytarVisibility(v)
    }

    private fun applyKeytarVisibility(v: ModelViewer): Boolean {
        val entity = keytarEntity ?: return false
        val rm = v.engine.renderableManager
        val instance = rm.getInstance(entity)
        if (instance == 0) return false
        // Inspector verified Object_115 is a separate keytar-only renderable.
        // glTF renderables use layer 0 (mask 1); hiding changes no transforms or geometry.
        rm.setLayerMask(instance, 0xff, if (keytarVisible) 1 else 0)
        keytarButton.text = if (keytarVisible) "Hide keytar" else "Show keytar"
        return true
    }

    private fun centerUpperTorso(v: ModelViewer): String {
        val asset = v.asset ?: return "No model to center."
        val tm = v.engine.transformManager
        val head = asset.entities.firstOrNull { asset.getName(it) == "Head_jnt_06" }
            ?: return "Whole-model center: head joint missing."
        val headInstance = tm.getInstance(head)
        val rootInstance = tm.getInstance(asset.root)
        if (headInstance == 0 || rootInstance == 0) return "Whole-model center: transform missing."
        val half = asset.boundingBox.halfExtent
        val extent = 2f * maxOf(half[0], half[1], half[2])
        if (!extent.isFinite() || extent <= 0f) return "Whole-model center: invalid bounds."
        val height = 4f * half[1] / extent
        if (!height.isFinite() || height <= 0f) return "Whole-model center: invalid height."
        val headWorld = FloatArray(16)
        val rootTransform = FloatArray(16)
        tm.getWorldTransform(headInstance, headWorld)
        tm.getTransform(rootInstance, rootTransform)
        // One-time translation of the normalized asset, before manual base transforms are cached.
        // The torso estimate is 12 percent of full height below the known head joint.
        val x = headWorld[12]
        val y = headWorld[13] - height * 0.12f
        val z = headWorld[14]
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return "Whole-model center: invalid head position."
        rootTransform[12] -= x
        rootTransform[13] -= y
        rootTransform[14] += -4f - z
        tm.setTransform(rootInstance, rootTransform)
        return "Upper-torso center estimated below head joint."
    }

    private fun updateNodeFilter(preferred: String? = joints.selectedItem as? String) {
        val terms = search.text.toString().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        visibleJointNames.clear()
        visibleJointNames.addAll(jointNames.filter { label -> terms.all { label.contains(it, ignoreCase = true) } })
        val labels = if (visibleJointNames.isEmpty()) listOf(if (jointNames.isEmpty()) "No transform nodes found" else "No matching nodes — clear filter") else visibleJointNames.toList()
        joints.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        if (visibleJointNames.isNotEmpty()) joints.setSelection(visibleJointNames.indexOf(preferred).coerceAtLeast(0))
        joints.isEnabled = readyReported && visibleJointNames.isNotEmpty()
        matches.text = "Showing ${visibleJointNames.size} of ${jointNames.size} transform nodes"
        syncControls()
    }

    private fun loadModel(v: ModelViewer) {
        val buffer = source ?: return
        playing = false; activeClip = -1; elapsed = 0.0; lastFrame = 0L; loaded = false; readyReported = false
        keytarEntity = null; keytarButton.isEnabled = false
        play.text = "Play"; play.isEnabled = false; stop.isEnabled = false; clips.isEnabled = false
        slider.isEnabled = false; joints.isEnabled = false; axis.isEnabled = false; search.isEnabled = false
        entities.clear(); original.clear(); offsets.clear(); jointNames.clear(); visibleJointNames.clear(); jawLabel = null
        updateNodeFilter()
        slider.progress = 90
        try {
            v.loadModelGlb(buffer.duplicate().apply { rewind() })
            val asset = v.asset ?: error("Filament could not load the GLB")
            bindKeytar(v)
            v.transformToUnitCube()
            centerNote = centerUpperTorso(v)
            val tm = v.engine.transformManager
            val records = asset.entities.mapNotNull { entity ->
                if (tm.getInstance(entity) == 0) null else Pair(entity, asset.getName(entity)?.takeIf { it.isNotBlank() } ?: "Unnamed node")
            }.sortedWith(compareBy<Pair<Int, String>> { if (it.second in faceNames) 0 else 1 }.thenBy { it.second.lowercase(Locale.ROOT) }.thenBy { it.first })
            for ((entity, name) in records) {
                val label = "$name [entity $entity]"
                jointNames.add(label); entities[label] = entity
                original[entity] = FloatArray(16).also { tm.getTransform(tm.getInstance(entity), it) }
                offsets[entity] = RigRestDefaults.offsets(name)
                if (name == jawName && jawLabel == null) jawLabel = label
            }
            for (label in jointNames) {
                val xyz = offsets[entities[label]] ?: continue
                if (xyz.any { it != 0f }) applyNode(label)
            }
            updateNodeFilter(jawLabel)
            axis.setSelection(1)
            val animator = v.animator
            val count = animator?.animationCount ?: 0
            val labels = (0 until count).map { index ->
                val name = animator!!.getAnimationName(index).ifBlank { "Unnamed clip ${index + 1}" }
                "${index + 1}. $name — ${String.format(Locale.US, "%.2f", animator.getAnimationDuration(index))} s"
            }
            clips.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, if (labels.isEmpty()) listOf("No embedded animation clips") else labels)
            inspection.text = "$modelReport\nFilament clips: $count\n${labels.joinToString("\n")}\nSelectable transform nodes: ${jointNames.size} (includes non-bone nodes).\nMorph targets are not automatically classified as facial controls."
            loaded = true
            status.text = "Geometry loaded; textures loading. Full-rig controls will enable when ready."
        } catch (error: Exception) { status.text = "Model load error: ${error.message}" }
    }

    private fun togglePlayback() {
        val v = viewer ?: return
        val animator = v.animator ?: return
        val index = clips.selectedItemPosition
        if (!readyReported || index !in 0 until animator.animationCount) return
        if (activeClip != index && activeClip >= 0) {
            loadModel(v); clips.setSelection(index)
            status.text = "Calibrated rest restored for clip change. Wait for loading, then tap Play."
            return
        }
        if (activeClip != index) { activeClip = index; elapsed = 0.0 }
        playing = !playing; lastFrame = 0L; play.text = if (playing) "Pause" else "Play"
        status.text = if (playing) "Looping clip ${index + 1}. Manual offsets may be overwritten by the clip. Stop restores calibrated rest." else "Animation paused. Stop before clean manual calibration."
    }

    private fun applyNode(label: String): Boolean {
        val v = viewer ?: return false
        val entity = entities[label] ?: return false
        val base = original[entity] ?: return false
        val xyz = offsets[entity] ?: return false
        val rotation = FloatArray(16)
        val result = FloatArray(16)
        Matrix.setIdentityM(rotation, 0)
        Matrix.rotateM(rotation, 0, xyz[0], 1f, 0f, 0f)
        Matrix.rotateM(rotation, 0, xyz[1], 0f, 1f, 0f)
        Matrix.rotateM(rotation, 0, xyz[2], 0f, 0f, 1f)
        Matrix.multiplyMM(result, 0, base, 0, rotation, 0)
        v.engine.transformManager.let { it.setTransform(it.getInstance(entity), result) }
        return true
    }

    private fun applyJawOpenness(openness: Float): Boolean {
        if (!openness.isFinite()) return false
        val label = jawLabel ?: return false
        val entity = entities[label] ?: return false
        offsets[entity] = floatArrayOf(0f, jawClosedDegrees + (jawOpenDegrees - jawClosedDegrees) * openness.coerceIn(0f, 1f), 0f)
        return applyNode(label)
    }

    private fun pausePlayback() { playing = false; lastFrame = 0L; play.text = "Play" }

    private fun selectJawEndpoint(openness: Float) {
        if (!readyReported) return
        pausePlayback()
        val label = jawLabel ?: run { status.text = "Joint not available: $jawName"; return }
        search.setText("")
        updateNodeFilter(label)
        axis.setSelection(1)
        if (applyJawOpenness(openness)) {
            viewer?.animator?.updateBoneMatrices(); syncControls()
            status.text = "Jaw: ${if (openness == 0f) jawClosedDegrees else jawOpenDegrees} degrees on local Y."
        }
    }

    private fun syncControls() {
        if (!::slider.isInitialized || !::angles.isInitialized || !::axis.isInitialized) return
        val label = joints.selectedItem as? String
        val entity = entities[label]
        val xyz = offsets[entity]
        val editable = readyReported && xyz != null
        slider.isEnabled = editable
        axis.isEnabled = editable
        if (xyz == null) { slider.progress = 90; angles.text = "No node selected — clear or change the filter"; return }
        val selectedAxis = axis.selectedItemPosition.coerceIn(0, 2)
        slider.progress = xyz[selectedAxis].toInt() + 90
        angles.text = "$label\nManual offsets: X ${xyz[0]}° · Y ${xyz[1]}° · Z ${xyz[2]}°\nSelected axis: ${listOf("X", "Y", "Z")[selectedAxis]} = ${xyz[selectedAxis]}°"
    }

    private fun pose(degrees: Float) {
        if (!readyReported) return
        pausePlayback()
        val label = joints.selectedItem as? String ?: return
        val entity = entities[label] ?: return
        val xyz = offsets[entity] ?: return
        xyz[axis.selectedItemPosition.coerceIn(0, 2)] = degrees.coerceIn(-90f, 90f)
        if (applyNode(label)) {
            viewer?.animator?.updateBoneMatrices(); syncControls()
            status.text = "Manual node pose applied. Stop restores the full rig; Reset selected affects this node only."
        }
    }

    private fun resetSelected() {
        if (!readyReported) return
        pausePlayback()
        val label = joints.selectedItem as? String ?: return
        val entity = entities[label] ?: return
        offsets[entity] = RigRestDefaults.offsets(viewer?.asset?.getName(entity))
        if (applyNode(label)) { viewer?.animator?.updateBoneMatrices(); syncControls(); status.text = "Selected node reset to calibrated rest; other manual offsets preserved." }
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
