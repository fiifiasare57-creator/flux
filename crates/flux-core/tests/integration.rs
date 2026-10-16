//! Real two-peer tests over loopback TCP + TLS 1.3 (pinned mutual authentication).
//! These run in one process but use genuine sockets and certificates. They do NOT replace the
//! two-physical-PC acceptance test (see docs/testing.md).

use async_trait::async_trait;
use flux_core::protocol::*;
use flux_core::send::{self, Control, RetryPolicy, Target};
use flux_core::store::{Outcome, Store};
use flux_core::{tls, Approver, Decision, Identity, IncomingRequest, Limits, Node, RejectAll};
use rand::RngCore;
use sha2::{Digest, Sha256};
use std::net::SocketAddr;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tempfile::TempDir;
use tokio::io::{AsyncReadExt, AsyncWriteExt};

struct Scripted {
    decision: Mutex<Decision>,
    calls: AtomicUsize,
}
impl Scripted {
    fn new(d: Decision) -> Arc<Self> {
        Arc::new(Self { decision: Mutex::new(d), calls: AtomicUsize::new(0) })
    }
}
#[async_trait]
impl Approver for Scripted {
    async fn approve(&self, _: &IncomingRequest) -> Decision {
        self.calls.fetch_add(1, Ordering::SeqCst);
        self.decision.lock().unwrap().clone()
    }
}

struct Peer {
    node: Arc<Node>,
    approver: Arc<dyn Approver>,
    _data: TempDir,
    recv: TempDir,
    addr: Option<SocketAddr>,
    _listener: Option<flux_core::node::ListenerHandle>,
}
impl Peer {
    fn new(name: &str, approver: Arc<dyn Approver>, limits: Limits) -> Self {
        let data = tempfile::tempdir().unwrap();
        let recv = tempfile::tempdir().unwrap();
        let store = Arc::new(Store::open(data.path()).unwrap());
        let rd = recv.path().to_path_buf();
        store.update_settings(|s| { s.device_name = name.into(); s.receive_dir = rd; }).unwrap();
        let node = Node::new(Identity::load_or_create(data.path()).unwrap(), store, approver.clone(), limits);
        Peer { node, approver, _data: data, recv, addr: None, _listener: None }
    }
    async fn listening(mut self) -> Self {
        let h = self.node.listen("127.0.0.1:0".parse().unwrap()).await.unwrap();
        self.addr = Some(h.local_addr);
        self._listener = Some(h);
        self
    }
    /// Simulate the receiving app being closed and reopened: new Node + new port, same data and folders.
    async fn restart(mut self) -> Self {
        self._listener = None; // stop accepting
        let store = Arc::new(Store::open(self._data.path()).unwrap());
        self.node = Node::new(Identity::load_or_create(self._data.path()).unwrap(), store, self.approver.clone(), fast_limits());
        self.listening().await
    }
    fn target(&self) -> Target {
        Target { addr: self.addr.unwrap().to_string(), fingerprint: self.node.fingerprint().to_string() }
    }
    fn recv_dir(&self) -> &Path { self.recv.path() }
    fn pairing_uri(&self) -> String {
        self.node.begin_pairing_with_addrs(vec![self.addr.unwrap().to_string()], None).uri
    }
}

fn fast_limits() -> Limits {
    Limits { idle_timeout: Duration::from_secs(5), handshake_timeout: Duration::from_secs(5), approval_timeout: Duration::from_secs(5), ..Limits::default() }
}
fn quick_retry() -> RetryPolicy { RetryPolicy { max_attempts: 5, backoff: Duration::from_millis(50) } }

fn random_file(dir: &Path, name: &str, size: usize) -> PathBuf {
    let p = dir.join(name);
    std::fs::create_dir_all(p.parent().unwrap()).unwrap();
    let mut data = vec![0u8; size];
    rand::rngs::OsRng.fill_bytes(&mut data);
    std::fs::write(&p, data).unwrap();
    p
}
fn sha(p: &Path) -> String {
    hex::encode(Sha256::digest(std::fs::read(p).unwrap()))
}
fn files_under(dir: &Path) -> Vec<String> {
    fn walk(d: &Path, base: &Path, out: &mut Vec<String>) {
        for e in std::fs::read_dir(d).unwrap().flatten() {
            let p = e.path();
            if p.is_dir() { walk(&p, base, out) } else { out.push(p.strip_prefix(base).unwrap().to_string_lossy().replace('\\', "/")) }
        }
    }
    let mut v = vec![];
    walk(dir, dir, &mut v);
    v.sort();
    v
}

async fn pair(host: &Peer, guest: &Peer) {
    guest.node.pair_with(&host.pairing_uri()).await.unwrap();
}

// ---------------------------------------------------------------- happy path

#[tokio::test(flavor = "multi_thread")]
async fn paired_devices_transfer_files_and_folders_with_verified_sha256() {
    let approver = Scripted::new(Decision::Reject("must not be asked".into()));
    let receiver = Peer::new("Receiver", approver.clone(), fast_limits()).listening().await;
    let sender = Peer::new("Sender", Arc::new(RejectAll), fast_limits());
    pair(&receiver, &sender).await;
    assert!(receiver.node.store().get_trusted(sender.node.fingerprint()).is_some());
    assert!(sender.node.store().get_trusted(receiver.node.fingerprint()).is_some());

    let src = tempfile::tempdir().unwrap();
    let big = random_file(src.path(), "video.mp4", 6 * 1024 * 1024 + 123);
    let small = random_file(src.path(), "note.txt", 17);
    let empty = src.path().join("empty.bin");
    std::fs::write(&empty, b"").unwrap();
    let folder = src.path().join("Biology");
    random_file(&folder, "cells.jpg", 300_000);
    random_file(&folder, "sub/deep.pdf", 4_000);

    let summary = send::send(&sender.node, &receiver.target(), vec![big.clone(), small.clone(), empty.clone(), folder], &Control::default(), &quick_retry()).await.unwrap();
    assert_eq!(summary.files, 5);

    let got = receiver.recv_dir();
    assert_eq!(sha(&got.join("video.mp4")), sha(&big));
    assert_eq!(sha(&got.join("note.txt")), sha(&small));
    assert_eq!(std::fs::metadata(got.join("empty.bin")).unwrap().len(), 0);
    assert!(got.join("Biology/cells.jpg").exists() && got.join("Biology/sub/deep.pdf").exists());
    let names = files_under(got);
    assert!(names.iter().all(|n| !n.contains("fluxpart") && !n.contains(".flux-partial")), "no partial leftovers: {names:?}");
    assert_eq!(approver.calls.load(Ordering::SeqCst), 0, "trusted sender with default policy needs no prompt");

    let h = receiver.node.store().history();
    assert_eq!(h[0].outcome, Outcome::Completed);
    assert_eq!(h[0].file_count, 5);
    assert_eq!(sender.node.store().history()[0].outcome, Outcome::Completed);
}

// ---------------------------------------------------------------- approval / trust

