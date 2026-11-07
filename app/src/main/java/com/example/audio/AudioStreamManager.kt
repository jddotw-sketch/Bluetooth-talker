package com.example.audio

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import com.example.model.AudioQuality
import com.example.model.AudioRoute
import com.example.model.AudioSourceMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class AudioStreamManager(
    private val context: Context,
    private val onAudioPacketReady: (ByteArray) -> Unit,
    private val onAmplitudeChanged: (isTransmitting: Boolean, amplitude: Float) -> Unit,
    private val onHeadsetStateChanged: (isPlugged: Boolean) -> Unit = {}
) {
    companion object {
        private const val TAG = "AudioStreamManager"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(Dispatchers.IO)

    var currentQuality: AudioQuality = AudioQuality.HI_FI_44K
        private set

    var currentRoute: AudioRoute = AudioRoute.HEADSET_OR_AUTO
        private set

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    // Generous playback queue (300 chunks) to completely prevent audio dropping during small BT jitter
    private val playbackQueue = LinkedBlockingQueue<ByteArray>(300)
    private var playbackJob: Job? = null

    private var recordingJob: Job? = null
    @Volatile
    private var isRecording = false
    private var isLoopback = false

    var micGain: Float = 1.2f
    var isMuted: Boolean = false
    var rogerBeepEnabled: Boolean = true

    // MediaProjection instance for Audio Relay (capturing all app/system audio)
    var mediaProjection: MediaProjection? = null

    // Throttle amplitude UI updates to ~20 fps (every 50ms)
    private var lastTxAmplitudeTime = 0L
    private var lastRxAmplitudeTime = 0L

    private var isHeadsetReceiverRegistered = false
    private val headsetReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_HEADSET_PLUG) {
                val state = intent.getIntExtra("state", -1)
                val isPlugged = state == 1
                Log.d(TAG, "Headset plug event: isPlugged=$isPlugged")
                if (isPlugged && currentRoute == AudioRoute.HEADSET_OR_AUTO) {
                    setAudioOutputRoute(AudioRoute.HEADSET_OR_AUTO)
                }
                onHeadsetStateChanged(isPlugged)
            }
        }
    }

    init {
        registerHeadsetReceiver()
        setAudioOutputRoute(AudioRoute.HEADSET_OR_AUTO)
        initAudioTrack(currentQuality.sampleRate)
        startPlaybackWorker()
    }

    private fun registerHeadsetReceiver() {
        if (!isHeadsetReceiverRegistered) {
            val filter = IntentFilter(Intent.ACTION_HEADSET_PLUG)
            context.registerReceiver(headsetReceiver, filter)
            isHeadsetReceiverRegistered = true
        }
    }

    fun setAudioQuality(quality: AudioQuality) {
        if (currentQuality == quality) return
        currentQuality = quality
        initAudioTrack(quality.sampleRate)
    }

    fun setRemoteSampleRate(sampleRate: Int) {
        val targetQuality = if (sampleRate >= 32000) AudioQuality.HI_FI_44K else AudioQuality.STANDARD_16K
        if (currentQuality.sampleRate != sampleRate) {
            Log.d(TAG, "Adapting AudioTrack to remote sample rate: $sampleRate Hz")
            currentQuality = targetQuality
            initAudioTrack(sampleRate)
        }
    }

    fun setAudioOutputRoute(route: AudioRoute) {
        currentRoute = route
        try {
            when (route) {
                AudioRoute.HEADSET_OR_AUTO -> {
                    // MODE_NORMAL is optimal for 3.5mm jack headsets, USB-C headphones & high-fidelity media
                    audioManager.mode = AudioManager.MODE_NORMAL
                    audioManager.isSpeakerphoneOn = false
                }
                AudioRoute.SPEAKERPHONE -> {
                    audioManager.mode = AudioManager.MODE_NORMAL
                    audioManager.isSpeakerphoneOn = true
                }
                AudioRoute.EARPIECE -> {
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    audioManager.isSpeakerphoneOn = false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring audio route: ${e.message}")
        }
    }

    @Synchronized
    private fun initAudioTrack(sampleRate: Int) {
        try {
            audioTrack?.let { oldTrack ->
                try {
                    oldTrack.stop()
                    oldTrack.release()
                } catch (ignored: Exception) {}
            }

            val minBufSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            // Generous Jitter Buffer (~500ms) to ensure continuous playback without cutting out
            val bufferSize = maxOf(minBufSize * 4, 16384)

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
                audioTrack?.play()
            } else {
                Log.e(TAG, "AudioTrack failed to initialize at $sampleRate Hz")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init AudioTrack: ${e.message}")
        }
    }

    private fun startPlaybackWorker() {
        playbackJob?.cancel()
        playbackJob = scope.launch {
            while (isActive) {
                try {
                    val data = playbackQueue.take()
                    if (audioTrack == null || audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                        initAudioTrack(currentQuality.sampleRate)
                    }
                    audioTrack?.let { track ->
                        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                            try {
                                track.play()
                            } catch (e: Exception) {
                                Log.e(TAG, "Error starting playback: ${e.message}")
                            }
                        }
                        track.write(data, 0, data.size)
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error in playback worker: ${e.message}")
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startRecording(sourceMode: AudioSourceMode = AudioSourceMode.MICROPHONE, loopbackMode: Boolean = false) {
        if (isRecording) return
        isLoopback = loopbackMode

        try {
            val sampleRate = currentQuality.sampleRate
            // ~20ms chunk size
            val chunkSize = (sampleRate * 2 * 20) / 1000 // 20ms of 16-bit PCM

            val minBufSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBufSize * 4, chunkSize * 6)

            // AUDIO RELAY (Capture internal device & app audio on Android 10+)
            if (sourceMode == AudioSourceMode.APP_AUDIO_RELAY && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mediaProjection != null) {
                try {
                    val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build()

                    val audioFormat = AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()

                    audioRecord = AudioRecord.Builder()
                        .setAudioPlaybackCaptureConfig(captureConfig)
                        .setAudioFormat(audioFormat)
                        .setBufferSizeInBytes(bufferSize)
                        .build()
                } catch (e: Exception) {
                    Log.e(TAG, "AudioPlaybackCapture creation failed, falling back to MIC: ${e.message}")
                    audioRecord = null
                }
            }

            // Fallback / Standard Microphone
            if (audioRecord == null) {
                val source = try {
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION
                } catch (e: Exception) {
                    MediaRecorder.AudioSource.MIC
                }

                audioRecord = AudioRecord(
                    source,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    audioRecord?.release()
                    audioRecord = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize
                    )
                }
            }

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord could not initialize")
                return
            }

            audioRecord?.startRecording()
            isRecording = true

            recordingJob = scope.launch {
                val buffer = ByteArray(chunkSize)
                while (isActive && isRecording) {
                    val readBytes = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (readBytes > 0) {
                        val processed = if (isMuted) {
                            ByteArray(readBytes)
                        } else {
                            applyGainAndCalculateAmplitude(buffer, readBytes, isTx = true)
                        }

                        if (isLoopback) {
                            playIncomingAudio(processed)
                        } else {
                            onAudioPacketReady(processed)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "startRecording error: ${e.message}")
            stopRecording()
        }
    }

    fun stopRecording() {
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "stopRecording error: ${e.message}")
        }
        audioRecord = null
        onAmplitudeChanged(true, 0f)
    }

    fun playIncomingAudio(data: ByteArray) {
        if (data.isEmpty()) return
        // Keep smooth playback buffer: only discard if buffer is completely saturated (>290)
        if (playbackQueue.remainingCapacity() < 2) {
            playbackQueue.poll()
        }
        playbackQueue.offer(data)

        // Calculate amplitude for visualizer (safely throttled)
        val now = System.currentTimeMillis()
        if (now - lastRxAmplitudeTime > 45) {
            lastRxAmplitudeTime = now
            calculateAmplitude(data, data.size, isTx = false)
        }
    }

    private fun applyGainAndCalculateAmplitude(buffer: ByteArray, length: Int, isTx: Boolean): ByteArray {
        if (length < 2) return buffer
        val numSamples = length / 2
        val shortArray = ShortArray(numSamples)
        ByteBuffer.wrap(buffer, 0, length)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(shortArray)

        var sumSquare = 0.0
        val gain = micGain.coerceIn(0.5f, 3.5f)

        for (i in 0 until numSamples) {
            val amplified = (shortArray[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
            shortArray[i] = amplified
            sumSquare += (amplified * amplified).toDouble()
        }

        val now = System.currentTimeMillis()
        if (now - lastTxAmplitudeTime > 45) {
            lastTxAmplitudeTime = now
            val mean = if (numSamples > 0) sumSquare / numSamples else 0.0
            val rms = sqrt(mean)
            val normalized = if (rms.isNaN()) 0f else (rms / 9000.0).toFloat().coerceIn(0f, 1f)
            onAmplitudeChanged(isTx, normalized)
        }

        val output = ByteArray(length)
        ByteBuffer.wrap(output)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .put(shortArray)

        return output
    }

    private fun calculateAmplitude(buffer: ByteArray, length: Int, isTx: Boolean) {
        if (length < 2) return
        val numSamples = length / 2
        val shortArray = ShortArray(numSamples)
        ByteBuffer.wrap(buffer, 0, length)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(shortArray)

        var sumSquare = 0.0
        for (i in 0 until numSamples) {
            val s = shortArray[i].toDouble()
            sumSquare += (s * s)
        }
        val mean = if (numSamples > 0) sumSquare / numSamples else 0.0
        val rms = sqrt(mean)
        val normalized = if (rms.isNaN()) 0f else (rms / 9000.0).toFloat().coerceIn(0f, 1f)
        onAmplitudeChanged(isTx, normalized)
    }

    fun playPttStartChirp() {
        scope.launch {
            val chirp = generateTone(frequencyHz = 1400.0, durationMs = 40, volume = 0.3f)
            playbackQueue.offer(chirp)
        }
    }

    fun playRogerBeep() {
        if (!rogerBeepEnabled) return
        scope.launch {
            val tone1 = generateTone(frequencyHz = 1200.0, durationMs = 50, volume = 0.3f)
            val tone2 = generateTone(frequencyHz = 950.0, durationMs = 50, volume = 0.3f)
            val silence = ByteArray(320)
            playbackQueue.offer(tone1 + silence + tone2)
        }
    }

    private fun generateTone(frequencyHz: Double, durationMs: Int, volume: Float): ByteArray {
        val sampleRate = currentQuality.sampleRate
        val numSamples = (sampleRate * durationMs) / 1000
        val shortArray = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            val angle = 2.0 * PI * i * frequencyHz / sampleRate
            val envelope = when {
                i < numSamples * 0.1 -> i / (numSamples * 0.1)
                i > numSamples * 0.9 -> (numSamples - i) / (numSamples * 0.1)
                else -> 1.0
            }
            shortArray[i] = (sin(angle) * 32767 * volume * envelope).toInt().toShort()
        }
        val output = ByteArray(numSamples * 2)
        ByteBuffer.wrap(output)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .put(shortArray)
        return output
    }

    fun release() {
        stopRecording()
        playbackJob?.cancel()
        playbackQueue.clear()
        if (isHeadsetReceiverRegistered) {
            try {
                context.unregisterReceiver(headsetReceiver)
            } catch (ignored: Exception) {}
            isHeadsetReceiverRegistered = false
        }
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.e(TAG, "release error: ${e.message}")
        }
        audioTrack = null
        mediaProjection?.stop()
        mediaProjection = null
    }
}
