package com.chase.companion

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

/** Process-memory status only. Ambient transcripts and microphone audio are never saved here. */
internal object HandsFreeState {
    @Volatile var active = false
    @Volatile var phase = "Stopped"
    @Volatile var prompt = ""
    @Volatile var reply = ""
}

class HandsFreeActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private val refresh = object : Runnable {
        override fun run() {
            status.text = "Service active=${HandsFreeState.active}\n${HandsFreeState.phase}\n\nLast triggered prompt: ${HandsFreeState.prompt}\n\nLast reply: ${HandsFreeState.reply}\n\nDevice: ${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE}; API ${Build.VERSION.SDK_INT}"
            main.postDelayed(this, 1000)
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 48, 24, 24) }
        fun text(value: String) { panel.addView(TextView(this).apply { text = value }) }
        fun button(value: String, action: () -> Unit) { panel.addView(Button(this).apply { text = value; setOnClickListener { action() } }) }
        text("Roxy hands-free — experimental local test")
        text("Not production 24/7 listening. Each opt-in session stops after 20 minutes. Uses repeated on-device speech-recognition sessions, NOT a dedicated low-power wake detector; gaps, battery drain and screen-off interruptions are possible. No cloud recognizer fallback. If local English recognition is unavailable, the test stops.")
        text("Say Roxy or Roxanne followed by a question. Name alone opens the overlay and waits up to 12 seconds for a prompt. Roxy, hide / hide yourself hides only the overlay. Roxy, stop listening stops this service. Wake words must begin the recognized utterance. Other speech is discarded.")
        text("Qwen still requires your existing phone-local server at 127.0.0.1:12434. Roxy's .pth/.index voice conversion is NOT connected. Replies are text-only unless you explicitly enable the ordinary offline TTS diagnostic below. Start this test while this screen is visible. Closing this screen does not intentionally stop the service; force-stop, reboot and system interruption are not recovered automatically.")
        panel.addView(Switch(this).apply {
            text = "Diagnostic ordinary offline TTS — NOT Roxy's voice"
            isChecked = getSharedPreferences("hands_free_options", MODE_PRIVATE).getBoolean("diagnostic_tts", false)
            setOnCheckedChangeListener { _, checked -> getSharedPreferences("hands_free_options", MODE_PRIVATE).edit().putBoolean("diagnostic_tts", checked).apply() }
        })
        button("Start local hands-free test (20 minutes)") { requestStart() }
        button("Stop listening / speech / request") { stopService(Intent(this, HandsFreeService::class.java)) }
        button("Show avatar (listening unchanged)") { FloatingAvatarOverlay.show(this) }
        button("Hide avatar (listening unchanged)") { FloatingAvatarOverlay.hide() }
        status = TextView(this).apply { setTextIsSelectable(true) }; panel.addView(status)
        setContentView(ScrollView(this).apply { addView(panel) })
    }
    private fun requestStart() {
        if (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            Toast.makeText(this, "No on-device recognizer is available. No cloud fallback will be used.", Toast.LENGTH_LONG).show(); return
        }
        val permissions = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        if (permissions.isNotEmpty()) requestPermissions(permissions.toTypedArray(), 401) else startTest()
    }
    private fun startTest() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        try { startForegroundService(Intent(this, HandsFreeService::class.java)) }
        catch (error: RuntimeException) { HandsFreeState.phase = "Start failed: ${error.message}" }
    }
    override fun onRequestPermissionsResult(request: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(request, permissions, results)
        if (request == 401 && !isFinishing) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startTest()
            else Toast.makeText(this, "Microphone permission is required; listening was not started.", Toast.LENGTH_LONG).show()
        }
    }
    override fun onResume() { super.onResume(); main.post(refresh) }
    override fun onPause() { main.removeCallbacks(refresh); super.onPause() }
}

