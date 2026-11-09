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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class AudioStreamManager(
    private val context: Context,
    private val onAudioPacketReady: (ByteArray) -> Unit,
    private val onAmplitudeChanged: (isTransmitting: Boolean, amplitude: Float) -> Unit,
    private val onHeadsetStateChanged: (isPlugged: Boolean) -> Unit = {},
    private val onSampleRateChanged: (actualRate: Int) -> Unit = {}
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

    // Playback queue (capacity 30 = ~600ms ceiling to prevent bufferbloat while absorbing jitter)
    private val playbackQueue = LinkedBlockingQueue<ByteArray>(30)
    private var playbackJob: Job? = null

    private var recordingJob: Job? = null
    @Volatile
    private var isRecording = false
    @Volatile
    private var isLoopback = false
    @Volatile
    private var playbackSampleRate = AudioQuality.HI_FI_44K.sampleRate
    @Volatile
    private var remoteSampleRate: Int? = null

    var micGain: Float = 1.2f
    var isMuted: Boolean = false
    var rogerBeepEnabled: Boolean = true

    /** Play remote speech on the media stream without requesting exclusive audio focus. */
    @Volatile
    var mixWithOtherAudio: Boolean = true
        private set

    fun setMixWithOtherAudio(enabled: Boolean) {
        if (mixWithOtherAudio == enabled) return
        mixWithOtherAudio = enabled
        // AudioAttributes are immutable, so create a new track at the same sample rate.
        initAudioTrack(playbackSampleRate, force = true)
    }

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
        // Changing our microphone quality must not retune playback of the peer's audio.
        if (remoteSampleRate == null && !isLoopback) {
            initAudioTrack(quality.sampleRate)
        }
    }

    fun setRemoteSampleRate(sampleRate: Int) {
        if (sampleRate !in listOf(16000, 44100, 48000)) {
            Log.w(TAG, "Ignoring unsupported peer sample rate: $sampleRate")
            return
        }
        remoteSampleRate = sampleRate
        if (!isLoopback && playbackSampleRate != sampleRate) {
            Log.d(TAG, "Configuring receiver for $sampleRate Hz peer audio")
            initAudioTrack(sampleRate)
        }
    }

    fun resetRemoteSampleRate() {
        remoteSampleRate = null
        if (!isLoopback && playbackSampleRate != currentQuality.sampleRate) {
            initAudioTrack(currentQuality.sampleRate)
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
    private fun initAudioTrack(sampleRate: Int, force: Boolean = false) {
        if (!force && playbackSampleRate == sampleRate && audioTrack?.state == AudioTrack.STATE_INITIALIZED) return
        try {
            val minBufSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBufSize <= 0) {
                Log.e(TAG, "AudioTrack does not support $sampleRate Hz")
                return
            }
            val bufferSize = maxOf(minBufSize * 3, 8192)
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(if (mixWithOtherAudio) AudioAttributes.CONTENT_TYPE_SPEECH else AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val replacement = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (replacement.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack failed to initialize at $sampleRate Hz")
                replacement.release()
                return
            }
            try {
                replacement.play()
            } catch (e: Exception) {
                replacement.release()
                throw e
            }

            // Keep the old output intact if creating its replacement fails.
            val previous = audioTrack
            audioTrack = replacement
            playbackSampleRate = sampleRate
            try {
                previous?.stop()
                previous?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Old AudioTrack cleanup failed: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioTrack at $sampleRate Hz: ${e.message}")
        }
    }

    /**
     * Start with a small jitter buffer once per talkspurt, rather than pausing
     * for another prebuffer every time a single Bluetooth packet arrives late.
     */
    private fun startPlaybackWorker() {
        playbackJob?.cancel()
        playbackJob = scope.launch {
            var needsPrebuffer = true
            while (isActive) {
                try {
                    val data = playbackQueue.poll(120, TimeUnit.MILLISECONDS)
                    if (data == null) {
                        needsPrebuffer = true
                        continue
                    }
                    if (needsPrebuffer) {
                        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50)
                        while (isActive && playbackQueue.size < 2 && System.nanoTime() < deadline) {
                            delay(5)
                        }
                        needsPrebuffer = false
                    }
                    writePlaybackFrame(data)
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    if (!isActive) break
                    Log.e(TAG, "Error in playback worker: ${e.message}")
                }
            }
        }
    }

    @Synchronized
    private fun writePlaybackFrame(data: ByteArray) {
        if (data.isEmpty()) return
        if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
            initAudioTrack(remoteSampleRate ?: currentQuality.sampleRate)
        }
        val track = audioTrack ?: return
        try {
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
            var offset = 0
            while (offset < data.size) {
                // Android can return a partial write after a route or output state change.
                val count = track.write(data, offset, data.size - offset, AudioTrack.WRITE_BLOCKING)
                if (count <= 0) {
                    Log.w(TAG, "AudioTrack.write failed with code $count")
                    return
                }
                offset += count
            }
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack playback error: ${e.message}")
        }
    }

    /**
     * Tries multiple candidate sample rates and audio sources to create a 100% valid AudioRecord.
     * Guaranteed never to throw uncaught exceptions or crash on real hardware.
     */
    @SuppressLint("MissingPermission")
    private fun createSafeAudioRecord(requestedSampleRate: Int, sourceMode: AudioSourceMode): Pair<AudioRecord, Int>? {
        // 1. Audio Relay mode (Internal system & app audio capture on Android 10+)
        if (sourceMode == AudioSourceMode.APP_AUDIO_RELAY && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mediaProjection != null) {
            try {
                val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()

                for (rate in listOf(requestedSampleRate, 48000, 44100, 16000)) {
                    val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    if (minBuf <= 0) continue
                    val bufSize = maxOf(minBuf * 4, 8192)
                    try {
                        val format = AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()
                        val rec = AudioRecord.Builder()
                            .setAudioPlaybackCaptureConfig(captureConfig)
                            .setAudioFormat(format)
                            .setBufferSizeInBytes(bufSize)
                            .build()
                        if (rec.state == AudioRecord.STATE_INITIALIZED) {
                            return Pair(rec, rate)
                        } else {
                            rec.release()
                        }
                    } catch (ignored: Exception) {}
                }
            } catch (e: Exception) {
                Log.e(TAG, "AudioPlaybackCapture config failed: ${e.message}")
            }
        }

        // 2. Microphone capture: Test candidate rates and audio sources supported by device hardware
        // Android microphone hardware standard rates: 16000 Hz and 48000 Hz are universal
        val candidateRates = when (requestedSampleRate) {
            44100 -> listOf(44100, 48000, 16000)
            else -> listOf(16000, 48000, 44100)
        }

        val candidateSources = listOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        )

        for (rate in candidateRates) {
            val minBuf = try {
                AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            } catch (e: Exception) {
                -1
            }
            if (minBuf <= 0) continue
            val bufSize = maxOf(minBuf * 4, 4096)

            for (src in candidateSources) {
                try {
                    val rec = AudioRecord(src, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
                    if (rec.state == AudioRecord.STATE_INITIALIZED) {
                        return Pair(rec, rate)
                    } else {
                        rec.release()
                    }
                } catch (ignored: Exception) {}
            }
        }

        return null
    }

    @SuppressLint("MissingPermission")
    fun startRecording(sourceMode: AudioSourceMode = AudioSourceMode.MICROPHONE, loopbackMode: Boolean = false): Boolean {
        if (isRecording) return true
        isLoopback = loopbackMode

        try {
            val pair = createSafeAudioRecord(currentQuality.sampleRate, sourceMode)
            if (pair == null) {
                Log.e(TAG, "Could not initialize AudioRecord for any supported rate/source")
                return false
            }

            val (record, actualRate) = pair
            audioRecord = record
            if (loopbackMode && playbackSampleRate != actualRate) {
                initAudioTrack(actualRate)
            }

            if (actualRate != currentQuality.sampleRate) {
                Log.d(TAG, "Recording at hardware sample rate $actualRate Hz (requested ${currentQuality.sampleRate})")
                onSampleRateChanged(actualRate)
            }

            try {
                record.startRecording()
            } catch (e: Exception) {
                Log.e(TAG, "record.startRecording failed: ${e.message}")
                stopRecording()
                return false
            }

            isRecording = true

            val chunkSize = (actualRate * 2 * 20) / 1000 // 20ms of audio

            recordingJob = scope.launch {
                try {
                    val buffer = ByteArray(chunkSize)
                    while (isActive && isRecording) {
                        val rec = audioRecord ?: break
                        var totalRead = 0
                        var readFailed = false
                        while (totalRead < chunkSize && isActive && isRecording) {
                            // AudioRecord may return fewer bytes than requested. Never transmit
                            // the incomplete frame: it causes irregular timing and ADPCM padding.
                            val count = try {
                                rec.read(buffer, totalRead, chunkSize - totalRead, AudioRecord.READ_BLOCKING)
                            } catch (e: Exception) {
                                Log.w(TAG, "AudioRecord.read failed: ${e.message}")
                                -1
                            }
                            when {
                                count > 0 -> totalRead += count
                                count == 0 -> delay(2)
                                else -> {
                                    Log.w(TAG, "AudioRecord returned error $count")
                                    readFailed = true
                                    break
                                }
                            }
                        }

                        if (readFailed) break
                        if (totalRead == chunkSize && isActive && isRecording) {
                            val processed = if (isMuted) {
                                ByteArray(totalRead)
                            } else {
                                applyGainAndCalculateAmplitude(buffer, totalRead, isTx = true)
                            }

                            if (isLoopback) {
                                playIncomingAudio(processed)
                            } else {
                                onAudioPacketReady(processed)
                            }
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Exception in recording loop: ${t.message}")
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "startRecording error: ${e.message}")
            stopRecording()
            return false
        }
    }

    fun stopRecording() {
        isRecording = false
        val wasLoopback = isLoopback
        isLoopback = false
        recordingJob?.cancel()
        recordingJob = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "stopRecording error: ${e.message}")
        }
        audioRecord = null
        if (wasLoopback) {
            val outputRate = remoteSampleRate ?: currentQuality.sampleRate
            if (playbackSampleRate != outputRate) initAudioTrack(outputRate)
        }
        onAmplitudeChanged(true, 0f)
    }

    fun playIncomingAudio(data: ByteArray) {
        if (data.isEmpty()) return
        // Keep enough capacity for brief scheduling or RFCOMM bursts before
        // discarding speech. Normal latency is set by the ~60ms startup prebuffer.
        while (playbackQueue.size >= 20) {
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

    fun clearPlaybackQueue() {
        playbackQueue.clear()
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
            try {
                val chirp = generateTone(frequencyHz = 1400.0, durationMs = 40, volume = 0.3f)
                playbackQueue.offer(chirp)
            } catch (ignored: Exception) {}
        }
    }

    fun playRogerBeep() {
        if (!rogerBeepEnabled) return
        scope.launch {
            try {
                val tone1 = generateTone(frequencyHz = 1200.0, durationMs = 50, volume = 0.3f)
                val tone2 = generateTone(frequencyHz = 950.0, durationMs = 50, volume = 0.3f)
                val silence = ByteArray(320)
                playbackQueue.offer(tone1 + silence + tone2)
            } catch (ignored: Exception) {}
        }
    }

    private fun generateTone(frequencyHz: Double, durationMs: Int, volume: Float): ByteArray {
        val sampleRate = playbackSampleRate
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
        try {
            mediaProjection?.stop()
        } catch (ignored: Exception) {}
        mediaProjection = null
    }
}
