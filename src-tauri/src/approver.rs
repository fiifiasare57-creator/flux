use crate::state::Pending;
use async_trait::async_trait;
use flux_core::send::new_transfer_id;
use flux_core::{Approver, Decision, IncomingRequest};
use serde::Serialize;
use tauri::{AppHandle, Emitter, Manager};
use tokio::sync::oneshot;

const MAX_FILES_IN_PROMPT: usize = 200;

#[derive(Serialize, Clone)]
pub struct FileDto {
    pub name: String,
    pub size: u64,
}

#[derive(Serialize, Clone)]
pub struct IncomingDto {
    pub request_id: String,
    pub peer_name: String,
    pub peer_fingerprint: String,
    pub trusted: bool,
    pub file_count: usize,
    pub files: Vec<FileDto>,
    pub total_bytes: u64,
    pub destination: String,
}

/// Bridges the engine's approval request to the UI and waits for the user's click.
pub struct UiApprover {
    pub app: AppHandle,
    pub pending: Pending,
}

struct Closer {
    id: String,
    pending: Pending,
    app: AppHandle,
}
impl Drop for Closer {
    fn drop(&mut self) {
        self.pending.lock().unwrap().remove(&self.id);
        let _ = self.app.emit("incoming-closed", &self.id);
    }
}

#[async_trait]
impl Approver for UiApprover {
    async fn approve(&self, r: &IncomingRequest) -> Decision {
        let id = new_transfer_id();
        let (tx, rx) = oneshot::channel();
        self.pending.lock().unwrap().insert(id.clone(), tx);
        let _closer = Closer { id: id.clone(), pending: self.pending.clone(), app: self.app.clone() };
        let dto = IncomingDto {
            request_id: id,
            peer_name: r.peer_name.clone(),
            peer_fingerprint: r.peer_fingerprint.clone(),
            trusted: r.trusted,
            file_count: r.files.len(),
            files: r.files.iter().take(MAX_FILES_IN_PROMPT).map(|(n, s)| FileDto { name: n.clone(), size: *s }).collect(),
            total_bytes: r.total_bytes,
            destination: r.destination.display().to_string(),
        };
        let _ = self.app.emit("incoming-request", dto);
        if let Some(w) = self.app.get_webview_window("main") {
            let _ = w.unminimize();
            let _ = w.show();
            let _ = w.set_focus();
        }
        match rx.await {
            Ok(true) => Decision::Accept,
            _ => Decision::Reject("declined by the receiving user".into()),
        }
    }
}
