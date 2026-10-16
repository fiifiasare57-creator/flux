# FLUX for Android (v0.2, phone <-> PC)

Scan the QR code shown by FLUX on your PC, then send photos, videos and files to it. Also appears in Android's **Share** menu.
Needs Android 10+ (API 29) and the phone on the same Wi-Fi as the PC.

## Get the APK (pick one)
**A. GitHub builds it for you (no installs).** Push this repo to GitHub -> Actions tab -> "Build Android APK" -> Run workflow ->
download the `flux-android-debug-apk` artifact -> unzip -> `app-debug.apk`.
**B. Android Studio.** Open the `android/` folder, let it sync, then Build -> Build APK(s). APK: `app/build/outputs/apk/debug/app-debug.apk`.
Install: copy the APK to the phone, open it, allow "Install unknown apps" for your file manager/browser. (It is a debug-signed build, not a Play Store release.)

## If it closes or fails
The app records any crash and shows the error text the next time you open it (select and send it to the developer). If the camera scanner won't work, use "Or paste the pairing link": on the PC tap Copy link, send it to the phone (chat/email), copy it there, then tap the button.

## Use it
1. PC: FLUX -> Devices -> Pair a device -> "Show a code".  2. Phone: FLUX -> "Scan QR code to pair".
3. "Choose files to send" - or in Gallery/Files tap Share -> FLUX.  The PC must have FLUX open. Keep the phone app open while sending.

## What is and isn't verified
Verified (see jvmtest/run.sh): the protocol code in `app/src/main/java/app/flux/protocol` (no Android dependencies) was run on a desktop JVM
against the real Rust receiver over pinned TLS 1.3: QR-link pairing, impostor refusal, single-use link, 25 MB SHA-256 check, folders, duplicate names,
cancel, hostile filenames, unpaired-sender approval. The Android UI code compiles against the Android framework jar (API 23) with the QR library stubbed.
NOT verified: the Gradle build, running on any phone, the Android Keystore key used for TLS client authentication, the QR scanner library,
the Share menu, large files on phone storage, battery/background behaviour. Expect first-run fixes; send me the error text.

## Receiving from the PC
On the PC choose Send Files and pick your phone: the files are *queued*. While FLUX is open on the phone it looks for queued files every few seconds (backing off when the PC is unreachable and stopping when the app is closed). Tap Receive; every file is verified (size + SHA-256) before it is saved to Downloads/FLUX, and the PC marks it delivered only after that. If the connection drops it stays queued and you can tap Receive again. The PC's queue is in memory: it is lost if the PC app closes.

## Limits (v0.2)
No resume if a transfer is interrupted (it restarts from the beginning). Phone sending is files only, not folders. No background delivery or notifications.
Must stay in the foreground (no background service yet). One paired PC at a time. If the PC's IP address changes, scan its QR code again.
