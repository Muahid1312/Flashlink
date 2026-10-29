#!/bin/sh
set -eu
project_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
test_output=$(mktemp -d)
trap 'rm -rf "$test_output"' EXIT HUP INT TERM
"${KOTLINC:-kotlinc}" \
  "$project_root/android/app/src/main/kotlin/com/flashlink/app/transfer/Protocol.kt" \
  "$project_root/android/app/src/main/kotlin/com/flashlink/app/transfer/TransferSession.kt" \
  "$project_root/android/app/src/main/kotlin/com/flashlink/app/transfer/ResumePoint.kt" \
  "$project_root/android/app/src/main/kotlin/com/flashlink/app/transfer/SecureTransport.kt" \
  "$project_root/android/app/src/test/kotlin/com/flashlink/app/transfer/ProtocolIntegration.kt" \
  "$project_root/android/app/src/test/kotlin/com/flashlink/app/transfer/RecoveryIntegration.kt" \
  "$project_root/android/app/src/test/kotlin/com/flashlink/app/transfer/AuditIntegration.kt" \
  -d "$test_output/protocol-tests.jar"
"${KOTLIN:-kotlin}" -classpath "$test_output/protocol-tests.jar" com.flashlink.app.transfer.ProtocolIntegration

"${KOTLIN:-kotlin}" -classpath "$test_output/protocol-tests.jar" com.flashlink.app.transfer.RecoveryIntegration

"${KOTLIN:-kotlin}" -classpath "$test_output/protocol-tests.jar" com.flashlink.app.transfer.AuditIntegration