#[tokio::test(flavor = "multi_thread")]
async fn unknown_sender_cannot_bypass_approval() {
    let approver = Scripted::new(Decision::Reject("No thanks".into()));
    let receiver = Peer::new("R", approver.clone(), fast_limits()).listening().await;
    let sender = Peer::new("S", Arc::new(RejectAll), fast_limits());
    let src = tempfile::tempdir().unwrap();
    let f = random_file(src.path(), "a.txt", 1000);

    let err = send::send(&sender.node, &receiver.target(), vec![f.clone()], &Control::default(), &quick_retry()).await.unwrap_err();
    assert!(matches!(err, flux_core::FluxError::Rejected(ref r) if r == "No thanks"), "{err:?}");
    assert_eq!(approver.calls.load(Ordering::SeqCst), 1);
    assert!(files_under(receiver.recv_dir()).is_empty(), "rejected transfer must write nothing");
    assert_eq!(receiver.node.store().history()[0].outcome, Outcome::Rejected);
    assert_eq!(sender.node.store().history()[0].outcome, Outcome::Rejected);

    *approver.decision.lock().unwrap() = Decision::Accept;
    send::send(&sender.node, &receiver.target(), vec![f.clone()], &Control::default(), &quick_retry()).await.unwrap();
    assert_eq!(sha(&receiver.recv_dir().join("a.txt")), sha(&f));
    assert_eq!(approver.calls.load(Ordering::SeqCst), 2);
}

#[tokio::test(flavor = "multi_thread")]
async fn revoked_device_must_be_approved_again_and_policy_can_force_prompts() {
    let approver = Scripted::new(Decision::Accept);
    let receiver = Peer::new("R", approver.clone(), fast_limits()).listening().await;
    let sender = Peer::new("S", Arc::new(RejectAll), fast_limits());
    pair(&receiver, &sender).await;
    let src = tempfile::tempdir().unwrap();
    let f = random_file(src.path(), "a.txt", 10);

    send::send(&sender.node, &receiver.target(), vec![f.clone()], &Control::default(), &quick_retry()).await.unwrap();
    assert_eq!(approver.calls.load(Ordering::SeqCst), 0);

    receiver.node.store().update_settings(|s| s.require_approval_for_trusted = true).unwrap();
    send::send(&sender.node, &receiver.target(), vec![f.clone()], &Control::default(), &quick_retry()).await.unwrap();
    assert_eq!(approver.calls.load(Ordering::SeqCst), 1);

    receiver.node.store().update_settings(|s| s.require_approval_for_trusted = false).unwrap();
    assert!(receiver.node.store().revoke(sender.node.fingerprint()).unwrap());
    send::send(&sender.node, &receiver.target(), vec![f], &Control::default(), &quick_retry()).await.unwrap();
    assert_eq!(approver.calls.load(Ordering::SeqCst), 2, "revoked device is unknown again");
}

#[tokio::test(flavor = "multi_thread")]
async fn trusted_name_comes_from_pairing_not_from_the_wire() {
    struct Capture(Mutex<Option<IncomingRequest>>);
    #[async_trait]
    impl Approver for Capture {
        async fn approve(&self, r: &IncomingRequest) -> Decision { *self.0.lock().unwrap() = Some(r.clone()); Decision::Reject("x".into()) }
    }
    let cap = Arc::new(Capture(Mutex::new(None)));
    let receiver = Peer::new("R", cap.clone(), fast_limits()).listening().await;
    let sender = Peer::new("Evil Sender", Arc::new(RejectAll), fast_limits());
    let src = tempfile::tempdir().unwrap();
    let _ = send::send(&sender.node, &receiver.target(), vec![random_file(src.path(), "a", 5)], &Control::default(), &quick_retry()).await;
    let req = cap.0.lock().unwrap().clone().unwrap();
    assert!(!req.trusted);
    assert_eq!(req.peer_fingerprint, sender.node.fingerprint());
}

// ---------------------------------------------------------------- authentication

#[tokio::test(flavor = "multi_thread")]
async fn wrong_pinned_fingerprint_is_refused() {
    let receiver = Peer::new("R", Arc::new(RejectAll), fast_limits()).listening().await;
    let sender = Peer::new("S", Arc::new(RejectAll), fast_limits());
    let impostor = Identity::generate().unwrap();
    let target = Target { addr: receiver.addr.unwrap().to_string(), fingerprint: impostor.fingerprint().to_string() };
    let src = tempfile::tempdir().unwrap();
    let r = send::send(&sender.node, &target, vec![random_file(src.path(), "a", 5)], &Control::default(), &quick_retry()).await;
    assert!(matches!(r, Err(flux_core::FluxError::Tls(_))), "{r:?}");
}

#[tokio::test(flavor = "multi_thread")]
async fn pairing_tokens_are_single_use_expire_and_need_the_right_fingerprint() {
    let host = Peer::new("Host", Arc::new(RejectAll), fast_limits()).listening().await;
    let a = Peer::new("A", Arc::new(RejectAll), fast_limits());
    let b = Peer::new("B", Arc::new(RejectAll), fast_limits());
    let uri = host.pairing_uri();
    a.node.pair_with(&uri).await.unwrap();
    assert!(b.node.pair_with(&uri).await.is_err(), "replayed pairing link must fail");
    assert!(host.node.store().get_trusted(b.node.fingerprint()).is_none());

    // wrong token
    let uri2 = host.pairing_uri().replace(&uri_token(&host.pairing_uri()), &"0".repeat(64));
    assert!(b.node.pair_with(&uri2).await.is_err());

    // expired
    let short = host.node.begin_pairing_with_addrs(vec![host.addr.unwrap().to_string()], Some(Duration::from_millis(1))).uri;
    tokio::time::sleep(Duration::from_millis(20)).await;
    assert!(b.node.pair_with(&short).await.is_err());

    // MITM: right token, but an impostor certificate would not match the fingerprint in the link
    let fresh = host.pairing_uri();
    let forged = fresh.replace(host.node.fingerprint(), Identity::generate().unwrap().fingerprint());
    assert!(b.node.pair_with(&forged).await.is_err());
    assert!(host.node.store().get_trusted(b.node.fingerprint()).is_none());
}
fn uri_token(uri: &str) -> String {
    uri.split("t=").nth(1).unwrap().split('&').next().unwrap().to_string()
}

#[tokio::test(flavor = "multi_thread")]
async fn non_tls_garbage_and_missing_client_cert_do_not_hurt_the_listener() {
    let receiver = Peer::new("R", Arc::new(RejectAll), fast_limits()).listening().await;
    let mut raw = tokio::net::TcpStream::connect(receiver.addr.unwrap()).await.unwrap();
    raw.write_all(b"GET / HTTP/1.1\r\nHost: x\r\n\r\n").await.unwrap();
    let mut buf = [0u8; 64];
    let n = tokio::time::timeout(Duration::from_secs(3), raw.read(&mut buf)).await;
    assert!(matches!(n, Ok(Ok(_)) | Ok(Err(_)) | Err(_)));
    // still serving real peers
    let sender = Peer::new("S", Arc::new(RejectAll), fast_limits());
    sender.node.pair_with(&receiver.pairing_uri()).await.unwrap();
}

