package com.example.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Independent playback for up to two Bluetooth peers. Android AudioFlinger
 * mixes the tracks; individual source jitter cannot block the other track.
 */
class PeerAudioMixer {
    companion object {
        private const val TAG = "PeerAudioMixer"
        private const val STARTUP_BUFFER_PACKETS = 3
        private const val STARTUP_BUFFER_WAIT_MS = 85L
        private const val IDLE_RESET_MS = 180L
        private const val MAX_BUFFERED_PACKETS = 30
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private val peers = mutableMapOf<String, Peer>()
    private val rates = mutableMapOf<String, Int>()
    private val mutex = Any()
    private val allowedRates = setOf(16000, 44100, 48000)

    private class Peer(val address: String, val sampleRate: Int, private val scope: CoroutineScope) {
        private val queue = LinkedBlockingQueue<ByteArray>(MAX_BUFFERED_PACKETS)
        private val track: AudioTrack
        private val writer: Job

        init {
            val minBytes = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            require(minBytes > 0) { "Invalid output rate: $sampleRate" }
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBytes * 3, sampleRate / 5 * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            require(track.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack init failed" }
            track.play()

            writer = scope.launch {
                var needInitialBuffer = true
                try {
                    while (isActive) {
                        val pcm = queue.poll(IDLE_RESET_MS, TimeUnit.MILLISECONDS)
                        if (pcm == null) {
                            // The sender went silent; rebuffer once when a new
                            // talkspurt begins, not after every individual packet.
                            needInitialBuffer = true
                            continue
                        }

                        if (needInitialBuffer) {
                            val deadline = SystemClock.elapsedRealtime() + STARTUP_BUFFER_WAIT_MS
                            while (isActive && queue.size < STARTUP_BUFFER_PACKETS &&
                                SystemClock.elapsedRealtime() < deadline) {
                                delay(5)
                            }
                            needInitialBuffer = false
                        }

                        var offset = 0
                        while (offset < pcm.size && isActive) {
                            val count = track.write(
                                pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING
                            )
                            if (count <= 0) {
                                Log.w(TAG, "AudioTrack write returned $count for $address")
                                break
                            }
                            offset += count
                        }
                    }
                } catch (e: InterruptedException) {
                    // Expected when a Bluetooth peer disconnects.
                } catch (e: Exception) {
                    if (isActive) Log.w(TAG, "Playback stopped for $address", e)
                }
            }
        }

        fun submit(pcm: ByteArray) {
            if (pcm.isEmpty()) return
            if (queue.offer(pcm)) return
            // A stalled peer must not accumulate arbitrarily old audio.
            queue.poll()
            if (!queue.offer(pcm)) {
                Log.w(TAG, "Unable to enqueue PCM for $address")
            }
        }

        fun close() {
            writer.cancel()
            queue.clear()
            try {
                track.pause()
                track.flush()
                track.release()
            } catch (_: Exception) { }
        }
    }

    fun setSampleRate(address: String, rate: Int) {
        if (rate !in allowedRates) return
        synchronized(mutex) {
            if (rates[address] == rate) return
            rates[address] = rate
            peers.remove(address)?.close()
            Log.i(TAG, "Configured $address playback at $rate Hz")
        }
    }

    fun receive(address: String, pcm: ByteArray) {
        if (pcm.isEmpty()) return
        synchronized(mutex) {
            // Sender capture hardware may choose 48 kHz despite a 44.1 kHz
            // preference. Each 20 ms decoded frame reveals the *actual*
            // sample rate, even if a TYPE_CONFIG frame arrived late.
            val inferred = PcmFrameProfile.inferSampleRate(pcm.size)
            if (inferred != null && rates[address] != inferred) {
                Log.w(TAG, "Correcting $address playback rate from ${rates[address]} to $inferred Hz (frame=${pcm.size} bytes)")
                rates[address] = inferred
                peers.remove(address)?.close()
            }

            val rate = rates[address] ?: 16000
            val peer = peers[address] ?: try {
                Peer(address, rate, scope).also { peers[address] = it }
            } catch (e: Exception) {
                Log.e(TAG, "Cannot create playback for $address at $rate Hz", e)
                return
            }
            peer.submit(pcm)
        }
    }

    fun remove(address: String) {
        synchronized(mutex) {
            peers.remove(address)?.close()
            rates.remove(address)
        }
    }

    fun retain(addresses: Set<String>) {
        synchronized(mutex) {
            (peers.keys + rates.keys).filterNot { it in addresses }.forEach {
                peers.remove(it)?.close()
                rates.remove(it)
            }
        }
    }

    fun release() {
        synchronized(mutex) {
            peers.values.forEach { it.close() }
            peers.clear()
            rates.clear()
        }
        scope.cancel()
    }
}
