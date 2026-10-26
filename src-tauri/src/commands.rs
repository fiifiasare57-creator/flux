use crate::state::{AppState, Job};
use flux_core::discovery::lan_addrs;
use flux_core::protocol::DeviceType;
use flux_core::send::{self, Control, RetryPolicy, Target};
use flux_core::store::{HistoryEntry, Settings, TrustedDevice};
use flux_core::outbox::QueuedInfo;
use flux_core::share::ShareInfo;
use flux_core::FluxError;
use serde::{Deserialize, Serialize};
use std::path::PathBuf;
use std::sync::atomic::Ordering;
use std::sync::Arc;
use tauri::{AppHandle, State};
use tauri_plugin_opener::OpenerExt;

type St<'a> = State<'a, AppState>;

#[derive(Serialize)]
pub struct PeerDto {
    pub fingerprint: String,
    pub name: String,
    pub device_type: DeviceType,
    pub addrs: Vec<String>,
    pub paired: bool,
}

/// A paired device plus whether it is reachable *right now* (authenticated contact within the presence timeout,
/// or currently visible on the local network). Being paired in the past never counts as online.
#[derive(Serialize)]
pub struct TrustedView {
    #[serde(flatten)]
    pub dev: TrustedDevice,
    pub online: bool,
    pub last_seen: Option<u64>,
}

#[derive(Serialize)]
pub struct AppInfo {
    pub fingerprint: String,
    pub port: u16,
    pub version: &'static str,
    pub settings: Settings,
    pub trusted: Vec<TrustedView>,
    pub queued: Vec<QueuedInfo>,
    pub shares: Vec<ShareView>,
    pub nearby: Vec<PeerDto>,
    pub lan_addrs: Vec<String>,
    pub discovery_active: bool,
}

#[derive(Serialize)]
pub struct PairingDto {
    pub uri: String,
    pub code: String,
    pub expires_in_secs: u64,
}

#[derive(Deserialize)]
pub struct SettingsPatch {
    pub device_name: Option<String>,
    pub receive_dir: Option<String>,
    pub require_approval_for_trusted: Option<bool>,
    pub discoverable: Option<bool>,
}

fn e<T: std::fmt::Display>(err: T) -> String {
    err.to_string()
}

#[derive(Serialize)]
pub struct ShareView {
    pub token: String,
    pub url: String,
    pub expires_at: u64,
    pub file_count: u32,
    pub total_bytes: u64,
    pub names: Vec<String>,
    pub max_downloads: Option<u32>,
    pub downloads: u32,
}

fn share_view(state: &AppState, s: &ShareInfo) -> ShareView {
    let ip = lan_addrs(state.port).first().and_then(|a| a.rsplit_once(':').map(|x| x.0.to_string())).unwrap_or_default();
    ShareView {
        url: format!("http://{ip}:{}/{}", s.port, s.token),
        token: s.token.clone(), expires_at: s.expires_at, file_count: s.file_count, total_bytes: s.total_bytes,
        names: s.names.clone(), max_downloads: s.max_downloads, downloads: s.downloads,
    }
}

#[derive(Serialize)]
pub struct ReceivedFile {
    pub rel: String,
    pub name: String,
    pub size: u64,
    pub modified: u64,
}

#[tauri::command]
pub fn app_info(state: St) -> AppInfo {
    let node = &state.node;
    let store = node.store();
    let own = node.fingerprint().to_string();
    let mut nearby: Vec<PeerDto> = Vec::new();
    for p in state.peers.lock().unwrap().values() {
        if p.fingerprint == own || nearby.iter().any(|n| n.fingerprint == p.fingerprint) {
            continue;
        }
        nearby.push(PeerDto {
            fingerprint: p.fingerprint.clone(),
            name: p.name.clone(),
            device_type: p.device_type,
            addrs: p.addrs.clone(),
            paired: store.get_trusted(&p.fingerprint).is_some(),
        });
    }
    let seen_on_lan: Vec<String> = nearby.iter().map(|n| n.fingerprint.clone()).collect();
    let trusted = store
        .trusted()
        .into_iter()
        .map(|dev| TrustedView {
            online: node.is_online(&dev.fingerprint) || seen_on_lan.contains(&dev.fingerprint),
            last_seen: node.last_seen(&dev.fingerprint),
            dev,
        })
        .collect();
    AppInfo {
        queued: node.queued(),
        shares: node.list_shares().iter().map(|x| share_view(&state, x)).collect(),
        fingerprint: own,
        port: state.port,
        version: env!("CARGO_PKG_VERSION"),
        settings: store.settings(),
        trusted,
        nearby,
        lan_addrs: lan_addrs(state.port),
        discovery_active: state.discovery.lock().unwrap().is_some(),
    }
}

