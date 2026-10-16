use thiserror::Error;

#[derive(Debug, Error)]
pub enum FluxError {
    #[error("I/O error: {0}")]
    Io(#[from] std::io::Error),
    #[error("TLS error: {0}")]
    Tls(String),
    #[error("protocol error: {0}")]
    Protocol(String),
    #[error("invalid path: {0}")]
    InvalidPath(String),
    #[error("pairing failed: {0}")]
    Pairing(String),
    #[error("rejected by receiver: {0}")]
    Rejected(String),
    #[error("transfer cancelled")]
    Cancelled,
    #[error("transfer paused")]
    Paused,
    #[error("delivery claim expired or was released")]
    ClaimLost,
    #[error("cancelled by the receiver")]
    RemoteCancelled,
    #[error("cancelled on this device")]
    LocalCancelled,
    #[error("limit exceeded: {0}")]
    Limit(String),
    #[error("integrity check failed: {0}")]
    Integrity(String),
    #[error("insufficient disk space: need {need} bytes, {have} available")]
    DiskSpace { need: u64, have: u64 },
    #[error("timed out: {0}")]
    Timeout(&'static str),
    #[error("storage error: {0}")]
    Storage(String),
    #[error("source file changed during transfer: {0}")]
    SourceChanged(String),
    #[error("remote error: {0}")]
    Remote(String),
}

pub type Result<T> = std::result::Result<T, FluxError>;

impl FluxError {
    /// Message that is safe to send to the remote peer (never leaks local paths or OS details).
    pub fn peer_message(&self) -> String {
        match self {
            FluxError::Io(_) | FluxError::Storage(_) => "receiver could not write the file".into(),
            FluxError::Tls(_) => "secure channel error".into(),
            other => other.to_string(),
        }
    }
}
