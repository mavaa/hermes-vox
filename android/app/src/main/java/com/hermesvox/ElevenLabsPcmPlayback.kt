package com.hermesvox

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import com.hermesvox.speech.ElevenLabsProtocol
import com.hermesvox.speech.PcmStream
import com.hermesvox.speech.SpeechChunks
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Android edges of the portable PCM pipeline. One track for the entire completed reply.
 * No retry/fallback, no RMS/barge-in claim. Stop pauses/flushes immediately; only the
 * playback worker releases the track, so cancellation cannot release underneath a write.
 */
internal class ElevenLabsPcmPlayback(
    private val config: ElevenLabsProtocol.Config,
    private val current: () -> Boolean
) : PcmStream.Sink {
    private val lock = Any()
    @Volatile private var cancelled = false
    private var connection: HttpURLConnection? = null
    private var track: AudioTrack? = null
    private var pipeline: PcmStream? = null
    private var submittedFrames = 0L

    fun play(text: String) {
        val chunks = SpeechChunks.split(text) // validate ALL text before submitting anything
        val watchdog = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "vox-pcm-deadline").apply { isDaemon = true }
        }
        try {
            // Total reply wall-clock budget, including playback/backpressure; no infinite trickle.
            watchdog.schedule({ cancel() }, 20, TimeUnit.MINUTES)
            checkActive()
            val minimum = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0) throw IOException("PCM output unavailable")
            val output = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(24000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minimum, 9600)) // >=200ms, device minimum may be larger
                .setTransferMode(AudioTrack.MODE_STREAM).build()
            synchronized(lock) {
                track = output
                checkActive()
                if (output.state != AudioTrack.STATE_INITIALIZED) throw IOException("PCM init failed")
                output.play()
            }
            val stream = PcmStream({ chunk -> open(chunk) }, this)
            synchronized(lock) { pipeline = stream; checkActive() }
            stream.play(chunks)
        } finally {
            watchdog.shutdownNow()
            cancel()
            synchronized(lock) {
                try { track?.release() } catch (_: Exception) {}
                track = null
                pipeline = null
            }
        }
    }

    private fun open(chunk: String): InputStream {
        checkActive()
        val request = ElevenLabsProtocol.streamSpeech(config, chunk)
        val next = URL(request.url).openConnection() as HttpURLConnection
        try {
            synchronized(lock) { checkActive(); connection = next }
            next.instanceFollowRedirects = false
            next.requestMethod = "POST"
            next.connectTimeout = 10_000
            next.readTimeout = 30_000 // idle network timeout, separate from wall-clock watchdog
            next.doOutput = true
            request.headers.forEach { (key, value) -> next.setRequestProperty(key, value) }
            next.setFixedLengthStreamingMode(request.body.size)
            next.outputStream.use { it.write(request.body) }
            checkActive()
            if (next.responseCode !in 200..299) throw IOException("Speech HTTP ${next.responseCode}")
            val type = next.contentType.orEmpty().substringBefore(';').trim().lowercase()
            if (type.isNotEmpty() && type != "application/octet-stream" &&
                type != "audio/pcm" && type != "audio/raw" && type != "audio/x-pcm")
                throw IOException("Unexpected PCM content type")
            val input = next.inputStream
            checkActive()
            return object : FilterInputStream(input) {
                override fun close() {
                    try { super.close() } finally {
                        next.disconnect()
                        synchronized(lock) { if (connection === next) connection = null }
                    }
                }
            }
        } catch (failure: Exception) {
            next.disconnect()
            synchronized(lock) { if (connection === next) connection = null }
            throw failure
        }
    }

    override fun write(bytes: ByteArray, length: Int) {
        var offset = 0
        var progressAt = SystemClock.elapsedRealtime()
        while (offset < length) {
            checkActive()
            val n = synchronized(lock) {
                checkActive()
                track?.write(bytes, offset, length - offset, AudioTrack.WRITE_NON_BLOCKING)
                    ?: throw IOException("PCM output missing")
            }
            if (n < 0 || n % 2 != 0) throw IOException("PCM output write failed")
            if (n > 0) {
                offset += n
                submittedFrames += n / 2
                progressAt = SystemClock.elapsedRealtime()
            } else {
                if (SystemClock.elapsedRealtime() - progressAt > 10_000) throw IOException("PCM output stalled")
                Thread.sleep(5)
            }
        }
    }

    override fun drain() {
        val capacity = synchronized(lock) { track?.bufferSizeInFrames ?: throw IOException("PCM output missing") }
        val padding = com.hermesvox.speech.PcmDrain.primingBytes(submittedFrames, capacity)
        if (padding > 0) write(ByteArray(padding), padding)
        // 20min wall budget keeps 24kHz frame count below unsigned 32-bit wrap.
        com.hermesvox.speech.PcmDrain.await(submittedFrames, {
            synchronized(lock) {
                checkActive()
                (track?.playbackHeadPosition ?: throw IOException("PCM output missing")).toLong() and 0xffffffffL
            }
        }, { !cancelled && current() }, { SystemClock.elapsedRealtime() }, { Thread.sleep(10) })
    }

    private fun checkActive() {
        if (cancelled || !current()) throw IOException("Speech stopped")
    }

    override fun cancel() {
        val network: HttpURLConnection?
        val stream: PcmStream?
        synchronized(lock) {
            if (cancelled) return
            cancelled = true
            try { track?.pause(); track?.flush() } catch (_: Exception) {}
            network = connection
            connection = null
            stream = pipeline
        }
        // Disconnect may block inside the HTTP implementation; never delay the Stop/UI thread.
        if (network != null) Thread({ network.disconnect() }, "vox-pcm-abort").apply { isDaemon = true }.start()
        stream?.cancel()
    }
}