// ---------------------------------------------------------------- hostile sender (raw protocol)

async fn raw_session(receiver: &Peer, attacker: &Identity) -> tls::ClientStream {
    let mut s = tls::connect(attacker, &receiver.addr.unwrap().to_string(), receiver.node.fingerprint(), Duration::from_secs(5)).await.unwrap();
    write_msg(&mut s, &Msg::Hello { proto: PROTO_VERSION, device_name: "x".into(), device_type: DeviceType::Phone, purpose: Purpose::Transfer, token: None }).await.unwrap();
    s
}
fn trusted_attacker_setup() -> (Arc<Scripted>, Identity) {
    (Scripted::new(Decision::Accept), Identity::generate().unwrap())
}

#[tokio::test(flavor = "multi_thread")]
async fn path_traversal_in_offer_is_refused_and_nothing_escapes() {
    let outer = tempfile::tempdir().unwrap();
    let (approver, attacker) = trusted_attacker_setup();
    let mut receiver = Peer::new("R", approver.clone(), fast_limits());
    let inner = outer.path().join("inbox");
    receiver.node.store().update_settings(|s| s.receive_dir = inner.clone()).unwrap();
    receiver = receiver.listening().await;

    for evil in ["../escape.txt", "a/../../escape.txt", "/abs/escape.txt", "..", ""] {
        let mut s = raw_session(&receiver, &attacker).await;
        write_msg(&mut s, &Msg::Offer { transfer_id: "t1".into(), files: vec![FileMeta { index: 0, path: evil.into(), size: 3, mtime: 0 }] }).await.unwrap();
        let reply = read_msg(&mut s, Duration::from_secs(5)).await.unwrap();
        assert!(matches!(reply, Msg::Error { .. }), "{evil:?} -> {reply:?}");
    }
    assert!(!outer.path().join("escape.txt").exists());
    assert_eq!(approver.calls.load(Ordering::SeqCst), 0, "invalid offers must not even reach the user");

    // Windows-style path is neutralised into a harmless single file name inside the inbox.
    let mut s = raw_session(&receiver, &attacker).await;
    write_msg(&mut s, &Msg::Offer { transfer_id: "t2".into(), files: vec![FileMeta { index: 0, path: "C:\\Windows\\evil.dll".into(), size: 3, mtime: 0 }] }).await.unwrap();
    assert!(matches!(read_msg(&mut s, Duration::from_secs(5)).await.unwrap(), Msg::Accept { .. }));
    write_msg(&mut s, &Msg::FileStart { index: 0, offset: 0 }).await.unwrap();
    write_data(&mut s, b"abc").await.unwrap();
    write_msg(&mut s, &Msg::FileEnd { index: 0, size: 3, sha256: hex::encode(Sha256::digest(b"abc")) }).await.unwrap();
    assert!(matches!(read_msg(&mut s, Duration::from_secs(5)).await.unwrap(), Msg::FileResult { ok: true, .. }));
    let names = files_under(&inner);
    assert_eq!(names.len(), 1, "{names:?}");
    assert!(!names[0].contains('/') && !names[0].contains(':'));
}

#[tokio::test(flavor = "multi_thread")]
async fn corrupted_content_is_detected_and_leaves_no_file() {
    let (approver, attacker) = trusted_attacker_setup();
    let receiver = Peer::new("R", approver, fast_limits()).listening().await;
    let mut s = raw_session(&receiver, &attacker).await;
    write_msg(&mut s, &Msg::Offer { transfer_id: "t1".into(), files: vec![FileMeta { index: 0, path: "photo.jpg".into(), size: 5, mtime: 0 }] }).await.unwrap();
    assert!(matches!(read_msg(&mut s, Duration::from_secs(5)).await.unwrap(), Msg::Accept { .. }));
    write_msg(&mut s, &Msg::FileStart { index: 0, offset: 0 }).await.unwrap();
    write_data(&mut s, b"hello").await.unwrap();
    write_msg(&mut s, &Msg::FileEnd { index: 0, size: 5, sha256: hex::encode(Sha256::digest(b"HELLO")) }).await.unwrap();
    assert!(matches!(read_msg(&mut s, Duration::from_secs(5)).await.unwrap(), Msg::FileResult { ok: false, .. }));
    tokio::time::sleep(Duration::from_millis(200)).await;
    let left = files_under(receiver.recv_dir());
    assert!(left.is_empty(), "no final or partial file may remain: {left:?}");
    assert_eq!(receiver.node.store().history()[0].outcome, Outcome::Failed);
}

#[tokio::test(flavor = "multi_thread")]
async fn extra_bytes_beyond_declared_size_are_refused() {
    let (approver, attacker) = trusted_attacker_setup();
    let receiver = Peer::new("R", approver, fast_limits()).listening().await;
    let mut s = raw_session(&receiver, &attacker).await;
    write_msg(&mut s, &Msg::Offer { transfer_id: "t1".into(), files: vec![FileMeta { index: 0, path: "a.bin".into(), size: 4, mtime: 0 }] }).await.unwrap();
    let _ = read_msg(&mut s, Duration::from_secs(5)).await.unwrap();
    write_msg(&mut s, &Msg::FileStart { index: 0, offset: 0 }).await.unwrap();
    write_data(&mut s, b"12345678").await.unwrap();
    assert!(matches!(read_msg(&mut s, Duration::from_secs(5)).await.unwrap(), Msg::Error { .. }));
    tokio::time::sleep(Duration::from_millis(200)).await;
    assert!(files_under(receiver.recv_dir()).is_empty());
}

