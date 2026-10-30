package com.flashlink.app.transfer

import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import android.content.Context
import java.io.FileOutputStream
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LedgerAuditTest {
    private lateinit var context: Context
    private lateinit var ledger: TransferLedger
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("flashlink.db")
        ledger = TransferLedger(context)
    }
    @After fun teardown() { ledger.close(); context.deleteDatabase("flashlink.db") }
    private fun manifest() = Manifest(Protocol.id(),listOf(FileMeta(Protocol.id(),"test.bin",10,"application/octet-stream",ByteArray(32))))
    private fun event(manifest: Manifest,status: String) = mapOf<String,Any?>("id" to manifest.id,"status" to status,"bytes" to manifest.total,"started" to 1000L,"averageBps" to 100.0)

    @Test fun diagnosticsAreBoundedPrivateDeduplicatedAndSurviveReopen() {
        val id = Protocol.id()
        val sample = mapOf<String,Any?>("diagnosticId" to id,"status" to "interrupted","direction" to "sent","resuming" to true,
            "peer" to "PRIVATE_PEER","files" to listOf("PRIVATE_NAME"),"uri" to "content://PRIVATE_URI", "total" to 5_000_000_000L,
            "metrics" to mapOf("attemptBytes" to 100L,"durationSeconds" to 1.0,"applicationBps" to 100.0,"readBps" to Double.NaN,"fileName" to "PRIVATE_NAME"))
        ledger.recordPerformance(sample,"localWifi"); ledger.recordPerformance(sample + ("status" to "completed"),"localWifi")
        ledger.close(); ledger = TransferLedger(context)
        val row = ledger.performance().single()
        assertEquals("interrupted",row["status"]); assertEquals(5_000_000_000L,row["total"])
        assertEquals(1,row["interruptions"]); assertEquals(true,row["resumed"])
        assertFalse(row.toString().contains("PRIVATE")); assertFalse(row.containsKey("readBps"))
        repeat(101) { ledger.recordPerformance(sample + ("diagnosticId" to Protocol.id()),"wifiDirect") }
        assertEquals(100,ledger.performance().size)
        ledger.clearPerformance(); assertTrue(ledger.performance().isEmpty())
    }
    @Test fun onlyVerifiedCompletedReceivesCanExposeDestinationDocuments() {
        val manifest = manifest(); ledger.create(manifest,"a".repeat(64),false,destination="content://test/tree/folder")
        assertTrue(runCatching { ledger.completedDocuments(manifest.id) }.isFailure)
        ledger.state(event(manifest,"completed"))
        assertTrue(runCatching { ledger.completedDocuments(manifest.id) }.isFailure)
        ledger.writableDatabase.execSQL("INSERT INTO resume_files(job,id,verified,export_uri) VALUES(?,?,1,?)",arrayOf(manifest.id,manifest.files.single().id,"content://test/document/complete"))
        assertEquals("content://test/document/complete",ledger.completedDocuments(manifest.id).single().first.toString())
        ledger.cancelSaved(manifest.id,true)
        assertTrue(runCatching { ledger.completedDocuments(manifest.id) }.isFailure)
    }

    @Test fun historyValidationDoesNotTruncateUnconfirmedTail() {
        val manifest = manifest(); val file = manifest.files.single()
        ledger.create(manifest,"a".repeat(64),false)
        val path = ledger.file(manifest.id,file.id)
        path.parentFile!!.mkdirs()
        FileOutputStream(path).use { out ->
            val prefix = "prefix".toByteArray(); out.write(prefix); out.fd.sync()
            ledger.checkpoint(manifest.id,file.id,prefix.size.toLong(),MessageDigest.getInstance("SHA-256").digest(prefix))
            out.write("tail".toByteArray()); out.fd.sync()
        }
        assertEquals(6L,ledger.verifyChunks(manifest.id,file))
        assertEquals("Inspecting resumability must not modify the file",10L,path.length())
    }
    @Test fun explicitResumeRepairsOnlyTheUnconfirmedTail() {
        val manifest = manifest(); val file = manifest.files.single()
        ledger.create(manifest,"a".repeat(64),false)
        val path = ledger.file(manifest.id,file.id); path.parentFile!!.mkdirs()
        FileOutputStream(path).use { out ->
            val prefix = "prefix".toByteArray(); out.write(prefix); out.fd.sync()
            ledger.checkpoint(manifest.id,file.id,6,MessageDigest.getInstance("SHA-256").digest(prefix))
            out.write("tail".toByteArray()); out.fd.sync()
        }
        assertEquals(6L,ledger.verifyChunks(manifest.id,file,repair=true)); assertEquals(6L,path.length())
    }
    @Test fun corruptedPartialDisablesResumeInsteadOfRepeatingAnUnsafeAction() {
        val manifest = manifest(); val file = manifest.files.single()
        ledger.create(manifest,"a".repeat(64),false); ledger.approve(manifest.id)
        val path = ledger.file(manifest.id,file.id); path.parentFile!!.mkdirs()
        FileOutputStream(path).use { out ->
            out.write("prefix".toByteArray()); out.fd.sync()
            ledger.checkpoint(manifest.id,file.id,6,MessageDigest.getInstance("SHA-256").digest("prefix".toByteArray()))
        }
        FileOutputStream(path).use { it.write("broken".toByteArray()); it.fd.sync() }
        ledger.state(event(manifest,"paused"))
        val saved = ledger.load(manifest.id)!!
        assertTrue(runCatching { PrivateReceiveStore(ledger,saved).resumePoint() }.isFailure)
        assertFalse(ledger.load(manifest.id)!!.approved)
        assertEquals("failed",ledger.histories().single()["status"])
    }
    @Test fun verifiedCompletionCannotBecomeCancelledWhenClosingAnIdleSession() {
        val manifest = manifest(); ledger.create(manifest,"a".repeat(64),true,listOf("content://test/document/1"))
        ledger.approve(manifest.id); ledger.state(event(manifest,"completed"))
        ledger.state(event(manifest,"cancelled"))
        val row = ledger.histories().single()
        assertEquals("completed",row["status"]); assertEquals(1L,row["verified"])
    }
    @Test fun failedHistoryWriteCannotCommitAReceiptOrDeleteStagedBytes() {
        val manifest = manifest(); ledger.create(manifest,"a".repeat(64),false)
        val staged = ledger.file(manifest.id,manifest.files.single().id); staged.parentFile!!.mkdirs()
        staged.writeBytes(ByteArray(10) { 7 })
        ledger.writableDatabase.execSQL("CREATE TRIGGER fail_history BEFORE INSERT ON transfer_sessions BEGIN SELECT RAISE(ABORT,'injected storage failure'); END")
        assertTrue(runCatching { ledger.state(event(manifest,"completed")) }.isFailure)
        assertFalse(ledger.receipt(manifest.id))
        assertEquals("waitingForApproval",ledger.load(manifest.id)!!.status)
        assertTrue(staged.exists()); assertArrayEquals(ByteArray(10) { 7 },staged.readBytes())
        ledger.writableDatabase.execSQL("DROP TRIGGER fail_history")
    }
    @Test fun interruptedValidationRetainsApprovalAndCheckpointInsteadOfMarkingCorruption() {
        val manifest = manifest(); ledger.create(manifest,"a".repeat(64),false); ledger.approve(manifest.id)
        ledger.state(event(manifest,"paused")); val saved = ledger.load(manifest.id)!!
        val store = PrivateReceiveStore(ledger,saved); store.cancelPending()
        assertTrue(runCatching { store.resumePoint() }.isFailure)
        assertTrue(ledger.load(manifest.id)!!.approved)
        assertEquals("paused",ledger.load(manifest.id)!!.status)
    }
    @Test fun transportInterruptionDuringValidationDoesNotRevokeTheApprovedManifest() {
        val manifest = manifest(); ledger.create(manifest,"a".repeat(64),false); ledger.approve(manifest.id)
        ledger.state(event(manifest,"paused")); val saved = ledger.load(manifest.id)!!
        val store = PrivateReceiveStore(ledger,saved)
        assertTrue(runCatching { store.resumePoint { throw java.io.InterruptedIOException("Transport disconnected") } }.isFailure)
        assertTrue(ledger.load(manifest.id)!!.approved); assertEquals("paused",ledger.load(manifest.id)!!.status)
    }
    @Test fun deleteHistoryRetainsAntiReplayTombstone() {
        val manifest = manifest(); ledger.create(manifest,"b".repeat(64),false)
        ledger.approve(manifest.id); ledger.state(event(manifest,"paused"))
        ledger.cancelSaved(manifest.id,true)
        assertTrue(ledger.seen(manifest.id)); assertNull(ledger.load(manifest.id)); assertTrue(ledger.histories().isEmpty())
        ledger.readableDatabase.rawQuery("SELECT manifest,peer,sources,destination FROM resume_jobs WHERE id=?",arrayOf(manifest.id)).use { c ->
            assertTrue(c.moveToFirst()); assertEquals(0,c.getBlob(0).size); assertEquals("",c.getString(1)); assertEquals("[]",c.getString(2)); assertTrue(c.isNull(3))
        }
    }
    @Test fun existingDeletedHistoryMetadataIsScrubbedWhenReopened() {
        val manifest = manifest(); ledger.create(manifest,"b".repeat(64),false,destination="content://private/tree/folder")
        ledger.writableDatabase.execSQL("UPDATE resume_jobs SET deleted=1 WHERE id=?",arrayOf(manifest.id))
        ledger.writableDatabase.execSQL("INSERT INTO resume_files(job,id,export_uri) VALUES(?,?,?)",arrayOf(manifest.id,manifest.files.single().id,"content://private/document/file"))
        ledger.close(); ledger = TransferLedger(context)
        assertTrue(ledger.seen(manifest.id)); assertNull(ledger.load(manifest.id))
        ledger.readableDatabase.rawQuery("SELECT manifest,peer,destination FROM resume_jobs WHERE id=?",arrayOf(manifest.id)).use { c ->
            assertTrue(c.moveToFirst()); assertEquals(0,c.getBlob(0).size); assertEquals("",c.getString(1)); assertTrue(c.isNull(2))
        }
        ledger.readableDatabase.rawQuery("SELECT COUNT(*) FROM resume_files WHERE job=?",arrayOf(manifest.id)).use { c -> assertTrue(c.moveToFirst()); assertEquals(0,c.getInt(0)) }
    }
    @Test fun resumeRequiresExactManifestAndApprovedPeer() {
        val manifest = manifest(); val pin = "c".repeat(64)
        ledger.create(manifest,pin,false); ledger.approve(manifest.id); ledger.state(event(manifest,"paused"))
        assertNotNull(ledger.matches(manifest,pin)); assertNull(ledger.matches(manifest,"d".repeat(64)))
        assertNull(ledger.matches(Manifest(manifest.id,listOf(manifest.files.single().copy(name="changed.bin"))),pin))
    }
    @Test fun destinationSelectionBindsOnlyTheUnapprovedIncomingManifest() {
        val first = manifest(); val second = manifest()
        ledger.create(first,"a".repeat(64),false); ledger.create(second,"a".repeat(64),false)
        assertTrue(ledger.chooseDestination(first.id,"content://test/tree/selected"))
        assertEquals("content://test/tree/selected",ledger.load(first.id)!!.destination)
        assertNull(ledger.load(second.id)!!.destination)
        ledger.approve(first.id)
        assertFalse(ledger.chooseDestination(first.id,"content://test/tree/other"))
        assertEquals("content://test/tree/selected",ledger.load(first.id)!!.destination)
    }
    @Test fun lateFolderResultsCannotChangeCancelledOutgoingOrDeletedTransfers() {
        val outgoing = manifest(); ledger.create(outgoing,"a".repeat(64),true)
        assertFalse(ledger.chooseDestination(outgoing.id,"content://test/tree/selected"))
        val cancelled = manifest(); ledger.create(cancelled,"a".repeat(64),false)
        ledger.cancelSaved(cancelled.id,false)
        assertFalse(ledger.chooseDestination(cancelled.id,"content://test/tree/selected"))
        val deleted = manifest(); ledger.create(deleted,"a".repeat(64),false)
        ledger.cancelSaved(deleted.id,true)
        assertFalse(ledger.chooseDestination(deleted.id,"content://test/tree/selected"))
    }

    private class ExportProvider: android.content.ContentProvider() {
        var deletes = 0
        var deny = false
        override fun onCreate() = true
        override fun call(method: String, arg: String?, extras: android.os.Bundle?): android.os.Bundle {
            check(method == "android:deleteDocument")
            if(deny) throw SecurityException("Folder permission revoked")
            deletes++; return android.os.Bundle()
        }
        override fun query(uri: android.net.Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): android.database.Cursor? = null
        override fun getType(uri: android.net.Uri): String? = null
        override fun insert(uri: android.net.Uri, values: android.content.ContentValues?): android.net.Uri? = null
        override fun delete(uri: android.net.Uri, selection: String?, args: Array<out String>?): Int = 0
        override fun update(uri: android.net.Uri, values: android.content.ContentValues?, selection: String?, args: Array<out String>?): Int = 0
    }
    private fun exportFixture(completed: Boolean = false): Pair<Manifest,ExportProvider> {
        val provider = ExportProvider()
        provider.attachInfo(context,android.content.pm.ProviderInfo().apply { authority = "flashlink.testexports" })
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("flashlink.testexports",provider)
        val manifest = manifest(); ledger.create(manifest,"a".repeat(64),false)
        ledger.writableDatabase.execSQL("INSERT INTO resume_files(job,id,export_uri) VALUES(?,?,?)",arrayOf<Any?>(manifest.id,manifest.files.single().id,"content://flashlink.testexports/document/partial"))
        if(completed) ledger.state(event(manifest,"completed"))
        return manifest to provider
    }
    @Test fun deletingAnAbandonedTransferRemovesItsJournalledSafDocuments() {
        val (manifest,provider) = exportFixture()
        assertTrue(ledger.cancelSaved(manifest.id,true))
        assertEquals(1,provider.deletes); assertNull(ledger.load(manifest.id)); assertTrue(ledger.seen(manifest.id))
    }
    @Test fun deletingCompletedHistoryNeverDeletesTheFinalDestinationDocuments() {
        val (manifest,provider) = exportFixture(completed=true)
        assertTrue(ledger.cancelSaved(manifest.id,true))
        assertEquals(0,provider.deletes); assertTrue(ledger.receipt(manifest.id))
    }
    @Test fun revokedSafPermissionIsReportedWithoutUndoingTheCancellationTombstone() {
        val (manifest,provider) = exportFixture(); provider.deny = true
        assertFalse(ledger.cancelSaved(manifest.id,false))
        assertEquals("cancelled",ledger.load(manifest.id)!!.status); assertTrue(ledger.seen(manifest.id))
    }

}
