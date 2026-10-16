# Security model (v0.1)
Assumptions: peers are on a network an attacker may share; a discovered device is untrusted until paired or approved by the user.
- Pairing: link = host fingerprint + addresses + 256-bit random token; token expires (default 120 s; CLI 5 min), single use, burned after 5 bad guesses, only a hash is held in memory. Link grants only "become a paired device", never file access.
- Pairing is MITM-resistant because the link's fingerprint is pinned in the TLS handshake. Pairing is NOT a replay target: tokens are consumed.
- Unknown senders must be approved per transfer; nothing is written before approval. Paired devices may be auto-accepted (setting `require_approval_for_trusted`).
- Peer-supplied names are untrusted: paired devices display the name stored at pairing; unpaired ones are flagged unverified.
- Paths: `..`, `.`, empty and absolute paths are rejected; `\ : * ? " < > |`, control chars, trailing dots/spaces, and reserved names (CON, NUL, COM1...) are neutralised; symlinked destination dirs refused; resolved path must stay under the receive folder; O_EXCL placeholders mean existing files are never overwritten.
- Limits: 2000 files/offer, 1 MiB control frames, 16 concurrent connections (extra are dropped), 4 pending approvals, handshake/idle/approval timeouts, disk-space check before accepting.
- Integrity: size + SHA-256 verified before a file appears; partial files live in `.flux-partial` and are never shown as received.
- Received files are never opened or executed by FLUX.
Not done / known gaps: private key not DPAPI-protected; no per-IP rate limiting (only global connection cap); no Windows Mark-of-the-Web on received files; no independent security review; browser companion certificate story is still to be designed.

## Outbox / phone additions (v0.2)
 * Only paired devices can check in or be queued for; revoking a device immediately locks it out. Unpaired requests get a generic error, never file names.
 * Claims are atomic and expire; locks are never held across file or network I/O.
 * Phone-side: a file is committed (made visible) only after exact size and SHA-256 match, and acknowledged only after that.
 * Known gaps: the outbox lives in memory (queued items are lost if the PC app closes); no per-device rate limit on check-ins (a paired device could poll rapidly); the phone's key is created with the NONE+SHA-256 digests Android's TLS stack needs and has not yet been confirmed on a physical device; no background delivery on Android; presence is in-memory and resets on restart.
