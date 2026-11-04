from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
J = ROOT / 'app/src/main/java/com/chase/companion'
p = J / 'AlongsideActivity.kt'
s = p.read_text()
def edit(old, new):
    global s
    if s.count(old) != 1: raise RuntimeError('Testing source anchor changed: ' + old[:100])
    s = s.replace(old, new, 1)
edit('    private fun renderPage() {', '    private fun renderPage() { renderTestingContent(); testing.columns(panel, page == "Chat"); window.decorView.post { testing.warning() } }\n    private fun renderTestingContent() {')
edit('    private fun animationPage() {', '    private fun animationPage() {\n        if (ready) { testing.editor(panel); nodePage() }')
edit('panel.addView(label("Settings", 22f));', 'panel.addView(label("Settings", 22f)); testing.settings(panel) { enforceVariant() };')
edit('    private fun enterPage(next: String) {', '    private fun enterPage(next: String) {\n        if (testing.discard { enterPage(next) }) return\n        if (ready) testing.stop(true)')
edit('    private fun chooseCharacter(i: Int, next: String = page) {', '''    private fun chooseCharacter(i: Int, next: String = page) {
        if (testing.discard { chooseCharacter(i, next) }) return
        check(testing.allowed(names[i])) { "This character variant is locked." }
        if (i != character && names[i].startsWith("naked ")) testing.activation()
        if (ready) testing.stop(true)''')
a = s.index('    private fun promptCharacter(next: String) {')
b = s.index('    private fun navigate(next: String)', a)
s = s[:a] + '''    private fun promptCharacter(next: String) {
        val ids = names.indices.filter { i -> (next in listOf("Node Control", "Defaults", "Animation Test", "Toggles", "Full Rig Inspection", "Character Settings") || i < 3) && testing.allowed(names[i]) }
        AlertDialog.Builder(this).setTitle("Choose character · $next").setItems(ids.map { names[it] }.toTypedArray()) { _, row -> chooseCharacter(ids[row], next) }.setNegativeButton("Cancel", null).show()
    }
''' + s[b:]
edit('    private fun togglesPage() {', '''    private fun enforceVariant() {
        stopRequest(); historyError = null; readHistory()
        if (!testing.allowed(names[character])) { val base = names.indexOf(names[character].removePrefix("naked ")); if (base >= 0) chooseCharacter(base) else finish() } else renderPage()
    }
    private fun togglesPage() {
        val base = names.indexOf(names[character].removePrefix("naked "))
        val nude = names.indexOf("naked ${names[character].removePrefix("naked ")}")
        if (base >= 0 && nude >= 0 && testing.allowed(names[nude])) toggle(panel, "Nude variant", character == nude) { chooseCharacter(if (it) nude else base, "Toggles") }''')
