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
import com.google.android.filament.utils.Manipulator
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class ModelNodeActivity : Activity() {
    private val models = listOf("roxanne_wolf_security_breach.glb", "fexa_blush_alt_nude.glb", "loona.glb")
    private var viewer: ModelViewer? = null
    private var cameraControls: Manipulator? = null
    private fun resetCameraView() { cameraControls?.let { it.jumpToBookmark(it.homeBookmark) } }
    private var light: IndirectLight? = null
    private var source: ByteBuffer? = null
    private var generation = 0
    private var alive = true
    private var resumed = false
    private var loading = false
    private var ready = false
    private var selectedModel = -1
    private var profileKey = ""
    private var profile = JSONObject()
    private var orientation = FloatArray(3)
    private var rootBase = FloatArray(16)
    private var pivot = FloatArray(4)
    private val entities = linkedMapOf<String, Int>()
    private val keys = mutableMapOf<Int, String>()
    private val bases = mutableMapOf<Int, FloatArray>()
    private val offsets = mutableMapOf<Int, FloatArray>()
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var filter: EditText
    private lateinit var nodes: Spinner
    private lateinit var axes: Spinner
    private lateinit var slider: SeekBar
    private lateinit var orientationAxis: Spinner
    private lateinit var orientationSlider: SeekBar
    private lateinit var orientationNote: TextView
    private lateinit var brightness: SeekBar
    private lateinit var lightingNote: TextView
    private var originalKeyIntensity = 100000f
    private val originalKeyDirection = FloatArray(3)
    private val lightingLevels = mutableMapOf<String, Int>()
    private val prefs by lazy { getSharedPreferences("model_node_calibration_v1", MODE_PRIVATE) }
    private fun defaultLighting(name: String) = if (name == "loona.glb") 30 else 0
    private fun applyLighting() {
        val v = viewer ?: return; val name = models.getOrNull(selectedModel) ?: return
        val multiplier = 1f + (lightingLevels[name] ?: defaultLighting(name)) / 10f
        light?.setIntensity(30000f * multiplier)
        val lm = v.engine.lightManager; val instance = lm.getInstance(v.light)
        if (instance != 0) {
            lm.setIntensity(instance, originalKeyIntensity * multiplier)
            if (name == "loona.glb") lm.setDirection(instance, 0f, -0.3f, -1f)
            else lm.setDirection(instance, originalKeyDirection[0], originalKeyDirection[1], originalKeyDirection[2])
        }
        lightingNote.text = "Inspection lighting: ${multiplier}x (screen session only)"
    }
    private fun vector(a: JSONArray?) = FloatArray(3) { i -> (a?.optDouble(i, 0.0) ?: 0.0).toFloat().let { if (it.isFinite()) it else 0f } }
    private fun array(v: FloatArray) = JSONArray().apply { v.forEach { put(it.toDouble()) } }
    private fun section(name: String): JSONObject = profile.optJSONObject(name) ?: JSONObject().also { profile.put(name, it) }
    private fun saved(entity: Int) = vector(section("defaults").optJSONArray(keys[entity] ?: ""))
    private fun limits(entity: Int, axis: Int): Pair<Float, Float> {
        val a = section("limits").optJSONArray("${keys[entity]}:$axis")
        return Pair((a?.optDouble(0, -90.0) ?: -90.0).toFloat(), (a?.optDouble(1, 90.0) ?: 90.0).toFloat())
    }
    private fun validateDefault(entity: Int, xyz: FloatArray) {
        for (a in 0..2) { val range = limits(entity, a); check(xyz[a].isFinite() && xyz[a] in range.first..range.second) { "Default outside saved limits; clear axis limits first" } }
    }
    private fun persist(note: String) {
        check(profileKey.isNotEmpty()) { "No loaded model profile" }
        prefs.edit().putString(profileKey, profile.toString()).apply()
        status.text = note; sync()
    }
    private fun guarded(action: () -> Unit) {
        if (!alive || !ready || loading) return
        try { action() } catch (e: Exception) {
            android.util.Log.e("ModelNodeInspector", "Calibration operation failed", e)
            status.text = "Operation failed: ${e.javaClass.simpleName}: ${e.message}"
        }
    }
    private fun selectedEntity(): Int? = if (ready) entities[nodes.selectedItem as? String] else null
    private fun axisIndex() = axes.selectedItemPosition.coerceIn(0, 2)
    private fun rotated(base: FloatArray, xyz: FloatArray): FloatArray {
        val r = FloatArray(16); val result = FloatArray(16); Matrix.setIdentityM(r, 0)
        Matrix.rotateM(r, 0, xyz[0], 1f, 0f, 0f); Matrix.rotateM(r, 0, xyz[1], 0f, 1f, 0f); Matrix.rotateM(r, 0, xyz[2], 0f, 0f, 1f)
        Matrix.multiplyMM(result, 0, base, 0, r, 0); return result
    }
    private fun applyOrientation() {
        val v = viewer ?: return; val asset = v.asset ?: return
        val tm = v.engine.transformManager; val instance = tm.getInstance(asset.root); check(instance != 0)
        val r = FloatArray(16); val result = FloatArray(16); Matrix.setIdentityM(r, 0)
        Matrix.translateM(r, 0, pivot[0], pivot[1], pivot[2])
        Matrix.rotateM(r, 0, orientation[0], 1f, 0f, 0f); Matrix.rotateM(r, 0, orientation[1], 0f, 1f, 0f); Matrix.rotateM(r, 0, orientation[2], 0f, 0f, 1f)
        Matrix.translateM(r, 0, -pivot[0], -pivot[1], -pivot[2])
        Matrix.multiplyMM(result, 0, r, 0, rootBase, 0); tm.setTransform(instance, result)
    }
    private fun restoreNodes(authored: Boolean = false, only: Int? = null) {
        val v = viewer ?: return; val asset = v.asset ?: return; val tm = v.engine.transformManager
        val current = asset.entities.toSet()
        val restore = bases.entries.filter { only == null || it.key == only }.map { (entity, base) ->
            check(entity in current) { "Stale node $entity" }; val instance = tm.getInstance(entity); check(instance != 0) { "Missing transform $entity" }
            val xyz = if (authored) FloatArray(3) else saved(entity)
            val matrix = if (xyz.all { it == 0f }) base.copyOf() else rotated(base, xyz)
            check(matrix.all { it.isFinite() }) { "Invalid transform $entity" }
            Triple(entity, instance, Pair(matrix, xyz))
        }
        for ((entity, instance, pose) in restore) { tm.setTransform(instance, pose.first); offsets[entity] = pose.second }
        v.animator?.updateBoneMatrices(); sync()
    }
    private fun captureLimit(minimum: Boolean) {
        val entity = selectedEntity() ?: return; val axis = axisIndex(); val value = offsets[entity]?.get(axis) ?: return
        val old = limits(entity, axis); val low = if (minimum) value else old.first; val high = if (minimum) old.second else value
        if (low > high) { status.text = "Minimum cannot exceed maximum. Clear this axis's limits first."; return }
        if (saved(entity)[axis] !in low..high) { status.text = "Saved default is outside that range. Set the node default first."; return }
        section("limits").put("${keys[entity]}:$axis", JSONArray().put(low.toDouble()).put(high.toDouble()))
        persist("Saved ${if (minimum) "minimum" else "maximum"}: $value degrees for this node and axis.")
    }
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(time: Long) {
            if (!alive || !resumed) return
            try { viewer?.let { v ->
                v.animator?.updateBoneMatrices(); v.render(time)
                if (loading && v.asset != null && v.progress >= 1f) {
                    resetCameraView()
                    loading = false; ready = true; refreshNodes()
                    status.text = "${models[selectedModel]}: ${entities.size} transforms; ${v.animator?.animationCount ?: 0} clips. Saved defaults loaded; camera reset; overlay unchanged."
                }
            } } catch (e: Exception) { ready = false; loading = false; status.text = "Render error: ${e.message}. Reload model." }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(12, 40, 12, 16) }
        root.addView(TextView(this).apply { text = "Alongside — model node inspector"; textSize = 20f })
        val selector = Spinner(this).apply { adapter = ArrayAdapter(this@ModelNodeActivity, android.R.layout.simple_spinner_dropdown_item, models) }; root.addView(selector)
        root.addView(Button(this).apply { text = "Original calibrated Roxanne / chat / overlay controls"; setOnClickListener { startActivity(Intent(this@ModelNodeActivity, AvatarPreviewActivity::class.java)) } })
        status = TextView(this); root.addView(status)
        val surface = SurfaceView(this); root.addView(surface, LinearLayout.LayoutParams(-1, 0, 1f))
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun button(label: String, action: () -> Unit) { panel.addView(Button(this).apply { text = label; setOnClickListener { guarded(action) } }) }
        fun spinner(labels: List<String>) = Spinner(this).apply { adapter = ArrayAdapter(this@ModelNodeActivity, android.R.layout.simple_spinner_dropdown_item, labels); panel.addView(this) }
        fun change(action: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) { if (fromUser) guarded { action(value) } }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        }
        button("Reset camera view (keep pose)") { resetCameraView(); status.text = "Camera angle, pan and zoom reset. Model orientation, node poses and saved settings unchanged." }
        lightingNote = TextView(this); panel.addView(lightingNote)
        brightness = SeekBar(this).apply { max = 150 }; panel.addView(brightness)
        brightness.setOnSeekBarChangeListener(change { lightingLevels[models[selectedModel]] = it; applyLighting() })
        button("Reset lighting for this model") { lightingLevels.remove(models[selectedModel]); brightness.progress = defaultLighting(models[selectedModel]); applyLighting() }
        orientationNote = TextView(this); panel.addView(orientationNote)
        orientationAxis = spinner(listOf("Model orientation X", "Model orientation Y", "Model orientation Z"))
        orientationSlider = SeekBar(this).apply { max = 360; progress = 180 }; panel.addView(orientationSlider)
        orientationSlider.setOnSeekBarChangeListener(change { orientation[orientationAxis.selectedItemPosition.coerceIn(0, 2)] = (it - 180).toFloat(); applyOrientation(); sync() })
        button("Set model orientation default") { profile.put("orientation", array(orientation)); persist("Saved model rotation only; camera view is not saved.") }
        button("Reset model orientation to saved default") { orientation = vector(profile.optJSONArray("orientation")); applyOrientation(); sync() }
        filter = EditText(this).apply { hint = "Filter nodes by name"; setSingleLine(true) }; panel.addView(filter)
        nodes = Spinner(this); panel.addView(nodes); axes = spinner(listOf("Local X", "Local Y", "Local Z"))
        detail = TextView(this).apply { setTextIsSelectable(true) }; panel.addView(detail)
        slider = SeekBar(this).apply { max = 180; progress = 90; isEnabled = false }; panel.addView(slider)
        slider.setOnSeekBarChangeListener(change { value -> selectedEntity()?.let { entity ->
            val range = limits(entity, axisIndex()); offsets[entity]?.set(axisIndex(), (value - 90).toFloat().coerceIn(range.first, range.second))
            val v = viewer ?: return@let; val instance = v.engine.transformManager.getInstance(entity); check(instance != 0)
            v.engine.transformManager.setTransform(instance, rotated(bases.getValue(entity), offsets.getValue(entity))); v.animator?.updateBoneMatrices(); sync()
        } })
        button("Set selected node default") { selectedEntity()?.let { entity ->
            val xyz = offsets.getValue(entity); validateDefault(entity, xyz)
            section("defaults").put(keys.getValue(entity), array(xyz)); persist("Saved selected node's XYZ default.")
        } }
        button("Set all node defaults") {
            val defaults = JSONObject()
            for ((entity, xyz) in offsets) { validateDefault(entity, xyz); defaults.put(keys.getValue(entity), array(xyz)) }
            profile.put("defaults", defaults); persist("Saved defaults for ${offsets.size} nodes; orientation saved separately.")
        }
        button("Set min (selected node / axis)") { captureLimit(true) }
        button("Set max (selected node / axis)") { captureLimit(false) }
        button("Clear selected axis limits") { selectedEntity()?.let { section("limits").remove("${keys[it]}:${axisIndex()}"); persist("Cleared selected axis limits; slider range is -90 to +90 degrees.") } }
        button("Reset selected node to default") { selectedEntity()?.let { restoreNodes(only = it); status.text = "Selected node restored to saved default." } }
        button("Reset all nodes to defaults") { restoreNodes(); status.text = "All nodes restored in one batch; orientation and lighting unchanged." }
        button("Restore authored pose (keep saved defaults)") { restoreNodes(authored = true); orientation = FloatArray(3); applyOrientation(); sync(); status.text = "Authored pose restored for inspection. Saved defaults and limits retained." }
        panel.addView(Button(this).apply { text = "Recenter / reload selected model"; setOnClickListener { if (alive && selectedModel >= 0) requestModel(selectedModel) } })
        panel.addView(TextView(this).apply { text = "Dragging or pinching changes the camera, not saved model rotation. Reset camera view restores the standard viewpoint without changing poses. Switching models resets the camera. Model orientation and node defaults/limits remain stored separately per model-file hash. Node offsets: -90 to +90 degrees; model orientation: -180 to +180 degrees. No automatic facial animation or overlay changes." })
        root.addView(ScrollView(this).apply { addView(panel) }, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.42f).toInt())); setContentView(root)
        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) { sync() }
            override fun onNothingSelected(p: AdapterView<*>?) { sync() }
        }
        nodes.onItemSelectedListener = listener; axes.onItemSelectedListener = listener; orientationAxis.onItemSelectedListener = listener
        filter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refreshNodes() }
            override fun afterTextChanged(s: Editable?) {}
        })
        surface.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                alive = false; generation++; Choreographer.getInstance().removeFrameCallback(frame)
                viewer?.let { it.scene.indirectLight = null; light?.let { l -> it.engine.destroyIndirectLight(l) } }; light = null; viewer = null; source = null; cameraControls = null
            }
        })
        try {
            Utils.init()
            val orbit = Manipulator.Builder().targetPosition(0f, 0f, -4f).orbitHomePosition(0f, 0f, 0f).viewport(surface.width.coerceAtLeast(1), surface.height.coerceAtLeast(1)).build(Manipulator.Mode.ORBIT)
            cameraControls = orbit
            val v = ModelViewer(surfaceView = surface, manipulator = orbit); viewer = v; v.autoPlayAnimations = false
            v.renderer.clearOptions = Renderer.ClearOptions().apply { clear = true; discard = true; clearColor = doubleArrayOf(0.0, 0.0, 0.0, 1.0) }
            val lm = v.engine.lightManager; val instance = lm.getInstance(v.light)
            if (instance != 0) { originalKeyIntensity = lm.getIntensity(instance); lm.getDirection(instance, originalKeyDirection) }
            light = IndirectLight.Builder().irradiance(1, floatArrayOf(0.7f, 0.7f, 0.7f)).intensity(30000f).build(v.engine)
            v.scene.indirectLight = light
            surface.setOnTouchListener { view, event -> if (alive && ready && !loading) v.onTouch(view, event) else true }
            selector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, view: View?, position: Int, id: Long) { if (position != selectedModel) requestModel(position) }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }; requestModel(0)
        } catch (e: Exception) { status.text = "Renderer setup error: ${e.message}" }
    }
    private fun requestModel(index: Int) {
        val v = viewer ?: return; selectedModel = index; val ticket = ++generation
        ready = false; loading = false; profileKey = ""; profile = JSONObject(); orientation = FloatArray(3)
        resetCameraView()
        entities.clear(); keys.clear(); bases.clear(); offsets.clear(); refreshNodes()
        brightness.progress = lightingLevels[models[index]] ?: defaultLighting(models[index]); applyLighting(); status.text = "Loading ${models[index]}…"
        Thread({
            try {
                val bytes = assets.open(models[index]).use { it.readBytes() }
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
                val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()); buffer.put(bytes).flip()
                runOnUiThread { if (alive && generation == ticket && viewer === v) {
                    try {
                        v.loadModelGlb(buffer); source = buffer; v.transformToUnitCube()
                        val asset = v.asset ?: error("No model loaded"); val tm = v.engine.transformManager
                        rootBase = FloatArray(16).also { tm.getTransform(tm.getInstance(asset.root), it) }
                        val center = asset.boundingBox.center
                        pivot = FloatArray(4).also { Matrix.multiplyMV(it, 0, rootBase, 0, floatArrayOf(center[0], center[1], center[2], 1f), 0) }
                        asset.entities.forEachIndexed { i, entity ->
                            val instance = tm.getInstance(entity)
                            if (instance != 0 && entity != asset.root) {
                                val name = asset.getName(entity) ?: "Unnamed node"
                                entities["$name [node $i]"] = entity; keys[entity] = "$i:$name"
                                bases[entity] = FloatArray(16).also { tm.getTransform(instance, it) }; offsets[entity] = FloatArray(3)
                            }
                        }
                        val layout = MessageDigest.getInstance("SHA-256").digest(keys.values.joinToString("|").toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
                        profileKey = "v1:${models[index]}:$digest:$layout"
                        profile = runCatching { JSONObject(prefs.getString(profileKey, "{}") ?: "{}") }.getOrElse { JSONObject() }
                        orientation = vector(profile.optJSONArray("orientation")); applyOrientation(); restoreNodes()
                        loading = true; sync(); status.text = "Loading textures and saved calibration…"
                    } catch (e: Exception) { ready = false; loading = false; status.text = "Model load error: ${e.message}. Reload to retry." }
                } }
            } catch (e: Exception) { runOnUiThread { if (alive && generation == ticket) status.text = "Asset read error: ${e.message}" } }
        }, "read-model-$ticket").start()
    }
    private fun refreshNodes() {
        if (!::nodes.isInitialized) return
        val old = nodes.selectedItem as? String
        val terms = filter.text.toString().trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val labels = entities.keys.filter { label -> terms.all { label.contains(it, true) } }.sorted()
        nodes.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels.ifEmpty { listOf("No matching nodes") })
        if (labels.isNotEmpty()) nodes.setSelection(labels.indexOf(old).coerceAtLeast(0))
        nodes.isEnabled = ready && labels.isNotEmpty(); sync()
    }
    private fun sync() {
        if (!::slider.isInitialized) return
        val entity = selectedEntity(); val xyz = offsets[entity]; val a = axisIndex()
        slider.isEnabled = xyz != null; axes.isEnabled = xyz != null
        slider.progress = (xyz?.get(a) ?: 0f).toInt() + 90
        detail.text = if (entity == null || xyz == null) "No editable node selected" else {
            val range = limits(entity, a)
            "${nodes.selectedItem} / ${entities.size} transforms\nX ${xyz[0]}° · Y ${xyz[1]}° · Z ${xyz[2]}°\nSelected axis: default ${saved(entity)[a]}°; min ${range.first}°; max ${range.second}°"
        }
        orientationSlider.isEnabled = ready; orientationAxis.isEnabled = ready
        orientationSlider.progress = orientation[orientationAxis.selectedItemPosition.coerceIn(0, 2)].toInt() + 180
        orientationNote.text = if (!ready) "Model orientation: loading ${models.getOrNull(selectedModel) ?: "model"}…" else "${models[selectedModel]} model rotation (not camera): X ${orientation[0]}° · Y ${orientation[1]}° · Z ${orientation[2]}°\nSaved: ${vector(profile.optJSONArray("orientation")).joinToString()} degrees"
    }
    override fun onResume() { super.onResume(); resumed = true; Choreographer.getInstance().removeFrameCallback(frame); Choreographer.getInstance().postFrameCallback(frame) }
    override fun onPause() { resumed = false; Choreographer.getInstance().removeFrameCallback(frame); super.onPause() }
    override fun onDestroy() { alive = false; generation++; super.onDestroy() }
}
