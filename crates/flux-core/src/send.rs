//! Sending side: streaming, bounded memory (one CHUNK_SIZE buffer), pause/cancel, verified resume,
//! and automatic retry that only re-sends what has not yet been verified by the receiver.

use crate::error::{FluxError, Result};
use crate::events::{TransferProgress, TransferState};
use crate::node::Node;
use crate::protocol::*;
use crate::receive::hash_prefix;
use crate::store::{unix_now, Direction, HistoryEntry, Outcome};
use crate::tls;
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use tokio::io::{AsyncReadExt, AsyncSeekExt};
use tokio_util::sync::CancellationToken;

#[derive(Clone, Debug)]
pub struct Target {
    /// "host:port"
    pub addr: String,
    /// Expected SHA-256 certificate fingerprint (pinned). Never derived from the address.
    pub fingerprint: String,
}

/// `cancel` discards partial data on the receiver; `pause` keeps it so the transfer can be resumed.
#[derive(Clone, Default)]
pub struct Control {
    pub cancel: CancellationToken,
    pub pause: CancellationToken,
}

#[derive(Clone, Debug)]
pub struct RetryPolicy {
    pub max_attempts: u32,
    pub backoff: Duration,
}
impl Default for RetryPolicy {
    fn default() -> Self {
        Self { max_attempts: 5, backoff: Duration::from_secs(2) }
    }
}

#[derive(Clone, Debug)]
pub struct SourceFile {
    pub abs: PathBuf,
    pub rel: String,
    pub size: u64,
    pub mtime: u64,
}

#[derive(Debug, Clone)]
pub struct TransferSummary {
    pub transfer_id: String,
    pub files: u32,
    pub bytes: u64,
    pub attempts: u32,
}

pub(crate) fn mtime_of(md: &std::fs::Metadata) -> u64 {
    md.modified().ok().and_then(|t| t.duration_since(UNIX_EPOCH).ok()).map(|d| d.as_secs()).unwrap_or(0)
}

/// Expand files and folders into a flat list. Folder structure is preserved in `rel`;
/// symlinks are skipped (never followed).
pub async fn collect(paths: Vec<PathBuf>, max_files: usize) -> Result<Vec<SourceFile>> {
    tokio::task::spawn_blocking(move || {
        let mut out = Vec::new();
        let mut stack: Vec<(PathBuf, String)> = Vec::new();
        for p in paths {
            let md = std::fs::symlink_metadata(&p)?;
            let name = p.file_name().map(|n| n.to_string_lossy().into_owned()).ok_or_else(|| FluxError::InvalidPath("path has no name".into()))?;
            if md.is_file() {
                out.push(SourceFile { size: md.len(), mtime: mtime_of(&md), abs: p, rel: name });
            } else if md.is_dir() {
                stack.push((p, name));
            }
        }
        while let Some((dir, rel)) = stack.pop() {
            for e in std::fs::read_dir(&dir)? {
                let e = e?;
                let md = e.metadata()?; // does not follow symlinks
                let child_rel = format!("{rel}/{}", e.file_name().to_string_lossy());
                if md.is_file() {
                    out.push(SourceFile { size: md.len(), mtime: mtime_of(&md), abs: e.path(), rel: child_rel });
                } else if md.is_dir() {
                    stack.push((e.path(), child_rel));
                }
                if out.len() > max_files {
                    return Err(FluxError::Limit(format!("more than {max_files} files; send in smaller batches")));
                }
            }
        }
        out.sort_by(|a, b| a.rel.cmp(&b.rel));
        if out.is_empty() {
            return Err(FluxError::InvalidPath("nothing to send".into()));
        }
        Ok(out)
    })
    .await
    .map_err(|e| FluxError::Storage(e.to_string()))?
}

struct Progress {
    node: Arc<Node>,
    transfer_id: String,
    peer_name: String,
    files_total: u32,
    bytes_total: u64,
    files_done: u32,
    bytes_done: u64,
    last: Instant,
}

