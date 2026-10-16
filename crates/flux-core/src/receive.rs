//! Receiving side of the transfer engine.
//!
//! Safety properties:
//!  * Nothing is written before the offer is validated and (unless the sender is trusted and the
//!    user allows it) approved.
//!  * Data goes to a partial file under `<receive dir>/.flux-partial/`; only after the whole file's
//!    size and SHA-256 match is it renamed into place over an O_EXCL placeholder, so a partial or
//!    corrupt file is never presented as complete and an existing file is never overwritten.
//!  * Partial data survives disconnects/pauses for resume, and is deleted on cancel or corruption.
//!    Resume offsets are derived from files on *our* disk; the sender must prove its own bytes match
//!    our prefix hash before any resumed byte is accepted.

use crate::error::{FluxError, Result};
use crate::events::{TransferProgress, TransferState};
use crate::node::{Decision, IncomingRequest, Node};
use crate::paths::{self, SafeRelPath};
use crate::protocol::*;
use crate::store::{unix_now, Direction, HistoryEntry, Outcome};
use sha2::{Digest, Sha256};
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncSeekExt, AsyncWrite, AsyncWriteExt};

pub(crate) struct Peer {
    pub fp: String,
    pub name: String,
    pub trusted: bool,
}

#[derive(Default)]
struct Session {
    transfer_id: String,
    names: Vec<String>,
    total: u64,
    files_done: u32,
    bytes_done: u64,
    part_paths: Vec<PathBuf>,
    user_cancel: Option<std::sync::Arc<std::sync::atomic::AtomicBool>>,
}

const PARTIAL_DIR: &str = ".flux-partial";

fn part_key(peer_fp: &str, tid: &str, path: &str, size: u64, mtime: u64) -> String {
    let h = Sha256::digest(format!("{peer_fp}|{tid}|{path}|{size}|{mtime}").as_bytes());
    hex::encode(&h[..16])
}

/// Hash the first `len` bytes of a file, returning the running hasher (to continue from).
pub(crate) async fn hash_prefix(path: &Path, len: u64) -> Result<Sha256> {
    let mut f = tokio::fs::File::open(path).await?;
    let mut h = Sha256::new();
    let mut buf = vec![0u8; CHUNK_SIZE];
    let mut left = len;
    while left > 0 {
        let want = left.min(buf.len() as u64) as usize;
        let n = f.read(&mut buf[..want]).await?;
        if n == 0 {
            return Err(FluxError::Io(std::io::ErrorKind::UnexpectedEof.into()));
        }
        h.update(&buf[..n]);
        left -= n as u64;
    }
    Ok(h)
}

pub(crate) fn cleanup_stale(node: &Arc<Node>) {
    let dir = node.store.settings().receive_dir.join(PARTIAL_DIR);
    let Ok(rd) = fs::read_dir(&dir) else { return };
    for e in rd.flatten() {
        let old = e.metadata().and_then(|m| m.modified()).ok()
            .and_then(|t| t.elapsed().ok())
            .is_some_and(|age| age > node.limits.partial_max_age);
        if old {
            let _ = fs::remove_file(e.path());
        }
    }
}

/// Read a frame, but stop immediately if the local user (or a newer connection) cancels this transfer.
async fn rf<S: AsyncRead + Unpin>(s: &mut S, idle: Duration, tok: &tokio_util::sync::CancellationToken) -> Result<Frame> {
    tokio::select! {
        _ = tok.cancelled() => Err(FluxError::LocalCancelled),
        r = read_frame(s, idle) => r,
    }
}

fn emit(node: &Node, peer: &Peer, s: &Session, state: TransferState, file: &str, err: Option<String>) {
    node.emit(TransferProgress {
        transfer_id: s.transfer_id.clone(),
        direction: Direction::Received,
        peer_name: peer.name.clone(),
        state,
        file_name: file.to_string(),
        files_done: s.files_done,
        files_total: s.names.len() as u32,
        bytes_done: s.bytes_done,
        bytes_total: s.total,
        error: err,
    });
}