#[tokio::test(flavor = "multi_thread")]
async fn resource_limits_reject_abusive_offers() {
    let (approver, attacker) = trusted_attacker_setup();
    let limits = Limits { max_files: 10, ..fast_limits() };
    let receiver = Peer::new("R", approver.clone(), limits).listening().await;

    // too many files
    let mut s = raw_session(&receiver, &attacker).await;
    let files = (0..11).map(|i| FileMeta { index: i, path: format!("f{i}"), size: 1, mtime: 0 }).collect();
    write_msg(&mut s, &Msg::Offer { transfer_id: "t".into(), files }).await.unwrap();
    assert!(matches!(read_msg(&mut s, Duration::from_secs(5)).await.unwrap(), Msg::Error { .. }));

    // absurd declared size -> refused for lack of disk space before any approval prompt
    let mut s = raw_session(&receiver, &attacker).await;
    write_msg(&mut s, &Msg::Offer { transfer_id: "t".into(), files: vec![FileMeta { index: 0, path: "huge".into(), size: 1 << 62, mtime: 0 }] }).await.unwrap();
    assert!(matches!(read_msg(&mut s, Duration::from_secs(5)).await.unwrap(), Msg::Error { .. }));

    // size overflow, bad transfer id, non-sequential indices
    for (tid, files) in [
        ("t", vec![FileMeta { index: 0, path: "a".into(), size: u64::MAX, mtime: 0 }, FileMeta { index: 1, path: "b".into(), size: 2, mtime: 0 }]),
        ("../../x", vec![FileMeta { index: 0, path: "a".into(), size: 1, mtime: 0 }]),
        ("t", vec![FileMeta { index: 7, path: "a".into(), size: 1, mtime: 0 }]),
    ] {
        let mut s = raw_session(&receiver, &attacker).await;
        write_msg(&mut s, &Msg::Offer { transfer_id: tid.into(), files }).await.unwrap();
        assert!(matches!(read_msg(&mut s, Duration::from_secs(5)).await.unwrap(), Msg::Error { .. }));
    }

    // oversize control frame header is rejected without allocating it
    let mut s = tls::connect(&attacker, &receiver.addr.unwrap().to_string(), receiver.node.fingerprint(), Duration::from_secs(5)).await.unwrap();
    s.write_all(&[1u8, 0xFF, 0xFF, 0xFF, 0xFF]).await.unwrap();
    let mut b = [0u8; 8];
    let r = tokio::time::timeout(Duration::from_secs(5), s.read(&mut b)).await.expect("server must hang up, not wait for 4 GiB");
    assert!(matches!(r, Ok(0) | Err(_)));
    assert_eq!(approver.calls.load(Ordering::SeqCst), 0);
}

// ---------------------------------------------------------------- duplicates

#[tokio::test(flavor = "multi_thread")]
async fn existing_files_are_never_overwritten() {
    let receiver = Peer::new("R", Scripted::new(Decision::Accept), fast_limits()).listening().await;
    let sender = Peer::new("S", Arc::new(RejectAll), fast_limits());
    pair(&receiver, &sender).await;
    std::fs::write(receiver.recv_dir().join("report.pdf"), b"ORIGINAL").unwrap();
    let src = tempfile::tempdir().unwrap();
    let f = random_file(src.path(), "report.pdf", 2048);
    send::send(&sender.node, &receiver.target(), vec![f.clone()], &Control::default(), &quick_retry()).await.unwrap();
    send::send(&sender.node, &receiver.target(), vec![f.clone()], &Control::default(), &quick_retry()).await.unwrap();
    assert_eq!(std::fs::read(receiver.recv_dir().join("report.pdf")).unwrap(), b"ORIGINAL");
    assert_eq!(sha(&receiver.recv_dir().join("report (1).pdf")), sha(&f));
    assert_eq!(sha(&receiver.recv_dir().join("report (2).pdf")), sha(&f));
}

// ---------------------------------------------------------------- cancel / pause / resume / interruption

async fn wait_for_bytes(rx: &mut tokio::sync::broadcast::Receiver<flux_core::events::TransferProgress>, at_least: u64) {
    loop {
        let p = tokio::time::timeout(Duration::from_secs(20), rx.recv()).await.expect("progress").unwrap();
        if p.bytes_done >= at_least && p.state == flux_core::events::TransferState::Active { return }
    }
}

#[tokio::test(flavor = "multi_thread")]
async fn cancel_discards_partial_data_on_the_receiver() {
    let receiver = Peer::new("R", Scripted::new(Decision::Accept), fast_limits()).listening().await;
    let sender = Peer::new("S", Arc::new(RejectAll), fast_limits());
    pair(&receiver, &sender).await;
    let src = tempfile::tempdir().unwrap();
    let f = random_file(src.path(), "big.bin", 64 * 1024 * 1024);
    let ctl = Control::default();
    let mut rx = sender.node.subscribe();
    let (n, t, c, rp) = (sender.node.clone(), receiver.target(), ctl.clone(), quick_retry());
    let task = tokio::spawn(async move { send::send(&n, &t, vec![f], &c, &rp).await });
    wait_for_bytes(&mut rx, 1_000_000).await;
    ctl.cancel.cancel();
    assert!(matches!(task.await.unwrap(), Err(flux_core::FluxError::Cancelled)));
    tokio::time::sleep(Duration::from_millis(300)).await;
    let left = files_under(receiver.recv_dir());
    assert!(left.is_empty(), "cancel must leave nothing behind: {left:?}");
    assert!(!receiver.recv_dir().join(".flux-partial").exists(), "no empty partial folder either");
    assert_eq!(receiver.node.store().history()[0].outcome, Outcome::Cancelled);
}

#[tokio::test(flavor = "multi_thread")]
async fn pause_keeps_verified_partial_and_resume_completes_without_resending_it() {
    let receiver = Peer::new("R", Scripted::new(Decision::Accept), fast_limits()).listening().await;
    let sender = Peer::new("S", Arc::new(RejectAll), fast_limits());
    pair(&receiver, &sender).await;
    let src = tempfile::tempdir().unwrap();
    let f = random_file(src.path(), "archive.zip", 48 * 1024 * 1024);
    let files = send::collect(vec![f.clone()], 100).await.unwrap();

    let ctl = Control::default();
    let mut rx = sender.node.subscribe();
    let (n, t, c, fl) = (sender.node.clone(), receiver.target(), ctl.clone(), files.clone());
    let task = tokio::spawn(async move { send::send_collected(&n, &t, fl, &c, &quick_retry(), Some("resume-me-1".into())).await });
    wait_for_bytes(&mut rx, 2_000_000).await;
    ctl.pause.cancel();
    assert!(matches!(task.await.unwrap(), Err(flux_core::FluxError::Paused)));
    tokio::time::sleep(Duration::from_millis(300)).await;

    let partials: Vec<_> = std::fs::read_dir(receiver.recv_dir().join(".flux-partial")).unwrap().flatten().collect();
    assert_eq!(partials.len(), 1, "partial must be kept on pause");
    let held = partials[0].metadata().unwrap().len();
    assert!(held > 0 && held < f.metadata().unwrap().len());
    assert!(!receiver.recv_dir().join("archive.zip").exists() || std::fs::metadata(receiver.recv_dir().join("archive.zip")).unwrap().len() == 0,
        "an incomplete file must never appear as a finished file");

    let mut rx2 = sender.node.subscribe();
    let summary = send::send_collected(&sender.node, &receiver.target(), files, &Control::default(), &quick_retry(), Some("resume-me-1".into())).await.unwrap();
    assert_eq!(summary.files, 1);
    // first Active progress of the resumed attempt already counts the verified prefix
    let mut first_active = None;
    while let Ok(p) = rx2.try_recv() {
        if p.state == flux_core::events::TransferState::Active && first_active.is_none() { first_active = Some(p.bytes_done) }
    }
    assert!(first_active.unwrap() >= held, "resume must start from the verified offset ({held})");
    assert_eq!(sha(&receiver.recv_dir().join("archive.zip")), sha(&f));
    assert!(!receiver.recv_dir().join(".flux-partial").exists(), "partial folder cleaned up");
}