impl Progress {
    fn emit(&mut self, state: TransferState, file: &str, err: Option<String>, force: bool) {
        if !force && self.last.elapsed() < Duration::from_millis(100) {
            return;
        }
        self.last = Instant::now();
        self.node.emit(TransferProgress {
            transfer_id: self.transfer_id.clone(),
            direction: Direction::Sent,
            peer_name: self.peer_name.clone(),
            state,
            file_name: file.to_string(),
            files_done: self.files_done,
            files_total: self.files_total,
            bytes_done: self.bytes_done,
            bytes_total: self.bytes_total,
            error: err,
        });
    }
}

fn retryable(e: &FluxError) -> bool {
    matches!(e, FluxError::Io(_) | FluxError::Timeout(_))
}

pub fn new_transfer_id() -> String {
    use rand::RngCore;
    let mut b = [0u8; 16];
    rand::rngs::OsRng.fill_bytes(&mut b);
    hex::encode(b)
}

/// Send files/folders to a device. Automatically reconnects and resumes from receiver-verified
/// data on network failures. Returns when the receiver has verified every file.
pub async fn send(node: &Arc<Node>, target: &Target, paths: Vec<PathBuf>, ctl: &Control, retry: &RetryPolicy) -> Result<TransferSummary> {
    let files = collect(paths, node.limits.max_files).await?;
    send_collected(node, target, files, ctl, retry, None).await
}

/// Like `send`, but lets a caller (e.g. "Resume transfer" in the UI) continue a known transfer id.
pub async fn send_collected(node: &Arc<Node>, target: &Target, files: Vec<SourceFile>, ctl: &Control, retry: &RetryPolicy, transfer_id: Option<String>) -> Result<TransferSummary> {
    let transfer_id = transfer_id.unwrap_or_else(new_transfer_id);
    let trusted = node.store.get_trusted(&target.fingerprint);
    let peer_name = trusted.as_ref().map(|t| t.name.clone()).unwrap_or_else(|| format!("Unverified device {}", &target.fingerprint[..8]));
    let mut prog = Progress {
        node: node.clone(),
        transfer_id: transfer_id.clone(),
        peer_name: peer_name.clone(),
        files_total: files.len() as u32,
        bytes_total: files.iter().map(|f| f.size).sum(),
        files_done: 0,
        bytes_done: 0,
        last: Instant::now() - Duration::from_secs(1),
    };
    let mut remaining = files.clone();
    let mut attempts = 0;
    let result: Result<()> = loop {
        attempts += 1;
        let mut verified = 0usize;
        match attempt(node, target, &transfer_id, &remaining, ctl, &mut prog, &mut verified).await {
            Ok(()) => break Ok(()),
            Err(e) => {
                remaining.drain(..verified); // files the receiver already verified are never re-sent
                if retryable(&e) && attempts < retry.max_attempts {
                    prog.emit(TransferState::Reconnecting, "", Some(e.to_string()), true);
                    tokio::select! {
                        _ = tokio::time::sleep(retry.backoff) => continue,
                        _ = ctl.cancel.cancelled() => break Err(FluxError::Cancelled),
                        _ = ctl.pause.cancelled() => break Err(FluxError::Paused),
                    }
                }
                break Err(e);
            }
        }
    };

    let (state, outcome, err) = match &result {
        Ok(()) => (TransferState::Completed, Some(Outcome::Completed), None),
        Err(FluxError::Paused) => (TransferState::Paused, None, Some("paused".to_string())),
        Err(FluxError::Cancelled) => (TransferState::Cancelled, Some(Outcome::Cancelled), Some("cancelled".to_string())),
        Err(FluxError::RemoteCancelled) => (TransferState::Cancelled, Some(Outcome::Cancelled), Some("cancelled by the receiver".to_string())),
        Err(FluxError::Rejected(r)) => (TransferState::Rejected, Some(Outcome::Rejected), Some(r.clone())),
        Err(e) => (TransferState::Failed, Some(Outcome::Failed), Some(e.to_string())),
    };
    prog.emit(state, "", err.clone(), true);
    if let Some(outcome) = outcome {
        let _ = node.store.add_history(HistoryEntry {
            id: transfer_id.clone(),
            timestamp: unix_now(),
            direction: Direction::Sent,
            peer_name,
            peer_fingerprint: target.fingerprint.clone(),
            files: files.iter().map(|f| f.rel.clone()).collect(),
            file_count: files.len() as u32,
            total_bytes: prog.bytes_total,
            outcome,
            error: err,
        });
    }
    if result.is_ok() && trusted.is_some() {
        let _ = node.store.set_last_addr(&target.fingerprint, &target.addr);
    }
    result.map(|()| TransferSummary { transfer_id, files: files.len() as u32, bytes: prog.bytes_total, attempts })
}

