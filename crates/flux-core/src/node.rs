use crate::error::{FluxError, Result};
use crate::events::TransferProgress;
use crate::identity::Identity;
use crate::pairing::{PairingManager, PairingPayload, DEFAULT_TTL};
use crate::protocol::*;
use crate::store::{unix_now, Store, TrustedDevice};
use crate::{receive, tls};
use async_trait::async_trait;
use std::net::SocketAddr;
use std::path::PathBuf;
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{broadcast, Semaphore};
use tokio_util::sync::CancellationToken;

#[derive(Clone, Debug)]
pub struct Limits {
    pub max_connections: usize,
    pub max_pending_approvals: usize,
    pub max_files: usize,
    pub handshake_timeout: Duration,
    pub idle_timeout: Duration,
    pub approval_timeout: Duration,
    /// Free space that must remain after a transfer is accepted.
    pub disk_reserve: u64,
    pub partial_max_age: Duration,
    /// A paired device counts as online only if it contacted us within this window.
    pub presence_timeout: Duration,
    /// A claimed outbox item returns to the queue if its claim is not refreshed within this window.
    pub claim_ttl: Duration,
}

impl Default for Limits {
    fn default() -> Self {
        Self {
            max_connections: 16,
            max_pending_approvals: 4,
            max_files: 2000,
            handshake_timeout: Duration::from_secs(10),
            idle_timeout: Duration::from_secs(30),
            approval_timeout: Duration::from_secs(120),
            disk_reserve: 16 * 1024 * 1024,
            partial_max_age: Duration::from_secs(7 * 24 * 3600),
            presence_timeout: Duration::from_secs(15),
            claim_ttl: Duration::from_secs(60),
        }
    }
}

#[derive(Debug, Clone)]
pub struct IncomingRequest {
    pub transfer_id: String,
    /// For trusted peers this is the name stored at pairing; otherwise it is *self-reported and unverified*.
    pub peer_name: String,
    pub peer_fingerprint: String,
    pub trusted: bool,
    pub files: Vec<(String, u64)>,
    pub total_bytes: u64,
    pub destination: PathBuf,
}

#[derive(Debug, Clone)]
pub enum Decision {
    Accept,
    Reject(String),
}

#[async_trait]
pub trait Approver: Send + Sync {
    async fn approve(&self, req: &IncomingRequest) -> Decision;
}

/// Safe default: nothing is accepted unless the application installs a real approver.
pub struct RejectAll;
#[async_trait]
impl Approver for RejectAll {
    async fn approve(&self, _: &IncomingRequest) -> Decision {
        Decision::Reject("receiver is not accepting transfers".into())
    }
}

pub struct Node {
    pub(crate) identity: Identity,
    pub(crate) store: Arc<Store>,
    pub(crate) pairing: PairingManager,
    pub(crate) approver: Arc<dyn Approver>,
    pub(crate) limits: Limits,
    pub(crate) approval_sem: Semaphore,
    events: broadcast::Sender<TransferProgress>,
    incoming: Mutex<HashMap<String, IncomingEntry>>,
    serial: AtomicU64,
    pub(crate) claim_seq: AtomicU64,
    pub(crate) seen: Mutex<HashMap<String, u64>>,
    pub(crate) outbox: Mutex<HashMap<String, Vec<crate::outbox::OutItem>>>,
}

struct IncomingEntry {
    serial: u64,
    fp: String,
    token: CancellationToken,
    user: Arc<AtomicBool>,
}

/// Registration of an in-flight incoming transfer so it can be cancelled by the local user.
pub(crate) struct IncomingGuard {
    node: Arc<Node>,
    id: String,
    serial: u64,
    pub token: CancellationToken,
    pub user: Arc<AtomicBool>,
}
impl Drop for IncomingGuard {
    fn drop(&mut self) {
        let mut m = self.node.incoming.lock().unwrap();
        if m.get(&self.id).is_some_and(|e| e.serial == self.serial) {
            m.remove(&self.id);
        }
    }
}

pub struct PairingOffer {
    pub uri: String,
    pub expires_in: Duration,
}

pub struct ListenerHandle {
    pub local_addr: SocketAddr,
    shutdown: CancellationToken,
}

impl ListenerHandle {
    pub fn stop(&self) {
        self.shutdown.cancel();
    }
}
impl Drop for ListenerHandle {
    fn drop(&mut self) {
        self.shutdown.cancel();
    }
}

pub(crate) fn clean_name(raw: &str) -> String {
    let s: String = raw.chars().filter(|c| !c.is_control()).take(64).collect();
    let s = s.trim().to_string();
    if s.is_empty() { "Unnamed device".into() } else { s }
}

