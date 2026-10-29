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
    }
    @Test fun resumeRequiresExactManifestAndApprovedPeer() {
        val manifest = manifest(); val pin = "c".repeat(64)
        ledger.create(manifest,pin,false); ledger.approve(manifest.id); ledger.state(event(manifest,"paused"))
        assertNotNull(ledger.matches(manifest,pin)); assertNull(ledger.matches(manifest,"d".repeat(64)))
        assertNull(ledger.matches(Manifest(manifest.id,listOf(manifest.files.single().copy(name="changed.bin"))),pin))
    }
}
