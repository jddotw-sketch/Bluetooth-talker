package com.example.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Independent receiver for up to two Bluetooth peers.
 *
 * Each peer has its own sample clock, output track and bounded jitter queue.
 * Android AudioFlinger combines the two tracks in the media output stream,
 * preserving simultaneous playback instead of interleaving packets from peers.
 */
class PeerAudioMixer {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val peers = mutableMapOf<String, Peer>()
    private val mutex = Any()

    private class Peer(val address: String, val sampleRate: Int, private val scope: CoroutineScope) {
        private val queue = Channel<ByteArray>(capacity = 24)
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
                try {
                    for (data in queue) {
                        var offset = 0
                        while (offset < data.size) {
                            val count = track.write(
                                data, offset, data.size - offset, AudioTrack.WRITE_BLOCKING
                            )
                            if (count <= 0) break
                            offset += count
                        }
                    }
                } catch (e: Exception) {
                    Log.w("PeerAudioMixer", "Playback stopped for $address", e)
                }
            }
        }

        fun submit(pcm: ByteArray) {
            if (pcm.isEmpty()) return
            if (queue.trySend(pcm).isFailure) {
                // Drop oldest frames only for this source, never another peer.
                queue.tryReceive().getOrNull()
                queue.trySend(pcm)
            }
        }

        fun close() {
            queue.close()
            writer.cancel()
            try { track.pause(); track.flush(); track.release() } catch (_: Exception) {}
        }
    }

    private val rates = mutableMapOf<String, Int>()
    private val allowedRates = setOf(16000, 44100, 48000)

    fun setSampleRate(address: String, rate: Int) {
        if (rate !in allowedRates) return
        synchronized(mutex) {
            if (rates[address] == rate) return
            rates[address] = rate
            peers.remove(address)?.close()
        }
    }

    fun receive(address: String, pcm: ByteArray) {
        synchronized(mutex) {
            val peer = peers[address] ?: try {
                Peer(address, rates[address] ?: 16000, scope).also { peers[address] = it }
            } catch (e: Exception) {
                Log.e("PeerAudioMixer", "Cannot create playback for $address", e)
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
