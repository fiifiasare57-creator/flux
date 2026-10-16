//! Computer -> phone delivery without the phone running a server.
//!
//! Queue item lifecycle:   Queued --claim--> Claimed --verified by the phone--> Delivered (removed)
//!                          ^                   |  \--phone/PC cancels--> Cancelled (removed)
//!                          '---release/expiry--'
//!  * A *peek* (the phone looks, then sends Reject) never changes state.
//!  * Claiming is atomic under one short lock, so two concurrent requests can never both claim an item.
//!  * A claim carries an id and a deadline that is refreshed while data flows. If the phone vanishes the claim
//!    expires and the item silently returns to Queued; a late/stale delivery attempt cannot complete it
//!    (its claim id no longer matches), which makes retries idempotent.
//!  * An item is Delivered only after the phone verified every file's SHA-256 and acknowledged it.
//!  * Locks are held only for state changes, never across file or network I/O.
//!  * Only paired devices can use any of this; unauthenticated peers learn nothing, not even file names.

use crate::error::{FluxError, Result};
use crate::events::{TransferProgress, TransferState};
use crate::node::Node;
use crate::protocol::*;
use crate::receive::Peer;
use crate::send::{mtime_of, new_transfer_id, SourceFile};
use crate::store::{unix_now, Direction, HistoryEntry, Outcome};
use serde::Serialize;
use sha2::{Digest, Sha256};
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite};
use tokio_util::sync::CancellationToken;

pub(crate) enum ItemState {
    Queued,
    Claimed { claim: u64, expires: Instant, token: CancellationToken, user: Arc<std::sync::atomic::AtomicBool> },
}

pub(crate) struct OutItem {
    pub transfer_id: String,
    pub files: Vec<SourceFile>,
    pub total: u64,
    pub created: u64,
    pub state: ItemState,
}

#[derive(Serialize, Clone, Copy, Debug, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum QueueState {
    Queued,
    Claimed,
    /// Terminal states are not kept in the live queue; they appear in history.
    Delivered,
    Cancelled,
}

#[derive(Serialize, Clone, Debug)]
pub struct QueuedInfo {
    pub transfer_id: String,
    pub peer_fingerprint: String,
    pub peer_name: String,
    pub file_count: u32,
    pub total_bytes: u64,
    pub names: Vec<String>,
    pub created: u64,
    pub state: QueueState,
}

pub(crate) struct Claim {
    pub id: u64,
    pub token: CancellationToken,
    /// Set only when the computer's user cancelled (as opposed to an expired claim).
    pub user: Arc<std::sync::atomic::AtomicBool>,
}

impl Node {
    /// Queue files for a paired device to pick up. Returns the transfer id.
    pub fn queue_for_peer(self: &Arc<Self>, fp: &str, files: Vec<SourceFile>) -> Result<String> {
        if self.store.get_trusted(fp).is_none() {
            return Err(FluxError::Pairing("pair with that device first".into()));
        }
        if files.is_empty() {
            return Err(FluxError::InvalidPath("nothing to send".into()));
        }
        let id = new_transfer_id();
        let total = files.iter().map(|f| f.size).sum();
        let item = OutItem { transfer_id: id.clone(), files, total, created: unix_now(), state: ItemState::Queued };
        self.outbox.lock().unwrap().entry(fp.to_string()).or_default().push(item);
        Ok(id)
    }

    /// Return expired claims to the queue and stop their (stale) streams.
    fn reap_expired(&self) {
        let now = Instant::now();
        let mut m = self.outbox.lock().unwrap();
        for it in m.values_mut().flatten() {
            if let ItemState::Claimed { expires, token, .. } = &it.state {
                if *expires <= now {
                    token.cancel();
                    it.state = ItemState::Queued;
                }
            }
        }
    }

    /// First queued (unclaimed) item for a device. Read-only: never changes state.
    fn peek_first(&self, fp: &str) -> Option<(String, Vec<SourceFile>, u64)> {
        self.reap_expired();
        let m = self.outbox.lock().unwrap();
        m.get(fp)?.iter().find(|i| matches!(i.state, ItemState::Queued)).map(|i| (i.transfer_id.clone(), i.files.clone(), i.total))
    }