#[tauri::command]
pub fn update_settings(state: St, patch: SettingsPatch) -> Result<Settings, String> {
    let store = state.node.store();
    if let Some(n) = &patch.device_name {
        let n = n.trim();
        if n.is_empty() || n.chars().count() > 64 || n.chars().any(|c| c.is_control()) {
            return Err("Device name must be 1-64 characters.".into());
        }
    }
    if let Some(d) = &patch.receive_dir {
        let p = PathBuf::from(d);
        if !p.is_absolute() {
            return Err("Choose a full folder path.".into());
        }
        flux_core::paths::canonical_root(&p).map_err(|err| format!("That folder can't be used: {err}"))?;
    }
    store
        .update_settings(|s| {
            if let Some(n) = patch.device_name { s.device_name = n.trim().to_string(); }
            if let Some(d) = patch.receive_dir { s.receive_dir = PathBuf::from(d); }
            if let Some(v) = patch.require_approval_for_trusted { s.require_approval_for_trusted = v; }
            if let Some(v) = patch.discoverable { s.discoverable = v; }
        })
        .map_err(e)?;
    Ok(store.settings())
}

#[tauri::command]
pub fn history(state: St) -> Vec<HistoryEntry> {
    state.node.store().history()
}

#[tauri::command]
pub fn clear_history(state: St) -> Result<(), String> {
    state.node.store().clear_history().map_err(e)
}

#[tauri::command]
pub fn begin_pairing(state: St) -> PairingDto {
    let o = state.node.begin_pairing(state.port);
    PairingDto { uri: o.uri, code: o.code, expires_in_secs: o.expires_in.as_secs() }
}

#[tauri::command]
pub async fn pair_with(state: St<'_>, uri: String) -> Result<TrustedDevice, String> {
    state.node.pair_with(&uri).await.map_err(e)
}

#[tauri::command]
pub fn revoke(state: St, fingerprint: String) -> Result<bool, String> {
    state.node.store().revoke(&fingerprint).map_err(e)
}

fn resolve_addr(state: &AppState, fp: &str) -> Option<String> {
    state
        .peers
        .lock()
        .unwrap()
        .values()
        .find(|p| p.fingerprint == fp)
        .and_then(|p| p.addrs.first().cloned())
        .or_else(|| state.node.store().get_trusted(fp).and_then(|t| t.last_addr))
}

fn start_job(state: &AppState, id: String) -> Result<(), String> {
    let (target, files, control, running) = {
        let mut jobs = state.jobs.lock().unwrap();
        let job = jobs.get_mut(&id).ok_or("That transfer is no longer available.")?;
        if job.running.swap(true, Ordering::SeqCst) {
            return Err("That transfer is already running.".into());
        }
        job.control = Control::default();
        (job.target.clone(), job.files.clone(), job.control.clone(), job.running.clone())
    };
    let node = state.node.clone();
    tauri::async_runtime::spawn(async move {
        let res = send::send_collected(&node, &target, files, &control, &RetryPolicy::default(), Some(id)).await;
        running.store(false, Ordering::SeqCst);
        // Failed/Paused jobs are kept (so "Resume"/"Retry" work); the UI removes finished ones via `forget_job`.
        drop(res); // outcome is reported to the UI through transfer-progress events and history
    });
    Ok(())
}

