package com.example.bluetooth

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutgoingPacketQueueTest {
    private val audio: Byte = 1
    private val pttStart: Byte = 2
    private val pttEnd: Byte = 3
    private val text: Byte = 5

    private fun queue(capacity: Int = 4, maxAudio: Int = 2) =
        OutgoingPacketQueue(capacity, maxAudio) { it == audio }

    @Test
    fun dropsOldestAudioWithoutLosingPttMessages() {
        val q = queue()
        assertTrue(q.offer(pttStart, byteArrayOf()))
        repeat(20) { assertTrue(q.offer(audio, byteArrayOf(it.toByte()))) }
        assertTrue(q.offer(pttEnd, byteArrayOf()))

        assertEquals(pttStart, q.take().first)
        assertArrayEquals(byteArrayOf(18), q.take().second)
        assertArrayEquals(byteArrayOf(19), q.take().second)
        assertEquals(pttEnd, q.take().first)
    }

    @Test
    fun fullControlQueueNeverDiscardsEarlierControlMessages() {
        val q = queue(capacity = 2)
        assertTrue(q.offer(pttStart, byteArrayOf()))
        assertTrue(q.offer(pttEnd, byteArrayOf()))
        assertFalse(q.offer(audio, byteArrayOf()))
        assertEquals(pttStart, q.take().first)
        assertEquals(pttEnd, q.take().first)
    }

    @Test
    fun clearAudioPreservesQueuedTextAndPtt() {
        val q = queue()
        assertTrue(q.offer(pttStart, byteArrayOf()))
        assertTrue(q.offer(audio, byteArrayOf(1)))
        assertTrue(q.offer(text, byteArrayOf(2)))
        q.clearAudio()
        assertEquals(pttStart, q.take().first)
        assertEquals(text, q.take().first)
    }
}