    /// Atomically claim a queued item. Exactly one concurrent caller can succeed.
    fn try_claim(&self, fp: &str, tid: &str) -> Option<Claim> {
        let mut m = self.outbox.lock().unwrap();
        let it = m.get_mut(fp)?.iter_mut().find(|i| i.transfer_id == tid)?;
        if !matches!(it.state, ItemState::Queued) {
            return None;
        }
        let id = self.claim_seq.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
        let (token, user) = (CancellationToken::new(), Arc::new(std::sync::atomic::AtomicBool::new(false)));
        it.state = ItemState::Claimed { claim: id, expires: Instant::now() + self.limits.claim_ttl, token: token.clone(), user: user.clone() };
        Some(Claim { id, token, user })
    }

    /// Extend a live claim. False means the claim is gone (expired, released, cancelled): stop sending.
    fn refresh_claim(&self, fp: &str, tid: &str, claim: u64) -> bool {
        let mut m = self.outbox.lock().unwrap();
        let Some(it) = m.get_mut(fp).and_then(|v| v.iter_mut().find(|i| i.transfer_id == tid)) else { return false };
        match &mut it.state {
            ItemState::Claimed { claim: c, expires, .. } if *c == claim => {
                *expires = Instant::now() + self.limits.claim_ttl;
                true
            }
            _ => false,
        }
    }

    /// Give a claimed item back to the queue (failure / disconnect). No effect if the claim was already lost.
    fn release_claim(&self, fp: &str, tid: &str, claim: u64) {
        let mut m = self.outbox.lock().unwrap();
        if let Some(it) = m.get_mut(fp).and_then(|v| v.iter_mut().find(|i| i.transfer_id == tid)) {
            if matches!(&it.state, ItemState::Claimed { claim: c, .. } if *c == claim) {
                it.state = ItemState::Queued;
            }
        }
    }

    /// Remove the item iff `claim` still owns it. True only for the legitimate (single) completer.
    fn finish_claim(&self, fp: &str, tid: &str, claim: u64) -> bool {
        let mut m = self.outbox.lock().unwrap();
        let Some(v) = m.get_mut(fp) else { return false };
        match v.iter().position(|i| i.transfer_id == tid && matches!(&i.state, ItemState::Claimed { claim: c, .. } if *c == claim)) {
            Some(pos) => {
                v.remove(pos);
                true
            }
            None => false,
        }
    }

    /// Remove an item only if it is still unclaimed (the phone declined it before a claim existed).
    fn remove_if_queued(&self, fp: &str, tid: &str) -> bool {
        let mut m = self.outbox.lock().unwrap();
        let Some(v) = m.get_mut(fp) else { return false };
        match v.iter().position(|i| i.transfer_id == tid && matches!(i.state, ItemState::Queued)) {
            Some(pos) => {
                v.remove(pos);
                true
            }
            None => false,
        }
    }

    /// Cancel from this computer: removes the item and stops any in-flight delivery.
    pub fn cancel_queued(&self, transfer_id: &str) -> bool {
        let found = {
            let mut m = self.outbox.lock().unwrap();
            let mut found = None;
            for (fp, v) in m.iter_mut() {
                if let Some(pos) = v.iter().position(|i| i.transfer_id == transfer_id) {
                    found = Some((fp.clone(), v.remove(pos)));
                    break;
                }
            }
            found
        };
        let Some((fp, it)) = found else { return false };
        if let ItemState::Claimed { token, user, .. } = &it.state {
            user.store(true, std::sync::atomic::Ordering::SeqCst);
            token.cancel();
        }
        let name = self.store.get_trusted(&fp).map(|t| t.name).unwrap_or_else(|| "device".into());
        record(self, &fp, &name, &it.transfer_id, &it.files, it.total, Outcome::Cancelled, Some("cancelled on this computer".into()));
        true
    }

    pub fn queued(&self) -> Vec<QueuedInfo> {
        self.reap_expired();
        let m = self.outbox.lock().unwrap();
        let mut out = Vec::new();
        for (fp, v) in m.iter() {
            let name = self.store.get_trusted(fp).map(|t| t.name).unwrap_or_else(|| "device".into());
            for i in v {
                out.push(QueuedInfo {
                    transfer_id: i.transfer_id.clone(),
                    peer_fingerprint: fp.clone(),
                    peer_name: name.clone(),
                    file_count: i.files.len() as u32,
                    total_bytes: i.total,
                    names: i.files.iter().take(20).map(|f| f.rel.clone()).collect(),
                    created: i.created,
                    state: if matches!(i.state, ItemState::Queued) { QueueState::Queued } else { QueueState::Claimed },
                });
            }
        }
        out.sort_by_key(|q| q.created);
        out
    }
}

