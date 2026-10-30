package com.flashlink.app.transfer

import org.junit.Test
import org.junit.Assert.*
import java.io.*

class PerformanceTest {
    @Test fun monotonicMetricsExcludeApprovalAndAlreadyResumedBytes() {
        var tick = 0L
        val metrics = TransferMetrics { tick }
        tick = 10_000_000_000L
        assertNull(metrics.sample(4_000_000_000L)["applicationBps"])
        metrics.begin(4_000_000_000L)
        metrics.measure(TransferMetrics.Stage.READ) { tick += 100_000_000; metrics.read(1_000_000) }
        tick += 900_000_000
        val sample = metrics.sample(4_001_000_000L,true)
        assertEquals(1_000_000L,sample["attemptBytes"])
        assertEquals(1.0,sample["durationSeconds"])
        assertEquals(1_000_000.0,sample["applicationBps"])
        assertEquals(10_000_000.0,sample["readBps"])
        assertEquals(1_000_000.0,sample["peakSampleBps"])
        tick += 90_000_000_000L
        assertEquals(sample,metrics.sample(4_001_000_000L,true))
    }
    @Test fun failedTimedOperationRetainsItsCostButDoesNotInventBytes() {
        var tick = 0L; val metrics = TransferMetrics { tick }; metrics.begin(0)
        runCatching { metrics.measure(TransferMetrics.Stage.WRITE) { tick += 500_000_000; throw IOException("disk full") } }
        val sample = metrics.sample(0,true)
        assertEquals(0.5,sample["writeSeconds"]); assertNull(sample["writeBps"]); assertNull(sample["applicationBps"])
    }
    @Test fun dataBatchingReducesUnderlyingWritesAndControlFlushesAllBytes() {
        var calls = 0
        val saved = ByteArrayOutputStream()
        val target = object: OutputStream() {
            override fun write(value: Int) { calls++; saved.write(value) }
            override fun write(value: ByteArray,offset: Int,length: Int) { calls++; saved.write(value,offset,length) }
        }
        val payload = ByteArray(65536) { (it%239).toByte() }
        val frame = ByteArray(payload.size+5).also { payload.copyInto(it,5) }
        val wire = Wire(ByteArrayInputStream(byteArrayOf()),target,65536)
        repeat(128) { wire.writeData(frame,payload.size) }
        wire.write(Protocol.KEEPALIVE,byteArrayOf())
        assertEquals("One bounded frame write plus one flushed control, rather than a tiny header write per DATA",129,calls)
        val reader = Wire(ByteArrayInputStream(saved.toByteArray()),ByteArrayOutputStream(),65536)
        val buffer = ByteArray(65536)
        repeat(128) { val header = reader.header(); assertEquals(Protocol.DATA,header.kind); reader.data(buffer,header.size); assertArrayEquals(payload,buffer) }
        assertEquals(Protocol.KEEPALIVE,reader.header().kind)
    }
    @Test fun shortDataTailFlushesBeforeCheckpointAndMalformedFramesAreRejected() {
        val bytes = ByteArrayOutputStream(); val wire = Wire(ByteArrayInputStream(byteArrayOf()),bytes)
        val frame = ByteArray(8); frame[5] = 7; frame[6] = 8; frame[7] = 9
        wire.writeData(frame,3); wire.write(Protocol.CHECKPOINT,byteArrayOf(1))
        val reader = Wire(ByteArrayInputStream(bytes.toByteArray()),ByteArrayOutputStream())
        assertEquals(Wire.Header(Protocol.DATA,3),reader.header())
        val payload = ByteArray(3); reader.data(payload,3); assertArrayEquals(byteArrayOf(7,8,9),payload)
        assertEquals(Wire.Header(Protocol.CHECKPOINT,1),reader.header())
        for(size in listOf(0,4,Protocol.MAX_DATA+1)) assertTrue(runCatching { wire.writeData(frame,size) }.isFailure)
    }
    @Test(timeout = 180_000) fun profilesReal100MBFileOverPinnedTlsWithEachBuffer() {
        RecoveryIntegration.profile()
    }
}
