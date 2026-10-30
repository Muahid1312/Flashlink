# FlashLink

## Download an Android test APK

The [Build Android APK workflow](https://github.com/Muahid1312/Flashlink/actions/workflows/build-apk.yml) runs on pushes to `main` and from **Actions → Build Android APK → Run workflow**. It uses Flutter 3.47.8, Java 21 and Android SDK 36. Flutter analysis/tests and the native SQLite, socket, TLS and recovery tests must pass before an APK is uploaded.

Open a **successful** run and download **FlashLink-Android-APK** from its **Artifacts** section (sign in to GitHub). Extract the ZIP to obtain `flashlink-debug.apk`. It is signed with the runner's debug key for testing, not a production release key. Artifacts are retained for 14 days; run the workflow again if one expires. Each fresh runner can use a different debug key, so updating an APK from another run may require uninstalling the previous test build first, which removes its local app data.

Copy the extracted APK to each phone and open it with a file manager, allowing installation from that file manager when Android asks. With USB debugging enabled, connect one authorized phone at a time and run `adb -d install -r /path/to/the/extracted/flashlink-debug.apk`, substituting the real extracted path. Repeat for the other phone. Device connectivity and transfers still require the physical tests below.

For a source ZIP, use **Code → Download ZIP** on GitHub. Local source-only archives can also be made with `git archive --format=zip --prefix=FlashLink/ HEAD -o ../FlashLink-source.zip`; only committed files are included, so ignored local settings, build caches and signing keys are excluded.

Offline Android file sharing over Wi-Fi Direct or an existing local Wi-Fi / manually enabled phone hotspot. The engine includes mutually authenticated TLS, private staged files, durable checkpoints, pause/resume and foreground-service ownership. No cloud, accounts, Bluetooth transfer or internet relay.

## Development

Use Flutter **3.47.8 stable** (Dart >=3.12), JDK 17 or 21 and Android SDK 36. Minimum Android is API 24. Gradle 9.3.1, AGP 9.1.0 and the Kotlin 2.4.0 compatibility declaration follow the Flutter SDK templates. Use the existing isolated checkout; do not create another worktree unless requested.

```sh
flutter pub get
dart format lib test
flutter analyze
flutter test
flutter build apk --debug
flutter run
```

Flutter artifacts require HTTPS access to `storage.googleapis.com`; the cloud environment has a pending domain allowance that must be applied before these commands can bootstrap. Do not disable TLS or checksum verification. Do not fabricate a lockfile: commit `pubspec.lock` after successful dependency resolution. Local SDK paths in `android/local.properties` are ignored. Release signing is not configured.

For standalone JVM integration tests, install a Kotlin compiler/runtime and JDK:

```sh
./tool/test_protocol.sh
```

`KOTLINC` and `KOTLIN` can select compiler/runtime launchers. Tests execute production framing, checkpoint validation and TLS code over actual loopback sockets and private files. Generated test certificates are temporary fixtures, not application identities. Host measurements are not Android storage or Wi-Fi Direct benchmarks. Flutter controller tests mock platform interfaces; SQLite repository tests use a real FFI database. Android Keystore, SAF, service lifecycle and SQLite interoperability still require Android tests.

## Connection modes and diagnosis

The home screen has **SEND** and **RECEIVE**. Local Wi-Fi / hotspot is the default. Optional Wi-Fi Direct, listening-address selection, streaming buffers and performance diagnostics live under **Settings → Connections & diagnostics**. Method selection and buffer changes are locked during an active session. The existing 64 KiB, 256 KiB (default) and 1 MiB streaming buffers remain available; no faster mode is claimed without measurements on the phones.

Wi-Fi Direct failures now show the operation, Android reason (`ERROR=0`, `P2P_UNSUPPORTED=1`, `BUSY=2`) and attempt. `adb logcat -s FlashLinkP2P FlashLinkConnection` shows native diagnostics without file contents or keys. The previous generic message discarded these details; it did not identify a hardware root cause. Connecting now waits for discovery-stop acknowledgement; BUSY retries stop after three attempts, generic ERROR retries once, and unsupported/permission failures do not loop. Discovery is bounded to 30 seconds, negotiation to 35 seconds and channel recovery to two attempts. Android 13+ requests Nearby devices; older versions require precise location and Location services, with coarse/fine requested together on Android 12. Device-specific radio/OEM causes require logs from the affected phones.

For the normal local flow, install the **same updated APK on both phones**:

1. On phone A open Android hotspot settings and enable its hotspot. On phone B connect Wi-Fi to A's hotspot. Accept Android's **stay connected / use without internet** prompt if shown. A common non-guest Wi-Fi network also works; no internet is required.
2. On the receiving phone open FlashLink → **RECEIVE**, choose a writable destination folder with Android's picker, then tap **Start receiving**. A QR appears only after the TCP listener is actually bound. It expires after two minutes; tap Start receiving again after expiry.
3. On the sending phone open FlashLink → **SEND**, **Choose files**, then **Scan receiver QR**. Camera permission is requested only when opening the scanner. QR recognition uses the bundled model and works offline on first use. Alternatively tap **Find receiver**, or expand **Paste invitation instead** and paste the copied receiver invitation.
4. Confirm **Connect** on the sender. Compare the **six-digit code on both phone screens**. On the receiver choose **Codes match · accept** only when the codes match. Otherwise reject and create a new invitation. Full certificate fingerprints are available in the pairing details. Receiver approval must arrive within 40 seconds.
5. Wait for **Connected · encrypted**, then tap **Send selected files** on the sender. On the receiver review the real names/count/sizes and destination, then tap **Accept files**. Completion means the files were hashed, saved to the selected SAF folder and acknowledged. History opens the saved file. Test a small file first, then a larger file.
6. After interruption/pause, create a fresh invitation and reconnect the **same phones/identities**, then resume the existing row from History on the original sender. Explicit Disconnect/Cancel retains the existing cancellation semantics; use Pause for resumable stopping.

Local sockets use observed private IPv4 Wi-Fi/hotspot subnets, bind to the selected Android Wi-Fi Network and source address, and have no cellular/internet relay fallback. The hotspot host is preferred when it has no ConnectivityManager Network; the sender uses the most specific matching subnet. AP bridge interfaces such as `br0` are recognized. Listener/route changes invalidate an active local session. IPv6-only networks are unsupported. Some OEMs block multicast or isolate clients: QR avoids the multicast dependency, but cannot bypass client isolation. Errors distinguish missing Wi-Fi/address, out-of-subnet peers, bind failure, refusal/unreachability, timeout, authentication and rejected/expired admission. **Update both phones**: connection admission v2 precedes the unchanged transfer protocol and rejects older pairing protocols.

Two-phone verification remains **PENDING**: permission denial/regrant on Android 12 and 13+, P2P ERROR/BUSY recovery, both group roles, Wi-Fi without internet, hotspot-owner/client roles, multicast blocked/manual fallback, wrong fingerprints, rejection/timeout, expired/replayed invitation, network loss/reconnect, backgrounding/activity recreation, and real verified files in both directions. Host socket/SQLite/Robolectric tests do not prove Android radio or OEM behavior.

## Optional Wi-Fi Direct

Under **Settings → Connections & diagnostics**, select Wi-Fi Direct. Grant Nearby devices on Android 13+, or precise location on older Android; older versions also need Location services enabled. Discover/connect on both phones. Android chooses the group owner. Both users still compare and approve their full certificate fingerprints in Send/Receive, then the owner opens its TCP listener and the client connects. This advanced path retains the prior pinned-certificate requirement and now also displays a committed pairing code. The normal hotspot/LAN flow does not request Wi-Fi Direct permissions.

Fingerprint replacement requires closing the session. Transfers remain bound to the original certificate identity and cannot resume with a different peer key. Native private keys are generated and retained in Android Keystore; they are not exported or hardcoded. Hardware-backed storage is device-dependent and is not claimed universally. Public certificate pins are stored in private app preferences; backup is disabled.

## Secure transport and protocol

`SecureTransport.kt` uses standard JSSE/Android TLS 1.2 or 1.3 with AEAD forward-secret suites and certificate validity checks. The sender pins the full receiver certificate from the invitation. A first-time LAN receiver starts a **pairing-only TLS listener without a pre-entered sender pin**: client key possession is verified by TLS, but application/file protocol access is quarantined until explicit code approval. Each side commits a random 256-bit nonce before either nonce is revealed. The six-digit comparison hashes both certificates, the single-use session and both committed nonces; commitments prevent adapting a key/nonce after seeing the other random value. Attempts are bounded; reject any code mismatch. The receiver persists the actual TLS client certificate pin only after user acceptance. No TransferSession or file metadata is admitted before that approval. Wi-Fi Direct continues to require previously approved pins on both endpoints. There is no plaintext or unauthenticated application-data fallback. Private keys remain in Android Keystore.

Protocol **v2** rejects v1 peers. Frames remain `kind:u8, length:i32 big-endian, payload`. Control frames are limited to 64 KiB; DATA frames to 1 MiB. Strict UTF-8 strings and UUIDs are bounded. Manifests allow at most 128 files, nonnegative 64-bit sizes, overflow-checked totals and safe names, plus each source's initial SHA-256. Filenames reject paths, dot paths, control/directional characters and names over 180 UTF-8 bytes. No sender string becomes an arbitrary output path.

HELLO exchanges protocol version, fresh session UUID, authenticated device display name, capabilities and readiness. TLS provides fresh session keys and record replay/tamper protection. Persistent UUID tombstones reject old OFFER approvals even after visible history deletion. Resume requests must match the stored approved manifest **byte-for-byte** and the authenticated certificate fingerprint. Completed receipts can reconcile a lost final ACK without allowing any new file bytes.

Pairing/connection attempts are capped at five per minute. Explicit reconnect performs at most three TLS attempts with bounded backoff; authentication failures are not retried. Transfer/resume requests are limited to ten per minute. These retries never invoke Android Wi-Fi pairing automatically. Idle control keepalives do not create progress; sessions expire after 30 minutes and require reauthentication. No theoretical link speeds are displayed.

## Durable pause/resume

Files stream sequentially through native bounded buffers (256 KiB default; 64 KiB and 1 MiB available for benchmark comparisons). Full content never crosses Flutter channels or resides in a whole-file byte array. Progress events are throttled; byte counters, decimal MB/s and ETA derive from actual IO and monotonic time. A resumed meter excludes the already-copied prefix. ETA is absent during validation/verification or without useful samples.

Before first sending, the source is hashed once into the immutable manifest. On resume, remaining source files are revalidated in full because SAF metadata can be unreliable; the current source prefix must also match the receiver's computed prefix hash. This extra read is an intentional correctness cost, not repeated per-buffer hashing. A source changed after validation still fails final SHA-256. Already verified files are skipped.

The receiver writes **app-private** `files/transfer-parts/<transfer UUID>/<file UUID>.part` files. Arbitrary SAF providers do not need random-access support. Approximately every 4 MiB, the sender submits a chunk hash and exact offset. The receiver checks its own chunk hash, fsyncs the file, then commits the chunk and offset in one SQLite transaction using FULL synchronization before acknowledging. Optimistic sent-byte counters are never used as trusted resume offsets. History inspection checks availability without reading file bodies or truncating them; full chunk validation and unconfirmed-tail repair occur only during an explicit resume. Chunk rows are streamed from SQLite rather than loaded as an unbounded list.

Pause requests stop the sender at the next bounded buffer boundary, checkpoint the tail, notify the peer and close the transport. Only then is Paused shown. Unexpected socket loss preserves the last durable checkpoint; unacknowledged trailing bytes are truncated only after the saved chunk hashes and partial length validate. Missing/corrupt partial data, invalid offsets, revoked grants and changed sources fail closed. They never silently restart at byte zero. Explicit Cancel deletes partial data; a new selection creates a new approved transfer if restart is necessary.

On resume, reconnect Wi-Fi Direct if needed, authenticate the **same peer**, then choose Resume on the original sending phone's History row. The receiver validates persisted chunks and computes its prefix proof. The sender validates the source and proof before sending more bytes. Receiver history states when it is ready for the original sender. Resume/Retry actions are hidden when required data or grants are unavailable, or the locally approved peer differs.

After all private files verify against their manifest hashes, they are exported to the chosen SAF tree using fresh UUID-prefixed names. Destination exports are journalled; an interrupted export can be removed and retried from staging without overwriting existing user files. Only finalized, verified files are reported complete. Local completion becomes terminal before the COMPLETE acknowledgement is flushed, and sender receipts become terminal before a later cancellation can be processed. Completion/history are committed atomically and a later connection close cannot erase completed history or destination files. Cancellation checks also cover export. Providers that cannot create/write/rename/delete may fail; cleanup is best effort and failures are surfaced. A persistent completed receipt survives lost acknowledgements. App-private staging is cleaned after durable completion or explicit cancellation/deletion.

## Android background operation

`TransferService` uses foreground-service type `connectedDevice`, with FOREGROUND_SERVICE and FOREGROUND_SERVICE_CONNECTED_DEVICE declarations. Existing CHANGE_WIFI_STATE satisfies its network-interaction prerequisite. It starts only from a user action while the activity is visible, posts a notification promptly, exposes Pause/Cancel actions, monitors actual P2P group loss and stops when there is no live session or transfer. It is not a sticky auto-restart or pairing loop.

POST_NOTIFICATIONS is requested only from the notification-controls entry point on Android 13+. If denied, Android can restrict the notification drawer presentation; its foreground-task UI still applies. Activity recreation detaches only Flutter listeners; service IO retains application context rather than Activity references. A killed process is recovered as Interrupted from the durable ledger on the next app launch. No claim is made that every OEM or Android power policy will preserve a service indefinitely.

Discovery still uses NEARBY_WIFI_DEVICES with neverForLocation on Android 13+, and precise location plus required location-services state on older Android. Android 12 requests coarse/fine together for the precision dialog. No broad storage permission is requested. CAMERA is optional and requested only from Scan receiver QR; denied/unavailable cameras can use the invitation fallback.

## Transfer preparation and saving

The existing binary v2 engine streams actual content URI bytes over the confirmed TLS session. Unknown document sizes are measured in a bounded-buffer preparation scan before the immutable offer is sent; a declared size that changes is rejected. Preparation progress counts source bytes checked, separately from transmitted bytes. Cancel closes the active source stream and signals Android provider cancellation off the UI thread. A provider that ignores cancellation/close still requires device-specific validation.

Incoming previews show per-file names, sizes, MIME types and the authenticated peer. A writable SAF tree may be chosen while the exact incoming offer is awaiting approval, including after connecting through Nearby/local Wi-Fi. Selection does not approve the offer. Late results for expired/replaced/cancelled requests cannot retarget another job; active or already-approved jobs cannot change destination. The selected destination is persisted with the job and displayed separately from the default for future transfers.

Received bytes remain private until SHA-256 verification. Final SAF copying rehashes and checks the exact staging bytes again before renaming temporary documents, and uses the file's valid MIME type (extension/generic fallback). Empty and mixed-file batches share the same path. Abandoned journalled SAF exports are removed on explicit cancellation/deletion; completed files survive history deletion. Revoked permissions or a provider's deletion failure produce a cleanup warning. Provider I/O does not hold the ledger monitor during another job's checkpoints.

Progress uses real streamed bytes and monotonic timing with decimal MB/s; preparation, validation and final saving have distinct labels. Native events remain bounded to roughly five updates per second. Streaming and final copying reuse bounded buffers, use 64-bit sizes/offsets and never encode file contents as JSON/Base64. Existing pause/checkpoint/resume, receiver approval and mutual TLS are retained.

## Performance diagnostics

Settings → Connections & diagnostics → Performance diagnostics displays real, monotonic per-attempt measurements: application average, peak ≥250 ms sample, source read/staging write rates, TLS payload I/O time, hashing, durable checkpoints, validation and verified destination saving. Source preparation/approval wait and pre-existing resumed bytes are excluded from the application average. Raw radio throughput/link speed are unavailable. The local SQLite store retains at most 100 results and excludes filenames, URIs, file hashes, addresses and peer identities. Clear measurements removes those samples. Keep the same files and compare both connection modes and all three streaming buffers on the actual phones; no faster-mode recommendation is inferred from host tests.

DATA frames use a reusable contiguous header/payload buffer; control boundaries still flush immediately. No integrity checkpoint, hash, TLS check or peer approval is skipped. Native progress stays at roughly 5 Hz; real notification progress is throttled to 1 Hz, with immediate state changes. A bounded renewable partial CPU wake lock is held only during preparation/streaming/verification/saving and released on terminal states/service destruction; Android/OEM policies can still stop the app. History can open the first completed received file or explicitly share the completed batch using read-only URI grants. The progress screen shows the current sequential file queue.

For a reproducible **host disk + pinned TLS loopback** profile (not Android radio throughput), use `./tool/test_protocol.sh --profile 100000000 3` with Kotlin/JDK installed; choose `1000000000` or a larger byte count only when temporary storage supports source and destination. It streams real temporary files, performs durable checkpoints, verifies the destination hash, reports timings, and cleans up; it does not populate app diagnostics/history. Test output is not a phone-speed claim.

## Storage, history and limits

SQLite version 3 retains prior history and adds exact manifests, peer fingerprints, source/destination URIs, approval state, per-file offsets/verification, export URIs, chunk hashes and update timestamps. History distinguishes Completed, Paused, Interrupted, Failed and Cancelled. Resume/Retry, Cancel and Delete history/partials operate on actual native ledger state. Deletion retains a minimal anti-replay UUID tombstone. General progress history is throttled separately from durable file checkpoints. Active source/checkpoint validation and destination finalization send liveness only as actual IO advances; idle timeouts remain bounded. Completed destination files survive history deletion. Cancelling a resume preflight preserves approved checkpoints instead of classifying cancellation as corruption.

Completion is published/acknowledged only after its SQLite receipt commits. Explicit cancellation waits for closed protocol workers before deleting saved journals or destination documents, so it cannot delete a batch whose completion already won. If a provider is still closing, cleanup is deferred with a visible retry message. Late session callbacks cannot restore a closed secure badge or overwrite a newer transfer.

One batch is active at a time; duplicate starts/resumes are rejected. There is no persisted pending multi-batch queue or multi-peer scheduler. This avoids silently executing a pending batch against a newly connected peer. A receiving device needs private staging space for the full batch, in addition to the destination space used during export. Storage-full and revoked-permission errors are failures, not successful transfers.

## Physical-device validation (pending)

No phones are attached to the coding environment. JVM TLS tests do not validate Android Keystore/Conscrypt compatibility, notifications, SAF providers, SQLite ownership or OEM power policy. A successful Android APK build and these checks remain release prerequisites:

1. Install on two phones; include API 24–32 and API 33+ coverage. Connect P2P and confirm the real group-owner role/address.
2. Compare/approve full fingerprints on both and accept the incoming authenticated connection on the listener phone; test a deliberately wrong fingerprint and confirm no secure badge or file acceptance. Restore the intended approvals.
3. Transfer a known 100 MB file, then a 1 GB file and a >4 GB file where space permits; record actual average speed in both directions on Wi-Fi Direct and local Wi-Fi/hotspot. Start once with no receive folder configured: select it from the incoming preview, verify that bytes do not flow until Accept, and check that a stale picker result cannot affect a later request. Transfer multiple files with duplicate names and an empty file; independently compare SHA-256. Then pause near 40% from each side. Verify durable nonzero offsets, reopen authenticated sessions and resume; independently compare final SHA-256.
4. Disconnect Wi-Fi mid-transfer. Reconnect manually, authenticate and resume without restarting at zero.
5. Recreate the activity, background/screen-lock during transfer, use notification Pause/Cancel, and force-stop/relaunch. Verify truthful Interrupted state and validated recovery; do not assume an OEM kept the process alive.
6. Modify/delete a source, corrupt/delete a private partial in a test build, revoke a URI grant, fill storage, and inject invalid offsets/duplicate requests in a test client. Confirm rejection and no false completion.
7. Replay an old OFFER or change its manifest; confirm rejection. Cancel/delete history and verify the old approval cannot be reused. Test final-ACK loss and completed-receipt reconciliation.
8. Check destination collisions and interrupted exports, then cancellation cleanup. Measure actual encrypted throughput in both directions and with each buffer size; do not extrapolate host loopback rates to phones.

A production release is not ready until the pending two-phone checks pass and the owner's production signing key is configured. The current acceptance matrix below is **awaiting physical-device verification**; host/Robolectric and Flutter tests are automated tests, not emulator or phone results. No emulator or physical phones are available in the coding environment.

Run every row on Wi-Fi Direct and on the same Wi-Fi/manually enabled hotspot, reverse sender/receiver roles, and retain the real Diagnostics results. Compare source and exported destination hashes independently on a trusted computer. Record actual phone models, Android versions, connection mode, buffer size and observed errors; do not fill in results before testing.

| Case | Two-phone procedure and acceptance condition | Status |
| --- | --- | --- |
| A | Discover on both phones; use QR/link/manual fallback if multicast is blocked. Only actual peers appear. | Manual pending |
| B | Reject then accept a connection; wrong pins/expired invitations fail. Confirm the exact file offer separately. | Manual pending |
| C | Transfer a known 10 MB file; bytes and independently computed SHA-256 match. | Manual pending |
| D | Repeat with 100 MB; record actual duration, application MB/s and verification/save time. | Manual pending |
| E | Repeat with 1 GB, then >4 GB when both staging and destination have room. | Manual pending |
| F | Send multiple files, an empty file and duplicate names. Verify each exported file and queue state. | Manual pending |
| G | Pause from each phone mid-file; reconnect the same peer and validate/resume at a durable nonzero offset. | Manual pending |
| H | Disable Wi-Fi mid-transfer, reopen it and create a fresh authenticated session; resume and verify the final hash. | Manual pending |
| I | Change a source or corrupt a partial in a test build; mismatches fail without a verified completion. | Manual pending |
| J | Disable mobile data and internet access while keeping the local Wi-Fi/hotspot active; transfers still succeed. | Manual pending |
| K | Background and screen-lock during transfer; inspect real notification progress and Pause/Cancel. Test process death/relaunch separately. | Manual pending |
| L | Deny nearby access, disable Wi-Fi, fill storage and revoke a URI grant. Show actionable errors and no false completion. | Manual pending |
| M | Relaunch after a completed batch; history remains verified and Open/Share work. Deleting history keeps destination files. | Manual pending |
| N | Run 20 sequential transfers, recreate the activity and background/return while listening; check discovery recovery and `adb shell dumpsys meminfo com.flashlink.app.preview` for growth. | Manual pending |

Android compatibility: minimum API 24, target/compile API 36 with the pinned Flutter toolchain. Android 13+ Wi-Fi Direct asks for Nearby Wi-Fi only when requested; API 24–32 uses fine location (Android 12 requests coarse/fine together). LAN has no broad storage/location request; SAF grants provide file access. The connected-device foreground-service type and normal permissions are declared; API 33+ notifications remain opt-in. Partial CPU wake locks are bounded, and no battery exemption or background-restart bypass is requested. Wi-Fi Direct hardware is optional; IPv4 LAN/hotspot remains an alternative. IPv6-only LAN, OEM radio behavior, guest isolation and battery restrictions require the manual cases above.


## Build and install a test APK

`./tool/build_apk.sh preview` produces a **release-mode/AOT, non-debuggable, Android-test-key-signed preview** when production signing is absent. It uses the separate `com.flashlink.app.preview` application ID, version `0.1.0-preview` (code 2), and output `build/artifacts/flashlink-preview.apk`. It is installable for acceptance testing, **not production signed**. CI uploads it separately as `FlashLink-Android-Preview-APK`, alongside the verified debug artifact. Test keys on clean CI runners can change; updating an older preview with a different signing certificate may require removing that test installation and its local data first. No helper automatically uninstalls user data.

Ordinary `./tool/build_apk.sh release` still refuses missing production signing credentials; the preview never silently substitutes for that command. All modes verify the actual APK signature, app label/identifier/version/SDK and merged permissions. Release/preview outputs must be non-debuggable and contain AOT code. Failed attempts remove stale named output artifacts.

No production release key is generated or replaced by these helpers. If a release key is available, put `storeFile`, `storePassword`, `keyAlias` and `keyPassword` in ignored `android/key.properties`; `storeFile` is resolved relative to `android`. Never commit that file or its keystore. `tool/build_apk.sh release` refuses missing signing configuration. With no release key, use a debug APK for testing.

```sh
./tool/build_apk.sh debug
./tool/build_apk.sh preview
# Or, only with your existing release keystore configured:
./tool/build_apk.sh release
```

The helper builds through Flutter, checks a nonempty APK, verifies its signature with Android `apksigner`, then prints its **actual** absolute artifact path. Only a successful verified build creates the named `flashlink-debug.apk`, `flashlink-preview.apk` or `flashlink-release.apk` under `build/artifacts`. A script's existence is not evidence that either APK exists.

Connect exactly one authorized USB test phone with USB debugging enabled, run `./tool/install_apk.sh` for the verified debug artifact, then repeat with the other phone. The helper performs `adb -d install -r` and refuses a missing/empty APK. Pass a verified release artifact path as its first argument to test that build. On machines without tools in PATH, set FLUTTER, ANDROID_HOME, APKSIGNER or ADB to their installed paths as appropriate.

For manual installation, copy the actual APK produced by a successful build to each phone, allow installation from that chosen file manager when Android asks, and open the file. Do not use an unsigned release artifact or an invented download URL. Follow the physical-device checklist above: discover/connect, confirm both fingerprints, accept/reject, test 100 MB/1 GB/multiple files in both directions, verify hashes, pause/resume, disable/reconnect Wi-Fi, cancel, background/recreate/restart, and inspect actual history.

For Android ledger unit tests after Flutter dependencies are available, run `cd android && ./gradlew testDebugUnitTest`. Robolectric exercises the actual SQLite ledger code, including transaction rollback under an injected storage error. These are host platform tests, not evidence that two physical phones connect or that every Android 10–16/OEM power policy works.
