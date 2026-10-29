package com.chase.companion

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/** Activity-owned controls: no settings or diagnostic dialogs in the floating window. */
object OverlaySettings {
    private const val DEFAULT_VERTICAL_STEP = -4

    internal fun preferences(context: Context): SharedPreferences {
        val p = context.getSharedPreferences("overlay_framing_v3", Context.MODE_PRIVATE)
        // Lower the avatar four existing adjustment steps once, never on every Show.
        // Preserve zoom, mode, horizontal position and subsequent manual adjustments.
        if (!p.getBoolean("chest_crop_lowered_v1", false)) {
            p.edit().putInt("vertical_step", (p.getInt("vertical_step", 0) + DEFAULT_VERTICAL_STEP).coerceIn(-18, 18))
                .putBoolean("chest_crop_lowered_v1", true).apply()
        }
        return p
    }

    @JvmStatic fun show(activity: Activity) {
        val p = preferences(activity)
        val panel = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 12, 24, 12) }
        val summary = TextView(activity)
        panel.addView(TextView(activity).apply { text = "Avatar-only overlay: 120 × 242 dp. Drag anywhere in its window; hold still to remove. Changes below apply immediately and are saved. Fit centers on the spine; Chest centers on the neck." })
        panel.addView(summary)
        fun refresh() {
            val mode = if (p.getBoolean("chest", true)) "Chest / neck" else "Fit / spine"
            summary.text = String.format(Locale.US, "%s · chest zoom %.1f×\nChest offset: horizontal %d; vertical %d", mode, p.getInt("zoom_tenths", 20) / 10.0, p.getInt("horizontal_step", 0), p.getInt("vertical_step", DEFAULT_VERTICAL_STEP))
        }
        fun change(action: SharedPreferences.Editor.() -> Unit) { p.edit().apply(action).apply(); FloatingAvatarOverlay.refreshSettings(); refresh() }
        fun row(vararg items: Pair<String, () -> Unit>) {
            val line = LinearLayout(activity)
            for ((title, action) in items) line.addView(Button(activity).apply { text = title; setOnClickListener { action() } }, LinearLayout.LayoutParams(0, -2, 1f))
            panel.addView(line)
        }
        row("Show" to { FloatingAvatarOverlay.show(activity) }, "Hide" to { FloatingAvatarOverlay.hide() })
        row("Fit / spine" to { change { putBoolean("chest", false) } }, "Chest / neck" to { change { putBoolean("chest", true) } })
        row("Zoom −" to { change { putBoolean("chest", true); putInt("zoom_tenths", (p.getInt("zoom_tenths", 20) - 2).coerceAtLeast(8)) } },
            "Zoom +" to { change { putBoolean("chest", true); putInt("zoom_tenths", (p.getInt("zoom_tenths", 20) + 2).coerceAtMost(60)) } })
        row("Avatar ↑" to { change { putBoolean("chest", true); putInt("vertical_step", (p.getInt("vertical_step", DEFAULT_VERTICAL_STEP) + 1).coerceAtMost(18)) } },
            "Avatar ↓" to { change { putBoolean("chest", true); putInt("vertical_step", (p.getInt("vertical_step", DEFAULT_VERTICAL_STEP) - 1).coerceAtLeast(-18)) } })
        row("Avatar ←" to { change { putBoolean("chest", true); putInt("horizontal_step", (p.getInt("horizontal_step", 0) - 1).coerceAtLeast(-18)) } },
            "Avatar →" to { change { putBoolean("chest", true); putInt("horizontal_step", (p.getInt("horizontal_step", 0) + 1).coerceAtMost(18)) } })
        panel.addView(TextView(activity).apply { text = "Chest zoom: 0.8×–6.0×, default 2.0×. Default chest crop lowers the avatar four steps (vertical −4). Fit ignores chest zoom and offsets. Head rests 10° down; eyes rest 10° up to compensate." })
        row("Reset chest framing" to { change { putBoolean("chest", true); putInt("zoom_tenths", 20); putInt("horizontal_step", 0); putInt("vertical_step", DEFAULT_VERTICAL_STEP) } })
        panel.addView(Switch(activity).apply {
            text = "Idle facial movement (speech jaw stays active)"; isChecked = p.getBoolean("idle", true)
            setOnCheckedChangeListener { _, checked -> change { putBoolean("idle", checked) } }
        })
        panel.addView(Switch(activity).apply {
            text = "Diagnostic purple background"; isChecked = p.getBoolean("debug_background", false)
            setOnCheckedChangeListener { _, checked -> change { putBoolean("debug_background", checked) } }
        })
        row("Render diagnostics" to {
            val report = FloatingAvatarOverlay.diagnostics()
            AlertDialog.Builder(activity).setTitle("Floating avatar diagnostics").setMessage(report)
                .setPositiveButton("Copy report") { _, _ ->
                    (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Overlay diagnostics", report))
                    Toast.makeText(activity, "Overlay report copied", Toast.LENGTH_SHORT).show()
                }.setNegativeButton("Close", null).show()
        })
        refresh()
        AlertDialog.Builder(activity).setTitle("Floating Roxanne settings").setView(ScrollView(activity).apply { addView(panel) }).setPositiveButton("Done", null).show()
    }
}
