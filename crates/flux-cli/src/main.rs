//! `flux` — command-line front end for FLUX core. Lets two machines be tested end-to-end today,
//! before the desktop UI exists.

use async_trait::async_trait;
use clap::{Parser, Subcommand};
use flux_core::discovery::{Discovery, DiscoveryEvent};
use flux_core::events::{TransferProgress, TransferState};
use flux_core::protocol::DeviceType;
use flux_core::send::{self, Control, RetryPolicy, Target};
use flux_core::{Approver, Decision, Identity, IncomingRequest, Limits, Node, Store};
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;
use tokio::io::{AsyncBufReadExt, BufReader};

#[derive(Parser)]
#[command(name = "flux", about = "FLUX: secure local file transfer (CLI)")]
struct Cli {
    /// Where FLUX keeps its identity, trusted devices and history.
    #[arg(long, global = true)]
    data_dir: Option<PathBuf>,
    #[command(subcommand)]
    cmd: Cmd,
}

#[derive(Subcommand)]
enum Cmd {
    /// Listen for incoming transfers, advertise on the LAN and print a pairing link.
    Receive {
        #[arg(long, default_value_t = 47800)]
        port: u16,
        #[arg(long)]
        dir: Option<PathBuf>,
        #[arg(long)]
        name: Option<String>,
        #[arg(long)]
        no_discovery: bool,
        /// Seconds the pairing link stays valid.
        #[arg(long, default_value_t = 300)]
        pair_ttl: u64,
        /// DEV/TEST ONLY: queue this file once for every paired device (exercises the computer-to-phone outbox).
        #[arg(long)]
        dev_outbox: Option<PathBuf>,
    },
    /// Pair with a host using the link printed by `flux receive`.
    Pair { link: String },
    /// Send files/folders to a paired device (name or fingerprint prefix) or to host:port with --fp.
    Send {
        #[arg(long)]
        to: String,
        #[arg(long)]
        fp: Option<String>,
        /// Continue an interrupted/paused transfer (id is printed when you press Ctrl+C).
        #[arg(long)]
        transfer_id: Option<String>,
        paths: Vec<PathBuf>,
    },
    /// List devices on the LAN (mDNS). Discovered != trusted.
    Discover {
        #[arg(long, default_value_t = 5)]
        seconds: u64,
    },
    Devices,
    Revoke { fingerprint: String },
    History,
    /// Show this device's fingerprint.
    Id,
}

struct PromptApprover(tokio::sync::Mutex<()>);

#[async_trait]
impl Approver for PromptApprover {
    async fn approve(&self, r: &IncomingRequest) -> Decision {
        let _one_at_a_time = self.0.lock().await;
        eprintln!("\n=== Incoming transfer ===");
        eprintln!("From: {} ({})", r.peer_name, if r.trusted { "paired device" } else { "UNVERIFIED, not paired" });
        eprintln!("Fingerprint: {}", r.peer_fingerprint);
        eprintln!("{} file(s), {} total -> {}", r.files.len(), human(r.total_bytes), r.destination.display());
        for (n, s) in r.files.iter().take(15) { eprintln!("  {n}  ({})", human(*s)); }
        if r.files.len() > 15 { eprintln!("  ... and {} more", r.files.len() - 15); }
        eprint!("Accept? [y/N] ");
        let mut line = String::new();
        let _ = BufReader::new(tokio::io::stdin()).read_line(&mut line).await;
        if line.trim().eq_ignore_ascii_case("y") { Decision::Accept } else { Decision::Reject("declined by user".into()) }
    }
}

fn human(b: u64) -> String {
    let (mut v, mut i) = (b as f64, 0);
    while v >= 1024.0 && i < 4 { v /= 1024.0; i += 1; }
    format!("{v:.1} {}", ["B", "KB", "MB", "GB", "TB"][i])
}

fn data_dir(cli: &Cli) -> PathBuf {
    cli.data_dir.clone().unwrap_or_else(|| dirs::data_dir().unwrap_or_else(|| PathBuf::from(".")).join("FLUX"))
}