edit('private fun loadModel() { schedulePreviewCameraRestore();', 'private fun loadModel() { if (!testing.allowed(names[character])) { enforceVariant(); return }; schedulePreviewCameraRestore();')
edit('    private fun animate(time: Long) {', '    private fun animate(time: Long) {\n        if (testing.tick(time)) return\n        if (page == "Animation Test" && clip < 0) return')
edit('(((time - animationStart) / 1e9) % duration).toFloat()', '(((time - animationStart) / 1e9) * testing.speed()).toFloat().coerceAtMost(duration)')
edit('a.applyAnimation(clip, if (duration > 0f)', 'if (duration > 0f && (time - animationStart) / 1e9 * testing.speed() >= duration) { clip = -1; testing.finish(); return }; a.applyAnimation(clip, if (duration > 0f)')
edit('clip = -1; model { restoreNodes() }', 'clip = -1; model { testing.stop(true) }')
edit('model { clip = chosen; animationStart = 0L }', 'model { testing.stop(true); clip = chosen; animationStart = 0L }')
edit('value -> model { val b = bounds(selectedNode, axis);', 'value -> model { testing.stop(); val b = bounds(selectedNode, axis);')
edit('private fun stopRequest() { request++;', 'private fun stopRequest() { OfflineAi.cancelChat(); request++;')
edit('override fun onPause() { resumed = false;', 'override fun onPause() { if (ready) testing.stop(true); resumed = false;')
edit('override fun onResume() { super.onResume();', 'override fun onResume() { super.onResume(); if (character >= 0 && !testing.allowed(names[character])) { enforceVariant(); return };')
edit('surface.setOnTouchListener { view, e -> if (ready) v.onTouch(view, e) else true }', 'surface.setOnTouchListener { view, e -> if (ready) { if (e.actionMasked == MotionEvent.ACTION_DOWN && (testing.running() || clip >= 0)) { clip = -1; testing.stop(true) }; v.onTouch(view, e) } else true }')
edit('"chat-${files[character]}.json"', '"chat-${if (testing.enabled()) "dev-" else ""}${files[character]}.json"')
a = s.index('    private fun submit(raw: String) {')
b = s.index('    private fun checkUpdates()', a)
s = s[:a] + '''    private fun submit(raw: String) {
        val prompt = raw.trim(); if (prompt.isEmpty() || busy || historyError != null) return
        check(prompt.length <= 1000) { "Local test messages are limited to 1000 characters." }
        stopSpeech(); val ticket = ++request; busy = true
        val turns = (0 until history.length()).map { history.getJSONObject(it) }.map { it.optString("role") to it.optString("content") }
        history.put(JSONObject().put("role", "user").put("content", prompt).put("time", System.currentTimeMillis())); writeHistory(); renderPage()
        OfflineAi.chat(this, names[character], prompt, turns, { note -> if (alive && ticket == request) status.text = note }) { answer, error ->
            if (alive && ticket == request) safe {
                busy = false
                if (answer != null) { history.put(JSONObject().put("role", "assistant").put("content", answer).put("time", System.currentTimeMillis())); writeHistory() }
                if (page == "Chat") { renderPage(); if (answer != null) speak(answer) }
                status.text = error ?: "Bundled Qwen3-4B reply received."
            }
        }
    }
''' + s[b:]
edit('    private fun dp(v: Int)', '''    private val testing by lazy { TestingFeatures(this, appPrefs, { names[character] }, { captureTesting(false) }, { captureTesting(true) }, { applyTesting(it) }, { renderPage() }) }
    private fun captureTesting(saved: Boolean): JSONObject {
        val nodes = JSONObject(); offsets.forEach { (k, xyz) -> nodes.put(k, array(if (saved) defaults(k) else xyz)) }
        val m = checkNotNull(orbit); val current = PreviewCameraDefaults.capture(m)
        val camera = if (!saved) current else profile.optJSONObject("preview_camera") ?: run { m.jumpToBookmark(m.homeBookmark); val home = PreviewCameraDefaults.capture(m); PreviewCameraDefaults.restore(m, current); home }
        return JSONObject().put("nodes", nodes).put("orientation", array(if (saved) vector(profile.optJSONArray("orientation")) else orientation)).put("camera", camera)
    }
    private fun applyTesting(pose: JSONObject) {
        val nodes = pose.getJSONObject("nodes")
        offsets.keys.toList().forEach { k -> val xyz = vector(nodes.optJSONArray(k) ?: array(defaults(k))); for (i in 0..2) { val b = bounds(k, i); xyz[i] = xyz[i].coerceIn(b.first, b.second) }; offsets[k] = xyz; applyNode(k, xyz) }
        orientation = vector(pose.optJSONArray("orientation")); applyOrientation(); orbit?.let { PreviewCameraDefaults.restore(it, pose.optJSONObject("camera")) }; viewer?.animator?.updateBoneMatrices(); nodeSync?.invoke()
    }
    private fun dp(v: Int)''')