pub(crate) async fn serve<S>(node: &Arc<Node>, s: &mut S, peer: &Peer)
where
    S: AsyncRead + AsyncWrite + Unpin + Send,
{
    let mut sess = Session::default();
    let res = serve_inner(node, s, peer, &mut sess).await;

    if let Err(e) = &res {
        let msg = match e {
            FluxError::Rejected(r) => Msg::Reject { reason: r.clone() },
            _ => Msg::Error { message: e.peer_message() },
        };
        if matches!(e, FluxError::Cancelled) {
            discard_all(&sess.part_paths); // sender cancelled: forget every partial of this transfer and tidy the folder
        }
        let by_user = matches!(e, FluxError::LocalCancelled) && sess.user_cancel.as_ref().is_some_and(|u| u.load(std::sync::atomic::Ordering::SeqCst));
        if by_user {
            let _ = write_msg(s, &Msg::Cancel).await; // tell the sender, then forget the partial data
            discard_all(&sess.part_paths);
        } else if !matches!(e, FluxError::Cancelled | FluxError::Paused | FluxError::LocalCancelled) {
            let _ = write_msg(s, &msg).await;
        }
    }
    if sess.transfer_id.is_empty() {
        return; // never saw a valid offer: nothing to record
    }
    let (outcome, state, err) = match &res {
        Ok(()) => (Outcome::Completed, TransferState::Completed, None),
        Err(FluxError::Cancelled) => (Outcome::Cancelled, TransferState::Cancelled, Some("cancelled by sender".to_string())),
        Err(FluxError::LocalCancelled) if sess.user_cancel.as_ref().is_some_and(|u| u.load(std::sync::atomic::Ordering::SeqCst)) => (Outcome::Cancelled, TransferState::Cancelled, Some("cancelled on this device".to_string())),
        Err(FluxError::LocalCancelled) => (Outcome::Failed, TransferState::Failed, Some("connection replaced by a newer one from the same device".to_string())),
        Err(FluxError::Rejected(r)) => (Outcome::Rejected, TransferState::Rejected, Some(r.clone())),
        Err(FluxError::Paused) => (Outcome::Failed, TransferState::Paused, Some("paused by sender; partial data kept".to_string())),
        Err(e) => (Outcome::Failed, TransferState::Failed, Some(e.to_string())),
    };
    emit(node, peer, &sess, state, "", err.clone());
    let _ = node.store.add_history(HistoryEntry {
        id: sess.transfer_id.clone(),
        timestamp: unix_now(),
        direction: Direction::Received,
        peer_name: peer.name.clone(),
        peer_fingerprint: peer.fp.clone(),
        files: sess.names.clone(),
        file_count: sess.names.len() as u32,
        total_bytes: sess.total,
        outcome,
        error: err,
    });
}

struct PartialGuard {
    path: PathBuf,
    discard: bool,
}
impl Drop for PartialGuard {
    fn drop(&mut self) {
        if self.discard {
            let _ = fs::remove_file(&self.path);
        }
    }
}

