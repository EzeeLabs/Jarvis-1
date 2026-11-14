package com.ezeelabs.jarvis

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Streams 24 kHz mono PCM16 between Android and the authenticated backend.
 * The OpenAI credential remains exclusively on the server.
 */
class VoiceSession(
    context: Context,
    private val baseUrl: String,
    private val token: String,
    private val onState: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val SAMPLE_RATE = 24000
        private const val FRAME_BYTES = 4800 // 100 ms, mono PCM16
    }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val running = AtomicBoolean(false)
    private val muted = AtomicBoolean(false)
    private val speaker = AtomicBoolean(true)
    private val playbackExecutor = Executors.newSingleThreadExecutor()
    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var aec: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var oldAudioMode = AudioManager.MODE_NORMAL
    private var oldSpeaker = false
    private var recordThread: Thread? = null

    fun connect() {
        if (running.get()) return
        val wsUrl = baseUrl.trimEnd('/')
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://") + "/ws/voice"
        val request = Request.Builder()
            .url(wsUrl)
            .header("Authorization", "Bearer $token")
            .build()
        onState("connecting")
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                try {
                    startAudio()
                    onState("listening")
                } catch (e: Exception) {
                    onError("Microphone/audio could not start: ${e.message ?: "unknown error"}")
                    close()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val event = JSONObject(text)
                    when (event.optString("type")) {
                        "audio" -> {
                            val encoded = event.optString("audio")
                            if (encoded.isNotEmpty()) {
                                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                                playbackExecutor.execute {
                                    try { player?.write(bytes, 0, bytes.size) } catch (_: Exception) { }
                                }
                            }
                        }
                        "state" -> onState(event.optString("state", "listening"))
                        "transcript" -> {
                            val role = event.optString("role")
                            onState(if (role == "jarvis") "speaking" else "listening")
                        }
                        "error" -> onError(event.optString("message", "Voice session error"))
                        "pong" -> Unit
                    }
                } catch (e: Exception) {
                    onError("Received an invalid voice event.")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onError("Voice connection failed: ${t.message ?: "network error"}")
                onState("ended")
                releaseAudio()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onState("ended")
                releaseAudio()
            }
        })
    }

    private fun startAudio() {
        oldAudioMode = audioManager.mode
        oldSpeaker = audioManager.isSpeakerphoneOn
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = speaker.get()

        val minIn = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val minOut = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minIn <= 0 || minOut <= 0) throw IllegalStateException("This device does not support the required audio format.")

        val input = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minIn, FRAME_BYTES * 2))
            .build()
        val output = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minOut, FRAME_BYTES * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        recorder = input
        player = output
        if (AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(input.audioSessionId)?.apply { enabled = true }
        }
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = NoiseSuppressor.create(input.audioSessionId)?.apply { enabled = true }
        }
        input.startRecording()
        output.play()
        running.set(true)
        recordThread = Thread({ captureLoop(input) }, "JarvisMicCapture").apply { start() }
    }

    private fun captureLoop(input: AudioRecord) {
        val buffer = ByteArray(FRAME_BYTES)
        while (running.get()) {
            val count = input.read(buffer, 0, buffer.size)
            if (count > 0 && !muted.get()) {
                val payload = Base64.encodeToString(buffer.copyOf(count), Base64.NO_WRAP)
                val event = JSONObject().put("type", "audio").put("audio", payload)
                socket?.send(event.toString())
            }
        }
    }

    fun setMuted(value: Boolean) {
        muted.set(value)
        onState(if (value) "muted" else "listening")
    }

    fun setSpeakerEnabled(value: Boolean) {
        speaker.set(value)
        audioManager.isSpeakerphoneOn = value
    }

    fun end() {
        socket?.send(JSONObject().put("type", "end").toString())
        socket?.close(1000, "User ended session")
        releaseAudio()
    }

    fun close() {
        running.set(false)
        socket?.close(1000, "Session closed")
        releaseAudio()
    }

    private fun releaseAudio() {
        running.set(false)
        try { recorder?.stop() } catch (_: Exception) { }
        try { recorder?.release() } catch (_: Exception) { }
        recorder = null
        try { player?.pause(); player?.flush(); player?.stop(); player?.release() } catch (_: Exception) { }
        player = null
        try { aec?.release() } catch (_: Exception) { }
        try { noiseSuppressor?.release() } catch (_: Exception) { }
        aec = null
        noiseSuppressor = null
        audioManager.isSpeakerphoneOn = oldSpeaker
        audioManager.mode = oldAudioMode
    }
}
