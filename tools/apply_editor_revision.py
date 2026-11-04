from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
J = ROOT / 'app/src/main/java/com/chase/companion'
def once(s, old, new):
    if s.count(old) != 1: raise RuntimeError('Revision anchor changed: ' + old[:100])
    return s.replace(old, new, 1)
p = J / 'AlongsideActivity.kt'
s = p.read_text()
s = once(s, 'if (ready) { testing.editor(panel); nodePage() }', 'if (ready) testing.libraryPage(panel)')
s = once(s, '            "Animation Test" -> animationPage()', '''            "Animation Test" -> animationPage()
            "Animation Editor" -> { if (ready) { nodePage(); testing.editor(panel) } else panel.addView(label("Loading model…")) }''')
s = once(s, 'nodeSync = null; panel.removeAllViews();', 'testing.editPage = { stopRequest(); clip = -1; if (ready) testing.stop(true); page = "Animation Editor"; intent.putExtra("page", page); renderPage() }; nodeSync = null; panel.removeAllViews();')
s = once(s, 'value -> model { testing.stop(); val b = bounds(selectedNode, axis);', 'value -> model { testing.stop(); if (page == "Animation Editor") testing.poseEdited(); val b = bounds(selectedNode, axis);')
s = once(s, 'v.onTouch(view, e) } else true }', 'if (page == "Animation Editor" && e.actionMasked == MotionEvent.ACTION_UP) testing.poseEdited(); v.onTouch(view, e) } else true }')
s = once(s, 'override fun onResume() { super.onResume();', 'override fun onResume() { super.onResume(); PersistentListening.resume(this);')
s = s.replace('"Animation Test"', '"Animations"').replace('Animation tests ·', 'Animations ·')
s = once(s, 'page = intent.getStringExtra("page") ?: "Full Rig Inspection"', 'page = intent.getStringExtra("page") ?: "Full Rig Inspection"; if (page == "Animation Test") page = "Animations"')
s = s.replace('"Stop clip"', '"Cancel animation"')
p.write_text(s)
p = J / 'TestingFeatures.kt'
s = p.read_text()
s = once(s, 'import java.util.UUID', 'import java.util.UUID\nimport kotlin.math.*\nimport android.graphics.Color\nimport android.graphics.drawable.GradientDrawable\nimport android.view.Gravity')
s = once(s, '    private var key = ""', '    var editPage: (() -> Unit)? = null\n    private var editorLoaded = false\n    private var cursor = 0\n    fun poseEdited() { dirty = true }\n    private var key = ""')
s = once(s, 'private fun restore() { draft = library.optJSONObject(selected)?.let { copy(it) } ?: fresh(); dirty = false }', '''private fun restore() {
        draft = library.optJSONObject(selected)?.let { copy(it) } ?: fresh()
        val poses = draft.getJSONArray("poses"); val editable = JSONArray()
        for (i in 0 until poses.length()) if (!poses.getJSONObject(i).optBoolean("locked", false)) editable.put(copy(poses.getJSONObject(i)))
        draft.put("poses", editable); cursor = 0; dirty = false
    }''')
