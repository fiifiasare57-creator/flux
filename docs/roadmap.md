# Roadmap (merged from the product spec and the upgrade proposals)
Vision: FLUX — Your files, in motion. Pillars: Share -> Flow -> Vault (Anywhere later).
- M1 DONE: core engine, pairing, resume/retry, CLI.
- M2 WRITTEN (needs first Windows run): Tauri + React UI matching design/ (Share, Devices, Activity, Settings, Quick Send, queue with pause/cancel, QR pairing, receiver approval dialog, notifications).
- M3: Windows hardening (DPAPI key, firewall guidance, installer), multi-transfer queue, receiver-side cancel, bandwidth limit, skip-identical-file detection, transfer reports.
- M4: Phone via browser (needs a researched HTTPS/certificate approach for mobile browsers; honest limits for iOS/Android).
- M5: FLUX Flow: saved destinations, presets, folder organisation + naming rules, collections (manifest = existing Offer; add preset layer).
- M6: Vault: opt-in local index of chosen folders per device, search across paired devices, duplicates; Quick actions: Explorer "Send with FLUX", opt-in clipboard/link sharing.
- Later: Anywhere (NAT traversal + optional relay; has hosting cost and needs its own security design); native Android/macOS; monetisation only after user research.
Large-file checklist: streaming DONE, folders DONE, integrity DONE, smart retry/resume DONE, queue (engine events DONE, UI pending), bandwidth controls TODO, duplicate detection TODO, transfer reports TODO.
