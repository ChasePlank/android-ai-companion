package com.chase.companion

import android.app.Activity
import android.content.Intent
import android.opengl.Matrix
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.*
import com.google.android.filament.IndirectLight
import com.google.android.filament.Renderer
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ModelNodeActivity : Activity() {
    private val models = listOf(
        "roxanne_wolf_security_breach.glb", "Roxanne_Wolf_Toggle.glb",
        "Roxanne_Wolf_Toggle_fix.glb", "classic_fexa.glb",
        "fexa_blush_alt_nude.glb", "loona.glb", "shameless_loona_pose.glb")
    private var viewer: ModelViewer? = null
    private var light: IndirectLight? = null
    private var source: ByteBuffer? = null
    private var generation = 0
    private var alive = true
    private var resumed = false
    private var loading = true
    private var ready = false
    private var selectedModel = -1
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var filter: EditText
    private lateinit var nodes: Spinner
    private lateinit var axes: Spinner
    private lateinit var slider: SeekBar
    private lateinit var brightness: SeekBar
    private lateinit var lightingNote: TextView
    private var originalKeyIntensity = 100000f
    private val originalKeyDirection = FloatArray(3)
    private val lightingLevels = mutableMapOf<String, Int>()
    private fun defaultLighting(name: String) = if (name == "loona.glb" || name == "classic_fexa.glb") 30 else 0
    private fun applyLighting() {
        val v = viewer ?: return
        val name = models.getOrNull(selectedModel) ?: return
        val level = lightingLevels[name] ?: defaultLighting(name)
        val multiplier = 1f + level / 10f
        light?.setIntensity(30000f * multiplier)
        val lm = v.engine.lightManager
        val instance = lm.getInstance(v.light)
        if (instance != 0) {
            lm.setIntensity(instance, originalKeyIntensity * multiplier)
            if (name == "loona.glb" || name == "classic_fexa.glb") {
                lm.setDirection(instance, 0f, -0.3f, -1f)
            } else {
                lm.setDirection(instance, originalKeyDirection[0], originalKeyDirection[1], originalKeyDirection[2])
            }
        }
        lightingNote.text = "Inspection lighting: ${multiplier}x. Brightness changes no nodes or materials; settings last for this screen session."
    }
    private val entities = linkedMapOf<String, Int>()
    private val bases = mutableMapOf<Int, FloatArray>()
    private val offsets = mutableMapOf<Int, FloatArray>()
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(time: Long) {
            if (!alive || !resumed) return
            try {
                viewer?.let { v ->
                    v.animator?.updateBoneMatrices()
                    v.render(time)
                    if (loading && v.asset != null && v.progress >= 1f) {
                        loading = false; ready = true
                        val count = v.animator?.animationCount ?: 0
                        status.text = "${models[selectedModel]}: ${entities.size} selectable transforms; $count clips. Authored rest pose; no Roxanne calibration applied."
                        refreshNodes()
                    }
                }
            } catch (e: Exception) {
                ready = false; loading = false
                status.text = "Render error: ${e.message}. Select another model to retry."
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(12, 40, 12, 16) }
        root.addView(TextView(this).apply { text = "Alongside — model node inspector"; textSize = 20f })
        val selector = Spinner(this).apply { adapter = ArrayAdapter(this@ModelNodeActivity, android.R.layout.simple_spinner_dropdown_item, models) }
        root.addView(selector)
        root.addView(Button(this).apply { text = "Original calibrated Roxanne / chat / overlay controls"; setOnClickListener { startActivity(Intent(this@ModelNodeActivity, AvatarPreviewActivity::class.java)) } })
        status = TextView(this).apply { text = "Preparing renderer…" }; root.addView(status)
        val surface = SurfaceView(this); root.addView(surface, LinearLayout.LayoutParams(-1, 0, 1f))
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lightingNote = TextView(this).apply { text = "Inspection lighting" }; panel.addView(lightingNote)
        brightness = SeekBar(this).apply { max = 150; progress = 0 }; panel.addView(brightness)
        brightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser && alive && selectedModel in models.indices) {
                    lightingLevels[models[selectedModel]] = value
                    applyLighting()
                }
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })
        panel.addView(Button(this).apply { text = "Reset lighting for this model"; setOnClickListener {
            models.getOrNull(selectedModel)?.let { name ->
                lightingLevels.remove(name); brightness.progress = defaultLighting(name); applyLighting()
            }
        } })
        filter = EditText(this).apply { hint = "Filter nodes by name"; setSingleLine(true) }; panel.addView(filter)
        nodes = Spinner(this); panel.addView(nodes)
        axes = Spinner(this).apply { adapter = ArrayAdapter(this@ModelNodeActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Local X", "Local Y", "Local Z")) }; panel.addView(axes)
        detail = TextView(this).apply { setTextIsSelectable(true) }; panel.addView(detail)
        slider = SeekBar(this).apply { max = 180; progress = 90; isEnabled = false }; panel.addView(slider)
        panel.addView(Button(this).apply { text = "Reset selected node"; setOnClickListener { selectedEntity()?.let { offsets[it] = FloatArray(3); applyPose(it); sync() } } })
        panel.addView(Button(this).apply { text = "Reset all nodes"; setOnClickListener { resetAllNodes() } })
        panel.addView(Button(this).apply { text = "Recenter / reload selected model"; setOnClickListener { if (selectedModel >= 0) requestModel(selectedModel) } })
        panel.addView(TextView(this).apply { text = "Drag to orbit; pinch to zoom. All transform nodes are listed, not just bones. Start with small rotations. No automatic jaw, blink, gaze, rest offsets or clip playback. Models with no skin can still expose movable object/group transforms. This screen does not change the overlay avatar. Uploaded model rights are not verified." })
        root.addView(ScrollView(this).apply { addView(panel) }, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.38f).toInt()))
        setContentView(root)
        filter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refreshNodes() }
            override fun afterTextChanged(s: Editable?) {}
        })
        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) { sync() }
            override fun onNothingSelected(p: AdapterView<*>?) { sync() }
        }
        nodes.onItemSelectedListener = listener; axes.onItemSelectedListener = listener
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser && ready) selectedEntity()?.let { entity ->
                    offsets[entity]?.set(axes.selectedItemPosition.coerceIn(0, 2), (value - 90).toFloat())
                    applyPose(entity); sync()
                }
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })
        surface.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                alive = false; generation++; Choreographer.getInstance().removeFrameCallback(frame)
                viewer?.let { it.scene.indirectLight = null; light?.let { l -> it.engine.destroyIndirectLight(l) } }
                light = null; viewer = null; source = null
            }
        })
        try {
            Utils.init()
            val v = ModelViewer(surfaceView = surface); viewer = v; v.autoPlayAnimations = false
            v.renderer.clearOptions = Renderer.ClearOptions().apply { clear = true; discard = true; clearColor = doubleArrayOf(0.0, 0.0, 0.0, 1.0) }
            val lm = v.engine.lightManager
            val keyInstance = lm.getInstance(v.light)
            if (keyInstance != 0) {
                originalKeyIntensity = lm.getIntensity(keyInstance)
                lm.getDirection(keyInstance, originalKeyDirection)
            }
            light = IndirectLight.Builder().irradiance(1, floatArrayOf(0.7f, 0.7f, 0.7f)).intensity(30000f).build(v.engine)
            v.scene.indirectLight = light; surface.setOnTouchListener(v)
            selector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, view: View?, position: Int, id: Long) { if (position != selectedModel) requestModel(position) }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            requestModel(0)
        } catch (e: Exception) { status.text = "Renderer setup error: ${e.message}" }
    }
    private fun requestModel(index: Int) {
        val v = viewer ?: return
        selectedModel = index; val ticket = ++generation
        brightness.progress = lightingLevels[models[index]] ?: defaultLighting(models[index])
        applyLighting()
        ready = false; loading = false; slider.isEnabled = false; axes.isEnabled = false; nodes.isEnabled = false
        entities.clear(); bases.clear(); offsets.clear(); refreshNodes()
        status.text = "Loading ${models[index]}…"
        Thread({
            try {
                val bytes = assets.open(models[index]).use { it.readBytes() }
                val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()); buffer.put(bytes).flip()
                runOnUiThread {
                    if (alive && generation == ticket && viewer === v) {
                        try {
                            v.loadModelGlb(buffer); source = buffer; v.transformToUnitCube()
                            val asset = v.asset ?: error("No model loaded")
                            val tm = v.engine.transformManager
                            asset.entities.forEach { entity ->
                                val instance = tm.getInstance(entity)
                                if (instance != 0) {
                                    val label = "${asset.getName(entity) ?: "Unnamed node"} [entity $entity]"
                                    entities[label] = entity
                                    bases[entity] = FloatArray(16).also { tm.getTransform(instance, it) }
                                    offsets[entity] = FloatArray(3)
                                }
                            }
                            loading = true
                            status.text = "${models[index]}: loading textures…"
                        } catch (e: Exception) { loading = false; ready = false; status.text = "Model load error: ${e.message}. Try another model." }
                    }
                }
            } catch (e: Exception) { runOnUiThread { if (alive && generation == ticket) status.text = "Asset read error: ${e.message}" } }
        }, "read-model-$ticket").start()
    }
    private fun selectedEntity(): Int? = if (ready) entities[nodes.selectedItem as? String] else null
    private fun refreshNodes() {
        if (!::nodes.isInitialized) return
        val previous = nodes.selectedItem as? String
        val terms = filter.text.toString().trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val labels = entities.keys.filter { label -> terms.all { label.contains(it, true) } }.sorted()
        nodes.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels.ifEmpty { listOf("No matching nodes") })
        if (labels.isNotEmpty()) nodes.setSelection(labels.indexOf(previous).coerceAtLeast(0))
        nodes.isEnabled = ready && labels.isNotEmpty(); sync()
    }
    private fun sync() {
        if (!::slider.isInitialized) return
        val xyz = selectedEntity()?.let { offsets[it] }
        slider.isEnabled = xyz != null; axes.isEnabled = xyz != null
        slider.progress = (xyz?.get(axes.selectedItemPosition.coerceIn(0, 2)) ?: 0f).toInt() + 90
        detail.text = if (xyz == null) "No editable node selected" else "${nodes.selectedItem} / ${entities.size} total transforms\nX ${xyz[0]}° · Y ${xyz[1]}° · Z ${xyz[2]}°"
    }
    private fun resetAllNodes() {
        if (!alive || !ready || loading) return
        val v = viewer ?: return
        val asset = v.asset ?: return
        ready = false
        sync()
        try {
            val tm = v.engine.transformManager
            val currentEntities = asset.entities.toSet()
            val restore = bases.entries.map { (entity, matrix) ->
                check(entity in currentEntities) { "Node no longer belongs to loaded model: $entity" }
                val instance = tm.getInstance(entity)
                check(instance != 0) { "Missing transform: $entity" }
                check(matrix.size == 16 && matrix.all { it.isFinite() }) { "Invalid cached transform: $entity" }
                Triple(entity, instance, matrix.copyOf())
            }
            for ((entity, instance, matrix) in restore) {
                tm.setTransform(instance, matrix)
                offsets[entity] = FloatArray(3)
            }
            v.animator?.updateBoneMatrices()
            ready = true
            sync()
            status.text = "Reset ${restore.size} nodes to cached authored transforms. Lighting and orientation unchanged."
        } catch (e: Exception) {
            android.util.Log.e("ModelNodeInspector", "Reset-all failed for ${models.getOrNull(selectedModel)}", e)
            ready = false
            sync()
            status.text = "Reset failed: ${e.javaClass.simpleName}: ${e.message}. Reload this model before continuing."
        }
    }
    private fun applyPose(entity: Int) {
        val v = viewer ?: return; val base = bases[entity] ?: return; val xyz = offsets[entity] ?: return
        val rotation = FloatArray(16); val result = FloatArray(16); Matrix.setIdentityM(rotation, 0)
        Matrix.rotateM(rotation, 0, xyz[0], 1f, 0f, 0f); Matrix.rotateM(rotation, 0, xyz[1], 0f, 1f, 0f); Matrix.rotateM(rotation, 0, xyz[2], 0f, 0f, 1f)
        Matrix.multiplyMM(result, 0, base, 0, rotation, 0)
        val tm = v.engine.transformManager; tm.setTransform(tm.getInstance(entity), result); v.animator?.updateBoneMatrices()
    }
    override fun onResume() { super.onResume(); resumed = true; Choreographer.getInstance().removeFrameCallback(frame); Choreographer.getInstance().postFrameCallback(frame) }
    override fun onPause() { resumed = false; Choreographer.getInstance().removeFrameCallback(frame); super.onPause() }
    override fun onDestroy() { alive = false; generation++; super.onDestroy() }
}
