package com.flashlink.app.transfer

import org.junit.Test

/** Makes the existing executable socket suites part of testDebugUnitTest in CI. */
class ProtocolIntegrationTest {
    @Test(timeout = 180_000)
    fun framingStreamingAndValidation() {
        ProtocolIntegration.main(emptyArray())
    }

    @Test(timeout = 180_000)
    fun authenticatedRecoveryAndResume() {
        RecoveryIntegration.main(emptyArray())
    }

    @Test(timeout = 180_000)
    fun cancellationAndFinalizationRegressions() {
        AuditIntegration.main(emptyArray())
    }
}
