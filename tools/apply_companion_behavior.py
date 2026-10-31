from pathlib import Path
import runpy

root = Path('app/src/main/java/com/chase/companion')
if 'private fun promptCharacter(' not in (root / 'AlongsideActivity.kt').read_text():
    runpy.run_path('tools/apply_screen_behavior.py', run_name='__main__')

def edit(name, changes):
    p = root / name
    s = p.read_text()
    for old, new in changes:
        if s.count(old) != 1:
            raise RuntimeError(f'{name}: source changed; refusing replacement: {old[:90]}')
        s = s.replace(old, new, 1)
    p.write_text(s)

edit('AlongsideActivity.kt', [('panel.addView(label("Settings", 22f));', 'panel.addView(label("Settings", 22f)); if (page == "Settings") CompanionCue.addSettings(this, panel, ink);')])
edit('HandsFreeActivity.kt', [
    ('(roxy|roxanne)', '(roxy|roxanne|loona|fexa)'),
    ('private val history = ArrayList<Pair<String, String>>()', 'private val histories = mutableMapOf<Int, ArrayList<Pair<String, String>>>()\n    private val history get() = histories.getOrPut(CompanionCue.character) { ArrayList() }'),
    ('val match = wake.matchEntire(text)', 'val match = wake.matchEntire(text)\n        if (match != null) CompanionCue.select(this, match.groupValues[1])'),
    ('heardWakeInSession = true\n            phase(', 'heardWakeInSession = true\n            wake.matchEntire(text)?.let { CompanionCue.select(this, it.groupValues[1]) }\n            CompanionCue.readyCue(this)\n            phase('),
    ('if (showWhenMicReady && awaiting) {', 'if (showWhenMicReady && awaiting) {\n            CompanionCue.readyCue(this)'),
    ('You are Roxy, a friendly personal AI companion.', 'You are ${CompanionCue.name}, a friendly personal AI companion.'),
    ('history.clear()', 'histories.clear()'),
    ('if (!diagnostic || !ttsReady) {', 'val options = getSharedPreferences("alongside_ui_v1", MODE_PRIVATE)\n        if (!diagnostic || !ttsReady || !options.getBoolean("speech:${CompanionCue.file}", true)) {'),
    ('TextToSpeech.QUEUE_FLUSH, null, id)', 'TextToSpeech.QUEUE_FLUSH, Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, options.getFloat("volume:${CompanionCue.file}", 1f)) }, id)')
])
edit('FloatingAvatarOverlay.kt', [
    ('if (session != null) return', 'if (!CompanionCue.overlayEnabled(context)) return\n        val selected = CompanionCue.character\n        if (session?.character == selected) return\n        session?.close()'),
    ('val candidate = Session(app)', 'val candidate = Session(app, selected)'),
    ('private class Session(private val context: Context)', 'private class Session(private val context: Context, val character: Int)'),
    ('val jawSet = applyRestingJaw(v)', 'val jawSet = character != 0 || applyRestingJaw(v)'),
    ('val facial = IdleFacialAnimator(v)', 'val facial = if (character == 0) IdleFacialAnimator(v) else null'),
    ('facial.setIdleEnabled(idleEnabled)', 'facial?.setIdleEnabled(idleEnabled)'),
    ('facial.missingJoints.isNotEmpty()', 'facial != null && facial.missingJoints.isNotEmpty()'),
    ('OverlaySettings.preferences(context)', 'OverlaySettings.preferences(context, character)'),
    ('root.contentDescription = "Floating Roxanne.', 'root.contentDescription = "Floating ${CompanionCue.names[character]}.'),
    ('context.assets.open("roxanne_wolf_security_breach.glb")', 'context.assets.open(CompanionCue.files[character])'),
    ('error("Filament could not load bundled Roxanne")', 'error("Filament could not load ${CompanionCue.names[character]}")')
])
edit('OverlaySettings.kt', [
    ('internal fun preferences(context: Context): SharedPreferences', 'internal fun preferences(context: Context, character: Int = 0): SharedPreferences'),
    ('getSharedPreferences("overlay_framing_v3", Context.MODE_PRIVATE)', 'getSharedPreferences(if (character == 0) "overlay_framing_v3" else "overlay_framing_v3:${CompanionCue.files[character]}", Context.MODE_PRIVATE)'),
    ('val p = preferences(activity)', 'val p = preferences(activity, CompanionCue.character)'),
    ('setTitle("Floating Roxanne settings")', 'setTitle("Floating ${CompanionCue.name} settings")')
])
manifest = Path('app/src/main/AndroidManifest.xml').read_text()
assert '<activity android:name=".RecoveryActivity" android:exported="false"/>' in manifest
assert manifest.count('android.intent.category.LAUNCHER') == 1
s = (root / 'AlongsideActivity.kt').read_text()
assert 'getIntExtra("character", 0)' in s and 'CompanionCue.addSettings' in s
assert '(roxy|roxanne|loona|fexa)' in (root / 'HandsFreeActivity.kt').read_text()
