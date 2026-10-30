package com.flashlink.app.transfer

/** Per-attempt measurements. Never contains names, URIs, peer IDs, addresses or file hashes. */
class TransferMetrics(private val clock: () -> Long = System::nanoTime) {
    enum class Stage { READ, WRITE, SOCKET, HASH, CHECKPOINT, VALIDATION, EXPORT }
    private val nanos = LongArray(Stage.entries.size)
    private var start: Long? = null
    private var end: Long? = null
    private var baseline = 0L
    private var bytes = 0L
    private var readBytes = 0L
    private var writeBytes = 0L
    private var lastTick = 0L
    private var lastBytes = 0L
    private var peak: Double? = null
    @Synchronized fun begin(offset: Long) {
        if(start != null) return
        start = clock(); baseline = offset; bytes = offset; lastBytes = offset; lastTick = start!!
    }
    @PublishedApi internal fun tick() = clock()
    @PublishedApi @Synchronized internal fun add(stage: Stage, duration: Long) { nanos[stage.ordinal] += duration.coerceAtLeast(0) }
    inline fun <T> measure(stage: Stage, block: () -> T): T {
        val started = tick()
        try { return block() } finally { add(stage,tick()-started) }
    }
    @Synchronized fun read(count: Int) { readBytes += count }
    @Synchronized fun written(count: Int) { writeBytes += count }
    @Synchronized fun sample(value: Long, terminal: Boolean = false): Map<String,Any?> {
        val now = end ?: clock()
        if(start != null) {
            bytes = maxOf(bytes,value)
            val window = now-lastTick
            // Peak is an unsmoothed >=250 ms sample, not the fastest individual chunk.
            if(window >= 250_000_000L) {
                val speed = (bytes-lastBytes)*1e9/window
                peak = maxOf(peak ?: speed,speed); lastTick = now; lastBytes = bytes
            }
        }
        if(terminal && end == null) end = now
        val duration = start?.let { (now-it).coerceAtLeast(0)/1e9 }
        fun seconds(stage: Stage) = nanos[stage.ordinal]/1e9
        fun rate(count: Long, stage: Stage): Double? = if(count > 0 && nanos[stage.ordinal] > 0) count*1e9/nanos[stage.ordinal] else null
        return mapOf("attemptBytes" to (bytes-baseline), "durationSeconds" to duration,
            "applicationBps" to if(duration != null && duration > 0 && bytes > baseline) (bytes-baseline)/duration else null,
            "peakSampleBps" to peak, "readBps" to rate(readBytes,Stage.READ), "writeBps" to rate(writeBytes,Stage.WRITE),
            "readSeconds" to seconds(Stage.READ), "writeSeconds" to seconds(Stage.WRITE),
            "socketIoSeconds" to seconds(Stage.SOCKET), "hashSeconds" to seconds(Stage.HASH),
            "checkpointSeconds" to seconds(Stage.CHECKPOINT), "verificationSeconds" to seconds(Stage.VALIDATION),
            "exportSeconds" to seconds(Stage.EXPORT))
    }
}