p.write_text(s)
q = J / 'OfflineAi.kt'
o = q.read_text(); anchor = '    fun show(a: Activity) {'
assert o.count(anchor) == 1
q.write_text(o.replace(anchor, r'''    fun cancelChat() { if (busy.get()) stop() }
    fun chat(a: Activity, name: String, text: String, turns: List<Pair<String, String>>, phase: (String) -> Unit, done: (String?, String?) -> Unit) {
        if (nativeError != null) { done(null, nativeError); return }
        if (!busy.compareAndSet(false, true)) { done(null, "Local inference is busy."); return }
        cancelled = false; resetNative()
        worker.execute {
            try {
                val model = prepare(a) { note -> a.runOnUiThread { phase(note) } }
                val prompt = buildString {
                    append("<|im_start|>system\nYou are ").append(content(name)).append(", a personal companion. Be concise and honest. Never claim phone actions you cannot perform.<|im_end|>\n")
                    turns.forEach { (role, value) -> if (role == "user" || role == "assistant") append("<|im_start|>").append(role).append("\n").append(content(value)).append("<|im_end|>\n") }
                    append("<|im_start|>user\n").append(content(text)).append("\n/no_think<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n")
                }
                val bytes = ByteArrayOutputStream()
                runNative(model.absolutePath, prompt.toByteArray(Charsets.UTF_8), (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4), object : Sink { override fun phase(text: String) { a.runOnUiThread { phase(text) } }; override fun chunk(chunk: ByteArray) { bytes.write(chunk) } })
                check(!cancelled) { "Cancelled" }; val answer = bytes.toString(Charsets.UTF_8.name()).trim(); check(answer.isNotEmpty()) { "Empty reply" }
                a.runOnUiThread { done(answer, null) }
            } catch (e: Exception) { a.runOnUiThread { done(null, "Local inference failed: ${e.message}. History is not silently shortened. Clear history or shorten the message if context is full.") } } finally { busy.set(false) }
        }
    }
''' + anchor, 1))
f = ROOT / 'tools/prepare_offline_ai.py'
t = f.read_text().replace("REV = '8e42d41f70cb6c571f58c3f31bd9287b372d97cc'", "REV = json.loads(urllib.request.urlopen('https://huggingface.co/api/models/lmstudio-community/Qwen3-4B-GGUF', timeout=180).read())['sha']").replace("REPO = 'Qwen/Qwen3-0.6B-GGUF'", "REPO = 'lmstudio-community/Qwen3-4B-GGUF'").replace("REMOTE = 'Qwen3-0.6B-Q4_K_M.gguf'", "REMOTE = 'Qwen3-4B-Q4_K_M.gguf'").replace('qwen3-0.6b-q4_k_m.gguf', 'qwen3-4b-q4_k_m.gguf')
f.write_text(t + "\n(assets / 'qwen3-0.6b-q4_k_m.gguf').unlink(missing_ok=True)\n")
b = ROOT / 'app/build.gradle'; b.write_text(b.read_text().replace('qwen3-0.6b-q4_k_m.gguf', 'qwen3-4b-q4_k_m.gguf'))
(J / 'TestingFeatures.kt').write_text(r'''package com.chase.companion
import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
import android.text.InputType
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal class TestingFeatures(private val a: Activity, private val prefs: SharedPreferences, private val character: () -> String, private val capture: () -> JSONObject, private val defaults: () -> JSONObject, private val apply: (JSONObject) -> Unit, private val refresh: () -> Unit) {
    private var key = ""
    private var library = JSONObject()
    private var selected = ""
    private var draft = fresh()
    private var dirty = false
    private var sequence: List<JSONObject>? = null
    private var initial = JSONObject()
    private var last = 0L
    private var elapsed = 0.0
    private var multiplier = 1.0
    private fun fresh() = JSONObject().put("speed", 1.0).put("poses", JSONArray())
    private fun copy(o: JSONObject) = JSONObject(o.toString())
    private fun base(n: String = character()) = n.removePrefix("naked ")
    private fun load() { val next = "studio:${character()}"; if (key == next) return; key = next; library = JSONObject(prefs.getString(key, "{}") ?: "{}"); selected = library.keys().asSequence().firstOrNull().orEmpty(); restore() }
    private fun restore() { draft = library.optJSONObject(selected)?.let { copy(it) } ?: fresh(); dirty = false }
    fun enabled(): Boolean { if (prefs.getBoolean("dev_access_blocked", false)) { prefs.edit().putBoolean("dev_enabled", false).apply(); return false }; return prefs.getBoolean("dev_enabled", false) }
    fun allowed(n: String): Boolean = !n.startsWith("naked ") || base(n) == "Fexa" || enabled() || prefs.getInt("relationship_real:${base(n)}", 100) in 1000..1099
    fun activation() { val count = prefs.getLong("nude_activation_count", 0) + 1; prefs.edit().putLong("nude_activation_count", count).putBoolean("nude_warning_pending", prefs.getBoolean("nude_warning_pending", false) || count % 10L == 0L).apply() }
    fun warning() { if (!prefs.getBoolean("nude_warning_pending", false) || a.isFinishing || a.isDestroyed) return; prefs.edit().putBoolean("nude_warning_pending", false).apply(); AlertDialog.Builder(a).setTitle("Nude model reminder").setMessage("Nude model display is enabled. Developer mode bypasses relationship requirements for testing.").setPositiveButton("OK", null).show() }
    private fun button(p: LinearLayout, title: String, action: () -> Unit) { p.addView(Button(a).apply { text = title; isAllCaps = false; setOnClickListener { try { action() } catch (e: Exception) { Toast.makeText(a, e.message, Toast.LENGTH_LONG).show() } } }) }
    fun settings(p: LinearLayout, changed: () -> Unit) {
        p.addView(Switch(a).apply { text = "Developer mode"; isChecked = enabled(); setOnCheckedChangeListener { _, on ->
            if (!on) { prefs.edit().putBoolean("dev_enabled", false).apply(); changed() } else {
                isChecked = false
                val input = EditText(a).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
                AlertDialog.Builder(a).setTitle("Developer password · case sensitive").setView(input).setNegativeButton("Cancel", null).setPositiveButton("Unlock") { _, _ ->
                    if (!prefs.getBoolean("dev_access_blocked", false) && input.text.toString() == "Roxanne") { prefs.edit().putBoolean("dev_enabled", true).apply(); changed() } else Toast.makeText(a, "Incorrect password or access blocked.", Toast.LENGTH_LONG).show()
                }.show()
            }
        } })
        if (enabled()) button(p, "Test relationship · ${base()}") {
            val levels = listOf("Hate", "Dislike", "Acquaintance", "Teammate", "Friend", "Trusted Companion", "Close Friend", "Best Friend", "Confidant", "Partner", "Sweetheart", "Lover")
            val scores = listOf(0, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000)
            AlertDialog.Builder(a).setTitle("Isolated developer override").setItems(levels.toTypedArray()) { _, i -> prefs.edit().putInt("relationship_override:${base()}", scores[i]).apply(); changed() }.show()
        }
    }
    fun columns(p: LinearLayout, chat: Boolean) {
        val views = (0 until p.childCount).map { p.getChildAt(it) }; p.removeAllViews(); var i = 0
        fun eligible(v: android.view.View?) = v is Button && v.text.toString() != "Chat" && !v.text.toString().startsWith("▸") && !v.text.toString().startsWith("▾")
        while (i < views.size) { if (!chat && eligible(views[i]) && eligible(views.getOrNull(i + 1))) { val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }; row.addView(views[i], LinearLayout.LayoutParams(0, -2, 1f)); row.addView(views[i + 1], LinearLayout.LayoutParams(0, -2, 1f)); p.addView(row); i += 2 } else { p.addView(views[i]); i++ } }
    }
    private fun number(title: String, old: Double, positive: Boolean = false, done: (Double) -> Unit) {
        val input = EditText(a).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; setText(old.toString()) }
        val d = AlertDialog.Builder(a).setTitle(title).setView(input).setNegativeButton("Cancel", null).setPositiveButton("Apply", null).create()
        d.setOnShowListener { d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { val n = input.text.toString().toDoubleOrNull(); if (n == null || !n.isFinite() || n < 0 || (positive && n <= 0) || n > 3600) input.error = "Enter a valid number up to 3600." else { done(n); dirty = true; d.dismiss(); refresh() } } }; d.show()
    }
    fun editor(p: LinearLayout) {
        load(); p.addView(TextView(a).apply { text = "Pose editor · calibration remains separate" })
        button(p, "Load saved animation") { val ids = library.keys().asSequence().toList(); if (ids.isEmpty()) Toast.makeText(a, "No saved animations.", Toast.LENGTH_SHORT).show() else AlertDialog.Builder(a).setTitle("Saved animations").setItems(ids.map { library.getJSONObject(it).optString("name", it) }.toTypedArray()) { _, i -> val open = { stop(); selected = ids[i]; restore(); refresh() }; if (!discard(open)) open() }.show() }
        button(p, "New animation") { val fresh = { stop(); selected = ""; draft = fresh(); dirty = false; refresh() }; if (!discard(fresh)) fresh() }
        button(p, "Overall speed · ${speed()}×") { number("Overall speed multiplier", speed(), true) { draft.put("speed", it) } }
        button(p, "Set new pose from current nodes and camera") { stop(); draft.getJSONArray("poses").put(JSONObject().put("pose", capture()).put("move", 1.0).put("hold", 1.0)); dirty = true; refresh() }
        val poses = draft.getJSONArray("poses")
        for (i in 0 until poses.length()) {
            val item = poses.getJSONObject(i)
            p.addView(TextView(a).apply { text = "Pose ${i + 1} · transition ${item.optDouble("move", 1.0)} s · hold ${item.optDouble("hold", 1.0)} s" })
            button(p, "Load pose ${i + 1}") { stop(); apply(item.getJSONObject("pose")) }
            button(p, "Set pose ${i + 1} from current state") { stop(); item.put("pose", capture()); dirty = true; refresh() }
            button(p, "Copy pose ${i + 1}") { poses.put(copy(item)); dirty = true; refresh() }
            button(p, "Transition · pose ${i + 1}") { number("Transition seconds", item.optDouble("move", 1.0)) { item.put("move", it) } }
            button(p, "Hold · pose ${i + 1}") { number("Hold seconds", item.optDouble("hold", 1.0)) { item.put("hold", it) } }
        }
        button(p, "Play animation") { require(poses.length() > 0) { "Set at least one pose." }; initial = capture(); sequence = (0 until poses.length()).map { copy(poses.getJSONObject(it)) } + JSONObject().put("pose", defaults()).put("move", 0.5).put("hold", 0.0); multiplier = speed(); last = 0L; elapsed = 0.0 }
        button(p, "Interrupt · instant reset") { stop(true) }
        button(p, "Save animation") { save(false) }; button(p, "Save as copy") { save(true) }
        button(p, "Don't save") { if (!discard { refresh() }) { stop(); restore(); refresh() } }
    }
    private fun save(asCopy: Boolean) { val input = EditText(a).apply { setText(draft.optString("name", "Animation")) }; AlertDialog.Builder(a).setTitle(if (asCopy) "Save as new copy" else "Save animation").setView(input).setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ -> if (asCopy || selected.isEmpty()) selected = UUID.randomUUID().toString(); draft.put("name", input.text.toString().trim().take(100).ifEmpty { "Animation" }); library.put(selected, copy(draft)); prefs.edit().putString(key, library.toString()).apply(); dirty = false; refresh() }.show() }
    fun discard(action: () -> Unit): Boolean { if (!dirty) return false; AlertDialog.Builder(a).setTitle("Discard unsaved animation changes?").setMessage("Restore the last saved animation? Calibration and saved defaults are unchanged.").setNegativeButton("Keep editing", null).setPositiveButton("Discard") { _, _ -> stop(); restore(); action() }.show(); return true }
    fun speed(): Double { load(); return draft.optDouble("speed", 1.0).let { if (it.isFinite() && it > 0) it else 1.0 } }
    fun running() = sequence != null
    fun stop(force: Boolean = false) { val reset = force || sequence != null; sequence = null; last = 0L; if (reset) apply(defaults()) }
    fun finish() { initial = capture(); sequence = listOf(JSONObject().put("pose", defaults()).put("move", 0.5).put("hold", 0.0)); multiplier = speed(); last = 0L; elapsed = 0.0 }
    private fun blend(x: JSONObject, y: JSONObject, t: Double): JSONObject {
        val result = JSONObject(); y.keys().forEach { k -> val a = x.opt(k); val b = y.get(k); val v: Any = when { a is JSONObject && b is JSONObject -> blend(a, b, t); a is JSONArray && b is JSONArray && a.length() == b.length() -> JSONArray((0 until b.length()).map { j -> a.getDouble(j) + (b.getDouble(j) - a.getDouble(j)) * t }); a is Number && b is Number && k != "version" -> a.toDouble() + (b.toDouble() - a.toDouble()) * t; else -> b }; result.put(k, v) }; return result
    }
    fun tick(time: Long): Boolean {
        val poses = sequence ?: return false; if (last == 0L) last = time; elapsed += (time - last).coerceAtLeast(0L) / 1e9 * multiplier; last = time
        var left = elapsed; var from = initial
        for (item in poses) { val move = item.optDouble("move", 1.0).coerceAtLeast(0.0); val hold = item.optDouble("hold", 0.0).coerceAtLeast(0.0); val to = item.getJSONObject("pose"); if (left < move) { val t = (left / move).coerceIn(0.0, 1.0); apply(blend(from, to, t * t * (3 - 2 * t))); return true }; left -= move; if (left < hold) { apply(to); return true }; left -= hold; from = to }
        apply(from); sequence = null; return true
    }
}
''')
print('Applied experimental testing implementation. Kotlin compilation and device tests still required.')
