package com.hermesvox

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.hermesvox.speech.ElevenLabsProtocol
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

internal object ElevenLabsSettings {
    const val BACKEND = "elevenlabs"
    const val KEY = "elevenlabs_key_sealed"
    const val STT_MODEL = "elevenlabs_stt_model"
    const val TTS_MODEL = "elevenlabs_tts_model"
    const val VOICE = "elevenlabs_voice_id"

    fun config(context: Context): ElevenLabsProtocol.Config {
        val prefs = context.getSharedPreferences("hv", Context.MODE_PRIVATE)
        // Unlike legacy SecureStore callers, never accept a plaintext legacy value.
        val sealed = prefs.getString(KEY, "").orEmpty()
        val key = if (sealed.contains(":")) SecureStore.decrypt(sealed).orEmpty() else ""
        return ElevenLabsProtocol.Config(key,
            prefs.getString(STT_MODEL, "scribe_v2").orEmpty(),
            prefs.getString(TTS_MODEL, "").orEmpty(),
            prefs.getString(VOICE, "").orEmpty())
    }
}

/** One bounded, non-retrying batch request. No error body, key, audio, or text logs. */
private fun elevenLabsPost(request: ElevenLabsProtocol.Request, limit: Int,
                           bind: (HttpURLConnection?) -> Unit): ByteArray {
    val connection = URL(request.url).openConnection() as HttpURLConnection
    try {
        bind(connection)
        connection.instanceFollowRedirects = false // never forward a provider key to a redirect target
        connection.requestMethod = "POST"
        connection.connectTimeout = 10_000
        connection.readTimeout = 60_000
        connection.doOutput = true
        request.headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
        connection.setFixedLengthStreamingMode(request.body.size)
        connection.outputStream.use { it.write(request.body) }
        val code = connection.responseCode
        if (code !in 200..299) throw java.io.IOException("Speech HTTP $code")
        return connection.inputStream.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (out.size() + count > limit) throw java.io.IOException("Speech response too large")
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
    } finally { bind(null); connection.disconnect() }
}

/** Batch WAV -> transcript only. Availability means locally configured, NOT provider-validated. */
class ElevenLabsStt(private val context: Context) : VoxStt {
    @Volatile private var ready = false
    @Volatile private var closed = false
    private var connection: HttpURLConnection? = null
    override val name get() = "ElevenLabs (batch)"
    override val isAvailable get() = ready && !closed
    override fun init(onReady: (Boolean) -> Unit) {
        ready = try {
            ElevenLabsProtocol.transcription(ElevenLabsSettings.config(context), byteArrayOf(1), "configuration-check")
            true
        } catch (_: Exception) { false }
        onReady(isAvailable) // deliberately no /models probe or paid test
    }
    override fun transcribe(samples: FloatArray, sampleRate: Int): String? {
        if (!isAvailable || sampleRate !in 8000..48000 || samples.isEmpty() ||
            samples.size.toLong() > sampleRate.toLong() * 60) return null
        return try {
            val request = ElevenLabsProtocol.transcription(ElevenLabsSettings.config(context),
                wavPcm16(samples, sampleRate), "vox-${java.util.UUID.randomUUID()}")
            val bytes = elevenLabsPost(request, 1_048_576) { next ->
                synchronized(this) {
                    if (next != null && closed) throw java.io.IOException("Stopped")
                    connection = next
                }
            }
            // Android's JSON parser: require the actual top-level string, not a nested text field.
            val value = org.json.JSONObject(String(bytes, Charsets.UTF_8)).opt("text")
            if (closed) null else (value as? String)?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            VoxLog.w("event=elevenlabs-stt failed")
            null
        }
    }
    override fun shutdown() {
        synchronized(this) { closed = true; ready = false; connection?.disconnect(); connection = null }
    }
}

/** Completed reply -> v4 chunked HTTP PCM stream on one AudioTrack; other models retain
 * the working batch MP3 path. supportsStreaming stays false: that VoxTts flag means
 * incremental Hermes TEXT, not HTTP audio. No calibrated speech barge-in or model fallback.
 * Epochs fence every late worker and completion, including replacement during drain.
 */