a = s.index('    private fun button(')
b = s.index('    fun settings(', a)
s = s[:a] + '''    private fun dp(n: Int) = (n * a.resources.displayMetrics.density).roundToInt()
    private fun style(b: Button) {
        val colors = when (prefs.getString("theme", "Natural")) {
            "Light" -> intArrayOf(Color.rgb(24,35,50), Color.WHITE, Color.rgb(176,35,48))
            "Dark" -> intArrayOf(Color.rgb(238,244,252), Color.rgb(29,43,60), Color.rgb(112,183,255))
            else -> intArrayOf(Color.rgb(255,145,138), Color.rgb(15,49,82), Color.rgb(244,76,84))
        }
        b.setTextColor(colors[0]); b.isAllCaps = false; b.gravity = Gravity.CENTER
        b.setPadding(dp(8), dp(8), dp(8), dp(8)); b.minHeight = dp(56)
        b.background = GradientDrawable().apply { setColor(colors[1]); cornerRadius = dp(10).toFloat(); setStroke(dp(1), colors[2]) }
    }
    private fun button(p: LinearLayout, title: String, action: () -> Unit) {
        p.addView(Button(a).apply { text = title; style(this); setOnClickListener { try { action() } catch (e: Exception) { Toast.makeText(a, e.message, Toast.LENGTH_LONG).show() } } }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
    }
    private fun pair(p: LinearLayout, left: String, right: String, first: () -> Unit, second: () -> Unit) {
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        button(row, left, first); button(row, right, second)
        for (i in 0..1) row.getChildAt(i).layoutParams = LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
        p.addView(row)
    }
    private fun caption(p: LinearLayout, value: String) { p.addView(TextView(a).apply { text = value; setPadding(dp(8), dp(6), dp(8), dp(6)); setTextColor(when (prefs.getString("theme", "Natural")) { "Light" -> Color.rgb(24,35,50); "Dark" -> Color.rgb(238,244,252); else -> Color.rgb(255,145,138) }) }) }
''' + s[b:]
a = s.index('    fun columns(')
b = s.index('    private fun number(', a)
s = s[:a] + '''    fun columns(p: LinearLayout, chat: Boolean) {
        val views = (0 until p.childCount).map { p.getChildAt(it) }.toMutableList()
        val nodeNames = listOf("Name selected node / edit alias", "Set selected node default", "Set all node defaults", "Set min · current node/axis", "Set max · current node/axis", "Clear selected axis limits", "Reset selected node")
        val nodes = nodeNames.map { name -> views.firstOrNull { it is Button && it.text.toString() == name } }
        if (nodes.all { it != null }) { val position = views.indexOf(nodes[0]); views.removeAll(nodes.toSet()); views.addAll(position, nodes.filterNotNull()) }
        fun theme(v: android.view.View) {
            if (v is Button && v !is android.widget.CompoundButton) style(v)
            if (v is android.view.ViewGroup) for (j in 0 until v.childCount) theme(v.getChildAt(j))
        }
        fun eligible(v: android.view.View?) = v is Button && v !is android.widget.CompoundButton && v.text.toString() !in listOf("Chat", "Name selected node / edit alias") && !v.text.toString().startsWith("▸") && !v.text.toString().startsWith("▾")
        p.removeAllViews(); var i = 0
        while (i < views.size) {
            val v = views[i]; theme(v)
            if (!chat && eligible(v) && eligible(views.getOrNull(i + 1))) {
                val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
                val other = views[i + 1]; theme(other)
                listOf(v, other).forEach { row.addView(it, LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }) }
                p.addView(row); i += 2
            } else { p.addView(v, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }); i++ }
        }
    }
''' + s[b:]
a = s.index('    fun editor(')
b = s.index('    private fun save(', a)
s = s[:a] + '''    private fun entry(pose: JSONObject, locked: Boolean = false) = JSONObject().put("pose", copy(pose)).put("move", 1.0).put("hold", if (locked) 0.0 else 1.0).put("local", 1.0).put("locked", locked)
    private fun ensurePose() { if (draft.getJSONArray("poses").length() == 0) draft.getJSONArray("poses").put(entry(defaults())); cursor = cursor.coerceIn(0, draft.getJSONArray("poses").length() - 1) }
    private fun commitPose() { ensurePose(); draft.getJSONArray("poses").getJSONObject(cursor).put("pose", capture()); dirty = true }
    private fun playDraft() {
        val poses = draft.getJSONArray("poses"); require(poses.length() > 0) { "Create at least one pose." }
        initial = capture(); sequence = listOf(entry(defaults(), true)) + (0 until poses.length()).map { copy(poses.getJSONObject(it)) } + entry(defaults(), true)
        multiplier = speed(); last = 0L; elapsed = 0.0
    }
    fun libraryPage(p: LinearLayout) {
        load(); editorLoaded = false; caption(p, "Animations")
        pair(p, "New animation", "Edit animation", {
            val open = { stop(); selected = ""; draft = fresh(); cursor = 0; ensurePose(); dirty = false; editPage?.invoke() }
            if (!discard(open)) open()
        }, {
            val ids = library.keys().asSequence().toList()
            if (ids.isEmpty()) Toast.makeText(a, "No saved animations.", Toast.LENGTH_SHORT).show()
            else AlertDialog.Builder(a).setTitle("Edit animation").setItems(ids.map { library.getJSONObject(it).optString("name", "Animation") }.toTypedArray()) { _, n ->
                val open = { stop(); selected = ids[n]; restore(); ensurePose(); editPage?.invoke() }; if (!discard(open)) open()
            }.show()
        })
        pair(p, "Play animation", "Cancel animation", {
            val ids = library.keys().asSequence().toList()
            if (ids.isEmpty()) Toast.makeText(a, "No saved animations.", Toast.LENGTH_SHORT).show()
            else AlertDialog.Builder(a).setTitle("Play animation").setItems(ids.map { library.getJSONObject(it).optString("name", "Animation") }.toTypedArray()) { _, n ->
                val play = { stop(); selected = ids[n]; restore(); playDraft() }; if (!discard(play)) play()
            }.show()
        }, { stop(true) })
    }
    private fun speedSlider(p: LinearLayout, title: String, value: Double, changed: (Double) -> Unit) {
        val label = TextView(a); p.addView(label)
        fun update(v: Double) { label.text = String.format(java.util.Locale.US, "%s · %.2f×", title, v); label.setTextColor(when (prefs.getString("theme", "Natural")) { "Light" -> Color.rgb(24,35,50); "Dark" -> Color.rgb(238,244,252); else -> Color.rgb(255,145,138) }) }
        val slider = SeekBar(a).apply { max = 200; progress = (100 + ln(value.coerceIn(0.25, 4.0)) / ln(2.0) * 50).roundToInt().coerceIn(0, 200) }
        update(value); p.addView(slider)
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(b: SeekBar, n: Int, user: Boolean) { if (user) { val v = 2.0.pow((n - 100) / 50.0); changed(v); dirty = true; update(v) } }
            override fun onStartTrackingTouch(b: SeekBar) {}
            override fun onStopTrackingTouch(b: SeekBar) {}
        })
        button(p, "Reset $title to 1×") { slider.progress = 100; changed(1.0); dirty = true; update(1.0) }
    }
    fun editor(p: LinearLayout) {
        load(); ensurePose(); val poses = draft.getJSONArray("poses"); val item = poses.getJSONObject(cursor); if (!editorLoaded) { apply(item.getJSONObject("pose")); editorLoaded = true }
        caption(p, "Pose ${cursor + 1} of ${poses.length()} · default start/end poses are added automatically and cannot be edited")
        pair(p, "Set pose", "Copy pose", { stop(); commitPose(); refresh() }, {
            stop(); commitPose(); poses.put(copy(item)); cursor = poses.length() - 1; dirty = true; apply(poses.getJSONObject(cursor).getJSONObject("pose")); refresh()
        })
        pair(p, "Previous pose", "Next pose", {
            stop(); commitPose(); cursor = (cursor - 1).coerceAtLeast(0); apply(poses.getJSONObject(cursor).getJSONObject("pose")); refresh()
        }, {
            stop(); commitPose(); if (cursor == poses.length() - 1) poses.put(entry(capture())); cursor++; dirty = true; apply(poses.getJSONObject(cursor).getJSONObject("pose")); refresh()
        })
        button(p, "Hold") { number("Hold seconds", item.optDouble("hold", 1.0)) { item.put("hold", it) } }
        speedSlider(p, "Pose speed", item.optDouble("local", 1.0)) { item.put("local", it) }
        speedSlider(p, "Animation speed", speed()) { draft.put("speed", it) }
        pair(p, "Play animation", "Cancel animation", { commitPose(); playDraft() }, { stop(true) })
        pair(p, "Save", "Save as copy", { commitPose(); save(false) }, { commitPose(); save(true) })
        button(p, "Don't save") { if (!discard { refresh() }) { stop(); restore(); refresh() } }
    }
''' + s[b:]
s = once(s, 'if (it.isFinite() && it > 0) it else 1.0', 'if (it.isFinite() && it > 0) it.coerceIn(0.25, 4.0) else 1.0')
s = once(s, 'stop(); restore(); action()', 'stop(); restore(); val poses = draft.getJSONArray("poses"); apply(if (poses.length() > 0) poses.getJSONObject(0).getJSONObject("pose") else defaults()); action()')
s = once(s, 'library.put(selected, copy(draft));', '''val stored = copy(draft); val poses = JSONArray().put(entry(defaults(), true)); val editable = draft.getJSONArray("poses"); for (i in 0 until editable.length()) poses.put(copy(editable.getJSONObject(i))); poses.put(entry(defaults(), true)); stored.put("poses", poses); library.put(selected, stored);''')
s = once(s, 'val move = item.optDouble("move", 1.0).coerceAtLeast(0.0);', 'val move = item.optDouble("move", 1.0).coerceAtLeast(0.0) / item.optDouble("local", 1.0).coerceIn(0.25, 4.0);')
p.write_text(s)
# Persistent microphone intent; OS interruptions do not change the saved toggle.
p = J / 'CompanionCue.kt'
s = p.read_text(); a = s.index('        toggle("Microphone listening'); b = s.index('        toggle("Overlay enabled"', a)
s = s[:a] + '''        toggle("Microphone listening", prefs(a).getBoolean("listening_enabled", false)) { _, enabled ->
            prefs(a).edit().putBoolean("listening_enabled", enabled).apply()
            if (!enabled) a.stopService(Intent(a, HandsFreeService::class.java))
            else if (a.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                a.startActivity(Intent(a, HandsFreeActivity::class.java))
            } else PersistentListening.resume(a)
        }
''' + s[b:]; p.write_text(s)
p = J / 'HandsFreeActivity.kt'; s = p.read_text()
s = once(s, 'text("Roxy hands-free — experimental local test")', 'text("Hands-free listening")')
a = s.index('        text("Not production 24/7 listening.')
b = s.index('\n', a)
s = s[:a] + '        text("Listening defaults off. Your toggle choice is saved until you change it; there is no 20-minute session limit. On-device recognition may have gaps or be interrupted by Android. After reboot or force-stop, open the app to resume the saved ON setting. No cloud recognition fallback.")' + s[b:]
s = once(s, 'button("Start local hands-free test (20 minutes)") { requestStart() }\n        button("Stop listening / speech / request") { stopService(Intent(this, HandsFreeService::class.java)) }', '''panel.addView(Switch(this).apply {
            text = "Microphone listening"
            isChecked = getSharedPreferences("hands_free_options", MODE_PRIVATE).getBoolean("listening_enabled", false)
            setOnCheckedChangeListener { _, enabled ->
                getSharedPreferences("hands_free_options", MODE_PRIVATE).edit().putBoolean("listening_enabled", enabled).apply()
                if (enabled) requestStart() else stopService(Intent(this@HandsFreeActivity, HandsFreeService::class.java))
            }
        })''')
