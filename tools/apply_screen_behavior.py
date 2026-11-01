from pathlib import Path

p = Path('app/src/main/java/com/chase/companion/AlongsideActivity.kt')
s = p.read_text()
def replace(old, new):
    global s
    if s.count(old) != 1:
        raise RuntimeError('Source changed; refusing unsafe replacement: ' + old[:100])
    s = s.replace(old, new, 1)

replace('character = intent.getIntExtra("character", -1).takeIf { it in files.indices } ?: -1', 'character = intent.getIntExtra("character", 0).takeIf { it in files.indices } ?: 0')
replace('page in listOf("Full Rig Inspection", "Chat", "Animation Test", "Voice Test")', 'page in listOf("Chat", "Animation Test", "Voice Test")')
replace('window.insetsController?.setSystemBarsAppearance', 'window.decorView.windowInsetsController?.setSystemBarsAppearance')
start = s.index('    private fun back()')
end = s.index('    private fun renderPage()', start)
s = s[:start] + """    private fun back() {
        if (page != "Full Rig Inspection") navigate("Full Rig Inspection") else finish()
    }
    @Deprecated("Activity navigation") override fun onBackPressed() { back() }
    private fun enterPage(next: String) {
        stopRequest(); page = next; clip = -1
        intent.putExtra("page", next)
        model { restoreNodes() }; renderPage()
    }
    private fun promptCharacter(next: String) {
        AlertDialog.Builder(this).setTitle("Choose character · $next")
            .setItems(names.toTypedArray()) { _, i -> chooseCharacter(i, next) }
            .setNegativeButton("Cancel", null).show()
    }
    private fun navigate(next: String) {
        when (next) {
            "Full Rig Inspection" -> chooseCharacter(0, next)
            "Settings" -> enterPage(next)
            else -> promptCharacter(next)
        }
    }
    private fun chooseCharacter(i: Int, next: String = page) {
        stopRequest(); intent.putExtra("character", i); intent.putExtra("page", next); recreate()
    }
""" + s[end:]
replace('            "Settings" -> settingsPage()', '            "Settings", "Character Settings" -> settingsPage()')
replace('button(panel, "Chat") { navigate("Chat") }; toggle(panel, "Live animations", appPrefs.getBoolean(per("live"), true)) { v -> appPrefs.edit().putBoolean(per("live"), v).apply(); model { restoreNodes() } }', 'button(panel, "Chat") { navigate("Chat") }')
replace('button(panel, "Change character") { stopRequest(); intent.removeExtra("character"); intent.removeExtra("page"); recreate() }', 'button(panel, "Change character") { promptCharacter(page) }')
replace("""        if (character >= 0) {
            panel.addView(label("Only ${names[character]}'s settings below", 18f));""", """        if (page == "Settings") button(panel, "Character settings…") { navigate("Character Settings") }
        if (page == "Character Settings" && character >= 0) {
            panel.addView(label("Only ${names[character]}'s settings below", 18f));""")
replace('intent.putExtra("page", "Settings"); recreate()', 'intent.putExtra("page", page); recreate()')
m = Path('app/src/main/AndroidManifest.xml')
manifest = m.read_text()
old = '<activity android:name=".AlongsideActivity" android:exported="false" android:windowSoftInputMode="adjustResize"/>'
new = '<activity android:name=".AlongsideActivity" android:exported="true" android:windowSoftInputMode="adjustResize"><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity>'
recovery = '<activity android:name=".RecoveryActivity" android:exported="true"><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity>'
assert manifest.count(old) == 1 and manifest.count(recovery) == 1, 'Launcher changed; refusing unsafe edit'
manifest = manifest.replace(old, new, 1).replace(recovery, '<activity android:name=".RecoveryActivity" android:exported="false"/>', 1)
assert 'private fun promptCharacter' in s
assert manifest.count('android.intent.category.LAUNCHER') == 1
replace('profile.put("orientation", array(orientation)); save("orientation default saved")', 'val camera = PreviewCameraDefaults.capture(checkNotNull(orbit) { "Camera is not ready" }); profile.put("orientation", array(orientation)); profile.put("preview_camera", camera); save("orientation and camera default saved")')
replace('orientation = vector(profile.optJSONArray("orientation")); applyOrientation(); renderPage()', 'orientation = vector(profile.optJSONArray("orientation")); applyOrientation(); orbit?.let { PreviewCameraDefaults.restore(it, profile.optJSONObject("preview_camera")) }; renderPage()')
replace('button(panel, "Reset camera view · keep pose") { orbit?.let { it.jumpToBookmark(it.homeBookmark) } }', 'button(panel, "Reset camera view · keep pose") { orbit?.let { if (!PreviewCameraDefaults.restore(it, profile.optJSONObject("preview_camera"))) it.jumpToBookmark(it.homeBookmark) } }')
replace('initializeRenderer(surface!!) }; renderPage()', 'initializeRenderer(surface!!) }; renderPage(); schedulePreviewCameraRestore()')
replace('private fun loadModel() {', 'private fun loadModel() { schedulePreviewCameraRestore();')
replace('    private fun dp(v: Int)', """    private var previewRestoreSerial = 0
    private fun schedulePreviewCameraRestore() {
        val ticket = ++previewRestoreSerial
        var attempts = 0
        val poll = object : Runnable {
            override fun run() {
                if (!alive || ticket != previewRestoreSerial) return
                if (!ready || orbit == null || profileKey.isEmpty()) {
                    if (++attempts < 1200) window.decorView.postDelayed(this, 50)
                    return
                }
                safe { orbit?.let { PreviewCameraDefaults.restore(it, profile.optJSONObject("preview_camera")) } }
            }
        }
        window.decorView.post(poll)
    }
    private fun dp(v: Int)""")
p.write_text(s)
m.write_text(manifest)