async fn serve_inner<S>(node: &Arc<Node>, s: &mut S, peer: &Peer, sess: &mut Session) -> Result<()>
where
    S: AsyncRead + AsyncWrite + Unpin + Send,
{
    let lim = &node.limits;
    let idle = lim.idle_timeout;

    // 1. Validate the offer (all untrusted input).
    let Msg::Offer { transfer_id, files: metas } = read_msg(s, lim.handshake_timeout).await? else {
        return Err(FluxError::Protocol("expected offer".into()));
    };
    if transfer_id.is_empty() || transfer_id.len() > 64 || !transfer_id.chars().all(|c| c.is_ascii_alphanumeric() || c == '-') {
        return Err(FluxError::Protocol("bad transfer id".into()));
    }
    if metas.is_empty() || metas.len() > lim.max_files {
        return Err(FluxError::Limit(format!("a transfer may contain 1 to {} files", lim.max_files)));
    }
    let mut rels = Vec::with_capacity(metas.len());
    let mut total: u64 = 0;
    for (i, m) in metas.iter().enumerate() {
        if m.index as usize != i {
            return Err(FluxError::Protocol("file indices must be sequential".into()));
        }
        rels.push(SafeRelPath::parse(&m.path)?);
        total = total.checked_add(m.size).ok_or_else(|| FluxError::Limit("total size overflow".into()))?;
    }
    sess.transfer_id = transfer_id.clone();
    sess.names = rels.iter().map(|r| r.display()).collect();
    sess.total = total;

    // 2. Destination + disk space.
    let settings = node.store.settings();
    let root = paths::canonical_root(&settings.receive_dir)?;
    let have = fs2::available_space(&root)?;
    let need = total.saturating_add(lim.disk_reserve);
    if have < need {
        return Err(FluxError::DiskSpace { need, have });
    }

    // 3. Approval policy. Unknown devices can never bypass this.
    if !(peer.trusted && !settings.require_approval_for_trusted) {
        emit(node, peer, sess, TransferState::AwaitingApproval, "", None);
        let _permit = node.approval_sem.try_acquire().map_err(|_| FluxError::Rejected("receiver is busy".into()))?;
        let req = IncomingRequest {
            transfer_id: transfer_id.clone(),
            peer_name: peer.name.clone(),
            peer_fingerprint: peer.fp.clone(),
            trusted: peer.trusted,
            files: metas.iter().zip(&sess.names).map(|(m, n)| (n.clone(), m.size)).collect(),
            total_bytes: total,
            destination: root.clone(),
        };
        match tokio::time::timeout(lim.approval_timeout, node.approver.approve(&req)).await {
            Ok(Decision::Accept) => {}
            Ok(Decision::Reject(r)) => return Err(FluxError::Rejected(r)),
            Err(_) => return Err(FluxError::Rejected("no response from the receiving user".into())),
        }
    }

    let guard = node.register_incoming(&transfer_id, &peer.fp)?;
    sess.user_cancel = Some(guard.user.clone());
    let tok = guard.token.clone();

    // 4. Find resumable partials (owned by this peer + transfer id + file identity).
    let part_dir = root.join(PARTIAL_DIR);
    match fs::symlink_metadata(&part_dir) {
        Ok(m) if m.file_type().is_symlink() || !m.is_dir() => return Err(FluxError::InvalidPath("partial folder is not a plain directory".into())),
        Ok(_) => {}
        Err(_) => fs::create_dir(&part_dir)?,
    }
    let part_paths: Vec<PathBuf> = metas.iter()
        .map(|m| part_dir.join(format!("{}.fluxpart", part_key(&peer.fp, &transfer_id, &m.path, m.size, m.mtime))))
        .collect();
    sess.part_paths = part_paths.clone();
    let mut have_len = vec![0u64; metas.len()];
    let mut resume = Vec::new();
    for (i, m) in metas.iter().enumerate() {
        let Ok(md) = fs::symlink_metadata(&part_paths[i]) else { continue };
        let len = md.len();
        if md.is_file() && len > 0 && len <= m.size {
            let h = hash_prefix(&part_paths[i], len).await?;
            have_len[i] = len;
            resume.push(ResumeInfo { index: i as u32, offset: len, prefix_sha256: hex::encode(h.finalize()) });
        } else {
            let _ = fs::remove_file(&part_paths[i]);
        }
    }
    sess.bytes_done = have_len.iter().sum();
    write_msg(s, &Msg::Accept { resume }).await?;

    // 5. Receive each file.
    let mut last_emit = Instant::now();
    for (i, m) in metas.iter().enumerate() {
        let offset = match rf(s, idle, &tok).await? {
            Frame::Control(Msg::FileStart { index, offset }) if index == i as u32 => offset,
            Frame::Control(Msg::Cancel) => { discard_all(&part_paths); return Err(FluxError::Cancelled) }
            Frame::Control(Msg::Pause) => return Err(FluxError::Paused),
            _ => return Err(FluxError::Protocol("expected file start".into())),
        };
        if offset != 0 && offset != have_len[i] {
            return Err(FluxError::Protocol("resume offset does not match receiver's partial".into()));
        }
        let part = &part_paths[i];
        let mut guard = PartialGuard { path: part.clone(), discard: false };
        let mut hasher;
        let mut file;
        if offset == 0 {
            sess.bytes_done -= have_len[i]; // sender chose to restart (prefix did not verify)
            have_len[i] = 0;
            hasher = Sha256::new();
            file = tokio::fs::File::create(part).await?;
        } else {
            hasher = hash_prefix(part, offset).await?;
            file = tokio::fs::OpenOptions::new().write(true).open(part).await?;
            file.set_len(offset).await?;
            file.seek(std::io::SeekFrom::Start(offset)).await?;
        }
        let mut received = offset;
        let name = sess.names[i].clone();
        loop {
            match rf(s, idle, &tok).await? {
                Frame::Data(buf) => {
                    if received + buf.len() as u64 > m.size {
                        guard.discard = true;
                        return Err(FluxError::Protocol("sender sent more data than declared".into()));
                    }
                    hasher.update(&buf);
                    if let Err(e) = file.write_all(&buf).await {
                        guard.discard = true;
                        return Err(e.into());
                    }
                    received += buf.len() as u64;
                    sess.bytes_done += buf.len() as u64;
                    if last_emit.elapsed() >= Duration::from_millis(100) {
                        last_emit = Instant::now();
                        emit(node, peer, sess, TransferState::Active, &name, None);
                    }
                }
                Frame::Control(Msg::FileEnd { index, size, sha256 }) => {
                    let digest = hex::encode(hasher.finalize());
                    if index != i as u32 || size != m.size || received != m.size || !digest.eq_ignore_ascii_case(&sha256) {
                        guard.discard = true;
                        let _ = write_msg(s, &Msg::FileResult { index: i as u32, ok: false, error: Some("integrity check failed".into()) }).await;
                        return Err(FluxError::Integrity(format!("{name}: size or SHA-256 mismatch")));
                    }
                    file.flush().await?;
                    file.sync_all().await?;
                    drop(file);
                    let final_path = paths::reserve_destination(&root, &rels[i])?;
                    if let Err(e) = fs::rename(part, &final_path) {
                        let _ = fs::remove_file(&final_path);
                        guard.discard = true;
                        return Err(e.into());
                    }
                    sess.files_done += 1;
                    write_msg(s, &Msg::FileResult { index: i as u32, ok: true, error: None }).await?;
                    emit(node, peer, sess, TransferState::Active, &name, None);
                    break;
                }
                Frame::Control(Msg::Cancel) => { guard.discard = true; return Err(FluxError::Cancelled) }
                Frame::Control(Msg::Pause) => return Err(FluxError::Paused),
                _ => return Err(FluxError::Protocol("unexpected message during file".into())),
            }
        }
    }
    let _ = fs::remove_dir(&part_dir); // only succeeds if empty
    drop(guard);
    write_msg(s, &Msg::Complete).await
}

fn discard_all(paths: &[PathBuf]) {
    for p in paths {
        let _ = fs::remove_file(p);
    }
    // Tidy up: remove the (now empty) partial folder; this fails harmlessly if anything is left in it.
    if let Some(dir) = paths.first().and_then(|p| p.parent()) {
        let _ = fs::remove_dir(dir);
    }
}