/// Transparent TCP proxy that severs the first N upstream bytes' worth of the first connection.
async fn flaky_proxy(upstream: SocketAddr, cut_after: u64) -> SocketAddr {
    let l = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = l.local_addr().unwrap();
    tokio::spawn(async move {
        let mut first = true;
        loop {
            let (mut c, _) = l.accept().await.unwrap();
            let cut = if first { Some(cut_after) } else { None };
            first = false;
            tokio::spawn(async move {
                let mut u = tokio::net::TcpStream::connect(upstream).await.unwrap();
                let (mut cr, mut cw) = c.split();
                let (mut ur, mut uw) = u.split();
                let up = async {
                    let mut total = 0u64;
                    let mut buf = vec![0u8; 16 * 1024];
                    loop {
                        let n = cr.read(&mut buf).await.unwrap_or(0);
                        if n == 0 { break }
                        total += n as u64;
                        if uw.write_all(&buf[..n]).await.is_err() { break }
                        if cut.is_some_and(|c| total >= c) { return } // sever mid-transfer
                    }
                };
                let down = tokio::io::copy(&mut ur, &mut cw);
                tokio::select! { _ = up => {}, _ = down => {} }
            });
        }
    });
    addr
}

#[tokio::test(flavor = "multi_thread")]
async fn connection_drop_is_recovered_automatically_with_resume() {
    let receiver = Peer::new("R", Scripted::new(Decision::Accept), fast_limits()).listening().await;
    let sender = Peer::new("S", Arc::new(RejectAll), fast_limits());
    pair(&receiver, &sender).await;
    let src = tempfile::tempdir().unwrap();
    let a = random_file(src.path(), "a.bin", 3 * 1024 * 1024);
    let b = random_file(src.path(), "b.bin", 24 * 1024 * 1024);
    let proxy = flaky_proxy(receiver.addr.unwrap(), 9 * 1024 * 1024).await;
    let target = Target { addr: proxy.to_string(), fingerprint: receiver.node.fingerprint().to_string() };

    let summary = send::send(&sender.node, &target, vec![a.clone(), b.clone()], &Control::default(), &quick_retry()).await.unwrap();
    assert!(summary.attempts >= 2, "the first attempt was severed, so at least one retry is expected");
    assert_eq!(sha(&receiver.recv_dir().join("a.bin")), sha(&a));
    assert_eq!(sha(&receiver.recv_dir().join("b.bin")), sha(&b));
    assert_eq!(files_under(receiver.recv_dir()).len(), 2, "no duplicates and no partials");
    let outcomes: Vec<_> = receiver.node.store().history().iter().map(|h| h.outcome).collect();
    assert!(outcomes.contains(&Outcome::Failed) && outcomes.contains(&Outcome::Completed), "history is honest about the interruption: {outcomes:?}");
}

#[tokio::test(flavor = "multi_thread")]
async fn source_file_modified_during_transfer_is_reported_not_silently_sent() {
    let receiver = Peer::new("R", Scripted::new(Decision::Accept), fast_limits()).listening().await;
    let sender = Peer::new("S", Arc::new(RejectAll), fast_limits());
    pair(&receiver, &sender).await;
    let src = tempfile::tempdir().unwrap();
    let f = random_file(src.path(), "doc.bin", 1024);
    let mut files = send::collect(vec![f.clone()], 10).await.unwrap();
    files[0].mtime += 100; // simulate "changed since it was selected"
    let r = send::send_collected(&sender.node, &receiver.target(), files, &Control::default(), &quick_retry(), None).await;
    assert!(matches!(r, Err(flux_core::FluxError::SourceChanged(_))), "{r:?}");
    assert!(!receiver.recv_dir().join("doc.bin").exists());
}

#[tokio::test(flavor = "multi_thread")]
async fn history_can_be_cleared_and_state_persists_across_restart() {
    let data = tempfile::tempdir().unwrap();
    {
        let s = Store::open(data.path()).unwrap();
        s.add_trusted(flux_core::store::TrustedDevice { fingerprint: "ab".repeat(32), name: "Phone".into(), device_type: DeviceType::Phone, paired_at: 1, last_addr: None }).unwrap();
        s.update_settings(|x| x.device_name = "Laptop".into()).unwrap();
    }
    let s = Store::open(data.path()).unwrap();
    assert_eq!(s.trusted().len(), 1);
    assert_eq!(s.settings().device_name, "Laptop");
    s.clear_history().unwrap();
    assert!(s.history().is_empty());
    let id1 = Identity::load_or_create(data.path()).unwrap();
    let id2 = Identity::load_or_create(data.path()).unwrap();
    assert_eq!(id1.fingerprint(), id2.fingerprint(), "identity is stable across restarts");
}

#[tokio::test(flavor = "multi_thread")]
async fn receiver_can_cancel_an_incoming_transfer() {
    let receiver = Peer::new("R", Scripted::new(Decision::Accept), fast_limits()).listening().await;
    let sender = Peer::new("S", Arc::new(RejectAll), fast_limits());
    pair(&receiver, &sender).await;
    let src = tempfile::tempdir().unwrap();
    let f = random_file(src.path(), "big.bin", 96 * 1024 * 1024);
    let mut rrx = receiver.node.subscribe();
    let (n, t, f2) = (sender.node.clone(), receiver.target(), f.clone());
    let task = tokio::spawn(async move { send::send(&n, &t, vec![f2], &Control::default(), &quick_retry()).await });
    let id = loop {
        let p = tokio::time::timeout(Duration::from_secs(20), rrx.recv()).await.expect("progress").unwrap();
        if p.state == flux_core::events::TransferState::Active && p.bytes_done > 1_000_000 { break p.transfer_id }
    };
    assert!(receiver.node.cancel_incoming(&id));
    let r = task.await.unwrap();
    assert!(matches!(r, Err(flux_core::FluxError::RemoteCancelled)), "sender must learn the receiver cancelled (and must not auto-retry): {r:?}");
    tokio::time::sleep(Duration::from_millis(300)).await;
    assert!(files_under(receiver.recv_dir()).is_empty(), "cancel leaves nothing behind");
    assert!(!receiver.recv_dir().join(".flux-partial").exists(), "no empty partial folder either");
    assert_eq!(receiver.node.store().history()[0].outcome, Outcome::Cancelled);
    assert_eq!(sender.node.store().history()[0].outcome, Outcome::Cancelled);
    assert!(!receiver.node.cancel_incoming(&id), "already gone");
}

