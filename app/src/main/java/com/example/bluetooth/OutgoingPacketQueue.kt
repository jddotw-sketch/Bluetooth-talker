package com.example.bluetooth

import java.util.concurrent.LinkedBlockingQueue

/**
 * Bounded Bluetooth send queue that preserves control packets under audio backpressure.
 * Only stale audio frames are eligible for eviction; a full control-only queue rejects
 * new packets instead of silently dropping an existing PTT/config/text command.
 */
internal class OutgoingPacketQueue(
    capacity: Int = 30,
    private val maxAudioPackets: Int = 15,
    private val isAudio: (Byte) -> Boolean
) {
    init {
        require(capacity > 0)
        require(maxAudioPackets in 1..capacity)
    }

    private val queue = LinkedBlockingQueue<Pair<Byte, ByteArray>>(capacity)

    @Synchronized
    fun offer(type: Byte, payload: ByteArray): Boolean {
        if (isAudio(type)) {
            // Prefer fresh voice data to stale buffered voice data.
            while (queue.count { isAudio(it.first) } >= maxAudioPackets) {
                if (!dropOldestAudio()) return false
            }
        }
        if (queue.offer(type to payload)) return true

        // Keep PTT, configuration, and text in order when the transport is congested.
        // Never evict a control packet just to enqueue newer audio.
        return dropOldestAudio() && queue.offer(type to payload)
    }

    private fun dropOldestAudio(): Boolean {
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            if (isAudio(iterator.next().first)) {
                iterator.remove()
                return true
            }
        }
        return false
    }

    fun take(): Pair<Byte, ByteArray> = queue.take()

    @Synchronized
    fun clearAudio() {
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            if (isAudio(iterator.next().first)) iterator.remove()
        }
    }

    @Synchronized
    fun clear() = queue.clear()
}
