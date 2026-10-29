# FlashLink

## Download an Android test APK

The [Build Android APK workflow](https://github.com/Muahid1312/Flashlink/actions/workflows/build-apk.yml) runs on pushes to `main` and from **Actions → Build Android APK → Run workflow**. It uses Flutter 3.47.8, Java 21 and Android SDK 36. Flutter analysis/tests and the native SQLite, socket, TLS and recovery tests must pass before an APK is uploaded.

Open a **successful** run and download **FlashLink-Android-APK** from its **Artifacts** section (sign in to GitHub). Extract the ZIP to obtain `flashlink-debug.apk`. It is signed with the runner's debug key for testing, not a production release key. Artifacts are retained for 14 days; run the workflow again if one expires. Each fresh runner can use a different debug key, so updating an APK from another run may require uninstalling the previous test build first, which removes its local app data.

Copy the extracted APK to each phone and open it with a file manager, allowing installation from that file manager when Android asks. With USB debugging enabled, connect one authorized phone at a time and run `adb -d install -r /path/to/the/extracted/flashlink-debug.apk`, substituting the real extracted path. Repeat for the other phone. Device connectivity and transfers still require the physical tests below.

For a source ZIP, use **Code → Download ZIP** on GitHub. Local source-only archives can also be made with `git archive --format=zip --prefix=FlashLink/ HEAD -o ../FlashLink-source.zip`; only committed files are included, so ignored local settings, build caches and signing keys are excluded.

Offline Android file sharing over Wi-Fi Direct. The engine includes mutually authenticated TLS, private staged files, durable checkpoints, pause/resume and foreground-service ownership. No cloud, accounts, Bluetooth transfer or internet relay.

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

## Pair and connect

1. In Nearby, grant the relevant OS permission, enable Wi-Fi and discover/connect both phones. Android controls pairing and which phone is group owner.
2. On Send or Receive, compare **both full certificate fingerprints** on the intended phones. Each user explicitly approves the other phone's fingerprint. The QR displays `flashlink:cert:<64 hex digits>`. It contains a public fingerprint, not a secret. A trusted external QR reader can read it for pasting; no in-app camera scanner or camera permission is included.
3. Choose a persistent writable SAF destination on the receiving phone before opening the secure session. Choose on both phones if both will receive. Select sender files using ACTION_OPEN_DOCUMENT; persistable read grants are taken only when Android offers them.
4. The actual group owner opens its listener first; the other phone connects. Both endpoints verify the approved peer certificate through mutual TLS. A locally approved fingerprint alone never produces a secure badge. The badge requires successful certificate validation, encryption and the versioned application handshake.
5. Either phone may send. The receiver sees the authenticated peer's chosen display name, exact names/count/size and destination, and explicitly Accepts or Rejects the new manifest. A Wi-Fi Direct connection or previous unrelated approval never authorizes files.

Fingerprint replacement requires closing the session. Transfers remain bound to the original certificate identity and cannot resume with a different peer key. Native private keys are generated and retained in Android Keystore; they are not exported or hardcoded. Hardware-backed storage is device-dependent and is not claimed universally. Public certificate pins are stored in private app preferences; backup is disabled.

## Secure transport and protocol

`SecureTransport.kt` uses standard JSSE/Android TLS 1.2 or TLS 1.3 with mutually pinned certificates and AEAD forward-secret suites. It checks certificate validity and full SHA-256 certificate pins on both endpoints. There is no accept-all trust manager, hostname bypass disguised as CA validation, or plaintext fallback. IP transport identity is the explicitly approved certificate, rather than a DNS hostname.

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

Discovery still uses NEARBY_WIFI_DEVICES with neverForLocation on Android 13+, and precise location plus required location-services state on older Android. Android 12 requests coarse/fine together for the precision dialog. No broad storage or camera permission is requested.

## Storage, history and limits

SQLite version 3 retains prior history and adds exact manifests, peer fingerprints, source/destination URIs, approval state, per-file offsets/verification, export URIs, chunk hashes and update timestamps. History distinguishes Completed, Paused, Interrupted, Failed and Cancelled. Resume/Retry, Cancel and Delete history/partials operate on actual native ledger state. Deletion retains a minimal anti-replay UUID tombstone. General progress history is throttled separately from durable file checkpoints. Active source/checkpoint validation and destination finalization send liveness only as actual IO advances; idle timeouts remain bounded. Completed destination files survive history deletion. Cancelling a resume preflight preserves approved checkpoints instead of classifying cancellation as corruption.

One batch is active at a time; duplicate starts/resumes are rejected. There is no persisted pending multi-batch queue or multi-peer scheduler. This avoids silently executing a pending batch against a newly connected peer. A receiving device needs private staging space for the full batch, in addition to the destination space used during export. Storage-full and revoked-permission errors are failures, not successful transfers.

## Physical-device validation (pending)

No phones are attached to the coding environment. JVM TLS tests do not validate Android Keystore/Conscrypt compatibility, notifications, SAF providers, SQLite ownership or OEM power policy. A successful Android APK build and these checks remain release prerequisites:

1. Install on two phones; include API 24–32 and API 33+ coverage. Connect P2P and confirm the real group-owner role/address.
2. Compare/approve full fingerprints on both; test a deliberately wrong fingerprint and confirm no secure badge or file acceptance. Restore the intended approvals.
3. Transfer a known 100 MB file, then a 1 GB file where space permits; record actual average speed in both directions. Transfer multiple files with duplicate names and an empty file; independently compare SHA-256. Then pause near 40% from each side. Verify durable nonzero offsets, reopen authenticated sessions and resume; independently compare final SHA-256.
4. Disconnect Wi-Fi mid-transfer. Reconnect manually, authenticate and resume without restarting at zero.
5. Recreate the activity, background/screen-lock during transfer, use notification Pause/Cancel, and force-stop/relaunch. Verify truthful Interrupted state and validated recovery; do not assume an OEM kept the process alive.
6. Modify/delete a source, corrupt/delete a private partial in a test build, revoke a URI grant, fill storage, and inject invalid offsets/duplicate requests in a test client. Confirm rejection and no false completion.
7. Replay an old OFFER or change its manifest; confirm rejection. Cancel/delete history and verify the old approval cannot be reused. Test final-ACK loss and completed-receipt reconciliation.
8. Check destination collisions and interrupted exports, then cancellation cleanup. Measure actual encrypted throughput in both directions and with each buffer size; do not extrapolate host loopback rates to phones.

A release is not ready until an APK builds and the pending two-phone checks pass.


## Build and install a test APK

No release key is generated or replaced by these helpers. If a release key is available, put `storeFile`, `storePassword`, `keyAlias` and `keyPassword` in ignored `android/key.properties`; `storeFile` is resolved relative to `android`. Never commit that file or its keystore. `tool/build_apk.sh release` refuses missing signing configuration. With no release key, use a debug APK for testing.

```sh
./tool/build_apk.sh debug
# Or, only with your existing release keystore configured:
./tool/build_apk.sh release
```

The helper builds through Flutter, checks a nonempty APK, verifies its signature with Android `apksigner`, then prints its **actual** absolute artifact path. Only a successful verified build creates the named `flashlink-debug.apk` or `flashlink-release.apk` under `build/artifacts`. A script's existence is not evidence that either APK exists.

Connect exactly one authorized USB test phone with USB debugging enabled, run `./tool/install_apk.sh` for the verified debug artifact, then repeat with the other phone. The helper performs `adb -d install -r` and refuses a missing/empty APK. Pass a verified release artifact path as its first argument to test that build. On machines without tools in PATH, set FLUTTER, ANDROID_HOME, APKSIGNER or ADB to their installed paths as appropriate.

For manual installation, copy the actual APK produced by a successful build to each phone, allow installation from that chosen file manager when Android asks, and open the file. Do not use an unsigned release artifact or an invented download URL. Follow the physical-device checklist above: discover/connect, confirm both fingerprints, accept/reject, test 100 MB/1 GB/multiple files in both directions, verify hashes, pause/resume, disable/reconnect Wi-Fi, cancel, background/recreate/restart, and inspect actual history.

For Android ledger unit tests after Flutter dependencies are available, run `cd android && ./gradlew testDebugUnitTest`. Robolectric exercises the actual SQLite ledger code, including transaction rollback under an injected storage error. These are host platform tests, not evidence that two physical phones connect or that every Android 10–16/OEM power policy works.
