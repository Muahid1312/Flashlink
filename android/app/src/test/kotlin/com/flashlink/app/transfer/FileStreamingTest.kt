package com.flashlink.app.transfer

import org.junit.Test
import org.junit.Assert.*
import java.io.*
import java.security.MessageDigest
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

class FileStreamingTest {
    private fun meta(bytes: ByteArray) = FileMeta(Protocol.id(),"archive.zip",bytes.size.toLong(),"application/zip",MessageDigest.getInstance("SHA-256").digest(bytes))
    @Test fun resumeHashAndPrefixValidationRejectStalledProviders() {
        val stalled = object: java.io.InputStream() {
            override fun read(): Int = 0
            override fun read(buffer: ByteArray,offset: Int,length: Int): Int = 0
        }
        assertTrue(runCatching { FileValidation.hash(stalled,1) }.exceptionOrNull() is java.io.IOException)
        assertTrue(runCatching { FileValidation.seed(stalled,1,MessageDigest.getInstance("SHA-256")) }.exceptionOrNull() is java.io.IOException)
    }
    @Test fun unknownSourceSizeIsMeasuredWithTheActualBytesAndHash() {
        val bytes = ByteArray(1_048_579) { (it % 251).toByte() }
        val samples = mutableListOf<Long>()
        val scan = SourcePreparation(65536).scan(ByteArrayInputStream(bytes),progress={ samples.add(it) })
        assertEquals(bytes.size.toLong(),scan.size); assertArrayEquals(meta(bytes).sha256,scan.sha256)
        assertEquals(bytes.size.toLong(),samples.last()); assertTrue(samples.zipWithNext().all { it.second > it.first })
    }
    @Test fun declaredSourceChangesAreRejectedBeforeAManifestIsCreated() {
        assertTrue(runCatching { SourcePreparation().scan(ByteArrayInputStream(byteArrayOf(1)),2) }.exceptionOrNull() is EOFException)
        assertTrue(runCatching { SourcePreparation().scan(ByteArrayInputStream(byteArrayOf(1,2)),1) }.exceptionOrNull() is IOException)
    }
    @Test(timeout=10_000) fun cancellationClosesABlockedSourceAndReleasesTheWorker() {
        val reading = CountDownLatch(1); val closed = CountDownLatch(1)
        val input = object: InputStream() {
            override fun read(): Int = error("bulk reads only")
            override fun read(bytes: ByteArray, offset: Int, size: Int): Int { reading.countDown(); check(closed.await(5,TimeUnit.SECONDS)); throw IOException("Source closed") }
            override fun close() { closed.countDown() }
        }
        val preparation = SourcePreparation(); val executor = Executors.newSingleThreadExecutor()
        try {
            val scan = executor.submit<SourcePreparation.Result> { preparation.scan(input) }
            assertTrue(reading.await(2,TimeUnit.SECONDS)); preparation.requestCancel(); preparation.close()
            assertTrue(runCatching { scan.get(2,TimeUnit.SECONDS) }.exceptionOrNull() is ExecutionException)
            assertEquals(0L,closed.count)
        } finally { preparation.close(); executor.shutdownNow() }
    }
    @Test fun cancellationBeforeAProviderReturnsStillClosesItsLateStream() {
        val closed = AtomicBoolean(false)
        val input = object: ByteArrayInputStream(byteArrayOf(1)) { override fun close() { closed.set(true); super.close() } }
        val preparation = SourcePreparation(); preparation.requestCancel()
        assertTrue(runCatching { preparation.scan(input) }.exceptionOrNull() is InterruptedIOException)
        assertTrue(closed.get())
    }
    @Test fun aBrokenProviderCannotCauseAnInfiniteEmptyReadLoop() {
        var reads = 0
        val source = object: InputStream() {
            override fun read() = 0
            override fun read(bytes: ByteArray, offset: Int, size: Int): Int { reads++; return 0 }
        }
        assertTrue(runCatching { SourcePreparation().scan(source) }.exceptionOrNull() is IOException)
        assertEquals(16,reads)
    }
    @Test fun exportCopiesRealBytesAndVerifiesEmptyAndMultipleFiles() {
        val files = listOf(ByteArray(0),ByteArray(2_000_003) { (it % 127).toByte() },"document".toByteArray())
        val buffer = ByteArray(65536)
        for(bytes in files) {
            val output = ByteArrayOutputStream(); var progress = 0L
            assertEquals(bytes.size.toLong(),FileValidation.copyVerified(ByteArrayInputStream(bytes),output,meta(bytes),buffer) { progress = it })
            assertArrayEquals(bytes,output.toByteArray()); assertEquals(bytes.size.toLong(),progress)
        }
    }
    @Test fun changedOrTruncatedStagingCannotReachDocumentFinalization() {
        val bytes = "verified bytes".toByteArray(); val file = meta(bytes)
        for(changed in listOf(bytes.copyOf(bytes.size-1),bytes+byteArrayOf(1),bytes.copyOf().also { it[0] = 0 })) {
            var finalized = false
            assertTrue(runCatching {
                FileValidation.copyVerified(ByteArrayInputStream(changed),ByteArrayOutputStream(),file)
                finalized = true
            }.isFailure)
            assertFalse(finalized)
        }
    }
    @Test fun exportPropagatesStorageFailuresAndCancellation() {
        val bytes = byteArrayOf(1,2,3); val file = meta(bytes)
        val full = object: OutputStream() { override fun write(value: Int) { throw IOException("Storage full") } }
        assertTrue(runCatching { FileValidation.copyVerified(ByteArrayInputStream(bytes),full,file) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { FileValidation.copyVerified(ByteArrayInputStream(bytes),ByteArrayOutputStream(),file,cancelled={ true }) }.exceptionOrNull() is InterruptedIOException)
    }
    @Test fun resumedOffsetsAndSpeedUse64BitCountsWithoutCountingOldBytesAgain() {
        val baseline = 5_000_000_000L
        val file = FileMeta(Protocol.id(),"large.bin",baseline+1_000_000,"application/octet-stream")
        val manifest = Manifest(Protocol.id(),listOf(file))
        val point = ResumePoint(0,baseline,ResumePoint.emptyHash())
        assertEquals(baseline,ResumePoint.decode(point.encode(),Manifest.decode(manifest.encode())).offset)
        var now = 0L; val meter = Meter(baseline) { now }; now = 1_000_000_000L
        val values = meter.sample(baseline+1_000_000,manifest.total)
        assertEquals(1_000_000.0,values["averageBps"]); assertEquals(1_000_000.0,values["currentBps"])
        assertEquals(0.0,values["etaSeconds"])
    }
}