class ElevenLabsTts(private val context: Context) : VoxTts {
    private val main = Handler(Looper.getMainLooper())
    private val epoch = AtomicLong()
    private val networkLock = Any()
    private var connection: HttpURLConnection? = null
    private var player: MediaPlayer? = null
    private var audioFile: File? = null
    private var pcmPlayback: ElevenLabsPcmPlayback? = null // owned under networkLock
    @Volatile private var closed = false
    override val name get() = "ElevenLabs"
    override val isWarm get() = false // not an on-device warm engine or calibrated reference
    override val warmReason get() = "Cloud speech; calibrated barge-in unavailable"
    override fun init(onReady: (Boolean) -> Unit) {
        val configured = try {
            ElevenLabsProtocol.speech(ElevenLabsSettings.config(context), "configuration-check")
            true
        } catch (_: Exception) { false }
        onReady(configured && !closed) // no synthesis, billing, or network probe
    }
    override fun speak(text: String, onDone: () -> Unit) {
        val ticket = epoch.incrementAndGet()
        cancelNetwork()
        main.post startSpeech@ {
            if (epoch.get() != ticket || closed) return@startSpeech
            releaseAudio()
            thread(name = "vox-elevenlabs-tts") {
                try {
                    val config = ElevenLabsSettings.config(context)
                    if (ElevenLabsProtocol.isDialogue(config)) {
                        val playback = ElevenLabsPcmPlayback(config) { !closed && epoch.get() == ticket }
                        synchronized(networkLock) {
                            if (closed || epoch.get() != ticket) throw java.io.IOException("Stopped")
                            pcmPlayback = playback
                        }
                        try { playback.play(text) } finally {
                            synchronized(networkLock) { if (pcmPlayback === playback) pcmPlayback = null }
                        }
                        main.post { finish(ticket, onDone) }
                        return@thread
                    }
                    val request = ElevenLabsProtocol.speech(config, text)
                    val bytes = elevenLabsPost(request, 10_485_760) { next ->
                        synchronized(networkLock) {
                            if (next != null && (closed || epoch.get() != ticket)) throw java.io.IOException("Stopped")
                            // An older request's finally must not clear a newer request's connection.
                            if (epoch.get() == ticket) connection = next
                        }
                    }
                    main.post {
                        if (epoch.get() != ticket || closed) return@post
                        try {
                            val file = File.createTempFile("elevenlabs-", ".mp3", context.noBackupFilesDir)
                            audioFile = file
                            file.writeBytes(bytes)
                            val p = MediaPlayer()
                            player = p
                            p.setAudioAttributes(AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                            p.setDataSource(file.absolutePath)
                            p.setOnPreparedListener { if (epoch.get() == ticket && !closed) it.start() }
                            p.setOnCompletionListener { finish(ticket, onDone) }
                            p.setOnErrorListener { _, _, _ -> finish(ticket, onDone); true }
                            p.prepareAsync()
                        } catch (_: Exception) { finish(ticket, onDone) }
                    }
                } catch (_: Exception) {
                    main.post { finish(ticket, onDone) }
                }
            }
        }
    }
    private fun finish(ticket: Long, onDone: () -> Unit) {
        if (closed || !epoch.compareAndSet(ticket, ticket + 1)) return // Stop/replacement wins atomically
        releaseAudio()
        onDone()
    }
    private fun releaseAudio() {
        try { player?.release() } catch (_: Exception) {}
        player = null
        audioFile?.delete()
        audioFile = null
    }
    private fun cancelNetwork() {
        val pending: HttpURLConnection?
        val pcm: ElevenLabsPcmPlayback?
        synchronized(networkLock) {
            pending = connection
            connection = null
            pcm = pcmPlayback
            pcmPlayback = null
        }
        pcm?.cancel() // pauses and flushes now; blocking network teardown stays off UI
        if (pending != null) thread(name = "vox-elevenlabs-abort", isDaemon = true) { pending.disconnect() }
    }
    override fun stop() {
        val ticket = epoch.incrementAndGet()
        cancelNetwork()
        if (Looper.myLooper() == Looper.getMainLooper()) releaseAudio()
        else main.post { if (epoch.get() == ticket) releaseAudio() }
    }
    override fun shutdown() { closed = true; stop() }
}