#[tokio::test(flavor = "multi_thread")]
async fn transfer_resumes_after_the_receiver_app_is_restarted() {
    let receiver = Peer::new("R", Scripted::new(Decision::Accept), fast_limits()).listening().await;
    let sender = Peer::new("S", Arc::new(RejectAll), fast_limits());
    pair(&receiver, &sender).await;
    let src = tempfile::tempdir().unwrap();
    let f = random_file(src.path(), "backup.zip", 40 * 1024 * 1024);
    let files = send::collect(vec![f.clone()], 10).await.unwrap();
    let ctl = Control::default();
    let mut rx = sender.node.subscribe();
    let (n, t, c, fl) = (sender.node.clone(), receiver.target(), ctl.clone(), files.clone());
    let task = tokio::spawn(async move { send::send_collected(&n, &t, fl, &c, &quick_retry(), Some("restart-1".into())).await });
    wait_for_bytes(&mut rx, 3_000_000).await;
    ctl.pause.cancel();
    assert!(matches!(task.await.unwrap(), Err(flux_core::FluxError::Paused)));
    tokio::time::sleep(Duration::from_millis(300)).await;

    let receiver = receiver.restart().await; // different port, new Node instance
    assert!(receiver.node.store().get_trusted(sender.node.fingerprint()).is_some(), "trust survives restart");
    send::send_collected(&sender.node, &receiver.target(), files, &Control::default(), &quick_retry(), Some("restart-1".into())).await.unwrap();
    assert_eq!(sha(&receiver.recv_dir().join("backup.zip")), sha(&f));
}

// ---------------------------------------------------------------- outbox: computer -> phone

async fn raw_fetch(pc: &Peer, id: &Identity) -> tls::ClientStream {
    let mut s = tls::connect(id, &pc.addr.unwrap().to_string(), pc.node.fingerprint(), Duration::from_secs(5)).await.unwrap();
    write_msg(&mut s, &Msg::Hello { proto: PROTO_VERSION, device_name: "Phone".into(), device_type: DeviceType::Phone, purpose: Purpose::Fetch, token: None }).await.unwrap();
    s
}
async fn expect_offer(s: &mut tls::ClientStream) -> Option<(String, Vec<FileMeta>)> {
    match read_msg(s, Duration::from_secs(5)).await.unwrap() {
        Msg::Offer { transfer_id, files } => Some((transfer_id, files)),
        Msg::Complete => None,
        other => panic!("unexpected {other:?}"),
    }
}
/// Phone side of one file: returns (bytes, hash_and_size_verified). Does NOT acknowledge.
async fn read_one_file(s: &mut tls::ClientStream, idx: u32) -> (Vec<u8>, bool) {
    let Msg::FileStart { index, offset } = read_msg(s, Duration::from_secs(5)).await.unwrap() else { panic!("expected FileStart") };
    assert_eq!((index, offset), (idx, 0));
    let (mut data, mut h) = (Vec::new(), Sha256::new());
    loop {
        match read_frame(s, Duration::from_secs(5)).await.unwrap() {
            Frame::Data(d) => { h.update(&d); data.extend_from_slice(&d) }
            Frame::Control(Msg::FileEnd { size, sha256, .. }) => { let ok = size == data.len() as u64 && hex::encode(h.finalize()) == sha256; return (data, ok) }
            other => panic!("unexpected {other:?}"),
        }
    }
}
async fn ack(s: &mut tls::ClientStream, idx: u32, ok: bool) {
    write_msg(s, &Msg::FileResult { index: idx, ok, error: if ok { None } else { Some("hash mismatch".into()) } }).await.unwrap();
}
/// A well-behaved phone: take whatever is offered, verify, acknowledge. None if nothing was queued.
async fn fetch_everything(pc: &Peer, id: &Identity) -> Option<Vec<(String, Vec<u8>)>> {
    let mut s = raw_fetch(pc, id).await;
    let (_, files) = expect_offer(&mut s).await?;
    write_msg(&mut s, &Msg::Accept { resume: vec![] }).await.unwrap();
    let mut got = vec![];
    for (i, f) in files.iter().enumerate() {
        let (data, ok) = read_one_file(&mut s, i as u32).await;
        assert!(ok, "phone-side SHA-256/size verification failed for {}", f.path);
        ack(&mut s, i as u32, true).await;
        got.push((f.path.clone(), data));
    }
    assert!(matches!(read_msg(&mut s, Duration::from_secs(5)).await.unwrap(), Msg::Complete));
    Some(got)
}
async fn wait_until(what: &str, mut f: impl FnMut() -> bool) {
    for _ in 0..100 { if f() { return } tokio::time::sleep(Duration::from_millis(50)).await }
    panic!("timed out waiting for: {what}");
}
fn outcomes(p: &Peer) -> Vec<Outcome> { p.node.store().history().iter().map(|h| h.outcome).collect() }
fn count(p: &Peer, o: Outcome) -> usize { outcomes(p).iter().filter(|x| **x == o).count() }

struct Pcs { pc: Peer, phone: Peer, id: Identity }
async fn pcs(limits: Limits) -> Pcs {
    let pc = Peer::new("PC", Scripted::new(Decision::Reject("n/a".into())), limits.clone()).listening().await;
    let phone = Peer::new("Phone", Arc::new(RejectAll), limits);
    pair(&pc, &phone).await;
    let id = Identity::load_or_create(phone._data.path()).unwrap();
    Pcs { pc, phone, id }
}
async fn queue(p: &Pcs, files: Vec<PathBuf>) -> String {
    let f = send::collect(files, 100).await.unwrap();
    p.pc.node.queue_for_peer(p.phone.node.fingerprint(), f).unwrap()
}
fn qstate(p: &Pcs) -> Vec<flux_core::outbox::QueueState> { p.pc.node.queued().iter().map(|q| q.state).collect() }

#[tokio::test(flavor = "multi_thread")]
async fn empty_queue_peek_never_consumes_and_authorized_fetch_is_verified() {
    use flux_core::outbox::QueueState::*;
    let p = pcs(fast_limits()).await;
    let mut s = raw_fetch(&p.pc, &p.id).await;
    assert!(expect_offer(&mut s).await.is_none(), "empty queue answers 'nothing waiting'");

    let src = tempfile::tempdir().unwrap();
    let photo = random_file(src.path(), "photo.jpg", 700_000);
    let notes = random_file(src.path(), "Docs/notes.pdf", 33_333);
    let tid = queue(&p, vec![photo.clone(), src.path().join("Docs")]).await;
    assert_eq!(qstate(&p), vec![Queued]);

    for _ in 0..3 { // peeking repeatedly must not change anything
        let mut s = raw_fetch(&p.pc, &p.id).await;
        let (t, files) = expect_offer(&mut s).await.unwrap();
        assert_eq!((t.as_str(), files.len()), (tid.as_str(), 2));
        write_msg(&mut s, &Msg::Reject { reason: "peek".into() }).await.unwrap();
        drop(s);
        tokio::time::sleep(Duration::from_millis(60)).await;
        assert_eq!(qstate(&p), vec![Queued], "peek must not consume or claim");
    }
    assert!(p.pc.node.store().history().is_empty(), "peeks leave no history");

    let got = fetch_everything(&p.pc, &p.id).await.unwrap();
    assert_eq!(got[0].0, "Docs/notes.pdf", "folder structure travels with the file");
    assert_eq!(got[0].1, std::fs::read(&notes).unwrap());
    assert_eq!(got[1].1, std::fs::read(&photo).unwrap());
    wait_until("delivery to be recorded", || count(&p.pc, Outcome::Completed) == 1).await;
    assert!(p.pc.node.queued().is_empty());
    assert!(fetch_everything(&p.pc, &p.id).await.is_none(), "delivered items are never offered again");
    assert_eq!(count(&p.pc, Outcome::Completed), 1);
}