/** Prototype only. No activity ownership, automatic boot start, cloud ASR, or RVC claims. */
class HandsFreeService : Service(), RecognitionListener {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var foreground = false
    private var destroyed = false
    private var recognizing = false
    private var busy = false
    private var speaking = false
    private var errors = 0
    private var awaitUntil = 0L
    private var speechId: String? = null
    @Volatile private var connection: HttpURLConnection? = null
    private val history = ArrayList<Pair<String, String>>()
    private val wake = Regex("^\\s*(roxy|roxanne)\\b[\\s\\p{Punct}]*(.*)$", RegexOption.IGNORE_CASE)
    private val restart = Runnable { listen() }
    private val recognitionTimeout = Runnable { end("Recognizer stopped responding. Test stopped; reopen controls to retry.") }
    private val testTimeout = Runnable { end("20-minute experimental session finished. Start another test from the app.") }
    private val promptTimeout = Runnable { awaitUntil = 0L; phase("Prompt timed out; waiting for Roxy / Roxanne") }
    private val speechTimeout = Runnable { tts?.stop(); speechId?.let { finishSpeech(it) } }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { end("Stopped by user"); return START_NOT_STICKY }
        if (HandsFreeState.active) return START_NOT_STICKY
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("roxy_hands_free", "Roxy local listening test", NotificationManager.IMPORTANCE_LOW))
            startForeground(812, notification("Starting local listening test"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            foreground = true
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { end("Microphone permission missing"); return START_NOT_STICKY }
            if (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) { end("On-device recognition unavailable; no cloud fallback"); return START_NOT_STICKY }
            HandsFreeState.active = true
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this).also { it.setRecognitionListener(this) }
            initializeTts()
            main.postDelayed(testTimeout, 20 * 60 * 1000L)
            schedule(300)
        } catch (error: RuntimeException) { end("Start failed: ${error.javaClass.simpleName}: ${error.message}") }
        return START_NOT_STICKY
    }
    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 812, Intent(this, HandsFreeActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 813, Intent(this, HandsFreeService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, "roxy_hands_free").setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Roxy — local listening TEST").setContentText(message.take(180))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_SECRET)
            .addAction(Notification.Action.Builder(null, "Stop listening", stop).build()).build()
    }
    private fun phase(message: String) {
        HandsFreeState.phase = message
        if (foreground) runCatching { getSystemService(NotificationManager::class.java).notify(812, notification(message)) }
    }
    private fun schedule(delay: Long = 650) {
        main.removeCallbacks(restart)
        if (!destroyed && HandsFreeState.active && !busy && !speaking) main.postDelayed(restart, delay)
    }
    private fun listen() {
        if (destroyed || !HandsFreeState.active || busy || speaking || recognizing) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { end("Microphone permission revoked"); return }
        if (awaitUntil != 0L && SystemClock.elapsedRealtime() >= awaitUntil) awaitUntil = 0L
        phase(if (awaitUntil != 0L) "Listening for your prompt" else "Waiting for Roxy / Roxanne — experimental session")
        try {
            recognizing = true
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            recognizer?.startListening(intent)
            main.postDelayed(recognitionTimeout, 45000)
        } catch (error: RuntimeException) { end("Recognition start failed: ${error.message}") }
    }
    override fun onResults(results: Bundle?) {
        if (destroyed || !recognizing) return
        recognizing = false; main.removeCallbacks(recognitionTimeout); errors = 0
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
        handleText(text)
    }
    private fun handleText(text: String) {
        val match = wake.matchEntire(text)
        val prompt = when {
            match != null -> match.groupValues[2].trim()
            awaitUntil > SystemClock.elapsedRealtime() -> text
            else -> { schedule(); return }
        }
        if (prompt.isBlank()) {
            if (match != null) {
                FloatingAvatarOverlay.show(this)
                awaitUntil = SystemClock.elapsedRealtime() + 12000
                main.removeCallbacks(promptTimeout); main.postDelayed(promptTimeout, 12000)
            }
            schedule(); return
        }
        awaitUntil = 0L; main.removeCallbacks(promptTimeout)
        val command = prompt.lowercase(Locale.ROOT).trim().trimEnd('.', '!', '?', ',')
        when (command) {
            "hide", "hide yourself" -> { FloatingAvatarOverlay.hide(); phase("Avatar hidden; listening remains enabled"); schedule(); return }
            "show", "show yourself" -> { FloatingAvatarOverlay.show(this); schedule(); return }
            "stop listening" -> { end("Stopped by voice command"); return }
        }
        FloatingAvatarOverlay.show(this)
        askQwen(prompt)
    }
    override fun onError(error: Int) {
        if (destroyed || !recognizing) return
        recognizing = false; main.removeCallbacks(recognitionTimeout)
        if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) { schedule(900); return }
        errors++
        val fatal = error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS || error == SpeechRecognizer.ERROR_AUDIO ||
            error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
            error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT || error == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS
        if (fatal || errors >= 3) end("Local recognition error $error. Test stopped; check installed English speech support and microphone access. No cloud fallback.")
        else { phase("Local recognition error $error; retrying"); schedule(2500) }
    }
    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    private fun askQwen(prompt: String) {
        if (prompt.length > 2000) { phase("Prompt too long for this test"); schedule(); return }
        busy = true; HandsFreeState.prompt = prompt; HandsFreeState.reply = ""
        phase("Waiting for local Qwen; microphone sessions paused")
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "You are Roxy, a friendly personal AI companion. Answer concisely and honestly. You cannot see the screen or control the phone. Do not claim to have performed actions."))
        var budget = prompt.length
        val recent = ArrayList<Pair<String, String>>()
        for (pair in history.asReversed()) {
            val size = pair.first.length + pair.second.length
            if (budget + size > 4000) break
            recent.add(0, pair); budget += size
        }
        for (pair in recent) {
            messages.put(JSONObject().put("role", "user").put("content", pair.first))
            messages.put(JSONObject().put("role", "assistant").put("content", pair.second))
        }
        messages.put(JSONObject().put("role", "user").put("content", prompt))
        worker.execute {
            var current: HttpURLConnection? = null
            try {
                if (destroyed) return@execute
                val c = URL("http://127.0.0.1:12434/v1/chat/completions").openConnection() as HttpURLConnection
                current = c; connection = c
                if (destroyed) return@execute
                c.connectTimeout = 10000; c.readTimeout = 90000; c.requestMethod = "POST"; c.doOutput = true; c.instanceFollowRedirects = false
                c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("Authorization", "Bearer anything")
                val body = JSONObject().put("model", "qwen2.5-0.5b-instruct-q4_k_m").put("messages", messages).put("stream", false).put("max_tokens", 192).put("temperature", 0.7)
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                if (code !in 200..299) throw IOException("Local Qwen HTTP $code")
                val raw = StringBuilder()
                c.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val buffer = CharArray(2048)
                    while (true) {
                        val count = reader.read(buffer); if (count < 0) break
                        raw.append(buffer, 0, count)
                        if (raw.length > 262144) throw IOException("Response too large")
                    }
                }
                val answer = JSONObject(raw.toString()).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
                if (answer.isEmpty()) throw IOException("Empty local reply")
                main.post {
                    if (!destroyed) {
                        busy = false; history.add(prompt to answer); while (history.size > 6) history.removeAt(0)
                        HandsFreeState.reply = answer; playReply(answer)
                    }
                }
            } catch (error: Exception) {
                main.post { if (!destroyed) { busy = false; phase("Local Qwen failed: ${error.message}. No cloud fallback."); schedule(1500) } }
            } finally { current?.disconnect(); if (connection === current) connection = null }
        }
    }
    private fun initializeTts() {
        tts = TextToSpeech(applicationContext) { result ->
            main.post {
                if (!destroyed && result == TextToSpeech.SUCCESS) {
                    val voice = tts?.voices?.filter { !it.isNetworkConnectionRequired && it.locale.language == "en" && !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }?.sortedBy { it.name }?.firstOrNull()
                    if (voice != null) ttsReady = tts?.setVoice(voice) == TextToSpeech.SUCCESS
                }
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { if (id != null) SpeechJawState.started(id) }
            override fun onDone(id: String?) { if (id != null) main.post { finishSpeech(id) } }
            override fun onError(id: String?) { if (id != null) main.post { finishSpeech(id) } }
            override fun onError(id: String?, code: Int) { if (id != null) main.post { finishSpeech(id) } }
            override fun onStop(id: String?, interrupted: Boolean) { if (id != null) main.post { finishSpeech(id) } }
        })
    }
    private fun playReply(answer: String) {
        val diagnostic = getSharedPreferences("hands_free_options", MODE_PRIVATE).getBoolean("diagnostic_tts", false)
        if (!diagnostic || !ttsReady) {
            phase(if (diagnostic) "Reply ready as text; ordinary offline TTS unavailable" else "Reply ready as text; Roxy voice is not connected")
            schedule(1500); return
        }
        speaking = true; phase("Speaking with ordinary offline TTS — NOT Roxy voice")
        val id = SpeechJawState.prepare(); speechId = id
        try {
            if (tts?.speak(answer.take(3500), TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) finishSpeech(id)
            else main.postDelayed(speechTimeout, 120000)
        } catch (_: RuntimeException) { finishSpeech(id) }
    }
    private fun finishSpeech(id: String) {
        SpeechJawState.finished(id)
        if (id != speechId) return
        speechId = null; speaking = false; main.removeCallbacks(speechTimeout); schedule(900)
    }
    private fun end(reason: String) { HandsFreeState.phase = reason; cleanup(); stopSelf() }
    private fun cleanup() {
        if (destroyed) return
        destroyed = true; HandsFreeState.active = false; recognizing = false
        main.removeCallbacksAndMessages(null)
        runCatching { recognizer?.cancel(); recognizer?.destroy() }; recognizer = null
        speechId?.let { SpeechJawState.finished(it) }; speechId = null
        runCatching { tts?.stop(); tts?.shutdown() }; tts = null
        val active = connection; if (active != null) Thread({ active.disconnect() }, "cancel-hands-free-qwen").start()
        worker.shutdownNow(); history.clear()
        if (foreground) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false }
    }
    override fun onDestroy() { if (!destroyed) HandsFreeState.phase = "Stopped"; cleanup(); super.onDestroy() }
}