s = once(s, 'setContentView(ScrollView(this).apply { addView(panel) })', 'setContentView(ScrollView(this).apply { addView(panel) }); if (getSharedPreferences("hands_free_options", MODE_PRIVATE).getBoolean("listening_enabled", false) && !HandsFreeState.active) panel.post { requestStart() }')
s = once(s, 'private fun startTest() {', 'private fun startTest() {\n        if (!getSharedPreferences("hands_free_options", MODE_PRIVATE).getBoolean("listening_enabled", false)) return')
s = once(s, 'override fun onResume() { super.onResume(); main.post(refresh) }', 'override fun onResume() { super.onResume(); PersistentListening.resume(this); main.post(refresh) }')
s = once(s, '    private val testTimeout = Runnable { end("20-minute experimental session finished. Start another test from the app.") }\n', '')
s = once(s, '            main.postDelayed(testTimeout, 20 * 60 * 1000L)\n', '')
s = once(s, 'if (intent?.action == "STOP") { end("Stopped by user"); return START_NOT_STICKY }', 'if (intent?.action == "STOP") { getSharedPreferences("hands_free_options", MODE_PRIVATE).edit().putBoolean("listening_enabled", false).apply(); end("Stopped by user"); return START_NOT_STICKY }; if (!getSharedPreferences("hands_free_options", MODE_PRIVATE).getBoolean("listening_enabled", false)) { end("Listening is off"); return START_NOT_STICKY }')
s = once(s, 'if (HandsFreeState.active) return START_NOT_STICKY', 'if (HandsFreeState.active) return START_STICKY')
s = once(s, '        return START_NOT_STICKY\n    }\n    private fun notification', '        return START_STICKY\n    }\n    private fun notification')
s = once(s, '"stop listening" -> { end("Stopped by voice command"); return }', '"stop listening" -> { getSharedPreferences("hands_free_options", MODE_PRIVATE).edit().putBoolean("listening_enabled", false).apply(); end("Stopped by voice command"); return }')
p.write_text(s)
(J / 'PersistentListening.kt').write_text('''package com.chase.companion
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.speech.SpeechRecognizer
internal object PersistentListening {
    fun resume(a: Activity) {
        if (!a.getSharedPreferences("hands_free_options", Context.MODE_PRIVATE).getBoolean("listening_enabled", false) || HandsFreeState.active) return
        if (a.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { HandsFreeState.phase = "Listening is saved ON; microphone permission is required."; return }
        if (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(a)) { HandsFreeState.phase = "Listening is saved ON; on-device recognition is unavailable."; return }
        try { a.startForegroundService(Intent(a, HandsFreeService::class.java)) } catch (e: RuntimeException) { HandsFreeState.phase = "Listening is saved ON; Android could not start capture: ${e.message}" }
    }
}
''')
print('Applied themed animation editor, node grouping, incoming-pose timing and persistent microphone toggle. Wave and inference code unchanged.')