#[allow(clippy::too_many_arguments)]
fn record(node: &Node, fp: &str, name: &str, tid: &str, files: &[SourceFile], total: u64, outcome: Outcome, error: Option<String>) {
    let _ = node.store.add_history(HistoryEntry {
        id: tid.to_string(),
        timestamp: unix_now(),
        direction: Direction::Sent,
        peer_name: name.to_string(),
        peer_fingerprint: fp.to_string(),
        files: files.iter().map(|f| f.rel.clone()).collect(),
        file_count: files.len() as u32,
        total_bytes: total,
        outcome,
        error,
    });
}

pub(crate) async fn serve_fetch<S>(node: &Arc<Node>, s: &mut S, peer: &Peer)
where
    S: AsyncRead + AsyncWrite + Unpin + Send,
{
    if let Err(e) = fetch_inner(node, s, peer).await {
        if !matches!(e, FluxError::Cancelled | FluxError::RemoteCancelled | FluxError::Paused | FluxError::ClaimLost) {
            let _ = write_msg(s, &Msg::Error { message: e.peer_message() }).await;
        }
    }
}

async fn fetch_inner<S>(node: &Arc<Node>, s: &mut S, peer: &Peer) -> Result<()>
where
    S: AsyncRead + AsyncWrite + Unpin + Send,
{
    if !peer.trusted {
        // Refuse before revealing anything about the queue.
        return Err(FluxError::Pairing("this device is not paired with the computer".into()));
    }
    let Some((tid, files, total)) = node.peek_first(&peer.fp) else {
        return write_msg(s, &Msg::Complete).await; // nothing waiting
    };
    let metas = files.iter().enumerate().map(|(i, f)| FileMeta { index: i as u32, path: f.rel.clone(), size: f.size, mtime: f.mtime }).collect();
    write_msg(s, &Msg::Offer { transfer_id: tid.clone(), files: metas }).await?;
    match read_msg(s, node.limits.idle_timeout).await? {
        Msg::Accept { .. } => {}
        Msg::Cancel => {
            if node.remove_if_queued(&peer.fp, &tid) {
                record(node, &peer.fp, &peer.name, &tid, &files, total, Outcome::Cancelled, Some("declined on the phone".into()));
            }
            return Ok(());
        }
        _ => return Ok(()), // Reject = peek / not now: nothing changes
    }
    let Some(claim) = node.try_claim(&peer.fp, &tid) else {
        return Err(FluxError::Protocol("already being delivered or no longer available".into()));
    };
    let res = stream_files(node, s, peer, &tid, &files, total, &claim).await;
    let (state, err) = match &res {
        Ok(()) => {
            if node.finish_claim(&peer.fp, &tid, claim.id) {
                record(node, &peer.fp, &peer.name, &tid, &files, total, Outcome::Completed, None);
                (TransferState::Completed, None)
            } else {
                // Our claim had expired and another delivery owns the item: report nothing as delivered twice.
                (TransferState::Failed, Some("delivery claim expired".to_string()))
            }
        }
        Err(FluxError::Cancelled) => {
            if claim.user.load(std::sync::atomic::Ordering::SeqCst) {
                (TransferState::Cancelled, Some("cancelled on this computer".to_string())) // history written by cancel_queued
            } else {
                (TransferState::Failed, Some("delivery timed out; the item is back in the queue".to_string())) // reaped
            }
        }
        Err(FluxError::RemoteCancelled) => {
            if node.finish_claim(&peer.fp, &tid, claim.id) {
                record(node, &peer.fp, &peer.name, &tid, &files, total, Outcome::Cancelled, Some("declined on the phone".into()));
            }
            (TransferState::Cancelled, Some("declined on the phone".to_string()))
        }
        Err(e) => {
            node.release_claim(&peer.fp, &tid, claim.id); // back to Queued: the phone can retry
            record(node, &peer.fp, &peer.name, &tid, &files, total, Outcome::Failed, Some(e.to_string()));
            (TransferState::Failed, Some(e.to_string()))
        }
    };
    node.emit(TransferProgress {
        transfer_id: tid.clone(), direction: Direction::Sent, peer_name: peer.name.clone(), state, file_name: String::new(),
        files_done: if matches!(state, TransferState::Completed) { files.len() as u32 } else { 0 }, files_total: files.len() as u32,
        bytes_done: if matches!(state, TransferState::Completed) { total } else { 0 }, bytes_total: total, error: err,
    });
    res
}

