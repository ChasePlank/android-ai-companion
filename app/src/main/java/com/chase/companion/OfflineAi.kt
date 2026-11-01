package com.chase.companion

import android.app.Activity
import android.app.AlertDialog
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal object OfflineAi {
    interface Sink { fun phase(text: String); fun chunk(bytes: ByteArray) }
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    @Volatile private var cancelled = false
    private val nativeError = try { System.loadLibrary("alongside_offline"); null } catch (e: LinkageError) { e.message ?: "Native inference library unavailable" }
    @JvmStatic private external fun resetNative()
    @JvmStatic private external fun cancelNative()
    @JvmStatic private external fun runNative(path: String, input: ByteArray, threads: Int, sink: Sink): DoubleArray
    private fun stop() { cancelled = true; if (nativeError == null) cancelNative() }
    private fun prepare(a: Activity, progress: (String) -> Unit): File {
        val meta = JSONObject(a.assets.open("offline/model.json").bufferedReader().use { it.readText() })
        val hash = meta.getString("sha256")
        check(hash.matches(Regex("[0-9a-f]{64}")))
        val size = meta.getLong("bytes")
        val dir = File(a.noBackupFilesDir, "offline-ai").apply { check(isDirectory || mkdirs()) }
        val target = File(dir, "$hash.gguf")
        if (target.isFile && target.length() == size) return target
        check(StatFs(dir.path).availableBytes > size + 64L * 1024 * 1024) { "Insufficient free storage to prepare bundled model" }
        val temp = File(dir, "$hash.partial")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            a.assets.open("offline/" + meta.getString("file")).use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        check(!cancelled) { "Cancelled" }
                        val n = input.read(buffer); if (n < 0) break
                        output.write(buffer, 0, n); digest.update(buffer, 0, n); copied += n
                        if (copied % (4 * 1024 * 1024) == 0L) progress("Preparing bundled model: ${copied / 1000000} / ${size / 1000000} MB")
                    }
                    output.fd.sync()
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            check(copied == size && actual == hash) { "Bundled model failed integrity check" }
            check(!cancelled) { "Cancelled" }
            check(temp.renameTo(target)) { "Could not finish model preparation" }
            return target
        } finally { temp.delete() }
    }
    fun show(a: Activity) {
        if (busy.get()) { Toast.makeText(a, "Offline AI test is already running", Toast.LENGTH_LONG).show(); return }
        val panel = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 12, 24, 12) }
        panel.addView(TextView(a).apply { text = "Experimental bundled Qwen3-0.6B Q4_K_M · CPU-only · 1024-token context · maximum 128 reply tokens. No server or runtime model download. The model is released after each test. Existing character chat is unchanged." })
        val prompt = EditText(a).apply { hint = "Test prompt"; setText("Say hello and briefly explain what an offline AI can do."); maxLines = 4 }; panel.addView(prompt)
        val report = TextView(a).apply { setTextIsSelectable(true); text = nativeError ?: "Ready. Stop the separate server and use airplane mode to test." }; panel.addView(report)
        val run = Button(a).apply { text = "Run offline test"; isAllCaps = false; isEnabled = nativeError == null }; panel.addView(run)
        panel.addView(Button(a).apply { text = "Cancel generation"; isAllCaps = false; setOnClickListener { if (busy.get()) { stop(); report.text = "Cancelling; waiting for native cleanup…" } } })
        val dialog = AlertDialog.Builder(a).setTitle("Test offline AI").setView(ScrollView(a).apply { addView(panel) }).setNegativeButton("Close", null).create()
        dialog.setOnDismissListener { if (busy.get()) stop() }
        run.setOnClickListener {
            val text = prompt.text.toString().trim()
            if (text.isEmpty() || text.length > 1000) { report.text = "Enter 1–1000 characters."; return@setOnClickListener }
            if (!busy.compareAndSet(false, true)) return@setOnClickListener
            cancelled = false; resetNative(); run.isEnabled = false; prompt.isEnabled = false
            report.text = "Preparing bundled model…"
            worker.execute {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                val bytes = ByteArrayOutputStream()
                var phase = "Preparing bundled model…"
                fun publish(value: String) { a.runOnUiThread { if (!a.isDestroyed && dialog.isShowing) report.text = value } }
                fun output() = bytes.toString(Charsets.UTF_8.name())
                val started = SystemClock.elapsedRealtime()
                try {
                    val model = prepare(a) { phase = it; publish(it) }
                    check(!cancelled) { "Cancelled" }
                    val result = runNative(model.absolutePath, text.toByteArray(Charsets.UTF_8), (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4), object : Sink {
                        override fun phase(text: String) { phase = text; publish(phase + "\n\n" + output()) }
                        override fun chunk(chunk: ByteArray) { bytes.write(chunk); publish(phase + "\n\n" + output()) }
                    })
                    check(!cancelled) { "Cancelled" }
                    val speed = if (result[2] > 0) result[3] * 1000 / result[2] else 0.0
                    publish(output() + String.format(Locale.US, "\n\nCompleted locally.\nLoad/context: %.2f s\nPrompt processing: %.2f s\nGeneration: %.2f s · %.0f tokens · %.2f tokens/s\nFirst token after native start: %.2f s\nTotal including preparation: %.2f s\nNative model/context released.", result[0] / 1000, result[1] / 1000, result[2] / 1000, result[3], speed, result[4] / 1000, (SystemClock.elapsedRealtime() - started) / 1000.0))
                } catch (e: Exception) { publish(output() + "\n\n" + if (cancelled) "Cancelled; native resources released." else "Offline test failed: ${e.message}") }
                finally {
                    busy.set(false)
                    a.runOnUiThread { if (!a.isDestroyed && dialog.isShowing) { run.isEnabled = nativeError == null; prompt.isEnabled = true } }
                }
            }
        }
        dialog.show()
    }
}
