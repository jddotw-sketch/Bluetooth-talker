package com.example.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class AudioStreamManager(
    private val context: Context,
    private val onAudioPacketReady: (ByteArray) -> Unit,
    private val onAmplitudeChanged: (isTransmitting: Boolean, amplitude: Float) -> Unit
) {
    companion object {
        const val SAMPLE_RATE = 16000
        const val CHUNK_SIZE = 640 // 320 16-bit samples = 20ms of audio
        private const val TAG = "AudioStreamManager"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(Dispatchers.IO)

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    private var recordingJob: Job? = null
    private var isRecording = false
    private var isLoopback = false

    var micGain: Float = 1.2f
    var isMuted: Boolean = false
    var rogerBeepEnabled: Boolean = true

    init {
        initAudioTrack()
    }

    private fun initAudioTrack() {
        try {
            val minBufSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBufSize * 2, 4096)

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init AudioTrack: ${e.message}")
        }
    }

    fun setSpeakerphone(enabled: Boolean) {
        try {
            audioManager.isSpeakerphoneOn = enabled
            if (enabled) {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            } else {
                audioManager.mode = AudioManager.MODE_NORMAL
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting speakerphone: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun startRecording(loopbackMode: Boolean = false) {
        if (isRecording) return
        isLoopback = loopbackMode

        try {
            val minBufSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBufSize * 2, CHUNK_SIZE * 4)

            // Prefer VOICE_COMMUNICATION for echo cancellation / noise suppression
            val source = try {
                MediaRecorder.AudioSource.VOICE_COMMUNICATION
            } catch (e: Exception) {
                MediaRecorder.AudioSource.MIC
            }

            audioRecord = AudioRecord(
                source,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord VOICE_COMMUNICATION failed, retrying with MIC")
                audioRecord?.release()
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            }

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord could not initialize")
                return
            }

            audioRecord?.startRecording()
            isRecording = true

            recordingJob = scope.launch {
                val buffer = ByteArray(CHUNK_SIZE)
                while (isActive && isRecording) {
                    val readBytes = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (readBytes > 0) {
                        val processedBuffer = if (isMuted) {
                            ByteArray(readBytes)
                        } else {
                            applyGainAndCalculateAmplitude(buffer, readBytes, isTx = true)
                        }

                        if (isLoopback) {
                            playIncomingAudio(processedBuffer)
                        } else {
                            onAudioPacketReady(processedBuffer)
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
        try {
            if (audioTrack == null || audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                initAudioTrack()
            }
            audioTrack?.let { track ->
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    track.play()
                }
                track.write(data, 0, data.size)
                // Calculate incoming audio amplitude for the visualizer
                calculateAmplitude(data, data.size, isTx = false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing audio: ${e.message}")
        }
    }

    private fun applyGainAndCalculateAmplitude(buffer: ByteArray, length: Int, isTx: Boolean): ByteArray {
        val shortArray = ShortArray(length / 2)
        ByteBuffer.wrap(buffer, 0, length)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(shortArray)

        var sumSquare = 0.0
        val gain = micGain.coerceIn(0.5f, 3.5f)

        for (i in shortArray.indices) {
            val amplified = (shortArray[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
            shortArray[i] = amplified
            sumSquare += (amplified * amplified).toDouble()
        }

        val rms = sqrt(sumSquare / shortArray.size)
        // Normalize 0.0 to 1.0 (typical voice rms ranges 500 to 12000)
        val normalized = (rms / 9000.0).toFloat().coerceIn(0f, 1f)
        onAmplitudeChanged(isTx, normalized)

        val output = ByteArray(length)
        ByteBuffer.wrap(output)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .put(shortArray)

        return output
    }

    private fun calculateAmplitude(buffer: ByteArray, length: Int, isTx: Boolean) {
        val shortArray = ShortArray(length / 2)
        ByteBuffer.wrap(buffer, 0, length)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(shortArray)

        var sumSquare = 0.0
        for (sample in shortArray) {
            sumSquare += (sample * sample).toDouble()
        }
        val rms = sqrt(sumSquare / shortArray.size)
        val normalized = (rms / 9000.0).toFloat().coerceIn(0f, 1f)
        onAmplitudeChanged(isTx, normalized)
    }

    fun playPttStartChirp() {
        scope.launch {
            val chirp = generateTone(frequencyHz = 1400.0, durationMs = 45, volume = 0.35f)
            playDirectly(chirp)
        }
    }

    fun playRogerBeep() {
        if (!rogerBeepEnabled) return
        scope.launch {
            val tone1 = generateTone(frequencyHz = 1200.0, durationMs = 60, volume = 0.35f)
            val tone2 = generateTone(frequencyHz = 950.0, durationMs = 60, volume = 0.35f)
            val silence = ByteArray(320) // 10ms gap
            playDirectly(tone1 + silence + tone2)
        }
    }

    private fun playDirectly(data: ByteArray) {
        try {
            audioTrack?.let { track ->
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    track.play()
                }
                track.write(data, 0, data.size)
            }
        } catch (e: Exception) {
            Log.e(TAG, "playDirectly error: ${e.message}")
        }
    }

    private fun generateTone(frequencyHz: Double, durationMs: Int, volume: Float): ByteArray {
        val numSamples = (SAMPLE_RATE * durationMs) / 1000
        val shortArray = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            val angle = 2.0 * PI * i * frequencyHz / SAMPLE_RATE
            // Apply slight envelope (attack and decay) to prevent audio clicks
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
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.e(TAG, "release error: ${e.message}")
        }
        audioTrack = null
    }
}
