package com.flashlink.app.transfer

import java.io.*
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Bounded source scanning before an immutable manifest is offered. The owner closes it off the UI thread. */
class SourcePreparation(private val bufferSize: Int = 262144) : Closeable {
    private val cancelled = AtomicBoolean(false)
    private val scratch by lazy { ByteArray(bufferSize) }
    private val current = AtomicReference<InputStream?>(null)
    data class Result(val size: Long, val sha256: ByteArray)
    init { require(bufferSize in Protocol.buffers) }
    fun ensureActive() { if(cancelled.get() || Thread.currentThread().isInterrupted) throw InterruptedIOException("Source preparation cancelled") }
    fun requestCancel() { cancelled.set(true) }
    fun scan(input: InputStream, expectedSize: Long = -1, progress: (Long)->Unit = {}): Result {
        require(expectedSize >= -1)
        if(!current.compareAndSet(null,input)) { input.close(); error("A source is already being scanned") }
        try {
            ensureActive()
            val digest = MessageDigest.getInstance("SHA-256"); val buffer = scratch
            var bytes = 0L; var emptyReads = 0
            while(true) {
                ensureActive()
                val n = input.read(buffer)
                ensureActive()
                if(n < 0) break
                if(n == 0) { if(++emptyReads >= 16) throw IOException("The document provider stopped returning source bytes"); continue }
                emptyReads = 0; bytes = Math.addExact(bytes,n.toLong())
                if(expectedSize >= 0 && bytes > expectedSize) throw IOException("Source grew while preparing; choose the file again")
                digest.update(buffer,0,n); progress(bytes)
            }
            if(expectedSize >= 0 && bytes != expectedSize) throw EOFException("Source changed size while preparing; choose the file again")
            return Result(bytes,digest.digest())
        } finally { current.compareAndSet(input,null); input.close() }
    }
    override fun close() { requestCancel(); current.getAndSet(null)?.close() }
}
