# FLUX protocol v1
Transport: TCP + TLS 1.3 only, ECDSA P-256 self-signed device certificates, **mutual** authentication.
Identity = SHA-256 of the certificate DER ("fingerprint"). Clients pin the server fingerprint (from pairing link / trust store);
servers accept any client cert at the TLS layer and apply trust policy in the application.
Frames: `kind u8 | len u32 BE | payload`; kind 1 = JSON control (<=1 MiB), kind 2 = file bytes (<=1 MiB, sent as 256 KiB chunks). Length is checked before allocation.
Flow — pair: Hello{purpose:pair, token} -> PairOk | Error. Transfer: Hello -> Offer{transfer_id, files[{index,path,size,mtime}]}
-> (validation, disk check, approval policy) -> Accept{resume[{index,offset,prefix_sha256}]} | Reject | Error
-> per file: FileStart{index,offset}, Data*, FileEnd{index,size,sha256} -> FileResult{ok} ... -> Complete.
Either side may send Cancel (discard partials) or Pause (keep partials). Resume: the receiver reports the length and SHA-256 of
its partial prefix; the sender resumes only if its own first `offset` bytes hash identically, otherwise restarts that file at 0.
The final SHA-256 always covers the whole file.

## Computer -> phone (outbox, `Purpose::Fetch`)
Phones don't listen for connections, so they *pull*. The PC queues files for a **paired** device; the phone checks in (authenticated, same pinned mutual TLS).
Check-in: Hello{purpose:"fetch"} -> the PC answers `Complete` (nothing queued) or `Offer{transfer_id, files}` for the first *unclaimed* item.
 * **Peek**: the phone replies `Reject` -> nothing changes (the item stays queued). Used for polling.
 * **Receive**: the phone replies `Accept` -> the PC atomically *claims* the item, then streams FileStart / data / FileEnd per file; the phone verifies size + SHA-256, commits the file, and sends `FileResult{index, ok}`; after the last ack the PC sends `Complete`.
 * **Decline**: `Cancel` instead of Accept/ack removes the item (recorded as cancelled).
Item states: Queued -> Claimed (id + deadline, refreshed while data flows) -> Delivered (removed, only after every file was acknowledged by the claim's owner) | Cancelled. A claim that is not refreshed within `claim_ttl` (default 60 s) returns to Queued; a stale connection can no longer complete it. Failures release the claim immediately, so retries are safe and idempotent. Acks must carry the expected file index (replays are rejected).
Presence: a paired device is "online" only if it authenticated within `presence_timeout` (default 15 s). Unauthenticated peers get an `Error` and learn nothing about the queue.

## Short-code pairing (`Purpose::PairCode`)
The QR link carries the PC's fingerprint, so the scanner can pin it. A typed 9-character code (alphabet of 31 unambiguous characters, ~44.6 bits) cannot, so it is used as a shared secret and bound to both identities:
client -> Hello{purpose:"pair_code"}, then PairCode{proof = HMAC-SHA256(code, "flux-pair-v1|client|<pc_fp>|<client_fp>")}; the PC verifies in constant time and answers PairOk{proof = HMAC(code, "flux-pair-v1|server|<pc_fp>|<client_fp>")}; the client verifies that, and only then trusts <pc_fp>.
A man in the middle presents a different certificate, so he can neither produce nor reuse either proof. The code is single use, expires (default 2 min), and is burned after 5 failed attempts. Caveat: an active attacker on the LAN who is tried with the code learns one HMAC and could brute-force the 44.6-bit space offline, but only after the code has expired or been used, which makes the result worthless.

## Share links (HTTP, separate listener)
`http://<ip>:<port>/<9-char token>` lists the shared files; `/<token>/<n>` downloads file n (Range supported). No path handling exists: files are addressed by index only. Tokens are 9 characters from a 55-symbol alphabet (~52 bits). Links expire (max 7 days), can be limited to N completed downloads (reserved while in flight), and can be revoked. The listener exists only while a link is active. Every miss returns the same 404; 15 bad requests in a minute block an address for a minute. Downloads are forced to attachment + nosniff. Plain HTTP on the local network: not encrypted.
