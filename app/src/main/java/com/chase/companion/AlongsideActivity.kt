package com.chase.companion

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.opengl.Matrix
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.AtomicFile
import android.view.*
import android.widget.*
import com.google.android.filament.IndirectLight
import com.google.android.filament.Renderer
import com.google.android.filament.utils.Manipulator
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.*

class AlongsideActivity : Activity() {
    private val files = listOf("roxanne_wolf_security_breach.glb", "fexa_blush_alt_nude.glb", "loona.glb")
    private val names = listOf("Roxanne", "Fexa", "Loona")
    private val appPrefs by lazy { getSharedPreferences("alongside_ui_v1", MODE_PRIVATE) }
    private val calibrationPrefs by lazy { getSharedPreferences("model_node_calibration_v1", MODE_PRIVATE) }
    private var character = -1
    private var page = "Full Rig Inspection"
    private var alive = true
    private var resumed = false
    private var ready = false
    private var loading = false
    private var generation = 0
    private var viewer: ModelViewer? = null
    private var orbit: Manipulator? = null
    private var ambient: IndirectLight? = null
    private var source: ByteBuffer? = null
    private var profile = JSONObject()
    private var profileKey = ""
    private var rootBase = FloatArray(16)
    private var pivot = FloatArray(4)
    private var orientation = FloatArray(3)
    private val entities = linkedMapOf<String, Int>()
    private val bases = linkedMapOf<String, FloatArray>()
    private val offsets = linkedMapOf<String, FloatArray>()
    private var selectedNode = ""
    private var axis = 0
    private var clip = -1
    private var animationStart = 0L
    private var brightness = 1f
    private var keyIntensity = 100000f
    private val keyDirection = FloatArray(3)
    private lateinit var panel: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private lateinit var toolbarTitle: TextView
    private var nodeSync: (() -> Unit)? = null
    private var bg = 0
    private var card = 0
    private var ink = 0
    private var accent = 0
    private val worker = Executors.newSingleThreadExecutor()
    private var request = 0
    private var busy = false
    @Volatile private var connection: HttpURLConnection? = null
    private var history = JSONArray()
    private var historyError: String? = null
    private var tts: TextToSpeech? = null
    private var voiceReady = false
    private var speaking = false
    private var utterance: String? = null
    private var speechSerial = 0
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    private fun per(key: String) = "$key:${files[character]}"
    private fun speechEnabled() = character >= 0 && appPrefs.getBoolean(per("speech"), true)
    private fun live() = character >= 0 && (page == "Chat" || appPrefs.getBoolean(per("live"), true)) && page in listOf("Full Rig Inspection", "Chat", "Animation Test", "Voice Test")
    private fun section(key: String): JSONObject = profile.optJSONObject(key) ?: JSONObject().also { profile.put(key, it) }
    private fun vector(a: JSONArray?) = FloatArray(3) { (a?.optDouble(it, 0.0) ?: 0.0).toFloat().let { v -> if (v.isFinite()) v else 0f } }
    private fun array(v: FloatArray) = JSONArray().apply { v.forEach { put(it.toDouble()) } }
    private fun defaults(key: String): FloatArray = vector(section("defaults").optJSONArray(key))
    private fun bounds(key: String, a: Int): Pair<Float, Float> { val v = section("limits").optJSONArray("$key:$a"); return Pair((v?.optDouble(0, -90.0) ?: -90.0).toFloat(), (v?.optDouble(1, 90.0) ?: 90.0).toFloat()) }
    private fun save(note: String) { check(ready && profileKey.isNotEmpty()); calibrationPrefs.edit().putString(profileKey, profile.toString()).apply(); status.text = "${names[character]}: $note"; nodeSync?.invoke() }
    private fun safe(action: () -> Unit) { try { action() } catch (e: Exception) { status.text = "${e.javaClass.simpleName}: ${e.message}" } }
    private fun model(action: () -> Unit) { if (character >= 0 && ready) safe(action) else status.text = "Select a character and wait for loading." }
    private fun label(text: String, size: Float = 15f) = TextView(this).apply { this.text = text; textSize = size; setTextColor(ink); setPadding(dp(8), dp(6), dp(8), dp(6)) }
    private fun button(parent: LinearLayout, title: String, action: () -> Unit): Button {
        val b = Button(this).apply { text = title; isAllCaps = false; setTextColor(ink); background = GradientDrawable().apply { setColor(card); cornerRadius = dp(10).toFloat(); setStroke(dp(1), accent) }; setOnClickListener { safe(action) } }
        parent.addView(b, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }); return b
    }
    private fun toggle(parent: LinearLayout, title: String, value: Boolean, change: (Boolean) -> Unit) { parent.addView(Switch(this).apply { text = title; setTextColor(ink); isChecked = value; setPadding(dp(8), dp(6), dp(8), dp(6)); setOnCheckedChangeListener { _, v -> safe { change(v) } } }) }
    private fun chooser(parent: LinearLayout, values: List<String>, initial: Int = 0, change: (Int) -> Unit): Spinner {
        val s = Spinner(this); s.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, values) {
            override fun getView(p: Int, old: View?, group: android.view.ViewGroup): View = (super.getView(p, old, group) as TextView).apply { setTextColor(ink); setBackgroundColor(card) }
            override fun getDropDownView(p: Int, old: View?, group: android.view.ViewGroup): View = (super.getDropDownView(p, old, group) as TextView).apply { setTextColor(ink); setBackgroundColor(card) }
        }; val first = initial.coerceIn(0, (values.size - 1).coerceAtLeast(0)); var previous = first; s.setSelection(first)
        s.onItemSelectedListener = object : AdapterView.OnItemSelectedListener { override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { if (pos != previous) { previous = pos; safe { change(pos) } } }; override fun onNothingSelected(p: AdapterView<*>?) {} }; parent.addView(s); return s
    }
    private fun seek(parent: LinearLayout, maximum: Int, value: Int, change: (Int) -> Unit): SeekBar { val s = SeekBar(this).apply { max = maximum; progress = value.coerceIn(0, maximum) }; parent.addView(s); s.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(b: SeekBar, v: Int, user: Boolean) { if (user) safe { change(v) } }; override fun onStartTrackingTouch(b: SeekBar) {}; override fun onStopTrackingTouch(b: SeekBar) {} }); return s }
    private fun details(title: String, content: String) { val body = label(content).apply { visibility = View.GONE; setTextIsSelectable(true) }; val b = button(panel, "▸ $title") { body.visibility = if (body.visibility == View.VISIBLE) View.GONE else View.VISIBLE }; b.setOnClickListener { val show = body.visibility != View.VISIBLE; body.visibility = if (show) View.VISIBLE else View.GONE; b.text = "${if (show) "▾" else "▸"} $title" }; panel.addView(body) }
    private fun confirm(title: String, action: () -> Unit) { AlertDialog.Builder(this).setTitle(title).setMessage("This affects only ${names[character]}.").setNegativeButton("Cancel", null).setPositiveButton("Confirm") { _, _ -> model(action) }.show() }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); character = intent.getIntExtra("character", -1).takeIf { it in files.indices } ?: -1
        page = intent.getStringExtra("page") ?: "Full Rig Inspection"
        when (appPrefs.getString("theme", "Natural")) {
            "Light" -> { bg = Color.rgb(245,247,251); card = Color.WHITE; ink = Color.rgb(24,35,50); accent = Color.rgb(176,35,48) }
            "Dark" -> { bg = Color.rgb(14,22,32); card = Color.rgb(29,43,60); ink = Color.rgb(238,244,252); accent = Color.rgb(112,183,255) }
            else -> { bg = Color.rgb(7,30,55); card = Color.rgb(15,49,82); ink = Color.rgb(255,145,138); accent = Color.rgb(244,76,84) }
        }
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        window.statusBarColor = bg; window.navigationBarColor = bg
        if (Build.VERSION.SDK_INT >= 30) window.insetsController?.setSystemBarsAppearance(if (appPrefs.getString("theme", "Natural") == "Light") WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS else 0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) { val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()); val keyboard = insets.getInsets(WindowInsets.Type.ime()); view.setPadding(bars.left + dp(8), bars.top, bars.right + dp(8), max(bars.bottom, keyboard.bottom)) }
            else view.setPadding(insets.systemWindowInsetLeft + dp(8), insets.systemWindowInsetTop, insets.systemWindowInsetRight + dp(8), insets.systemWindowInsetBottom)
            insets
        }
        val toolbar = LinearLayout(this); button(toolbar, "‹") { back() }.layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)); toolbarTitle = label("Alongside", 19f); toolbar.addView(toolbarTitle, LinearLayout.LayoutParams(0, -2, 1f)); button(toolbar, "⚙") { navigate("Settings") }.layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)); root.addView(toolbar)
        val surface = if (character >= 0) SurfaceView(this).also { root.addView(it, LinearLayout.LayoutParams(-1, 0, 1f)) } else null
        status = label(if (character >= 0) "Loading ${names[character]}…" else "Choose a character before opening model controls.", 12f); root.addView(status)
        panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; scroll = ScrollView(this).apply { addView(panel) }; root.addView(scroll, LinearLayout.LayoutParams(-1, 0, if (surface == null) 1f else 1.2f)); setContentView(root); root.requestApplyInsets()
        tts = TextToSpeech(applicationContext) { result -> runOnUiThread { if (alive && result == TextToSpeech.SUCCESS) { val language = tts?.setLanguage(Locale.US); voiceReady = language != TextToSpeech.LANG_MISSING_DATA && language != TextToSpeech.LANG_NOT_SUPPORTED } } }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) { runOnUiThread { if (alive && id == utterance) speaking = true } }
            override fun onDone(id: String) { runOnUiThread { if (id == utterance) { speaking = false; utterance = null } } }
            override fun onError(id: String) { onDone(id) }
            override fun onStop(id: String, interrupted: Boolean) { onDone(id) }
        })
        if (character >= 0) { readHistory(); initializeRenderer(surface!!) }; renderPage()
    }
    private fun back() { if (page != "Full Rig Inspection") navigate("Full Rig Inspection") else { stopRequest(); intent.removeExtra("character"); intent.removeExtra("page"); recreate() } }
    @Deprecated("Activity navigation") override fun onBackPressed() { if (character < 0 && page != "Settings") super.onBackPressed() else back() }
    private fun navigate(next: String) { stopSpeech(); page = next; clip = -1; model { restoreNodes() }; renderPage() }
    private fun chooseCharacter(i: Int) { stopRequest(); intent.putExtra("character", i); intent.putExtra("page", "Full Rig Inspection"); recreate() }
    private fun renderPage() {
        nodeSync = null; panel.removeAllViews(); toolbarTitle.text = if (character < 0) "Alongside" else "${names[character]} · $page"
        if (character < 0 && page != "Settings") { panel.addView(label("Choose your character", 23f)); names.forEachIndexed { i, name -> button(panel, name) { chooseCharacter(i) } }; return }
        when (page) {
            "Settings" -> settingsPage()
            "Full Rig Inspection" -> {
                button(panel, "Chat") { navigate("Chat") }; toggle(panel, "Live animations", appPrefs.getBoolean(per("live"), true)) { v -> appPrefs.edit().putBoolean(per("live"), v).apply(); model { restoreNodes() } }
                listOf("Node Control", "Animation Test", "Toggles", "Voice Test", "Defaults").forEach { title -> button(panel, title) { navigate(title) } }
                button(panel, "Change character") { stopRequest(); intent.removeExtra("character"); intent.removeExtra("page"); recreate() }
                details("Model details", "${files[character]}\n${entities.size} editable transforms. ${viewer?.animator?.animationCount ?: 0} embedded clips.\nLive animation requires character-specific mapped channels. Node controls pause live animation.")
            }
            "Node Control", "Defaults" -> nodePage()
            "Animation Test" -> animationPage()
            "Toggles" -> togglesPage()
            "Voice Test" -> { panel.addView(label("Speech and mouth movement use this character's settings.")); speechToggle(); button(panel, "Test voice") { speak("Hello, this is ${names[character]}. This is my voice and animation test.") }; button(panel, "Stop voice") { stopSpeech() }; details("Animation availability", "Speech moves only this character's mapped jaw channel. Fexa and Loona need their own channel mapping; Roxanne mappings are not reused.") }
            "Chat" -> chatPage()
        }
    }
    private fun saveDefault(key: String) { val xyz = offsets.getValue(key); for (a in 0..2) { val b = bounds(key, a); check(xyz[a] in b.first..b.second) { "Clear conflicting limits first" } }; section("defaults").put(key, array(xyz)) }
    private fun nodePage() {
        panel.addView(label(if (page == "Defaults") "Orientation and saved rest poses" else "Manual node testing · live animation paused"))
        if (!ready) { panel.addView(label("Wait for the model to finish loading.")); return }
        if (page == "Defaults") {
            val info = label("Model orientation: ${orientation.joinToString()}°"); panel.addView(info)
            var orientAxis = 0; chooser(panel, listOf("Orientation X", "Orientation Y", "Orientation Z")) { orientAxis = it; renderPage() }
            seek(panel, 360, orientation[orientAxis].toInt() + 180) { orientation[orientAxis] = (it - 180).toFloat(); applyOrientation(); info.text = "Model orientation: ${orientation.joinToString()}°" }
            button(panel, "Set orientation default") { model { profile.put("orientation", array(orientation)); save("orientation default saved") } }
            button(panel, "Reset orientation to saved default") { model { orientation = vector(profile.optJSONArray("orientation")); applyOrientation(); renderPage() } }
        }
        val all = entities.keys.toList(); if (selectedNode !in entities) selectedNode = all.firstOrNull() ?: ""
        val search = EditText(this).apply { hint = "Filter nodes"; setTextColor(ink); setHintTextColor(ink); setSingleLine(true) }; panel.addView(search)
        val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; panel.addView(holder)
        fun rebuild(query: String) { holder.removeAllViews(); val terms = query.trim().split(' ').filter { it.isNotBlank() }; val matches = all.filter { k -> terms.all { k.contains(it, true) } }; if (matches.isEmpty()) { holder.addView(label("No matching nodes")); return }; if (selectedNode !in matches) selectedNode = matches[0]; chooser(holder, matches, matches.indexOf(selectedNode)) { selectedNode = matches[it]; nodeSync?.invoke() } }
        search.addTextChangedListener(object : TextWatcher { override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}; override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { rebuild(s.toString()) }; override fun afterTextChanged(s: Editable?) {} })
        chooser(panel, listOf("Local X", "Local Y", "Local Z"), axis) { axis = it; nodeSync?.invoke() }
        val info = label(""); panel.addView(info)
        val slider = seek(panel, 180, 90) { value -> model { val b = bounds(selectedNode, axis); offsets.getValue(selectedNode)[axis] = (value - 90).toFloat().coerceIn(b.first, b.second); applyNode(selectedNode, offsets.getValue(selectedNode)); viewer?.animator?.updateBoneMatrices(); nodeSync?.invoke() } }
        nodeSync = { if (selectedNode in offsets) { val b = bounds(selectedNode, axis); val xyz = offsets.getValue(selectedNode); slider.progress = xyz[axis].toInt() + 90; info.text = "$selectedNode\nXYZ: ${xyz.joinToString()}°\nAxis default ${defaults(selectedNode)[axis]}° · min ${b.first}° · max ${b.second}°" } }; rebuild(""); nodeSync?.invoke()
        button(panel, "Set selected node default") { model { saveDefault(selectedNode); save("selected node default saved") } }
        button(panel, "Set all node defaults") { model { val old = JSONObject(profile.toString()); try { offsets.keys.forEach { saveDefault(it) }; save("all node defaults saved") } catch (e: Exception) { profile = old; throw e } } }
        fun limit(minimum: Boolean) { model { val old = bounds(selectedNode, axis); val v = offsets.getValue(selectedNode)[axis]; val low = if (minimum) v else old.first; val high = if (minimum) old.second else v; check(low <= high) { "Minimum must not exceed maximum" }; check(defaults(selectedNode)[axis] in low..high) { "Set a default inside the range first" }; section("limits").put("$selectedNode:$axis", JSONArray().put(low.toDouble()).put(high.toDouble())); save("axis limit saved") } }
        button(panel, "Set min · current node/axis") { limit(true) }; button(panel, "Set max · current node/axis") { limit(false) }
        button(panel, "Clear selected axis limits") { model { section("limits").remove("$selectedNode:$axis"); save("axis limits cleared") } }
        button(panel, "Reset selected node") { model { restoreNodes(only = selectedNode); nodeSync?.invoke() } }
        details("Control details", "Angles are local offsets relative to the uploaded pose. Limits are captured from the current angle and apply only to this node and axis for ${names[character]}. Saves remain on this phone.")
    }
    private fun animationPage() {
        panel.addView(label("Animation tests · ${names[character]}", 20f)); toggle(panel, "Live animations", appPrefs.getBoolean(per("live"), true)) { appPrefs.edit().putBoolean(per("live"), it).apply(); model { restoreNodes() } }
        val count = viewer?.animator?.animationCount ?: 0
        if (count > 0) { val labels = (0 until count).map { viewer?.animator?.getAnimationName(it)?.ifBlank { "Clip ${it + 1}" } ?: "Clip ${it + 1}" }; var chosen = 0; chooser(panel, labels) { chosen = it }; button(panel, "Play embedded clip") { model { clip = chosen; animationStart = 0L } }; button(panel, "Stop clip") { clip = -1; model { restoreNodes() } } } else panel.addView(label("No embedded animation clips. Live movement uses mapped node channels."))
        if (ready && entities.isNotEmpty()) {
            val keys = entities.keys.toList(); var node = keys.indexOf(selectedNode).coerceAtLeast(0); var a = axis; var kind = 0
            chooser(panel, keys, node) { node = it }; chooser(panel, listOf("Local X", "Local Y", "Local Z"), a) { a = it }
            val kinds = listOf("Idle", "Blink toward min", "Blink toward max", "Speech toward min", "Speech toward max"); chooser(panel, kinds) { kind = it }
            button(panel, "Assign animation channel") { model { val key = keys[node]; check(section("limits").has("$key:$a")) { "Calibrate and save this axis's min/max first" }; val range = bounds(key, a); section("animations").put("$key:$a", JSONObject().put("node", key).put("axis", a).put("kind", kind).put("low", range.first.toDouble()).put("high", range.second.toDouble())); save("animation channel assigned"); renderPage() } }
            button(panel, "Remove selected animation channel") { model { section("animations").remove("${keys[node]}:$a"); save("animation channel removed"); restoreNodes(); renderPage() } }
        }
        details("Configured channels", section("animations").toString(2)); details("Mapping notes", "Roxanne starts with known jaw/lid/head channels. Fexa and Loona require their own node mappings and limits. Chat locks live-animation mode on, but only configured channels move. No skeleton mapping is copied between characters.")
    }
    private fun togglesPage() {
        if (!ready) { panel.addView(label("Model loading…")); return }; val v = viewer ?: return; val rm = v.engine.renderableManager
        panel.addView(label("Mesh visibility · ${names[character]}", 20f))
        entities.forEach { (key, entity) -> val instance = rm.getInstance(entity); if (instance != 0) toggle(panel, key, !section("hidden").optBoolean(key, false)) { show -> model { rm.setLayerMask(instance, 255, if (show) 1 else 0); section("hidden").put(key, !show); save("mesh visibility saved") } } }
        details("Visibility notes", "Names do not establish anatomy. These switches hide renderable meshes, not arbitrary bones, and affect only this character.")
    }
    private fun speechToggle() { toggle(panel, "Spoken replies and speech mouth movement", speechEnabled()) { appPrefs.edit().putBoolean(per("speech"), it).apply(); if (!it) stopSpeech() } }
    private fun settingsPage() {
        panel.addView(label("Settings", 22f)); val themes = listOf("Light", "Dark", "Natural"); chooser(panel, themes, themes.indexOf(appPrefs.getString("theme", "Natural")).coerceAtLeast(0)) { i -> appPrefs.edit().putString("theme", themes[i]).apply(); intent.putExtra("page", "Settings"); recreate() }
        details("Theme details", "Light and Dark are neutral themes. Natural uses blue backgrounds with red accents and red text. Theme is app-wide; character settings are separate.")
        if (character >= 0) {
            panel.addView(label("Only ${names[character]}'s settings below", 18f)); speechToggle(); panel.addView(label("Response volume")); seek(panel, 100, (appPrefs.getFloat(per("volume"), 1f) * 100).toInt()) { appPrefs.edit().putFloat(per("volume"), it / 100f).apply(); stopSpeech() }
            panel.addView(label("Inspection light intensity")); seek(panel, 150, ((brightness - 1f) * 10).toInt()) { brightness = 1f + it / 10f; appPrefs.edit().putFloat(per("brightness"), brightness).apply(); applyLighting() }
            button(panel, "Reset camera view · keep pose") { orbit?.let { it.jumpToBookmark(it.homeBookmark) } }
            button(panel, "Refresh selected character") { model { loadModel() } }
            button(panel, "Reset nodes to saved defaults") { confirm("Reset ${names[character]}'s nodes?") { restoreNodes(); status.text = "Saved node defaults restored." } }
            button(panel, "Restore authored pose · keep saved defaults") { confirm("Restore authored pose?") { restoreNodes(authored = true); orientation = FloatArray(3); applyOrientation() } }
            button(panel, "Clear this character's calibration") { confirm("Delete ${names[character]}'s saved calibration?") { calibrationPrefs.edit().remove(profileKey).apply(); loadModel() } }
            button(panel, "Clear this character's chat history") { confirm("Delete ${names[character]}'s local conversation?") { stopRequest(); history = JSONArray(); historyError = null; writeHistory(); status.text = "Conversation cleared." } }
        }
        button(panel, "Check for updates") { checkUpdates() }; button(panel, "GitHub · newest diagnostic APK") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ChasePlank/android-ai-companion/actions/workflows/android.yml"))) }
        details("Local server and diagnostics", "Chat uses http://127.0.0.1:12434/v1/chat/completions and the existing local model identifier. There is no cloud fallback. Speech uses your installed Android TTS engine. Update checks contact GitHub and can fail for private repositories; the browser link remains available. Installed: ${packageManager.getPackageInfo(packageName, 0).versionName}")
    }
    private fun chatFile() = AtomicFile(File(filesDir, "chat-${files[character]}.json"))
    private fun readHistory() { try { val f = chatFile(); history = if (f.baseFile.exists()) JSONArray(f.openRead().bufferedReader().use { it.readText() }) else JSONArray() } catch (e: Exception) { historyError = "History could not be read; it will not be overwritten. ${e.message}"; history = JSONArray() } }
    private fun writeHistory() { check(historyError == null) { historyError ?: "History unavailable" }; val f = chatFile(); val out = f.startWrite(); try { out.write(history.toString().toByteArray(Charsets.UTF_8)); f.finishWrite(out) } catch (e: Exception) { f.failWrite(out); throw e } }
    private fun chatPage() {
        panel.addView(label("Chat · animation mode locked on", 18f)); speechToggle(); historyError?.let { panel.addView(label(it)) }
        for (i in 0 until history.length()) { val m = history.getJSONObject(i); val user = m.optString("role") == "user"; val bubble = label("${if (user) "You" else names[character]}: ${m.optString("content")}").apply { gravity = if (user) Gravity.END else Gravity.START; setTextIsSelectable(true); background = GradientDrawable().apply { setColor(card); cornerRadius = dp(12).toFloat() } }; panel.addView(bubble, LinearLayout.LayoutParams(-1, -2).apply { setMargins(if (user) dp(28) else dp(4), dp(4), if (user) dp(4) else dp(28), dp(4)) }) }
        val input = EditText(this).apply { hint = "Message ${names[character]}"; setTextColor(ink); setHintTextColor(ink); maxLines = 4; isEnabled = !busy && historyError == null }; panel.addView(input)
        button(panel, if (busy) "Waiting for local reply…" else "Send") { submit(input.text.toString()) }.isEnabled = !busy && historyError == null
        button(panel, "Stop response / speech") { stopRequest(); renderPage() }; scroll.post { if (alive && page == "Chat") scroll.fullScroll(View.FOCUS_DOWN) }
    }
    private fun stopSpeech() { speaking = false; utterance = null; tts?.stop() }
    private fun speak(text: String) { stopSpeech(); if (!speechEnabled()) return; if (!voiceReady) { status.text = "Text is available; Android speech is not ready."; return }; val id = "alongside-${++speechSerial}"; utterance = id; val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, appPrefs.getFloat(per("volume"), 1f)) }; if (tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, id) == TextToSpeech.ERROR) stopSpeech() }
    private fun stopRequest() { request++; busy = false; stopSpeech(); val c = connection; if (c != null) Thread { c.disconnect() }.start() }
    private fun submit(raw: String) {
        val prompt = raw.trim(); if (prompt.isEmpty() || busy || character < 0 || historyError != null) return; check(prompt.length <= 8000) { "Message too long" }; stopSpeech(); val ticket = ++request; busy = true
        history.put(JSONObject().put("role", "user").put("content", prompt).put("time", System.currentTimeMillis())); writeHistory()
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "You are ${names[character]}, a friendly personal companion. Be concise and honest. You cannot see the screen or control this phone. Do not claim to perform actions."))
        val recent = mutableListOf<JSONObject>(); var budget = 0
        for (i in history.length() - 1 downTo 0) { val m = history.getJSONObject(i); val content = m.optString("content"); if (budget + content.length > 12000) break; recent.add(0, JSONObject().put("role", m.optString("role")).put("content", content)); budget += content.length }; recent.forEach { messages.put(it) }; renderPage(); status.text = "Waiting for local reply…"
        worker.execute { var c: HttpURLConnection? = null; try {
            if (!alive || ticket != request) return@execute
            c = URL("http://127.0.0.1:12434/v1/chat/completions").openConnection() as HttpURLConnection; connection = c; c.connectTimeout = 10000; c.readTimeout = 180000; c.requestMethod = "POST"; c.doOutput = true; c.instanceFollowRedirects = false; c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("Authorization", "Bearer anything")
            val body = JSONObject().put("model", "qwen2.5-0.5b-instruct-q4_k_m").put("messages", messages).put("stream", false).put("max_tokens", 256).put("temperature", 0.7); c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }; check(c.responseCode in 200..299) { "Local server HTTP ${c.responseCode}" }
            val result = c.inputStream.bufferedReader().use { r -> val b = StringBuilder(); val chars = CharArray(2048); while (true) { val n = r.read(chars); if (n < 0) break; b.append(chars, 0, n); check(b.length <= 262144) { "Response too large" } }; b.toString() }
            val answer = JSONObject(result).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim(); check(answer.isNotEmpty()) { "Empty response" }
            runOnUiThread { if (alive && ticket == request) safe { busy = false; history.put(JSONObject().put("role", "assistant").put("content", answer).put("time", System.currentTimeMillis())); writeHistory(); if (page == "Chat") { renderPage(); speak(answer) }; status.text = "Local reply received." } }
        } catch (e: Exception) { runOnUiThread { if (alive && ticket == request) { busy = false; if (page == "Chat") renderPage(); status.text = "Local reply failed: ${e.message}. No cloud fallback used." } } } finally { c?.disconnect(); if (connection === c) connection = null } }
    }
    private fun checkUpdates() {
        status.text = "Checking GitHub for a newer successful diagnostic build…"
        worker.execute { var c: HttpURLConnection? = null; try { c = URL("https://api.github.com/repos/ChasePlank/android-ai-companion/actions/workflows/android.yml/runs?status=success&branch=main&per_page=1").openConnection() as HttpURLConnection; c.connectTimeout = 10000; c.readTimeout = 10000; c.setRequestProperty("Accept", "application/vnd.github+json"); check(c.responseCode == 200) { "GitHub HTTP ${c.responseCode}; private repositories may require browser sign-in" }; val runs = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getJSONArray("workflow_runs"); check(runs.length() > 0) { "No successful diagnostic builds found" }; val run = runs.getJSONObject(0).getLong("run_number"); val installed = packageManager.getPackageInfo(packageName, 0).longVersionCode; val note = if (run + 4 > installed) "Newer successful diagnostic build $run is available. Open GitHub to install it." else "No newer successful diagnostic build was found."; runOnUiThread { if (alive) status.text = note } } catch (e: Exception) { runOnUiThread { if (alive) status.text = "Update check unavailable: ${e.message}. Use the GitHub link." } } finally { c?.disconnect() } }
    }
    private fun matrix(base: FloatArray, xyz: FloatArray): FloatArray { val r = FloatArray(16); val out = FloatArray(16); Matrix.setIdentityM(r, 0); Matrix.rotateM(r, 0, xyz[0], 1f, 0f, 0f); Matrix.rotateM(r, 0, xyz[1], 0f, 1f, 0f); Matrix.rotateM(r, 0, xyz[2], 0f, 0f, 1f); Matrix.multiplyMM(out, 0, base, 0, r, 0); return out }
    private fun applyNode(key: String, xyz: FloatArray) { val v = viewer ?: return; val entity = entities[key] ?: return; val base = bases[key] ?: return; val tm = v.engine.transformManager; val instance = tm.getInstance(entity); check(instance != 0); tm.setTransform(instance, matrix(base, xyz)) }
    private fun restoreNodes(authored: Boolean = false, only: String? = null) {
        val v = viewer ?: return; val asset = v.asset ?: return; val tm = v.engine.transformManager; val current = asset.entities.toSet()
        val records = bases.entries.filter { only == null || it.key == only }.map { (key, base) -> val entity = entities.getValue(key); check(entity in current); val instance = tm.getInstance(entity); check(instance != 0); val xyz = if (authored) FloatArray(3) else defaults(key); val value = if (xyz.all { it == 0f }) base.copyOf() else matrix(base, xyz); check(value.all { it.isFinite() }); Triple(key, instance, Pair(value, xyz)) }
        records.forEach { (key, instance, value) -> tm.setTransform(instance, value.first); offsets[key] = value.second }; v.animator?.updateBoneMatrices()
    }
    private fun applyOrientation() { val v = viewer ?: return; val asset = v.asset ?: return; val r = FloatArray(16); val out = FloatArray(16); Matrix.setIdentityM(r, 0); Matrix.translateM(r, 0, pivot[0], pivot[1], pivot[2]); Matrix.rotateM(r, 0, orientation[0], 1f, 0f, 0f); Matrix.rotateM(r, 0, orientation[1], 0f, 1f, 0f); Matrix.rotateM(r, 0, orientation[2], 0f, 0f, 1f); Matrix.translateM(r, 0, -pivot[0], -pivot[1], -pivot[2]); Matrix.multiplyMM(out, 0, r, 0, rootBase, 0); val tm = v.engine.transformManager; tm.setTransform(tm.getInstance(asset.root), out) }
    private fun applyLighting() { val v = viewer ?: return; ambient?.setIntensity(30000f * brightness); val lm = v.engine.lightManager; val instance = lm.getInstance(v.light); if (instance != 0) { lm.setIntensity(instance, keyIntensity * brightness); if (character == 2) lm.setDirection(instance, 0f, -0.3f, -1f) else lm.setDirection(instance, keyDirection[0], keyDirection[1], keyDirection[2]) } }
    private fun seedRoxanne() {
        if (character != 0 || profile.has("animations")) return
        fun add(name: String, a: Int, kind: Int, low: Float, high: Float) { val key = entities.keys.firstOrNull { it.substringAfter(':') == name } ?: return; section("animations").put("$key:$a", JSONObject().put("node", key).put("axis", a).put("kind", kind).put("low", low.toDouble()).put("high", high.toDouble())) }
        add(FacialCalibration.JAW, 1, 4, -8f, 20f); listOf(FacialCalibration.LEFT_UPPER_LID, FacialCalibration.RIGHT_UPPER_LID).forEach { add(it, 0, 2, 0f, 50f) }; listOf(FacialCalibration.LEFT_LOWER_LID, FacialCalibration.RIGHT_LOWER_LID).forEach { add(it, 0, 1, -50f, 0f) }; add(RigRestDefaults.HEAD, 1, 0, -10f, 10f)
    }
    private fun animate(time: Long) {
        val v = viewer ?: return; if (clip >= 0 && page == "Animation Test") { if (animationStart == 0L) animationStart = time; val a = v.animator ?: return; if (clip < a.animationCount) { val duration = a.getAnimationDuration(clip); a.applyAnimation(clip, if (duration > 0f) (((time - animationStart) / 1e9) % duration).toFloat() else 0f) }; return }
        if (!live()) return; val t = time / 1e9; val blinkPhase = t % 4.7; val blink = if (blinkPhase < 0.28) sin(blinkPhase / 0.28 * PI).toFloat() else 0f; val speech = if (speaking && speechEnabled()) (0.2 + 0.65 * abs(sin(t * 13))).toFloat() else 0f
        val poses = mutableMapOf<String, FloatArray>(); val channels = section("animations"); val it = channels.keys()
        while (it.hasNext()) { val channel = channels.getJSONObject(it.next()); val key = channel.optString("node"); if (key !in entities) continue; val a = channel.optInt("axis").coerceIn(0, 2); val kind = channel.optInt("kind"); val xyz = poses.getOrPut(key) { defaults(key) }; val low = channel.optDouble("low", -10.0).toFloat(); val high = channel.optDouble("high", 10.0).toFloat(); val range = bounds(key, a); val amount = if (kind <= 2) blink else speech; xyz[a] = when (kind) { 0 -> xyz[a] + sin(t * 0.7).toFloat() * min(abs(high - low) / 4f, 10f); 1, 3 -> xyz[a] + (low - xyz[a]) * amount; else -> xyz[a] + (high - xyz[a]) * amount }.coerceIn(range.first, range.second) }; poses.forEach { (key, xyz) -> applyNode(key, xyz) }
    }
    private val frame = object : Choreographer.FrameCallback { override fun doFrame(time: Long) { if (!alive || !resumed) return; try { viewer?.let { v -> if (ready) { animate(time); v.animator?.updateBoneMatrices() }; v.render(time); if (loading && v.asset != null && v.progress >= 1f) { loading = false; ready = true; orbit?.let { it.jumpToBookmark(it.homeBookmark) }; status.text = "${names[character]} ready."; renderPage() } } } catch (e: Exception) { ready = false; loading = false; status.text = "Render error: ${e.message}. Refresh this character." }; Choreographer.getInstance().postFrameCallback(this) } }
    private fun initializeRenderer(surface: SurfaceView) {
        surface.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener { override fun onViewAttachedToWindow(v: View) {}; override fun onViewDetachedFromWindow(v: View) { alive = false; generation++; Choreographer.getInstance().removeFrameCallback(frame); viewer?.let { it.scene.indirectLight = null; ambient?.let { a -> it.engine.destroyIndirectLight(a) } }; ambient = null; viewer = null; orbit = null; source = null } })
        safe { Utils.init(); val m = Manipulator.Builder().targetPosition(0f, 0f, -4f).orbitHomePosition(0f, 0f, 0f).viewport(1, 1).build(Manipulator.Mode.ORBIT); orbit = m; val v = ModelViewer(surfaceView = surface, manipulator = m); viewer = v; v.autoPlayAnimations = false; v.renderer.clearOptions = Renderer.ClearOptions().apply { clear = true; discard = true; clearColor = doubleArrayOf(Color.red(bg) / 255.0, Color.green(bg) / 255.0, Color.blue(bg) / 255.0, 1.0) }; val lm = v.engine.lightManager; val instance = lm.getInstance(v.light); if (instance != 0) { keyIntensity = lm.getIntensity(instance); lm.getDirection(instance, keyDirection) }; ambient = IndirectLight.Builder().irradiance(1, floatArrayOf(0.7f, 0.7f, 0.7f)).intensity(30000f).build(v.engine); v.scene.indirectLight = ambient; surface.setOnTouchListener { view, e -> if (ready) v.onTouch(view, e) else true }; loadModel() }
    }
    private fun loadModel() {
        val v = viewer ?: return; val ticket = ++generation; ready = false; loading = false; clip = -1; stopSpeech(); profile = JSONObject(); profileKey = ""; orientation = FloatArray(3); entities.clear(); bases.clear(); offsets.clear(); orbit?.let { it.jumpToBookmark(it.homeBookmark) }; renderPage(); status.text = "Loading ${names[character]}…"
        Thread { try { val bytes = assets.open(files[character]).use { it.readBytes() }; val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }; val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()); buffer.put(bytes).flip(); runOnUiThread { if (alive && generation == ticket && viewer === v) safe {
            v.loadModelGlb(buffer); source = buffer; v.transformToUnitCube(); val asset = v.asset ?: error("Model load failed"); val tm = v.engine.transformManager; rootBase = FloatArray(16).also { tm.getTransform(tm.getInstance(asset.root), it) }; val center = asset.boundingBox.center; pivot = FloatArray(4).also { Matrix.multiplyMV(it, 0, rootBase, 0, floatArrayOf(center[0], center[1], center[2], 1f), 0) }
            asset.entities.forEachIndexed { i, entity -> val instance = tm.getInstance(entity); if (instance != 0 && entity != asset.root) { val key = "$i:${asset.getName(entity) ?: "Unnamed node"}"; entities[key] = entity; bases[key] = FloatArray(16).also { tm.getTransform(instance, it) }; offsets[key] = FloatArray(3) } }
            val layout = MessageDigest.getInstance("SHA-256").digest(entities.keys.joinToString("|").toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }; profileKey = "v1:${files[character]}:$digest:$layout"; profile = JSONObject(calibrationPrefs.getString(profileKey, "{}") ?: "{}"); seedRoxanne(); orientation = vector(profile.optJSONArray("orientation")); applyOrientation(); restoreNodes(); val rm = v.engine.renderableManager; entities.forEach { (key, entity) -> val instance = rm.getInstance(entity); if (instance != 0) rm.setLayerMask(instance, 255, if (section("hidden").optBoolean(key, false)) 0 else 1) }; brightness = appPrefs.getFloat(per("brightness"), if (character == 2) 4f else 1f); applyLighting(); loading = true; status.text = "Loading textures…"
        } } } catch (e: Exception) { runOnUiThread { if (alive && generation == ticket) status.text = "Load failed: ${e.message}" } } }.start()
    }
    override fun onResume() { super.onResume(); resumed = true; Choreographer.getInstance().removeFrameCallback(frame); Choreographer.getInstance().postFrameCallback(frame) }
    override fun onPause() { resumed = false; stopSpeech(); Choreographer.getInstance().removeFrameCallback(frame); super.onPause() }
    override fun onDestroy() { alive = false; generation++; stopRequest(); worker.shutdownNow(); tts?.shutdown(); super.onDestroy() }
}