impl Node {
    pub fn new(identity: Identity, store: Arc<Store>, approver: Arc<dyn Approver>, limits: Limits) -> Arc<Self> {
        let (events, _) = broadcast::channel(512);
        Arc::new(Self {
            approval_sem: Semaphore::new(limits.max_pending_approvals),
            identity,
            store,
            pairing: PairingManager::default(),
            approver,
            limits,
            events,
            incoming: Mutex::new(HashMap::new()),
            serial: AtomicU64::new(1),
            claim_seq: AtomicU64::new(1),
            seen: Mutex::new(HashMap::new()),
            outbox: Mutex::new(HashMap::new()),
        })
    }

    /// Unix seconds when a *paired* device last contacted us (pairing, transfer or check-in). In-memory only.
    pub fn last_seen(&self, fp: &str) -> Option<u64> {
        self.seen.lock().unwrap().get(fp).copied()
    }
    /// Record an authenticated contact. Monotonic: overlapping requests can never move the time backwards.
    pub(crate) fn touch(&self, fp: &str) {
        let now = unix_now();
        let mut m = self.seen.lock().unwrap();
        let e = m.entry(fp.to_string()).or_insert(0);
        if now > *e {
            *e = now;
        }
    }

    /// True only if the paired device authenticated within `limits.presence_timeout`.
    /// Being paired in the past proves nothing about *now*.
    pub fn is_online(&self, fp: &str) -> bool {
        self.last_seen(fp).is_some_and(|t| unix_now().saturating_sub(t) < self.limits.presence_timeout.as_secs().max(1))
    }

    /// Cancel an incoming transfer from this device. The sender is told and partial data is deleted.
    pub fn cancel_incoming(&self, transfer_id: &str) -> bool {
        let m = self.incoming.lock().unwrap();
        match m.get(transfer_id) {
            Some(e) => {
                e.user.store(true, Ordering::SeqCst);
                e.token.cancel();
                true
            }
            None => false,
        }
    }

    /// A reconnect from the same device supersedes its stale connection (so resume is immediate);
    /// a *different* device may not reuse an id that is in flight.
    pub(crate) fn register_incoming(self: &Arc<Self>, id: &str, fp: &str) -> Result<IncomingGuard> {
        let mut m = self.incoming.lock().unwrap();
        if let Some(old) = m.get(id) {
            if old.fp != fp {
                return Err(FluxError::Protocol("transfer id already in use".into()));
            }
            old.token.cancel(); // stale connection of the same peer: let it end quietly
        }
        let serial = self.serial.fetch_add(1, Ordering::SeqCst);
        let (token, user) = (CancellationToken::new(), Arc::new(AtomicBool::new(false)));
        m.insert(id.to_string(), IncomingEntry { serial, fp: fp.to_string(), token: token.clone(), user: user.clone() });
        Ok(IncomingGuard { node: self.clone(), id: id.to_string(), serial, token, user })
    }

    pub fn fingerprint(&self) -> &str {
        self.identity.fingerprint()
    }
    pub fn store(&self) -> &Arc<Store> {
        &self.store
    }
    pub fn subscribe(&self) -> broadcast::Receiver<TransferProgress> {
        self.events.subscribe()
    }
    pub(crate) fn emit(&self, p: TransferProgress) {
        let _ = self.events.send(p);
    }
    pub(crate) fn device_name(&self) -> String {
        clean_name(&self.store.settings().device_name)
    }

    /// Create a short-lived pairing link/QR payload for the given reachable addresses.
    pub fn begin_pairing_with_addrs(&self, addrs: Vec<String>, ttl: Option<Duration>) -> PairingOffer {
        let ttl = ttl.unwrap_or(DEFAULT_TTL);
        let token = self.pairing.create(ttl);
        let uri = PairingPayload { addrs, fingerprint: self.fingerprint().to_string(), token, host_name: self.device_name() }.to_uri();
        PairingOffer { uri, expires_in: ttl }
    }

    pub fn begin_pairing(&self, port: u16) -> PairingOffer {
        self.begin_pairing_with_addrs(crate::discovery::lan_addrs(port), None)
    }

    /// Pair with a host from its pairing link. The host's fingerprint (from the link) is pinned
    /// during the TLS handshake, so an impostor cannot complete pairing even with the right token.
    pub async fn pair_with(&self, uri: &str) -> Result<TrustedDevice> {
        let p = PairingPayload::parse(uri)?;
        let mut last = FluxError::Pairing("no address worked".into());
        for addr in &p.addrs {
            match self.pair_once(addr, &p).await {
                Ok(d) => return Ok(d),
                Err(e) => last = e,
            }
        }
        Err(last)
    }

