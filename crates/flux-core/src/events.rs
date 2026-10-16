use crate::store::Direction;
use serde::Serialize;

#[derive(Clone, Copy, Debug, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum TransferState {
    AwaitingApproval,
    Active,
    /// Connection lost; automatically retrying and resuming from verified data.
    Reconnecting,
    Paused,
    Completed,
    Failed,
    Cancelled,
    Rejected,
}

/// Real, measured progress. Speed/ETA are derived by the UI from successive `bytes_done`
/// samples; the engine never invents them.
#[derive(Clone, Debug, Serialize)]
pub struct TransferProgress {
    pub transfer_id: String,
    pub direction: Direction,
    pub peer_name: String,
    pub state: TransferState,
    pub file_name: String,
    pub files_done: u32,
    pub files_total: u32,
    pub bytes_done: u64,
    pub bytes_total: u64,
    pub error: Option<String>,
}