fn check_control(ctl: &Control) -> Result<()> {
    if ctl.cancel.is_cancelled() { return Err(FluxError::Cancelled) }
    if ctl.pause.is_cancelled() { return Err(FluxError::Paused) }
    Ok(())
}

/// If a write fails because the receiver hung up, its last message usually says why.
async fn explain<S: tokio::io::AsyncRead + Unpin>(s: &mut S, original: FluxError) -> FluxError {
    match read_frame(s, Duration::from_secs(1)).await {
        Ok(Frame::Control(Msg::Error { message })) => FluxError::Remote(message),
        Ok(Frame::Control(Msg::Reject { reason })) => FluxError::Rejected(reason),
        Ok(Frame::Control(Msg::Cancel)) => FluxError::RemoteCancelled,
        _ => original,
    }
}

async fn attempt(node: &Arc<Node>, target: &Target, tid: &str, files: &[SourceFile], ctl: &Control, prog: &mut Progress, verified: &mut usize) -> Result<()> {
    let lim = &node.limits;
    check_control(ctl)?;
    prog.emit(TransferState::AwaitingApproval, "", None, true);
    let mut s = tokio::select! {
        _ = ctl.cancel.cancelled() => return Err(FluxError::Cancelled),
        _ = ctl.pause.cancelled() => return Err(FluxError::Paused),
        r = tls::connect(&node.identity, &target.addr, &target.fingerprint, lim.handshake_timeout) => r?,
    };
    write_msg(&mut s, &Msg::Hello { proto: PROTO_VERSION, device_name: node.device_name(), device_type: DeviceType::Desktop, purpose: Purpose::Transfer, token: None }).await?;
    let metas: Vec<FileMeta> = files.iter().enumerate().map(|(i, f)| FileMeta { index: i as u32, path: f.rel.clone(), size: f.size, mtime: f.mtime }).collect();
    write_msg(&mut s, &Msg::Offer { transfer_id: tid.to_string(), files: metas }).await?;

    let reply = tokio::select! {
        _ = ctl.cancel.cancelled() => return Err(FluxError::Cancelled),
        _ = ctl.pause.cancelled() => return Err(FluxError::Paused),
        r = read_msg(&mut s, lim.approval_timeout + Duration::from_secs(10)) => r?,
    };
    let resume: HashMap<u32, ResumeInfo> = match reply {
        Msg::Accept { resume } => resume.into_iter().filter(|r| (r.index as usize) < files.len() && r.offset <= files[r.index as usize].size).map(|r| (r.index, r)).collect(),
        Msg::Reject { reason } => return Err(FluxError::Rejected(reason)),
        Msg::Error { message } => return Err(FluxError::Remote(message)),
        _ => return Err(FluxError::Protocol("unexpected reply to offer".into())),
    };

    // Progress base for this attempt: files verified earlier are not part of `files` any more.
    let base_files = prog.files_done;
    let base_bytes = prog.bytes_done;
    let _ = (base_files, base_bytes);
    let mut buf = vec![0u8; CHUNK_SIZE];

    for (i, f) in files.iter().enumerate() {
        if let Err(e) = check_control(ctl) {
            let _ = write_msg(&mut s, &if matches!(e, FluxError::Paused) { Msg::Pause } else { Msg::Cancel }).await;
            return Err(e);
        }
        let mut file = tokio::fs::File::open(&f.abs).await?;
        let md = file.metadata().await?;
        if md.len() != f.size || mtime_of(&md) != f.mtime {
            return Err(FluxError::SourceChanged(f.rel.clone()));
        }

        // Verify the receiver's partial prefix against *our* bytes before resuming from it.
        let mut hasher = Sha256::new();
        let mut offset = 0u64;
        if let Some(r) = resume.get(&(i as u32)) {
            let h = hash_prefix(&f.abs, r.offset).await?;
            if hex::encode(h.clone().finalize()).eq_ignore_ascii_case(&r.prefix_sha256) {
                offset = r.offset;
                hasher = h;
                file.seek(std::io::SeekFrom::Start(offset)).await?;
            }
        }
        // Count already-held bytes towards progress (receiver reports what it will keep).
        let file_start_bytes = prog.bytes_done;
        prog.bytes_done += offset;

        write_msg(&mut s, &Msg::FileStart { index: i as u32, offset }).await?;
        let mut sent = offset;
        while sent < f.size {
            if let Err(e) = check_control(ctl) {
                let _ = write_msg(&mut s, &if matches!(e, FluxError::Paused) { Msg::Pause } else { Msg::Cancel }).await;
                prog.bytes_done = file_start_bytes + sent; // bytes safely on the receiver's disk
                return Err(e);
            }
            let want = (f.size - sent).min(CHUNK_SIZE as u64) as usize;
            let n = file.read(&mut buf[..want]).await?;
            if n == 0 {
                return Err(FluxError::SourceChanged(format!("{} became shorter", f.rel)));
            }
            hasher.update(&buf[..n]);
            if let Err(e) = write_data(&mut s, &buf[..n]).await {
                return Err(explain(&mut s, e).await);
            }
            sent += n as u64;
            prog.bytes_done += n as u64;
            prog.emit(TransferState::Active, &f.rel, None, false);
        }
        let md2 = tokio::fs::metadata(&f.abs).await?;
        if md2.len() != f.size || mtime_of(&md2) != f.mtime {
            let _ = write_msg(&mut s, &Msg::Cancel).await;
            return Err(FluxError::SourceChanged(f.rel.clone()));
        }
        if let Err(e) = write_msg(&mut s, &Msg::FileEnd { index: i as u32, size: f.size, sha256: hex::encode(hasher.finalize()) }).await {
            return Err(explain(&mut s, e).await);
        }
        match read_msg(&mut s, lim.idle_timeout).await? {
            Msg::FileResult { ok: true, .. } => {
                *verified += 1;
                prog.files_done += 1;
                prog.bytes_done = file_start_bytes + f.size;
                prog.emit(TransferState::Active, &f.rel, None, true);
            }
            Msg::FileResult { ok: false, error, .. } => return Err(FluxError::Remote(error.unwrap_or_else(|| "receiver rejected file".into()))),
            Msg::Error { message } => return Err(FluxError::Remote(message)),
            Msg::Cancel => return Err(FluxError::RemoteCancelled),
            _ => return Err(FluxError::Protocol("unexpected reply after file".into())),
        }
    }
    match read_msg(&mut s, lim.idle_timeout).await? {
        Msg::Complete => Ok(()),
        Msg::Error { message } => Err(FluxError::Remote(message)),
        Msg::Cancel => Err(FluxError::RemoteCancelled),
        _ => Err(FluxError::Protocol("expected completion".into())),
    }
}

#[allow(dead_code)]
fn _unused(_: SystemTime) {}
