# Testing
Automated (`cargo test`): 11 unit tests (paths, pairing) + 17 integration tests using real sockets and TLS on loopback:
pairing (single-use, expiry, replay, wrong token, MITM fingerprint), pinned-fingerprint refusal, approval bypass attempts, revocation,
transfer of files+folders+empty file with SHA-256 comparison, duplicate names, traversal/Windows-path attacks (raw hostile client),
corrupt hash, oversized data, abusive offers (file count, absurd size, overflow, bad id, bad indices, 4 GiB frame header),
cancel (no leftovers), pause + resume from verified offset, automatic recovery from a severed connection (TCP proxy), source-changed detection, persistence.
Manual, run on Linux in the sandbox: two separate `flux` processes with separate identities: pair, send 1 GiB + folder, hashes match; peak RSS ~7 MB sender and receiver. mDNS found the advertised device (same host).
NOT yet verified: Windows build/run, two physical PCs, real Wi-Fi/firewall behaviour, Android/browser, throughput on real networks, disk-full mid-transfer, app crash mid-transfer on the receiver, long soak tests, concurrent transfers under load.

## Milestone 2 (desktop app)
Verified: `cargo check -p flux-app` (Rust shell, 0 warnings); `npm run build` (TypeScript strict + Vite); headless-Chromium render of all four screens
and the approval dialog with mocked IPC + sample data (tools/screenshot.py), focus stays trapped in the dialog, no console errors.
NOT verified: any run inside Tauri/WebView2, real IPC round trips, drag-and-drop, native dialogs, packaging, behaviour on Windows, accessibility with a real screen reader.

## Milestone 3 (receiver cancel, Android client)
Rust: 11 unit + 19 integration tests pass. New: receiver cancels an incoming transfer (sender is told, no retry, nothing left behind); transfer resumes after the receiving app is restarted (new Node, new port, same data); cancel leaves no empty partial folder (this was a real bug found by a tighter assertion).
Android: `android/jvmtest/run.sh` runs the Java protocol client (the same code the phone app uses) against the real Rust receiver: 12/12 pass. The Android UI compiles against the Android framework jar (API 23) with the QR library stubbed.
NOT verified: Gradle/APK build, any real phone, Android Keystore TLS client auth, QR scanning, Share menu, background behaviour, PC-to-phone (not implemented).

## Milestone 4 (bidirectional transfer, hardening, device visuals)
Automated, actually run: Rust 11 unit + 29 integration tests, including the outbox matrix: empty queue, non-consuming peek (x3), authorized fetch with SHA-256 verification, unauthorized/revoked fetch (no name leak), concurrent claims (exactly one wins), hash mismatch -> not delivered -> idempotent retry, PC-side cancel mid-transfer (stream stops), phone declines before/after claiming, expired claim -> back in queue and a stale delivery cannot double-complete, network drop -> requeued -> delivered on retry, duplicate/replayed acknowledgement rejected, presence timeout and 20 overlapping check-ins.
Java client vs the real Rust PC (android/jvmtest/run.sh): 20/20, both directions (JVM, not a phone).
`cargo check -p flux-app` ok; `npm run build` ok; Android code compiles against the Android framework jar (API 23) with the QR library stubbed; Devices page and pairing dialog rendered in headless Chromium at 1280x720, 1366x768 and 1920x1080 with mocked IPC: no overflow, images load, no console errors.
NOT verified (needs hardware): the Gradle/APK build, a physical phone, AndroidKeyStore TLS client key with the new digests, the QR scanner, MediaStore saving, the Share menu, polling/lifecycle behaviour with real Wi-Fi changes, Windows (the app has been confirmed running by the user on their PC; the new commands have not).

## Milestone 5 (redesign, short codes, share links, Android hardening)
Run and passing: Rust 13 unit + 37 integration (new: short-code pairing both ways, single use, wrong/malformed/expired codes and burn-after-5, impostor server that fakes a proof, junk/dead candidates skipped; share links: page, byte-identical download, ranges, HEAD, 405, HTML escaping, traversal/odd-path misses all identical 404, expiry, revoke, download limit with parallel requests, listener closes when idle, rate limiting, changed file -> 410). Java client vs real Rust PC: 25/25 (software identity with a hand-built X.509 certificate accepted by the Rust server, link and typed-code pairing, both transfer directions, cancel, hostile names). cargo check (Tauri), strict TypeScript + Vite build, Android compile against the framework jar.
Redesign rendered in headless Chromium (mocked IPC, sample data) at 1280x720, 1366x768, 1440x900, 1920x1080: no horizontal overflow, all images load, focus stays inside dialogs, no console errors; Home, Files, Transfers, Devices, Settings and the Send / Share / Receive / Pair dialogs captured.
NOT verified: running inside Tauri/WebView2 with the new commands, any physical phone, the Android Gradle build, the Wi-Fi scan used by code pairing, MediaStore saving, the camera scanner, Windows firewall behaviour for the share-link port (47801).

## Milestone 6 (phone redesign, new logo, workflows)
Run and passing: Rust 13 unit + 37 integration (unchanged); Java client vs real Rust PC 25/25 (peek now returns per-file sizes); strict TypeScript + Vite build; cargo check (Tauri); desktop redesign re-rendered at 1280/1366/1440/1920 with the new logo (no overflow, images load, no console errors). Android: the whole app compiles against the Android 34 framework (QR library stubbed); all 25 resource XML files are well-formed; both workflow files are valid YAML.
Code review found and fixed before release: shared-file launch could leave the app on the splash screen; the manual-code box forced capitals (would corrupt a pasted link); the Activity restarted on dark-mode/font-size changes (would lose a transfer screen).
NOT verified: any of the phone screens visually or on a device; the Gradle/APK build in GitHub Actions; the Windows installer build in Actions (paths corrected for this workspace but not run); thumbnails; the Android file picker flow; saving into Downloads/FLUX.

## Milestone 7 (phone typography overhaul, driven by on-device screenshots)
Found on a real phone (screenshots): headings left-aligned while descriptions centred, bottom-nav labels off-centre, a stale "files are waiting" banner contradicting a "not reachable" card, and "Transfer failed (bad address)". Causes and fixes: text views without explicit layout params (now all text goes through Ui.text); fixed-height nav bar and buttons (now wrap_content + minimum height); banners that never expired (now clear after 8s, and pending files are only shown while the PC is reachable); and an address-list rebuild using String.replace that could leave an empty entry (now parsed, validated and de-duplicated).
Audit before: 18 distinct text sizes, 26 distinct dp values, 34 text views without explicit params. After: 8 text sizes, one spacing scale, all text via Ui.text.
Run and passing: android/tools/design_lint.py (24 rules), compile against the Android 34 framework, Java protocol client vs real Rust PC (25/25), YAML validity.
NOT verified: the redesigned screens on a physical phone, behaviour at large system font sizes and on 320dp screens (designed for, checked only by rule), landscape.
