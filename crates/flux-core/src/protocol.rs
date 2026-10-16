//! Wire protocol (v1), spoken inside the authenticated TLS channel.
//!
//! Frame: `kind:u8 | len:u32 (big-endian) | payload`.
//!   kind 1 = JSON control message (<= MAX_CONTROL_FRAME), kind 2 = raw file bytes (<= MAX_DATA_FRAME).
//! Lengths are validated *before* any allocation so a peer cannot force large allocations.
//! File bytes are streamed in CHUNK_SIZE frames; TCP flow control provides backpressure.

use crate::error::{FluxError, Result};
use serde::{Deserialize, Serialize};
use std::time::Duration;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

pub const PROTO_VERSION: u32 = 1;
pub const MAX_CONTROL_FRAME: usize = 1 << 20;
pub const CHUNK_SIZE: usize = 256 * 1024;
pub const MAX_DATA_FRAME: usize = 1 << 20;

const KIND_CONTROL: u8 = 1;
const KIND_DATA: u8 = 2;

#[derive(Serialize, Deserialize, Debug, Clone, Copy, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum DeviceType {
    Desktop,
    Laptop,
    Phone,
    Tablet,
    Unknown,
}

#[derive(Serialize, Deserialize, Debug, Clone, Copy, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum Purpose {
    Pair,
    Transfer,
    /// A paired device (e.g. a phone) asks the computer for files queued for it.
    Fetch,
}

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, Eq)]
pub struct FileMeta {
    pub index: u32,
    /// '/'-separated relative path. UNTRUSTED: always run through `SafeRelPath::parse`.
    pub path: String,
    pub size: u64,
    /// Source modification time (unix seconds); part of the identity used to match partial files.
    pub mtime: u64,
}

/// Receiver-held partial data for a file: bytes `[0, offset)` are already on disk and
/// `prefix_sha256` is the SHA-256 of exactly those bytes, so the sender can *verify* the
/// prefix against its own file before resuming (a partial is never trusted blindly).
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, Eq)]
pub struct ResumeInfo {
    pub index: u32,
    pub offset: u64,
    pub prefix_sha256: String,
}

#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(tag = "t", rename_all = "snake_case")]
pub enum Msg {
    Hello { proto: u32, device_name: String, device_type: DeviceType, purpose: Purpose, token: Option<String> },
    PairOk { device_name: String, device_type: DeviceType },
    Offer { transfer_id: String, files: Vec<FileMeta> },
    Accept { resume: Vec<ResumeInfo> },
    Reject { reason: String },
    FileStart { index: u32, offset: u64 },
    FileEnd { index: u32, size: u64, sha256: String },
    FileResult { index: u32, ok: bool, error: Option<String> },
    Complete,
    /// Abort and discard partial data.
    Cancel,
    /// Stop now but keep verified partial data so the transfer can be resumed.
    Pause,
    Error { message: String },
}

#[derive(Debug)]
pub enum Frame {
    Control(Msg),
    Data(Vec<u8>),
}

pub async fn write_msg<W: AsyncWrite + Unpin>(w: &mut W, msg: &Msg) -> Result<()> {
    let body = serde_json::to_vec(msg).map_err(|e| FluxError::Protocol(e.to_string()))?;
    if body.len() > MAX_CONTROL_FRAME {
        return Err(FluxError::Limit("control message too large".into()));
    }
    write_frame(w, KIND_CONTROL, &body).await
}

pub async fn write_data<W: AsyncWrite + Unpin>(w: &mut W, data: &[u8]) -> Result<()> {
    debug_assert!(data.len() <= MAX_DATA_FRAME);
    write_frame(w, KIND_DATA, data).await
}

async fn write_frame<W: AsyncWrite + Unpin>(w: &mut W, kind: u8, body: &[u8]) -> Result<()> {
    let mut header = [0u8; 5];
    header[0] = kind;
    header[1..].copy_from_slice(&(body.len() as u32).to_be_bytes());
    w.write_all(&header).await?;
    w.write_all(body).await?;
    w.flush().await?;
    Ok(())
}

/// Read one frame, failing if the peer is idle for longer than `idle`.
pub async fn read_frame<R: AsyncRead + Unpin>(r: &mut R, idle: Duration) -> Result<Frame> {
    tokio::time::timeout(idle, read_frame_inner(r))
        .await
        .map_err(|_| FluxError::Timeout("waiting for peer"))?
}

async fn read_frame_inner<R: AsyncRead + Unpin>(r: &mut R) -> Result<Frame> {
    let mut header = [0u8; 5];
    r.read_exact(&mut header).await?;
    let len = u32::from_be_bytes([header[1], header[2], header[3], header[4]]) as usize;
    match header[0] {
        KIND_CONTROL => {
            if len > MAX_CONTROL_FRAME {
                return Err(FluxError::Limit("control frame too large".into()));
            }
            let mut buf = vec![0u8; len];
            r.read_exact(&mut buf).await?;
            let msg = serde_json::from_slice(&buf).map_err(|e| FluxError::Protocol(format!("bad message: {e}")))?;
            Ok(Frame::Control(msg))
        }
        KIND_DATA => {
            if len > MAX_DATA_FRAME {
                return Err(FluxError::Limit("data frame too large".into()));
            }
            let mut buf = vec![0u8; len];
            r.read_exact(&mut buf).await?;
            Ok(Frame::Data(buf))
        }
        k => Err(FluxError::Protocol(format!("unknown frame kind {k}"))),
    }
}

/// Expect a control message; anything else is a protocol violation.
pub async fn read_msg<R: AsyncRead + Unpin>(r: &mut R, idle: Duration) -> Result<Msg> {
    match read_frame(r, idle).await? {
        Frame::Control(m) => Ok(m),
        Frame::Data(_) => Err(FluxError::Protocol("unexpected data frame".into())),
    }
}
