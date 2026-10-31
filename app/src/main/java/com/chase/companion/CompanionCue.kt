package com.chase.companion

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.Toast
import java.util.Locale

object CompanionCue {
    val names = listOf("Roxanne", "Fexa", "Loona")
    val files = listOf("roxanne_wolf_security_breach.glb", "fexa_blush_alt_nude.glb", "loona.glb")
    var character = 0
        private set
    val name get() = names[character]
    val file get() = files[character]
    private var lastCue = -1000L
    private val main = Handler(Looper.getMainLooper())
    private fun prefs(c: Context) = c.getSharedPreferences("hands_free_options", Context.MODE_PRIVATE)
    fun overlayEnabled(c: Context) = prefs(c).getBoolean("overlay_enabled", true)
    fun select(c: Context, spoken: String) {
        val next = when (spoken.lowercase(Locale.ROOT)) { "roxy", "roxanne" -> 0; "fexa" -> 1; "loona" -> 2; else -> return }
        if (character != next) FloatingAvatarOverlay.hide()
        character = next
    }
    fun readyCue(c: Context) {
        if (!prefs(c).getBoolean("ready_cue", true)) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastCue < 700) return
        lastCue = now
        try {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 45)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
            main.postDelayed({ tone.release() }, 200)
        } catch (_: RuntimeException) {}
    }
    fun addSettings(a: Activity, panel: LinearLayout, ink: Int) {
        fun toggle(title: String, checked: Boolean, action: (Switch, Boolean) -> Unit) {
            val control = Switch(a).apply { text = title; setTextColor(ink); isChecked = checked }
            panel.addView(control)
            var changing = false
            control.setOnCheckedChangeListener { _, enabled ->
                if (!changing) { changing = true; try { action(control, enabled) } finally { changing = false } }
            }
        }
        fun button(title: String, action: () -> Unit) { panel.addView(Button(a).apply { text = title; isAllCaps = false; setOnClickListener { action() } }) }
        toggle("Microphone listening · local 20-minute test", HandsFreeState.active) { control, enabled ->
            if (!enabled) a.stopService(Intent(a, HandsFreeService::class.java))
            else if (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(a)) {
                control.isChecked = false; Toast.makeText(a, "On-device speech recognition is unavailable. No cloud fallback.", Toast.LENGTH_LONG).show()
            } else if (a.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                control.isChecked = false
                a.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 901)
                Toast.makeText(a, "After granting microphone permission, turn listening on again.", Toast.LENGTH_LONG).show()
            } else {
                try { a.startForegroundService(Intent(a, HandsFreeService::class.java)) }
                catch (e: RuntimeException) { control.isChecked = false; Toast.makeText(a, "Listening could not start: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
        toggle("Overlay enabled", overlayEnabled(a)) { control, enabled ->
            if (enabled && !Settings.canDrawOverlays(a)) {
                control.isChecked = false; prefs(a).edit().putBoolean("overlay_enabled", false).apply()
                Toast.makeText(a, "Grant overlay permission, then enable the overlay here.", Toast.LENGTH_LONG).show()
                try { a.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${a.packageName}"))) }
                catch (_: RuntimeException) { Toast.makeText(a, "Overlay permission settings unavailable.", Toast.LENGTH_LONG).show() }
            } else {
                prefs(a).edit().putBoolean("overlay_enabled", enabled).apply()
                if (enabled) FloatingAvatarOverlay.show(a) else FloatingAvatarOverlay.hide()
            }
        }
        toggle("Ready-to-listen audio cue", prefs(a).getBoolean("ready_cue", true)) { _, enabled -> prefs(a).edit().putBoolean("ready_cue", enabled).apply() }
        button("Test audio cue") { readyCue(a) }
        button("Overlay framing, zoom, idle and diagnostics…") {
            android.app.AlertDialog.Builder(a).setTitle("Choose character").setItems(names.toTypedArray()) { _, i -> select(a, names[i]); OverlaySettings.show(a) }.setNegativeButton("Cancel", null).show()
        }
    }
}