    async fn pair_once(&self, addr: &str, p: &PairingPayload) -> Result<TrustedDevice> {
        let mut s = tls::connect(&self.identity, addr, &p.fingerprint, self.limits.handshake_timeout).await?;
        write_msg(&mut s, &Msg::Hello {
            proto: PROTO_VERSION,
            device_name: self.device_name(),
            device_type: DeviceType::Desktop,
            purpose: Purpose::Pair,
            token: Some(p.token.clone()),
        }).await?;
        match read_msg(&mut s, self.limits.handshake_timeout).await? {
            Msg::PairOk { device_name, device_type } => {
                let dev = TrustedDevice {
                    fingerprint: p.fingerprint.clone(),
                    name: clean_name(&device_name),
                    device_type,
                    paired_at: unix_now(),
                    last_addr: Some(addr.to_string()),
                };
                self.store.add_trusted(dev.clone())?;
                Ok(dev)
            }
            Msg::Error { message } => Err(FluxError::Pairing(message)),
            _ => Err(FluxError::Protocol("unexpected reply to pairing".into())),
        }
    }

    /// Start accepting connections. Bounded concurrency; every connection has handshake/idle timeouts.
    pub async fn listen(self: &Arc<Self>, bind: SocketAddr) -> Result<ListenerHandle> {
        let listener = TcpListener::bind(bind).await?;
        let local_addr = listener.local_addr()?;
        let acceptor = tls::acceptor(&self.identity)?;
        let shutdown = CancellationToken::new();
        let conn_sem = Arc::new(Semaphore::new(self.limits.max_connections));
        receive::cleanup_stale(self);
        let (node, sd) = (self.clone(), shutdown.clone());
        tokio::spawn(async move {
            loop {
                let (tcp, _peer) = tokio::select! {
                    _ = sd.cancelled() => break,
                    r = listener.accept() => match r { Ok(x) => x, Err(_) => continue },
                };
                let Ok(permit) = conn_sem.clone().try_acquire_owned() else { continue }; // shed load
                let (node, acceptor) = (node.clone(), acceptor.clone());
                tokio::spawn(async move {
                    let _permit = permit;
                    if let Err(e) = node.handle_conn(tcp, acceptor).await {
                        tracing::debug!("connection ended: {e}");
                    }
                });
            }
        });
        Ok(ListenerHandle { local_addr, shutdown })
    }

    async fn handle_conn(self: Arc<Self>, tcp: TcpStream, acceptor: tokio_rustls::TlsAcceptor) -> Result<()> {
        tcp.set_nodelay(true).ok();
        let hs = self.limits.handshake_timeout;
        let mut s = tokio::time::timeout(hs, acceptor.accept(tcp))
            .await
            .map_err(|_| FluxError::Timeout("TLS handshake"))?
            .map_err(|e| FluxError::Tls(e.to_string()))?;
        let fp = tls::client_fingerprint(&s)?;
        let Msg::Hello { proto, device_name, device_type, purpose, token } = read_msg(&mut s, hs).await? else {
            return Err(FluxError::Protocol("expected hello".into()));
        };
        if proto != PROTO_VERSION {
            let _ = write_msg(&mut s, &Msg::Error { message: format!("unsupported protocol version {proto}") }).await;
            return Err(FluxError::Protocol("version mismatch".into()));
        }
        if self.store.get_trusted(&fp).is_some() {
            self.touch(&fp);
        }
        match purpose {
            Purpose::Fetch => {
                let trusted = self.store.get_trusted(&fp);
                let peer = receive::Peer {
                    name: trusted.as_ref().map(|t| t.name.clone()).unwrap_or_else(|| clean_name(&device_name)),
                    trusted: trusted.is_some(),
                    fp,
                };
                crate::outbox::serve_fetch(&self, &mut s, &peer).await;
                Ok(())
            }
            Purpose::Pair => {
                match self.pairing.consume(token.as_deref().unwrap_or("")) {
                    Ok(()) => {
                        let fp_copy = fp.clone();
                        self.store.add_trusted(TrustedDevice {
                            fingerprint: fp,
                            name: clean_name(&device_name),
                            device_type,
                            paired_at: unix_now(),
                            last_addr: None,
                        })?;
                        self.touch(&fp_copy);
                        write_msg(&mut s, &Msg::PairOk { device_name: self.device_name(), device_type: DeviceType::Desktop }).await
                    }
                    Err(e) => {
                        let _ = write_msg(&mut s, &Msg::Error { message: e.to_string() }).await;
                        Err(e)
                    }
                }
            }
            Purpose::Transfer => {
                let trusted = self.store.get_trusted(&fp);
                let peer = receive::Peer {
                    name: trusted.as_ref().map(|t| t.name.clone()).unwrap_or_else(|| clean_name(&device_name)),
                    trusted: trusted.is_some(),
                    fp,
                };
                receive::serve(&self, &mut s, &peer).await;
                Ok(())
            }
        }
    }
}
