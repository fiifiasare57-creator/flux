# FLUX — your files, in motion

Secure, local-first file transfer between your devices. **Status: Milestone 2 (desktop app written; engine tested; app NOT yet run on Windows).**

## What exists and is tested
- `crates/flux-core`: device identity, QR/link pairing, pinned mutual-TLS 1.3, streaming transfer engine
  (SHA-256 verified, pause/cancel, verified resume, auto-retry), safe path handling, trusted-device store, history, mDNS discovery.
- `crates/flux-cli`: `flux` command to test everything between two machines today.

## Android app (phone <-> PC)
`android/` is a native Android app that scans the PC's pairing QR code, sends files to the PC, and receives files the PC queued for it (the phone checks in while the app is open; nothing is delivered in the background). See `android/README.md` to get the APK (GitHub Actions or Android Studio). Not yet run on a phone.

## Website
`website/index.html` is the marketing page (see `website/README.md`). Set `DOWNLOAD_URL` once you have an installer.

## Desktop app (Tauri + React) — how to run it on Windows
Extra prerequisite: Node.js 20+ (https://nodejs.org). WebView2 ships with Windows 11 (installer: https://developer.microsoft.com/microsoft-edge/webview2/).
```powershell
npm install
npm run tauri dev      # runs the app with hot reload
npm run tauri build    # produces an NSIS installer under target\release\bundle\nsis
```
Screens: Share (drop zone, nearby devices, Quick Send, transfer queue with pause/resume/cancel, recent activity),
Devices (pair with QR/link, unpair, troubleshooting), Activity (live + history with details), Settings.
The UI shows only real data from the engine: no devices means an empty state, no transfers means an empty queue.
**What was verified for the app:** the Rust shell compiles (`cargo check -p flux-app`, 0 warnings), the UI type-checks and builds,
and the UI renders in headless Chromium against a mocked IPC (`tools/screenshot.py`, sample data used only there).
**What was NOT verified:** running inside Tauri/WebView2, real IPC calls, drag-and-drop from Explorer, the native file dialogs,
the installer. Expect to fix small integration issues on the first `npm run tauri dev`; please send me the console output.

## Prerequisites (Windows)
1. Rust (stable, 1.85+): https://rustup.rs  — choose the MSVC toolchain.
2. Visual Studio Build Tools ("Desktop development with C++") — rustup will tell you if missing.
No Docker, no cloud, no paid APIs.

## Build and test (PowerShell, in the repo folder)
```powershell
cargo test            # 11 unit + 17 integration/security tests
cargo build --release # produces target\release\flux.exe
```

## Try it between two Windows PCs (the real acceptance test)
PC A (receiver):
```powershell
.\flux.exe receive --dir "$HOME\Downloads\FLUX" --name "PC-A"
```
It prints a one-time pairing link (valid 5 min). Windows will ask to allow it through the firewall: allow **Private** networks.
PC B (sender):
```powershell
.\flux.exe pair "flux://pair?...paste the link..."
.\flux.exe send --to PC-A C:\Videos\big.mp4 C:\Docs\Biology
```
Ctrl+C while sending **pauses** (partial data is kept); re-run with the printed `--transfer-id` to resume.
Unpaired senders (`--to host:port --fp <fingerprint>`) trigger an Accept/Reject prompt on the receiver.
Other commands: `discover`, `devices`, `revoke <fingerprint>`, `history`, `id`.

## Known limitations (read these)
- **Not yet run on Windows or on two physical machines.** Everything was verified on Linux/loopback (see docs/testing.md).
- No phone/browser companion, no Flow/Vault/clipboard yet (see docs/roadmap.md). FLUX Flow appears in the UI as "Coming soon".
- UI gaps: native window frame (no custom title bar), no dark theme, no notifications or start-with-Windows option (not implemented, so not shown),
  the receiving side cannot pause or cancel an incoming transfer, renaming the device or toggling visibility applies after restart.
- QR codes are shown for pairing, but there is no QR scanner yet: PC-to-PC pairing is by pasting the link.
- Identity private key is a file in the app-data folder (not yet DPAPI-protected).
- Discovery is mDNS only: it can fail on guest Wi-Fi/client isolation, VPNs or strict firewalls; use the pairing link then.
- Receiver cannot cancel an in-flight transfer yet (UI milestone); stale partials auto-delete after 7 days.
- No bandwidth limiting, duplicate-skip or transfer reports yet.