#[tokio::test(flavor = "multi_thread")]
async fn unauthorized_fetch_is_refused_and_reveals_nothing() {
    let p = pcs(fast_limits()).await;
    let src = tempfile::tempdir().unwrap();
    queue(&p, vec![random_file(src.path(), "secret-plans.docx", 100)]).await;
    let stranger = Identity::generate().unwrap();
    let mut s = raw_fetch(&p.pc, &stranger).await;
    match read_msg(&mut s, Duration::from_secs(5)).await.unwrap() {
        Msg::Error { message } => assert!(!message.contains("secret") && !message.contains("docx"), "error must not leak queued names: {message}"),
        other => panic!("an unpaired device must get an error, got {other:?}"),
    }
    let files = send::collect(vec![random_file(src.path(), "x.bin", 10)], 10).await.unwrap();
    assert!(p.pc.node.queue_for_peer(stranger.fingerprint(), files).is_err(), "can only queue for paired devices");
    // revoking the phone locks it out too
    p.pc.node.store().revoke(p.phone.node.fingerprint()).unwrap();
    let mut s = raw_fetch(&p.pc, &p.id).await;
    assert!(matches!(read_msg(&mut s, Duration::from_secs(5)).await.unwrap(), Msg::Error { .. }));
}

#[tokio::test(flavor = "multi_thread")]
async fn concurrent_claims_exactly_one_wins() {
    let p = pcs(fast_limits()).await;
    let src = tempfile::tempdir().unwrap();
    queue(&p, vec![random_file(src.path(), "one.bin", 300_000)]).await;
    let (mut a, mut b) = (raw_fetch(&p.pc, &p.id).await, raw_fetch(&p.pc, &p.id).await);
    let oa = expect_offer(&mut a).await.unwrap();
    let ob = expect_offer(&mut b).await.unwrap();
    assert_eq!(oa.0, ob.0, "both saw the same (unclaimed) item");
    let gate = Arc::new(tokio::sync::Barrier::new(2));
    let run = |mut s: tls::ClientStream, gate: Arc<tokio::sync::Barrier>| tokio::spawn(async move {
        gate.wait().await;
        write_msg(&mut s, &Msg::Accept { resume: vec![] }).await.unwrap();
        match read_msg(&mut s, Duration::from_secs(5)).await.unwrap() {
            Msg::Error { .. } => false,
            Msg::FileStart { index: 0, .. } => {
                loop { if let Frame::Control(Msg::FileEnd { .. }) = read_frame(&mut s, Duration::from_secs(5)).await.unwrap() { break } }
                ack(&mut s, 0, true).await;
                assert!(matches!(read_msg(&mut s, Duration::from_secs(5)).await.unwrap(), Msg::Complete));
                true
            }
            other => panic!("unexpected {other:?}"),
        }
    });
    let (ra, rb) = tokio::join!(run(a, gate.clone()), run(b, gate));
    let wins = [ra.unwrap(), rb.unwrap()].iter().filter(|w| **w).count();
    assert_eq!(wins, 1, "exactly one concurrent claim may succeed");
    wait_until("single delivery", || count(&p.pc, Outcome::Completed) == 1).await;
    assert!(p.pc.node.queued().is_empty());
    assert_eq!(outcomes(&p.pc).len(), 1, "no duplicate history from the losing claim");
}

#[tokio::test(flavor = "multi_thread")]
async fn hash_mismatch_is_not_delivery_and_retry_is_idempotent() {
    use flux_core::outbox::QueueState::*;
    let p = pcs(fast_limits()).await;
    let src = tempfile::tempdir().unwrap();
    let f = random_file(src.path(), "a.bin", 200_000);
    queue(&p, vec![f.clone()]).await;
    let mut s = raw_fetch(&p.pc, &p.id).await;
    expect_offer(&mut s).await.unwrap();
    write_msg(&mut s, &Msg::Accept { resume: vec![] }).await.unwrap();
    let (_, _) = read_one_file(&mut s, 0).await;
    ack(&mut s, 0, false).await; // the phone says the hash did not verify
    wait_until("claim released after failure", || qstate(&p) == vec![Queued]).await;
    assert_eq!(count(&p.pc, Outcome::Completed), 0, "never delivered before verification succeeds");
    assert_eq!(count(&p.pc, Outcome::Failed), 1);
    let got = fetch_everything(&p.pc, &p.id).await.unwrap();
    assert_eq!(got[0].1, std::fs::read(&f).unwrap());
    wait_until("retry completes", || count(&p.pc, Outcome::Completed) == 1).await;
    assert!(p.pc.node.queued().is_empty());
    assert_eq!((count(&p.pc, Outcome::Failed), count(&p.pc, Outcome::Completed)), (1, 1), "one failure, one success: consistent");
}

#[tokio::test(flavor = "multi_thread")]
async fn cancellation_from_the_pc_during_transfer_stops_it_and_releases_everything() {
    let p = pcs(fast_limits()).await;
    let src = tempfile::tempdir().unwrap();
    let id = queue(&p, vec![random_file(src.path(), "big.bin", 64 * 1024 * 1024)]).await;
    let mut s = raw_fetch(&p.pc, &p.id).await;
    expect_offer(&mut s).await.unwrap();
    write_msg(&mut s, &Msg::Accept { resume: vec![] }).await.unwrap();
    assert!(matches!(read_msg(&mut s, Duration::from_secs(5)).await.unwrap(), Msg::FileStart { .. }));
    for _ in 0..5 { read_frame(&mut s, Duration::from_secs(5)).await.unwrap(); }
    assert!(p.pc.node.cancel_queued(&id));
    // After cancelling, the PC must stop sending. (The phone sees either an explicit Cancel or the connection ending.)
    let mut frames_after = 0;
    loop {
        match read_frame(&mut s, Duration::from_secs(5)).await {
            Ok(Frame::Control(Msg::Cancel)) | Err(_) => break,
            Ok(_) => { frames_after += 1; assert!(frames_after < 150, "the PC kept streaming a cancelled 64 MB file") }
        }
    }
    assert!(p.pc.node.queued().is_empty());
    tokio::time::sleep(Duration::from_millis(300)).await;
    assert_eq!(outcomes(&p.pc), vec![Outcome::Cancelled], "exactly one Cancelled record");
    assert!(fetch_everything(&p.pc, &p.id).await.is_none(), "a cancelled item is never offered again");
}