#[tauri::command]
pub async fn send_to(state: St<'_>, fingerprint: String, paths: Vec<String>) -> Result<String, String> {
    if paths.is_empty() {
        return Err("Choose at least one file or folder.".into());
    }
    let addr = resolve_addr(&state, &fingerprint).ok_or("That device isn't reachable right now. Make sure FLUX is open on it and both devices are on the same network.")?;
    let files = send::collect(paths.into_iter().map(PathBuf::from).collect(), flux_core::Limits::default().max_files)
        .await
        .map_err(|err| match err {
            FluxError::Limit(m) => m,
            other => format!("Couldn't read the selection: {other}"),
        })?;
    let id = send::new_transfer_id();
    state.jobs.lock().unwrap().insert(
        id.clone(),
        Job {
            target: Target { addr, fingerprint },
            files,
            control: Control::default(),
            running: Arc::new(false.into()),
        },
    );
    start_job(&state, id.clone())?;
    Ok(id)
}

/// Queue files for a paired *phone* to pick up (phones don't listen, so they pull when FLUX is open on them).
#[tauri::command]
pub async fn queue_send(state: St<'_>, fingerprint: String, paths: Vec<String>) -> Result<String, String> {
    if paths.is_empty() {
        return Err("Choose at least one file or folder.".into());
    }
    let files = send::collect(paths.into_iter().map(PathBuf::from).collect(), flux_core::Limits::default().max_files)
        .await
        .map_err(|err| match err {
            FluxError::Limit(m) => m,
            other => format!("Couldn't read the selection: {other}"),
        })?;
    state.node.queue_for_peer(&fingerprint, files).map_err(e)
}

#[tauri::command]
pub fn cancel_queued(state: St, id: String) -> bool {
    state.node.cancel_queued(&id)
}

#[tauri::command]
pub fn resume_transfer(state: St, id: String) -> Result<(), String> {
    start_job(&state, id)
}

#[tauri::command]
pub fn pause_transfer(state: St, id: String) -> Result<(), String> {
    let jobs = state.jobs.lock().unwrap();
    jobs.get(&id).ok_or("Transfer not found.")?.control.pause.cancel();
    Ok(())
}

#[tauri::command]
pub fn cancel_transfer(state: St, id: String) -> Result<(), String> {
    let mut jobs = state.jobs.lock().unwrap();
    if let Some(j) = jobs.get(&id) {
        j.control.cancel.cancel();
        if !j.running.load(Ordering::SeqCst) {
            jobs.remove(&id); // paused/failed: nothing is running, just forget it
        }
    }
    Ok(())
}

/// Called by the UI once a transfer reaches a terminal state that can't be resumed.
#[tauri::command]
pub fn forget_job(state: St, id: String) {
    let mut jobs = state.jobs.lock().unwrap();
    if jobs.get(&id).is_some_and(|j| !j.running.load(Ordering::SeqCst)) {
        jobs.remove(&id);
    }
}

#[tauri::command]
pub fn cancel_incoming(state: St, id: String) -> bool {
    state.node.cancel_incoming(&id)
}

#[tauri::command]
pub fn respond_incoming(state: St, request_id: String, accept: bool) -> Result<(), String> {
    let tx = state.pending.lock().unwrap().remove(&request_id);
    match tx {
        Some(tx) => {
            let _ = tx.send(accept);
            Ok(())
        }
        None => Err("This request has already been answered or has expired.".into()),
    }
}

#[tauri::command]
pub fn open_receive_folder(app: AppHandle, state: St) -> Result<(), String> {
    let dir = flux_core::paths::canonical_root(&state.node.store().settings().receive_dir).map_err(e)?;
    app.opener().open_path(dir.to_string_lossy(), None::<&str>).map_err(e)
}

/// Pair using the 9-character code. Candidates are the FLUX devices currently visible on the local network.
#[tauri::command]
pub async fn pair_with_code(state: St<'_>, code: String) -> Result<TrustedDevice, String> {
    let cands: Vec<String> = state.peers.lock().unwrap().values().flat_map(|p| p.addrs.clone()).collect();
    if cands.is_empty() {
        return Err("No other FLUX device was found on your network. Open FLUX on it (same Wi-Fi), or pair with the full link instead.".into());
    }
    state.node.pair_with_code(&code, &cands).await.map_err(e)
}

