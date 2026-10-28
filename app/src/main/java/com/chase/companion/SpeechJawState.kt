package com.chase.companion

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Process-local playback gate. TTS callbacks never touch Filament or Android views. */
object SpeechJawState {
    private data class Playback(val id: String?, val startedAt: Long)
    @Volatile private var playback = Playback(null, 0L)
    private var sequence = 0L

    @JvmStatic @Synchronized fun prepare(): String {
        val id = "companion-speech-${++sequence}"
        playback = Playback(id, 0L)
        return id
    }

    @JvmStatic @Synchronized fun started(id: String?) {
        val current = playback
        if (id != null && current.id == id && current.startedAt == 0L) playback = Playback(id, System.nanoTime())
    }

    @JvmStatic @Synchronized fun finished(id: String?) {
        if (id != null && playback.id == id) playback = Playback(null, 0L)
    }

    /** Stylized talking motion during playback, not audio amplitude or phoneme lip-sync. */
    fun openness(frameTime: Long): Float {
        val current = playback
        if (current.id == null || current.startedAt == 0L) return 0f
        val seconds = ((frameTime - current.startedAt).toDouble() / 1_000_000_000.0).coerceAtLeast(0.0)
        val cycle = 0.5 - 0.5 * cos(seconds * 2.0 * PI * 4.2)
        val variation = 0.35 + 0.65 * (0.5 + 0.5 * sin(seconds * 2.0 * PI * 1.3))
        return (cycle * variation).toFloat().coerceIn(0f, 1f)
    }
}