fn print_progress(p: &TransferProgress) {
    let pct = if p.bytes_total > 0 { p.bytes_done * 100 / p.bytes_total } else { 100 };
    match p.state {
        TransferState::Active => eprint!("\r[{:?}] {} {pct:>3}%  {}/{}  files {}/{}   ", p.direction, trunc(&p.file_name), human(p.bytes_done), human(p.bytes_total), p.files_done, p.files_total),
        s => eprintln!("\r[{:?}] {s:?} {}", p.direction, p.error.clone().unwrap_or_default()),
    }
}
fn trunc(s: &str) -> String { if s.chars().count() > 32 { format!("…{}", s.chars().skip(s.chars().count() - 31).collect::<String>()) } else { s.to_string() } }

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let cli = Cli::parse();
    let dir = data_dir(&cli);
    let store = Arc::new(Store::open(&dir)?);
    let identity = Identity::load_or_create(&dir.join("identity"))?;

    match cli.cmd {
        Cmd::Id => println!("{}", identity.fingerprint()),
        Cmd::Devices => {
            for t in store.trusted() { println!("{:<24} {}  {}", t.name, t.fingerprint, t.last_addr.unwrap_or_default()); }
        }
        Cmd::Revoke { fingerprint } => println!("{}", if store.revoke(&fingerprint)? { "revoked" } else { "no such device" }),
        Cmd::History => {
            for h in store.history().iter().take(30) {
                println!("{:?} {:?} {} {} file(s) {} {}", h.direction, h.outcome, h.peer_name, h.file_count, human(h.total_bytes), h.error.clone().unwrap_or_default());
            }
        }
        Cmd::Discover { seconds } => {
            let d = Discovery::new()?;
            let mut rx = d.browse()?;
            let end = tokio::time::Instant::now() + Duration::from_secs(seconds);
            while let Ok(Some(ev)) = tokio::time::timeout_at(end, rx.recv()).await {
                if let DiscoveryEvent::Found(p) = ev {
                    let trusted = store.get_trusted(&p.fingerprint).is_some();
                    println!("{:<24} {:?} {} {:?} {}", p.name, p.device_type, p.fingerprint, p.addrs, if trusted { "[paired]" } else { "[not paired]" });
                }
            }
            d.shutdown();
        }
        Cmd::Pair { link } => {
            let node = Node::new(identity, store, Arc::new(flux_core::RejectAll), Limits::default());
            let dev = node.pair_with(&link).await?;
            println!("Paired with {} ({})", dev.name, dev.fingerprint);
        }
        Cmd::Receive { port, dir: rdir, name, no_discovery, pair_ttl, dev_outbox } => {
            store.update_settings(|s| {
                if let Some(d) = &rdir { s.receive_dir = d.clone(); }
                if let Some(n) = &name { s.device_name = n.clone(); }
            })?;
            let node = Node::new(identity, store.clone(), Arc::new(PromptApprover(Default::default())), Limits::default());
            let handle = node.listen(format!("0.0.0.0:{port}").parse()?).await?;
            let settings = store.settings();
            println!("FLUX receiving as '{}' on port {}", settings.device_name, handle.local_addr.port());
            println!("Saving to: {}", settings.receive_dir.display());
            println!("Fingerprint: {}", node.fingerprint());
            let offer = node.begin_pairing_with_addrs(flux_core::discovery::lan_addrs(port), Some(Duration::from_secs(pair_ttl)));
            println!("\nPairing link (valid {}s, single use):\n{}\n", offer.expires_in.as_secs(), offer.uri);
            println!("Pairing code: {}\n", offer.code);
            let disc = if !no_discovery && settings.discoverable {
                match Discovery::new().and_then(|d| d.advertise(&settings.device_name, port, node.fingerprint(), DeviceType::Desktop).map(|_| d)) {
                    Ok(d) => { println!("Advertising on the local network (mDNS)."); Some(d) }
                    Err(e) => { eprintln!("mDNS advertising unavailable: {e}"); None }
                }
            } else { None };
            if let Some(path) = dev_outbox {
                let (n, st) = (node.clone(), store.clone());
                tokio::spawn(async move {
                    let mut done: std::collections::HashSet<String> = Default::default();
                    loop {
                        for t in st.trusted() {
                            if done.contains(&t.fingerprint) { continue }
                            if let Ok(files) = send::collect(vec![path.clone()], 100).await {
                                if n.queue_for_peer(&t.fingerprint, files).is_ok() { println!("[dev] queued {} for {}", path.display(), t.name); done.insert(t.fingerprint.clone()); }
                            }
                        }
                        tokio::time::sleep(Duration::from_millis(300)).await;
                    }
                });
            }
            let mut ev = node.subscribe();
            tokio::spawn(async move { while let Ok(p) = ev.recv().await { print_progress(&p) } });
            println!("Press Ctrl+C to stop.");
            tokio::signal::ctrl_c().await?;
            if let Some(d) = disc { d.shutdown(); }
            handle.stop();
        }
        Cmd::Send { to, fp, transfer_id, paths } => {
            let node = Node::new(identity, store.clone(), Arc::new(flux_core::RejectAll), Limits::default());
            let target = match fp {
                Some(fp) => Target { addr: to, fingerprint: fp.to_ascii_lowercase() },
                None => {
                    let t = store.trusted().into_iter().find(|t| t.name.eq_ignore_ascii_case(&to) || t.fingerprint.starts_with(&to.to_ascii_lowercase()))
                        .ok_or("no paired device with that name; pair first, or pass host:port with --fp")?;
                    Target { addr: t.last_addr.ok_or("no known address for that device; use --to host:port --fp <fingerprint>")?, fingerprint: t.fingerprint }
                }
            };
            let files = send::collect(paths, Limits::default().max_files).await?;
            let tid = transfer_id.unwrap_or_else(send::new_transfer_id);
            let mut ev = node.subscribe();
            tokio::spawn(async move { while let Ok(p) = ev.recv().await { print_progress(&p) } });
            let ctl = Control::default();
            let c2 = ctl.clone();
            tokio::spawn(async move { let _ = tokio::signal::ctrl_c().await; c2.pause.cancel(); });
            match send::send_collected(&node, &target, files, &ctl, &RetryPolicy::default(), Some(tid.clone())).await {
                Ok(s) => println!("\nDone: {} file(s), {} verified by the receiver (SHA-256), {} attempt(s).", s.files, human(s.bytes), s.attempts),
                Err(flux_core::FluxError::Paused) => println!("\nPaused. Resume with: flux send --to {} --transfer-id {tid} <same paths>", target.addr),
                Err(e) => { eprintln!("\nTransfer failed: {e}"); std::process::exit(1) }
            }
        }
    }
    Ok(())
}
