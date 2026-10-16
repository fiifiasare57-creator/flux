//! Small durable store (settings, trusted devices, bounded history) kept as one JSON file,
//! written atomically (tmp + fsync + rename). Holds metadata only: never file contents,
//! never private keys. SQLite is deliberately not used yet: the data set is tiny and bounded.

use crate::error::{FluxError, Result};
use crate::protocol::DeviceType;
use serde::{Deserialize, Serialize};
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

pub const MAX_HISTORY: usize = 1000;
const MAX_HISTORY_NAMES: usize = 50;

pub fn unix_now() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0)
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct Settings {
    pub device_name: String,
    pub receive_dir: PathBuf,
    /// If false, devices you have paired with may send without a prompt.
    pub require_approval_for_trusted: bool,
    pub discoverable: bool,
}

impl Default for Settings {
    fn default() -> Self {
        let device_name = std::env::var("COMPUTERNAME")
            .or_else(|_| std::env::var("HOSTNAME"))
            .unwrap_or_else(|_| "FLUX device".into());
        let receive_dir = dirs::download_dir()
            .or_else(dirs::home_dir)
            .unwrap_or_else(|| PathBuf::from("."))
            .join("FLUX");
        Self { device_name, receive_dir, require_approval_for_trusted: false, discoverable: true }
    }
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct TrustedDevice {
    pub fingerprint: String,
    pub name: String,
    pub device_type: DeviceType,
    pub paired_at: u64,
    /// Last address this device was reached at (a hint only; identity is the fingerprint).
    pub last_addr: Option<String>,
}

#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum Direction {
    Sent,
    Received,
}

#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum Outcome {
    Completed,
    Failed,
    Cancelled,
    Rejected,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct HistoryEntry {
    pub id: String,
    pub timestamp: u64,
    pub direction: Direction,
    pub peer_name: String,
    pub peer_fingerprint: String,
    pub files: Vec<String>,
    pub file_count: u32,
    pub total_bytes: u64,
    pub outcome: Outcome,
    pub error: Option<String>,
}

impl HistoryEntry {
    pub fn cap_names(mut self) -> Self {
        self.files.truncate(MAX_HISTORY_NAMES);
        self
    }
}

#[derive(Serialize, Deserialize, Default)]
struct Data {
    settings: Settings,
    trusted: Vec<TrustedDevice>,
    history: Vec<HistoryEntry>,
}

pub struct Store {
    path: PathBuf,
    inner: Mutex<Data>,
}

impl Store {
    pub fn open(dir: &Path) -> Result<Self> {
        fs::create_dir_all(dir)?;
        let path = dir.join("state.json");
        let data = if path.exists() {
            serde_json::from_slice(&fs::read(&path)?).map_err(|e| FluxError::Storage(format!("corrupt state.json: {e}")))?
        } else {
            Data::default()
        };
        Ok(Self { path, inner: Mutex::new(data) })
    }

    fn mutate<T>(&self, f: impl FnOnce(&mut Data) -> T) -> Result<T> {
        let mut g = self.inner.lock().unwrap();
        let out = f(&mut g);
        let tmp = self.path.with_extension("tmp");
        let mut file = fs::File::create(&tmp)?;
        file.write_all(&serde_json::to_vec_pretty(&*g).map_err(|e| FluxError::Storage(e.to_string()))?)?;
        file.sync_all()?;
        drop(file);
        fs::rename(&tmp, &self.path)?;
        Ok(out)
    }

    pub fn settings(&self) -> Settings {
        self.inner.lock().unwrap().settings.clone()
    }
    pub fn update_settings(&self, f: impl FnOnce(&mut Settings)) -> Result<()> {
        self.mutate(|d| f(&mut d.settings))
    }

    pub fn trusted(&self) -> Vec<TrustedDevice> {
        self.inner.lock().unwrap().trusted.clone()
    }
    pub fn get_trusted(&self, fp: &str) -> Option<TrustedDevice> {
        self.inner.lock().unwrap().trusted.iter().find(|t| t.fingerprint == fp).cloned()
    }
    pub fn add_trusted(&self, dev: TrustedDevice) -> Result<()> {
        self.mutate(|d| {
            d.trusted.retain(|t| t.fingerprint != dev.fingerprint);
            d.trusted.push(dev);
        })
    }
    pub fn revoke(&self, fp: &str) -> Result<bool> {
        self.mutate(|d| {
            let before = d.trusted.len();
            d.trusted.retain(|t| t.fingerprint != fp);
            d.trusted.len() != before
        })
    }
    pub fn set_last_addr(&self, fp: &str, addr: &str) -> Result<()> {
        self.mutate(|d| {
            if let Some(t) = d.trusted.iter_mut().find(|t| t.fingerprint == fp) {
                t.last_addr = Some(addr.to_string());
            }
        })
    }

    pub fn add_history(&self, e: HistoryEntry) -> Result<()> {
        self.mutate(|d| {
            d.history.push(e.cap_names());
            let n = d.history.len();
            if n > MAX_HISTORY {
                d.history.drain(..n - MAX_HISTORY);
            }
        })
    }
    pub fn history(&self) -> Vec<HistoryEntry> {
        let mut h = self.inner.lock().unwrap().history.clone();
        h.reverse();
        h
    }
    pub fn clear_history(&self) -> Result<()> {
        self.mutate(|d| d.history.clear())
    }
}