async fn stream_files<S>(node: &Arc<Node>, s: &mut S, peer: &Peer, tid: &str, files: &[SourceFile], total: u64, claim: &Claim) -> Result<()>
where
    S: AsyncRead + AsyncWrite + Unpin + Send,
{
    let idle = node.limits.idle_timeout;
    let mut buf = vec![0u8; CHUNK_SIZE];
    let (mut done, mut files_done) = (0u64, 0u32);
    let mut last = Instant::now() - Duration::from_secs(1);
    let emit = |state, file: &str, done, files_done| {
        node.emit(TransferProgress { transfer_id: tid.to_string(), direction: Direction::Sent, peer_name: peer.name.clone(), state, file_name: file.to_string(),
            files_done, files_total: files.len() as u32, bytes_done: done, bytes_total: total, error: None });
    };
    for (i, f) in files.iter().enumerate() {
        if claim.token.is_cancelled() {
            let _ = write_msg(s, &Msg::Cancel).await;
            return Err(FluxError::Cancelled);
        }
        let mut file = tokio::fs::File::open(&f.abs).await?;
        let md = file.metadata().await?;
        if md.len() != f.size || mtime_of(&md) != f.mtime {
            return Err(FluxError::SourceChanged(f.rel.clone()));
        }
        write_msg(s, &Msg::FileStart { index: i as u32, offset: 0 }).await?;
        let (mut hasher, mut sent) = (Sha256::new(), 0u64);
        while sent < f.size {
            let want = (f.size - sent).min(CHUNK_SIZE as u64) as usize;
            let n = file.read(&mut buf[..want]).await?;
            if n == 0 {
                return Err(FluxError::SourceChanged(format!("{} became shorter", f.rel)));
            }
            hasher.update(&buf[..n]);
            // A stalled peer must not hold the claim (or this task) forever: stop as soon as the claim is cancelled.
            tokio::select! {
                _ = claim.token.cancelled() => return Err(FluxError::Cancelled),
                r = write_data(s, &buf[..n]) => r?,
            }
            sent += n as u64;
            done += n as u64;
            if last.elapsed() >= Duration::from_millis(100) {
                last = Instant::now();
                if !node.refresh_claim(&peer.fp, tid, claim.id) {
                    return Err(FluxError::ClaimLost);
                }
                emit(TransferState::Active, &f.rel, done, files_done);
            }
        }
        let md2 = tokio::fs::metadata(&f.abs).await?;
        if md2.len() != f.size || mtime_of(&md2) != f.mtime {
            let _ = write_msg(s, &Msg::Cancel).await;
            return Err(FluxError::SourceChanged(f.rel.clone()));
        }
        write_msg(s, &Msg::FileEnd { index: i as u32, size: f.size, sha256: hex::encode(hasher.finalize()) }).await?;
        let ack = tokio::select! {
            _ = claim.token.cancelled() => return Err(FluxError::Cancelled),
            r = read_msg(s, idle) => r?,
        };
        match ack {
            // The phone verified size + SHA-256 before saying ok. The index must match: duplicates/replays are rejected.
            Msg::FileResult { index, ok: true, .. } if index == i as u32 => files_done += 1,
            Msg::FileResult { ok: false, error, .. } => return Err(FluxError::Remote(error.unwrap_or_else(|| "the phone rejected a file".into()))),
            Msg::Cancel => return Err(FluxError::RemoteCancelled),
            _ => return Err(FluxError::Protocol("unexpected or duplicate acknowledgement".into())),
        }
        if !node.refresh_claim(&peer.fp, tid, claim.id) {
            return Err(FluxError::ClaimLost);
        }
        emit(TransferState::Active, &f.rel, done, files_done);
    }
    write_msg(s, &Msg::Complete).await
}