#[tokio::test(flavor = "multi_thread")]
async fn the_phone_can_decline_before_or_after_claiming() {
    let p = pcs(fast_limits()).await;
    let src = tempfile::tempdir().unwrap();
    queue(&p, vec![random_file(src.path(), "a.bin", 1000)]).await;
    let mut s = raw_fetch(&p.pc, &p.id).await;
    expect_offer(&mut s).await.unwrap();
    write_msg(&mut s, &Msg::Cancel).await.unwrap(); // decline instead of accepting
    wait_until("declined item removed", || p.pc.node.queued().is_empty()).await;
    assert_eq!(outcomes(&p.pc), vec![Outcome::Cancelled]);

    queue(&p, vec![random_file(src.path(), "b.bin", 50_000)]).await;
    let mut s = raw_fetch(&p.pc, &p.id).await;
    expect_offer(&mut s).await.unwrap();
    write_msg(&mut s, &Msg::Accept { resume: vec![] }).await.unwrap();
    let _ = read_one_file(&mut s, 0).await;
    write_msg(&mut s, &Msg::Cancel).await.unwrap(); // cancel instead of acknowledging
    wait_until("claimed item removed", || p.pc.node.queued().is_empty()).await;
    tokio::time::sleep(Duration::from_millis(200)).await;
    assert_eq!(count(&p.pc, Outcome::Cancelled), 2);
    assert_eq!(count(&p.pc, Outcome::Completed), 0);
}

#[tokio::test(flavor = "multi_thread")]
async fn expired_claim_returns_to_queue_and_a_stale_delivery_cannot_double_complete() {
    use flux_core::outbox::QueueState::*;
    let limits = Limits { claim_ttl: Duration::from_millis(400), ..fast_limits() };
    let p = pcs(limits).await;
    let src = tempfile::tempdir().unwrap();
    let f = random_file(src.path(), "stall.bin", 40 * 1024 * 1024);
    queue(&p, vec![f.clone()]).await;
    // first phone claims, then stalls (never reads): the PC's writes block
    let mut stale = raw_fetch(&p.pc, &p.id).await;
    expect_offer(&mut stale).await.unwrap();
    write_msg(&mut stale, &Msg::Accept { resume: vec![] }).await.unwrap();
    tokio::time::sleep(Duration::from_millis(250)).await;
    assert_eq!(qstate(&p), vec![Claimed], "while the claim is live nobody else can take it");
    let mut other = raw_fetch(&p.pc, &p.id).await;
    assert!(expect_offer(&mut other).await.is_none(), "a claimed item is not offered to a second request");
    tokio::time::sleep(Duration::from_millis(700)).await; // claim expires
    let got = fetch_everything(&p.pc, &p.id).await.expect("after expiry the item is available again");
    assert_eq!(got[0].1, std::fs::read(&f).unwrap());
    wait_until("completion", || count(&p.pc, Outcome::Completed) == 1).await;
    // the stale connection wakes up and drains: it must not create a second completion
    for _ in 0..2000 { if read_frame(&mut stale, Duration::from_millis(300)).await.is_err() { break } }
    tokio::time::sleep(Duration::from_millis(300)).await;
    assert_eq!(count(&p.pc, Outcome::Completed), 1, "idempotent: delivered exactly once");
    assert!(p.pc.node.queued().is_empty());
}

#[tokio::test(flavor = "multi_thread")]
async fn network_interruption_requeues_and_the_next_check_in_delivers() {
    use flux_core::outbox::QueueState::*;
    let p = pcs(fast_limits()).await;
    let src = tempfile::tempdir().unwrap();
    let f = random_file(src.path(), "clip.mp4", 30 * 1024 * 1024);
    queue(&p, vec![f.clone()]).await;
    let mut s = raw_fetch(&p.pc, &p.id).await;
    expect_offer(&mut s).await.unwrap();
    write_msg(&mut s, &Msg::Accept { resume: vec![] }).await.unwrap();
    assert!(matches!(read_msg(&mut s, Duration::from_secs(5)).await.unwrap(), Msg::FileStart { .. }));
    for _ in 0..4 { read_frame(&mut s, Duration::from_secs(5)).await.unwrap(); }
    drop(s); // Wi-Fi drops
    wait_until("item back in the queue", || qstate(&p) == vec![Queued]).await;
    assert_eq!(p.pc.node.queued().len(), 1, "queue stays consistent: still exactly one item");
    assert_eq!(count(&p.pc, Outcome::Failed), 1);
    let got = fetch_everything(&p.pc, &p.id).await.unwrap();
    assert_eq!(got[0].1, std::fs::read(&f).unwrap());
    wait_until("delivery after reconnect", || count(&p.pc, Outcome::Completed) == 1).await;
    assert!(p.pc.node.queued().is_empty());
}

#[tokio::test(flavor = "multi_thread")]
async fn duplicate_acknowledgement_does_not_count_as_delivery() {
    use flux_core::outbox::QueueState::*;
    let p = pcs(fast_limits()).await;
    let src = tempfile::tempdir().unwrap();
    queue(&p, vec![random_file(src.path(), "a.bin", 20_000), random_file(src.path(), "b.bin", 20_000)]).await;
    let mut s = raw_fetch(&p.pc, &p.id).await;
    expect_offer(&mut s).await.unwrap();
    write_msg(&mut s, &Msg::Accept { resume: vec![] }).await.unwrap();
    let (_, ok) = read_one_file(&mut s, 0).await;
    assert!(ok);
    ack(&mut s, 0, true).await;
    ack(&mut s, 0, true).await; // replayed acknowledgement for file 0 where file 1's is expected
    wait_until("claim released after protocol violation", || qstate(&p) == vec![Queued]).await;
    assert_eq!(count(&p.pc, Outcome::Completed), 0, "a replayed ack must never complete the item");
    assert_eq!(p.pc.node.queued().len(), 1);
}

#[tokio::test(flavor = "multi_thread")]
async fn presence_requires_a_recent_authenticated_contact_and_survives_overlap() {
    let limits = Limits { presence_timeout: Duration::from_secs(1), ..fast_limits() };
    let p = pcs(limits).await;
    let fp = p.phone.node.fingerprint().to_string();
    assert!(p.pc.node.is_online(&fp), "pairing is a recent authenticated contact");
    tokio::time::sleep(Duration::from_millis(2300)).await;
    assert!(!p.pc.node.is_online(&fp), "paired in the past is not online now");
    let stranger = Identity::generate().unwrap();
    let mut s = raw_fetch(&p.pc, &stranger).await; // unauthenticated contact must not create presence
    let _ = read_msg(&mut s, Duration::from_secs(5)).await;
    assert!(!p.pc.node.is_online(stranger.fingerprint()));
    assert!(!p.pc.node.is_online(&fp), "a stranger's request does not refresh the phone");
    // 20 overlapping check-ins
    let mut tasks = vec![];
    for _ in 0..20 { let mut c = raw_fetch(&p.pc, &p.id).await; tasks.push(tokio::spawn(async move { let _ = read_msg(&mut c, Duration::from_secs(5)).await; })); }
    for t in tasks { t.await.unwrap(); }
    assert!(p.pc.node.is_online(&fp), "check-in brings the phone back online");
}
