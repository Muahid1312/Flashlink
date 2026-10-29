package com.flashlink.app.transfer

import android.content.*
import android.database.sqlite.*
import android.net.Uri
import android.os.StatFs
import android.provider.DocumentsContract
import org.json.JSONArray
import java.io.*
import java.security.MessageDigest

class TransferLedger(val context: Context) : SQLiteOpenHelper(context,"flashlink.db",null,3) {
    private var lastStateId: String? = null
    private var lastStateTick = 0L
    init { setWriteAheadLoggingEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS transfers (id TEXT PRIMARY KEY,file_name TEXT NOT NULL,bytes INTEGER NOT NULL,direction TEXT NOT NULL,completed_at INTEGER NOT NULL)")
        onUpgrade(db,1,3)
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        db.execSQL("CREATE TABLE IF NOT EXISTS transfer_sessions (id TEXT PRIMARY KEY,direction TEXT,peer TEXT,file_name TEXT,file_count INTEGER,total INTEGER,bytes INTEGER,started INTEGER,ended INTEGER,average REAL,status TEXT,verified INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS resume_jobs (id TEXT PRIMARY KEY,manifest BLOB NOT NULL,peer TEXT NOT NULL,direction TEXT NOT NULL,sources TEXT,destination TEXT,approved INTEGER NOT NULL DEFAULT 0,status TEXT NOT NULL,updated INTEGER NOT NULL,deleted INTEGER NOT NULL DEFAULT 0,completion_receipt INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE IF NOT EXISTS resume_files (job TEXT NOT NULL,id TEXT NOT NULL,offset INTEGER NOT NULL DEFAULT 0,verified INTEGER NOT NULL DEFAULT 0,export_uri TEXT,PRIMARY KEY(job,id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS resume_chunks (job TEXT NOT NULL,file TEXT NOT NULL,start INTEGER NOT NULL,end INTEGER NOT NULL,hash BLOB NOT NULL,PRIMARY KEY(job,file,end))")
    }
    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        db.execSQL("PRAGMA synchronous=FULL")
        onUpgrade(db,3,3)
        db.execSQL("UPDATE resume_jobs SET status='interrupted' WHERE status IN ('preparing','transferring','verifying','finalizing','waitingForApproval','reconnecting')")
        db.execSQL("UPDATE transfer_sessions SET status='interrupted',verified=0 WHERE status IN ('preparing','transferring','verifying','finalizing','waitingForApproval','reconnecting')")
        db.rawQuery("SELECT id FROM resume_jobs WHERE status='cancelled' OR deleted=1",null).use { c ->
            while(c.moveToNext()) File(context.filesDir,"transfer-parts/${c.getString(0)}").deleteRecursively()
        }
    }
    data class Saved(val manifest: Manifest, val peer: String, val outgoing: Boolean, val sources: List<String>, val destination: String?, val approved: Boolean, val status: String)
    @Synchronized fun load(id: String): Saved? = readableDatabase.rawQuery("SELECT * FROM resume_jobs WHERE id=? AND deleted=0",arrayOf(id)).use { c ->
        if(!c.moveToFirst()) null else {
            fun text(name: String) = c.getString(c.getColumnIndexOrThrow(name))
            val sources = JSONArray(text("sources") ?: "[]")
            Saved(Manifest.decode(c.getBlob(c.getColumnIndexOrThrow("manifest"))),text("peer"),text("direction") == "sent",
                List(sources.length()) { sources.getString(it) },text("destination"),c.getInt(c.getColumnIndexOrThrow("approved")) == 1,text("status"))
        }
    }
    fun receipt(id: String): Boolean = readableDatabase.rawQuery("SELECT completion_receipt FROM resume_jobs WHERE id=?",arrayOf(id)).use { it.moveToFirst() && it.getInt(0) == 1 }
    fun seen(id: String): Boolean = readableDatabase.rawQuery("SELECT 1 FROM resume_jobs WHERE id=?",arrayOf(id)).use { it.moveToFirst() }
    @Synchronized fun create(manifest: Manifest, peer: String, outgoing: Boolean, sources: List<String> = emptyList(), destination: String? = null) {
        manifest.validate(); check(!seen(manifest.id)) { "Transfer ID already used" }
        val values = ContentValues().apply {
            put("id",manifest.id); put("manifest",manifest.encode()); put("peer",peer); put("direction",if(outgoing) "sent" else "received")
            put("sources",JSONArray(sources).toString()); put("destination",destination); put("approved",0)
            put("status","waitingForApproval"); put("updated",System.currentTimeMillis())
        }
        writableDatabase.insertOrThrow("resume_jobs",null,values)
    }
    fun approve(id: String) { writableDatabase.execSQL("UPDATE resume_jobs SET approved=1,updated=? WHERE id=?",arrayOf<Any?>(System.currentTimeMillis(),id)) }
    fun matches(manifest: Manifest, peer: String): Saved? {
        val saved = load(manifest.id) ?: return null
        if(saved.peer != peer || !saved.approved || saved.status !in setOf("paused","interrupted","failed","completed") ||
            !MessageDigest.isEqual(saved.manifest.encode(),manifest.encode())) return null
        return saved
    }
    fun file(id: String, file: String): File {
        Protocol.validId(id); Protocol.validId(file)
        return File(File(context.filesDir,"transfer-parts/$id"),"$file.part")
    }
    fun offset(job: String, file: String): Long = readableDatabase.rawQuery("SELECT offset FROM resume_files WHERE job=? AND id=?",arrayOf(job,file)).use { if(it.moveToFirst()) it.getLong(0) else 0L }
    fun verified(job: String, file: String): Boolean = readableDatabase.rawQuery("SELECT verified FROM resume_files WHERE job=? AND id=?",arrayOf(job,file)).use { it.moveToFirst() && it.getInt(0) == 1 }
    @Synchronized fun checkpoint(job: String, file: String, end: Long, hash: ByteArray) {
        val start = offset(job,file); require(end >= start && hash.size == 32)
        val db = writableDatabase; db.beginTransaction()
        try {
            if(end > start) db.execSQL("INSERT INTO resume_chunks(job,file,start,end,hash) VALUES(?,?,?,?,?)",arrayOf<Any?>(job,file,start,end,hash))
            db.execSQL("INSERT OR IGNORE INTO resume_files(job,id,offset,verified) VALUES(?,?,0,0)",arrayOf<Any?>(job,file))
            db.execSQL("UPDATE resume_files SET offset=? WHERE job=? AND id=?",arrayOf<Any?>(end,job,file))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun verifyChunks(job: String, meta: FileMeta, repair: Boolean = false, activity: ()->Unit = {}): Long {
        val end = offset(job,meta.id); require(end in 0..meta.size)
        val partial = file(job,meta.id)
        if(end == 0L && !partial.exists()) return 0
        require(partial.isFile && partial.length() >= end) { "Partial file is missing or shorter than its checkpoint" }
        readableDatabase.rawQuery("SELECT start,end,hash FROM resume_chunks WHERE job=? AND file=? ORDER BY end",arrayOf(job,meta.id)).use { cursor ->
            val chunks = sequence { while(cursor.moveToNext()) yield(ChunkCheckpoint(cursor.getLong(0),cursor.getLong(1),cursor.getBlob(2))) }
            CheckpointValidation.verify(partial,end,chunks.asIterable(),activity)
        }
        if(repair) { activity(); if(Thread.currentThread().isInterrupted) throw InterruptedIOException("Checkpoint repair interrupted"); RandomAccessFile(partial,"rw").use { it.setLength(end); it.fd.sync() } }
        return end
    }
    @Synchronized fun state(event: Map<String,Any?>) {
        val id = event["id"] as? String ?: return
        val status = event["status"] as? String ?: return
        val saved = load(id) ?: return
        val completedReceipt = receipt(id)
        if(completedReceipt) {
            // A late Cancel/EOF must never invalidate a committed receipt or verified history.
            if(status != "completed") return
            val alreadyRecorded = readableDatabase.rawQuery("SELECT 1 FROM transfer_sessions WHERE id=? AND status='completed' AND verified=1",arrayOf(id)).use { it.moveToFirst() }
            if(alreadyRecorded) return
        }
        val tick = System.nanoTime()
        if(status in setOf("transferring","verifying","finalizing") && lastStateId == id && tick-lastStateTick < 1_000_000_000L) return
        lastStateId = id; lastStateTick = tick
        val values = ContentValues().apply {
            put("id",id); put("direction",if(saved.outgoing) "sent" else "received"); put("peer",event["peer"] as? String ?: saved.peer)
            put("file_name",saved.manifest.files.joinToString(", ") { it.name }); put("file_count",saved.manifest.files.size)
            put("total",saved.manifest.total)
            val observed = (event["bytes"] as? Number)?.toLong() ?: 0
            val previous = if(event["resuming"] == true && status == "preparing") readableDatabase.rawQuery("SELECT bytes FROM transfer_sessions WHERE id=?",arrayOf(id)).use { if(it.moveToFirst()) it.getLong(0) else 0L } else 0L
            put("bytes",maxOf(observed,previous))
            put("started",(event["started"] as? Number)?.toLong() ?: System.currentTimeMillis()); put("ended",System.currentTimeMillis())
            put("average",(event["averageBps"] as? Number)?.toDouble()); put("status",status); put("verified",if(status == "completed") 1 else 0)
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE resume_jobs SET status=?,updated=?,completion_receipt=CASE WHEN ?='completed' THEN 1 ELSE completion_receipt END WHERE id=?",arrayOf<Any?>(status,System.currentTimeMillis(),status,id))
            check(db.insertWithOnConflict("transfer_sessions",null,values,SQLiteDatabase.CONFLICT_REPLACE) != -1L)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if(status == "completed") {
            File(context.filesDir,"transfer-parts/$id").deleteRecursively()
            releaseUnused(saved.sources)
        }
    }
    fun available(saved: Saved): Boolean = runCatching {
        check(saved.approved && saved.status in setOf("paused","interrupted","failed"))
        if(saved.outgoing) {
            check(saved.sources.size == saved.manifest.files.size)
            saved.sources.forEach { context.contentResolver.openAssetFileDescriptor(Uri.parse(it),"r")?.use { } ?: error("Source missing") }
        } else {
            check(saved.destination != null && context.contentResolver.persistedUriPermissions.any { it.uri.toString() == saved.destination && it.isWritePermission })
            saved.manifest.files.forEach { meta ->
                val n = offset(saved.manifest.id,meta.id)
                check(n in 0..meta.size)
                if(n > 0 || verified(saved.manifest.id,meta.id)) {
                    val path = file(saved.manifest.id,meta.id)
                    check(path.isFile && path.length() >= n)
                }
            }
        }
    }.isSuccess
    fun histories(): List<Map<String,Any?>> = readableDatabase.rawQuery("SELECT * FROM transfer_sessions ORDER BY ended DESC LIMIT 500",null).use { c -> buildList {
        while(c.moveToNext()) {
            val row = c.columnNames.associateWith { column -> val i = c.getColumnIndexOrThrow(column); when(c.getType(i)) { 0 -> null; 1 -> c.getLong(i); 2 -> c.getDouble(i); else -> c.getString(i) } }
            val saved = load(row["id"] as String)
            add(row + mapOf("resumable" to (saved != null && available(saved))))
        }
    } }
    fun releaseUnused(candidates: List<String>) {
        val references = mutableSetOf<String>()
        context.getSharedPreferences("flashlink-destination",0).getString("tree",null)?.let { references.add(it) }
        readableDatabase.rawQuery("SELECT sources,destination FROM resume_jobs WHERE deleted=0 AND status NOT IN ('completed','cancelled')",null).use { c ->
            while(c.moveToNext()) {
                val source = JSONArray(c.getString(0) ?: "[]"); repeat(source.length()) { references.add(source.getString(it)) }
                c.getString(1)?.let { references.add(it) }
            }
        }
        candidates.distinct().filter { it !in references }.forEach { value ->
            val grant = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri.toString() == value }
            if(grant != null) runCatching { context.contentResolver.releasePersistableUriPermission(grant.uri,
                (if(grant.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                (if(grant.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)) }
        }
    }
    @Synchronized fun cancelSaved(id: String, delete: Boolean) {
        val saved = load(id) ?: return
        if(receipt(id) && !delete) return
        File(context.filesDir,"transfer-parts/$id").deleteRecursively()
        // Keep a minimal UUID tombstone; deleting history must not permit approval replay.
        writableDatabase.execSQL("UPDATE resume_jobs SET status='cancelled',sources='[]',destination=NULL,approved=0,deleted=? WHERE id=?",arrayOf<Any?>(if(delete) 1 else 0,id))
        writableDatabase.execSQL(if(delete) "DELETE FROM transfer_sessions WHERE id=?" else "UPDATE transfer_sessions SET status='cancelled',verified=0 WHERE id=?",arrayOf<Any?>(id))
        releaseUnused(saved.sources + listOfNotNull(saved.destination))
    }
}

class PrivateReceiveStore(private val ledger: TransferLedger, private val saved: TransferLedger.Saved) : ReceiveStore {
    private val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var exporting: OutputStream? = null
    @Volatile private var exportStarted = false
    private val exportStopped = java.util.concurrent.CountDownLatch(1)
    override fun release() {
        synchronized(this) { synchronized(outputs) { outputs.values.forEach { runCatching { it.close() } }; outputs.clear() } }
        if(exportStarted) { runCatching { exporting?.close() }; exportStopped.await() }
    }
    override fun cancelPending() { cancelled.set(true) }
    private val manifest = saved.manifest
    private val outputs = mutableMapOf<String,FileOutputStream>()
    @Synchronized override fun open(file: FileMeta): OutputStream {
        check(!cancelled.get()) { "Transfer closed before opening a partial file" }
        val end = ledger.offset(manifest.id,file.id)
        val path = ledger.file(manifest.id,file.id)
        check(path.parentFile!!.isDirectory || path.parentFile!!.mkdirs()) { "Cannot create private staging directory" }
        RandomAccessFile(path,"rw").use { it.setLength(end) }
        return FileOutputStream(path,true).also { synchronized(outputs) { outputs[file.id] = it } }
    }
    override fun checkpoint(file: FileMeta, offset: Long, hash: ByteArray) {
        synchronized(outputs) { outputs[file.id]?.fd?.sync() ?: error("Partial output is closed") }
        ledger.checkpoint(manifest.id,file.id,offset,hash)
    }
    override fun prefix(file: FileMeta, offset: Long, digest: MessageDigest) { prefix(file,offset,digest) {} }
    override fun prefix(file: FileMeta, offset: Long, digest: MessageDigest, activity: ()->Unit) {
        if(offset > 0) FileInputStream(ledger.file(manifest.id,file.id)).use { FileValidation.seed(it,offset,digest,activity) }
    }
    override fun commit(file: FileMeta) {
        require(ledger.file(manifest.id,file.id).length() == file.size)
        ledger.writableDatabase.execSQL("UPDATE resume_files SET verified=1 WHERE job=? AND id=?",arrayOf<Any?>(manifest.id,file.id))
    }
    override fun resumePoint(): ResumePoint = resumePoint {}
    override fun resumePoint(activity: ()->Unit): ResumePoint {
        try {
            if(cancelled.get() || Thread.currentThread().isInterrupted) throw InterruptedIOException("Checkpoint validation interrupted")
            activity(); return validatedResumePoint(activity)
        }
        catch(error: Exception) {
            if(cancelled.get() || error is InterruptedIOException || Thread.currentThread().isInterrupted) throw error
            ledger.writableDatabase.execSQL("UPDATE resume_jobs SET status='failed',approved=0,updated=? WHERE id=?",arrayOf<Any?>(System.currentTimeMillis(),manifest.id))
            ledger.writableDatabase.execSQL("UPDATE transfer_sessions SET status='failed',verified=0 WHERE id=?",arrayOf<Any?>(manifest.id))
            throw error
        }
    }
    private fun validatedResumePoint(activity: ()->Unit): ResumePoint {
        var completed = 0; var offset = 0L; var prefix = ResumePoint.emptyHash()
        for(file in manifest.files) {
            val n = ledger.verifyChunks(manifest.id,file,repair=true,activity=activity)
            if(ledger.verified(manifest.id,file.id)) {
                require(n == file.size)
                val digest = FileInputStream(ledger.file(manifest.id,file.id)).use { FileValidation.hash(it,file.size,activity=activity).first }
                require(MessageDigest.isEqual(digest,file.sha256)) { "Verified staging file changed" }
                completed++
            } else {
                offset = n
                if(n > 0) FileInputStream(ledger.file(manifest.id,file.id)).use { input ->
                    val hash = MessageDigest.getInstance("SHA-256"); FileValidation.seed(input,n,hash,activity); prefix = hash.digest()
                }
                break
            }
        }
        return ResumePoint(completed,offset,prefix)
    }
    override fun finish() { finish { } }
    override fun finish(progress: (Long)->Unit) {
        synchronized(this) { check(!cancelled.get()); exportStarted = true }
        try {
        var finalized = 0L
        val resolver = ledger.context.contentResolver
        val tree = Uri.parse(saved.destination ?: error("Destination is missing"))
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree))
        for(file in manifest.files) {
            check(!cancelled.get()) { "Export cancelled" }
            // Persist newly-created URIs before copying, so interruption can safely clean or retry export.
            val old = ledger.readableDatabase.rawQuery("SELECT export_uri FROM resume_files WHERE job=? AND id=?",arrayOf(manifest.id,file.id)).use { if(it.moveToFirst()) it.getString(0) else null }
            if(old != null) check(DocumentsContract.deleteDocument(resolver,Uri.parse(old))) { "Old interrupted export cannot be removed" }
            val uri = synchronized(this) {
                check(!cancelled.get())
                (DocumentsContract.createDocument(resolver,parent,"application/octet-stream",".${file.id}.part") ?: error("Cannot create destination")).also {
                    ledger.writableDatabase.execSQL("UPDATE resume_files SET export_uri=? WHERE job=? AND id=?",arrayOf(it.toString(),manifest.id,file.id))
                }
            }
            resolver.openOutputStream(uri,"w")!!.use { output ->
                exporting = output
                FileInputStream(ledger.file(manifest.id,file.id)).use { input ->
                    val buffer = ByteArray(262144)
                    while(true) {
                        check(!cancelled.get()) { "Export cancelled" }
                        val n = input.read(buffer); if(n < 0) break
                        output.write(buffer,0,n); finalized += n; progress(finalized)
                    }
                }
            }
            exporting = null
            synchronized(this) {
                check(!cancelled.get())
                val finalUri = DocumentsContract.renameDocument(resolver,uri,"${file.id}_${file.name}") ?: error("Cannot finalize destination")
                ledger.writableDatabase.execSQL("UPDATE resume_files SET export_uri=? WHERE job=? AND id=?",arrayOf(finalUri.toString(),manifest.id,file.id))
            }
        }
            } finally { exportStopped.countDown() }
    }
    @Synchronized override fun rollback(): Boolean {
        cancelled.set(true); runCatching { exporting?.close() }
        synchronized(outputs) { outputs.values.forEach { runCatching { it.close() } }; outputs.clear() }
        var success = true
        ledger.readableDatabase.rawQuery("SELECT export_uri FROM resume_files WHERE job=? AND export_uri IS NOT NULL",arrayOf(manifest.id)).use { c ->
            while(c.moveToNext()) runCatching { if(!DocumentsContract.deleteDocument(ledger.context.contentResolver,Uri.parse(c.getString(0)))) success = false }.onFailure { success = false }
        }
        if(!File(ledger.context.filesDir,"transfer-parts/${manifest.id}").deleteRecursively()) success = false
        return success
    }
}