/// Create a share link: a short URL anyone on the same network can open in a browser. The listener only exists while a link does.
#[tauri::command]
pub async fn create_share(state: St<'_>, paths: Vec<String>, ttl_secs: u64, max_downloads: Option<u32>) -> Result<ShareView, String> {
    if paths.is_empty() {
        return Err("Choose at least one file or folder.".into());
    }
    let files = send::collect(paths.into_iter().map(PathBuf::from).collect(), flux_core::Limits::default().max_files)
        .await
        .map_err(|err| match err {
            FluxError::Limit(m) => m,
            other => format!("Couldn't read the selection: {other}"),
        })?;
    let info = state.node.create_share(files, std::time::Duration::from_secs(ttl_secs.clamp(60, 7 * 24 * 3600)), max_downloads.filter(|m| *m > 0)).await.map_err(e)?;
    Ok(share_view(&state, &info))
}

#[tauri::command]
pub fn list_shares(state: St) -> Vec<ShareView> {
    state.node.list_shares().iter().map(|x| share_view(&state, x)).collect()
}

#[tauri::command]
pub fn revoke_share(state: St, token: String) -> bool {
    state.node.revoke_share(&token)
}

/// Files in the receive folder (up to 3 levels deep, 2000 entries). Nothing outside the receive folder is ever listed.
#[tauri::command]
pub async fn list_received(state: St<'_>) -> Result<Vec<ReceivedFile>, String> {
    let root = flux_core::paths::canonical_root(&state.node.store().settings().receive_dir).map_err(e)?;
    tauri::async_runtime::spawn_blocking(move || {
        let mut out = Vec::new();
        let mut stack = vec![(root.clone(), 0usize)];
        while let Some((dir, depth)) = stack.pop() {
            let Ok(rd) = std::fs::read_dir(&dir) else { continue };
            for ent in rd.flatten() {
                let name = ent.file_name().to_string_lossy().to_string();
                let Ok(md) = std::fs::symlink_metadata(ent.path()) else { continue };
                if md.file_type().is_symlink() || name == ".flux-partial" || name.ends_with(".fluxpart") {
                    continue;
                }
                if md.is_dir() {
                    if depth < 3 { stack.push((ent.path(), depth + 1)); }
                } else if md.is_file() && out.len() < 2000 {
                    let rel = ent.path().strip_prefix(&root).map(|r| r.to_string_lossy().replace('\\', "/")).unwrap_or_default();
                    let modified = md.modified().ok().and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok()).map(|d| d.as_secs()).unwrap_or(0);
                    out.push(ReceivedFile { rel, name, size: md.len(), modified });
                }
            }
        }
        out.sort_by(|a, b| b.modified.cmp(&a.modified));
        out
    })
    .await
    .map_err(e)
}

/// Resolve a path the UI gives us to a real file strictly inside the receive folder.
fn resolve_received(state: &AppState, rel: &str) -> Result<PathBuf, String> {
    if rel.is_empty() || rel.split(['/', '\\']).any(|c| c == ".." || c.is_empty()) || PathBuf::from(rel).is_absolute() {
        return Err("That path isn't allowed.".into());
    }
    let root = flux_core::paths::canonical_root(&state.node.store().settings().receive_dir).map_err(e)?;
    let p = std::fs::canonicalize(root.join(rel)).map_err(|_| "That file no longer exists.".to_string())?;
    if !p.starts_with(&root) || !p.is_file() {
        return Err("That path isn't allowed.".into());
    }
    Ok(p)
}

#[tauri::command]
pub fn open_received(app: AppHandle, state: St, rel: String) -> Result<(), String> {
    let p = resolve_received(&state, &rel)?;
    // Files from other devices are untrusted: never launch anything that can run code. "Show in folder" is always available.
    const RISKY: &[&str] = &["exe", "msi", "bat", "cmd", "com", "scr", "ps1", "vbs", "vbe", "js", "jse", "wsf", "lnk", "jar", "reg", "hta", "dll", "appx", "msix"];
    let ext = p.extension().map(|x| x.to_string_lossy().to_lowercase()).unwrap_or_default();
    if RISKY.contains(&ext.as_str()) {
        return Err("For safety FLUX won't open this type of file. Use Show in folder instead.".into());
    }
    app.opener().open_path(p.to_string_lossy(), None::<&str>).map_err(e)
}

#[tauri::command]
pub fn reveal_received(app: AppHandle, state: St, rel: String) -> Result<(), String> {
    let p = resolve_received(&state, &rel)?;
    app.opener().reveal_item_in_dir(p).map_err(e)
}
