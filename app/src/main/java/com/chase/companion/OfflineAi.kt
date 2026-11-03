package com.chase.companion

import android.app.Activity
import android.app.AlertDialog
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.widget.*
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
    private const val correctionRule = "Recheck factual claims when challenged. Earlier assistant answers are fallible, not evidence. Compare the original request with the objection. Correct an error plainly when the objection is valid; do not invent a justification. Do not agree with an incorrect correction merely because the user insists. If uncertain, say so."
    private fun content(text: String) = text.replace("<|", "< |")
    private fun compose(text: String, turns: List<Pair<String, String>>, history: Boolean, correction: Boolean, recovery: Boolean = false): String = buildString {
        append("<|im_start|>system\nYou are a helpful assistant. Answer briefly and honestly.")
        if (correction || recovery) append("\n").append(correctionRule)
        if (recovery) append("\nA repetition safeguard requested a fresh attempt. Previous assistant messages were excluded, but previous user messages remain in order. Re-evaluate the task against the latest user message. Do not invent what an omitted assistant message said. Follow the requested output format. If the available context is insufficient, say so. A user correction can be wrong; check it rather than agreeing automatically.")
        append("<|im_end|>\n")
        if (history) turns.forEach { (role, value) ->
            require(role == "user" || role == "assistant")
            append("<|im_start|>").append(role).append("\n").append(content(value)).append("<|im_end|>\n")
        }
        append("<|im_start|>user\n").append(content(text))
        append("\n/no_think<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n")
    }
    fun show(a: Activity) {
        if (busy.get()) { Toast.makeText(a, "Offline AI test is already running", Toast.LENGTH_LONG).show(); return }
        val turns = mutableListOf<Pair<String, String>>()
        var lastInput = ""
        val panel = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 12, 24, 12) }
        panel.addView(TextView(a).apply { text = "Local diagnostic only; character memories are unchanged. History and correction guidance default ON. Turn history uses separate ChatML roles. The correction-loop guard is an English-language heuristic, not a fact checker: after a challenged answer repeats, it makes at most one fresh attempt with prior assistant messages excluded. Both prompts remain inspectable. No normal history is silently trimmed. 1024-token context; maximum 128 tokens per attempt. Overlong prompts fail visibly." })
        val historyMode = CheckBox(a).apply { text = "Conversation history (session only; OFF means no previous turns)"; isChecked = true }; panel.addView(historyMode)
        val correctionMode = CheckBox(a).apply { text = "Factual correction guidance"; isChecked = true }; panel.addView(correctionMode)
        val guardMode = CheckBox(a).apply { text = "Correction-loop guard (one retry maximum)"; isChecked = true }; panel.addView(guardMode)
        val transcript = TextView(a).apply { setTextIsSelectable(true); text = "No conversation turns yet." }; panel.addView(transcript)
        fun refresh() { transcript.text = if (turns.isEmpty()) "No conversation turns yet." else turns.joinToString("\n\n") { (role, value) -> "$role: $value" } }
        val prompt = EditText(a).apply { hint = "Test prompt or follow-up"; setText("Count from 1 to 30, separated by commas. Do not add any explanation"); maxLines = 5 }; panel.addView(prompt)
        val report = TextView(a).apply { setTextIsSelectable(true); text = nativeError ?: "Ready. Conversation history, correction guidance and loop recovery are ON. Sampling settings are unchanged." }; panel.addView(report)
        val progress = ProgressBar(a).apply { visibility = android.view.View.GONE }; panel.addView(progress)
        val run = Button(a).apply { text = "Run offline test"; isAllCaps = false; isEnabled = nativeError == null }; panel.addView(run)
        val clear = Button(a).apply { text = "Clear test history"; isAllCaps = false; setOnClickListener { if (!busy.get()) { turns.clear(); lastInput = ""; refresh(); report.text = "Test history cleared. Character data unchanged." } } }; panel.addView(clear)
        panel.addView(Button(a).apply { text = "Inspect complete native prompt(s)"; isAllCaps = false; setOnClickListener {
            val body = TextView(a).apply { text = lastInput.ifEmpty { "No request submitted yet." }; setTextIsSelectable(true); setPadding(24, 12, 24, 12) }
            AlertDialog.Builder(a).setTitle("Exact formatted prompt(s)").setView(ScrollView(a).apply { addView(body) }).setPositiveButton("Close", null).show()
        } })
        panel.addView(Button(a).apply { text = "Cancel generation"; isAllCaps = false; setOnClickListener { if (busy.get()) { stop(); report.text = "Cancelling; waiting for worker cleanup..." } } })
        val dialog = AlertDialog.Builder(a).setTitle("Test offline AI").setView(ScrollView(a).apply { addView(panel) }).setNegativeButton("Close", null).create()
        dialog.setOnDismissListener { if (busy.get()) stop() }
        run.setOnClickListener {
            val text = prompt.text.toString().trim()
            if (text.isEmpty() || text.length > 1000) { report.text = "Enter 1-1000 characters."; return@setOnClickListener }
            val useHistory = historyMode.isChecked
            val useCorrection = correctionMode.isChecked
            val useGuard = guardMode.isChecked && useHistory
            val snapshot = turns.toList()
            val input = compose(text, snapshot, useHistory, useCorrection)
            if (!busy.compareAndSet(false, true)) return@setOnClickListener
            cancelled = false; resetNative(); lastInput = input
            run.isEnabled = false; prompt.isEnabled = false; clear.isEnabled = false; historyMode.isEnabled = false; correctionMode.isEnabled = false; guardMode.isEnabled = false
            progress.visibility = android.view.View.VISIBLE; report.text = "Preparing model / processing..."
            worker.execute {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                val bytes = ByteArrayOutputStream()
                var phase = "Preparing bundled model..."
                var attempt = 1
                fun publish(value: String) { a.runOnUiThread { if (!a.isDestroyed && dialog.isShowing) report.text = value } }
                fun output() = bytes.toString(Charsets.UTF_8.name())
                val started = SystemClock.elapsedRealtime()
                try {
                    val model = prepare(a) { phase = it; publish(it) }
                    fun generate(formatted: String): DoubleArray {
                        check(!cancelled) { "Cancelled" }
                        return runNative(model.absolutePath, formatted.toByteArray(Charsets.UTF_8), (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4), object : Sink {
                            override fun phase(text: String) { phase = text; publish("Attempt $attempt: " + phase + "\n\n" + output()) }
                            override fun chunk(chunk: ByteArray) { bytes.write(chunk); publish("Attempt $attempt: " + phase + "\n\n" + output()) }
                        })
                    }
                    var result = generate(input)
                    check(!cancelled) { "Cancelled" }
                    var answer = output()
                    val repeated = useGuard && OfflineLoopGuard.shouldRecover(text, answer, snapshot)
                    var blocked = false
                    if (repeated) {
                        val rejected = answer
                        val retryInput = compose(text, snapshot.filter { it.first == "user" }, true, true, true)
                        a.runOnUiThread { lastInput = "ATTEMPT 1\n$input\n\nRECOVERY ATTEMPT 2 (prior assistant turns excluded)\n$retryInput" }
                        check(!cancelled) { "Cancelled" }
                        attempt = 2; bytes.reset()
                        publish("Repeated answer after a challenge detected. Making ONE fresh attempt; prior user messages retained, prior assistant messages excluded. The saved transcript is unchanged.")
                        result = generate(retryInput)
                        check(!cancelled) { "Cancelled" }
                        answer = output()
                        blocked = OfflineLoopGuard.similar(answer, rejected) || OfflineLoopGuard.shouldRecover(text, answer, snapshot)
                    }
                    val capped = result[3] >= 128.0
                    val accepted = !blocked && !capped && answer.isNotBlank()
                    val speed = if (result[2] > 0) result[3] * 1000 / result[2] else 0.0
                    val metrics = String.format(Locale.US, "\n\nLast-attempt load/context: %.2f s\nLast-attempt prompt processing: %.2f s\nLast-attempt generation: %.2f s; %.0f tokens; %.2f tokens/s\nLast-attempt first token: %.2f s\nTotal including preparation and any retry: %.2f s\nAttempts: %d; context released; model remains resident.", result[0] / 1000, result[1] / 1000, result[2] / 1000, result[3], speed, result[4] / 1000, (SystemClock.elapsedRealtime() - started) / 1000.0, attempt)
                    val displayed = if (blocked) "APP SAFEGUARD: The recovery attempt still repeated an earlier answer. No further automatic retries were made. This exchange was not saved to history. This does not establish which answer is correct." else answer
                    a.runOnUiThread {
                        if (!a.isDestroyed && dialog.isShowing && !cancelled) {
                            if (useHistory && accepted) { turns.add("user" to text); turns.add("assistant" to answer); refresh(); prompt.setText("") }
                            report.text = displayed + metrics + "\nHistory: $useHistory; correction guidance: $useCorrection; loop guard active: $useGuard." + (if (repeated) "\nOne recovery attempt used; prior assistant turns excluded only for that attempt. Inspect both prompts above. A different reply is not proof of correctness." else "") + (if (capped) "\nReply reached token cap; exchange NOT added to history." else "") + (if (answer.isBlank()) "\nEmpty reply; exchange NOT added to history." else "")
                        }
                    }
                } catch (e: Exception) {
                    publish(output() + "\n\n" + if (cancelled) "Cancelled. Exchange not added to history." else "Offline test failed: ${e.message}\nHistory unchanged. If context is too long, clear test history or shorten the message. Recovery never silently truncates user history.")
                } finally {
                    a.runOnUiThread {
                        busy.set(false)
                        if (!a.isDestroyed && dialog.isShowing) { run.isEnabled = nativeError == null; prompt.isEnabled = true; clear.isEnabled = true; historyMode.isEnabled = true; correctionMode.isEnabled = true; guardMode.isEnabled = true; progress.visibility = android.view.View.GONE }
                    }
                }
            }
        }
        dialog.show()
    }
}
