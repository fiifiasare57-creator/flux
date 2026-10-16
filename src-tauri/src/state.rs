use flux_core::discovery::{Discovery, DiscoveredPeer};
use flux_core::node::ListenerHandle;
use flux_core::send::{Control, SourceFile, Target};
use flux_core::Node;
use std::collections::HashMap;
use std::sync::atomic::AtomicBool;
use std::sync::{Arc, Mutex};
use tokio::sync::oneshot;

pub type Pending = Arc<Mutex<HashMap<String, oneshot::Sender<bool>>>>;

/// A send operation the user can pause, resume, retry or cancel.
pub struct Job {
    pub target: Target,
    pub files: Vec<SourceFile>,
    pub control: Control,
    pub running: Arc<AtomicBool>,
}

pub struct AppState {
    pub node: Arc<Node>,
    pub port: u16,
    pub peers: Mutex<HashMap<String, DiscoveredPeer>>,
    pub pending: Pending,
    pub jobs: Mutex<HashMap<String, Job>>,
    pub discovery: Mutex<Option<Discovery>>,
    pub _listener: ListenerHandle,
}
