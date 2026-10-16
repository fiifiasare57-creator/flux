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
