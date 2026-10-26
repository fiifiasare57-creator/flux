#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod approver;
mod commands;
mod state;

use approver::UiApprover;
use flux_core::discovery::{Discovery, DiscoveryEvent};
use flux_core::protocol::DeviceType;
use flux_core::{Identity, Limits, Node, Store};
use state::AppState;
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use tauri::{AppHandle, Emitter, Manager};

const PREFERRED_PORT: u16 = 47800;

async fn init(app: AppHandle, dir: PathBuf) -> Result<AppState, Box<dyn std::error::Error>> {
    let store = Arc::new(Store::open(&dir)?);
    let identity = Identity::load_or_create(&dir.join("identity"))?;
    let pending: state::Pending = Arc::new(Mutex::new(HashMap::new()));
    let approver = Arc::new(UiApprover { app: app.clone(), pending: pending.clone() });
    let node = Node::new(identity, store.clone(), approver, Limits::default());

    // Listen on the preferred port, or any free port if it is taken.
    let listener = match node.listen(format!("0.0.0.0:{PREFERRED_PORT}").parse()?).await {
        Ok(l) => l,
        Err(_) => node.listen("0.0.0.0:0".parse()?).await?,
    };
    let port = listener.local_addr.port();

    // Forward real engine progress to the UI.
    let mut ev = node.subscribe();
    let a = app.clone();
    tauri::async_runtime::spawn(async move {
        loop {
            match ev.recv().await {
                Ok(p) => {
                    let _ = a.emit("transfer-progress", p);
                }
                Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => continue,
                Err(_) => break,
            }
        }
    });

    // Local-network discovery (hints only; trust comes from pairing).
    let settings = store.settings();
    let mut discovery = None;
    if settings.discoverable {
        match Discovery::new().and_then(|d| d.advertise(&settings.device_name, port, node.fingerprint(), DeviceType::Desktop).map(|_| d)) {
            Ok(d) => discovery = Some(d),
            Err(err) => eprintln!("FLUX: local discovery unavailable: {err}"),
        }
    }
    let browser = Discovery::new().ok();
    let rx = browser.as_ref().and_then(|d| d.browse().ok());
    if let Some(mut rx) = rx {
        let (a, n) = (app.clone(), node.clone());
        tauri::async_runtime::spawn(async move {
            while let Some(ev) = rx.recv().await {
                let st = a.state::<AppState>();
                match ev {
                    DiscoveryEvent::Found(p) => {
                        if let (Some(addr), true) = (p.addrs.first(), n.store().get_trusted(&p.fingerprint).is_some()) {
                            let _ = n.store().set_last_addr(&p.fingerprint, addr);
                        }
                        st.peers.lock().unwrap().insert(p.instance.clone(), p);
                    }
                    DiscoveryEvent::Lost { instance } => {
                        st.peers.lock().unwrap().remove(&instance);
                    }
                }
                let _ = a.emit("peers-changed", ());
            }
        });
    }
    // Keep the browsing daemon alive for the app's lifetime.
    std::mem::forget(browser);

    Ok(AppState {
        node,
        port,
        peers: Mutex::new(HashMap::new()),
        pending,
        jobs: Mutex::new(HashMap::new()),
        discovery: Mutex::new(discovery),
        _listener: listener,
    })
}

fn main() {
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_opener::init())
        .setup(|app| {
            let dir = app.path().app_data_dir()?;
            let state = tauri::async_runtime::block_on(init(app.handle().clone(), dir))?;
            app.manage(state);
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            commands::app_info,
            commands::update_settings,
            commands::history,
            commands::clear_history,
            commands::begin_pairing,
            commands::pair_with,
            commands::revoke,
            commands::send_to,
            commands::pair_with_code,
            commands::create_share,
            commands::list_shares,
            commands::revoke_share,
            commands::list_received,
            commands::open_received,
            commands::reveal_received,
            commands::queue_send,
            commands::cancel_queued,
            commands::resume_transfer,
            commands::pause_transfer,
            commands::cancel_transfer,
            commands::forget_job,
            commands::respond_incoming,
            commands::cancel_incoming,
            commands::open_receive_folder,
        ])
        .run(tauri::generate_context!())
        .expect("error while running FLUX");
}
