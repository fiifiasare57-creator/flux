//! Share links: `http://<pc-ip>:<port>/<9-char token>` that anyone on the same network can open in a browser.
//!
//! Security model (read this before changing anything):
//!  * The token is 9 random characters from a 55-symbol alphabet (~52 bits), generated from the OS CSPRNG.
//!  * A link exposes ONLY the files chosen when it was created, addressed by index: there is no path handling at all.
//!  * Links expire (default 1 hour), can be limited to N downloads, and can be revoked instantly.
//!  * The HTTP listener exists ONLY while at least one link is active; it is closed when the last one ends.
//!  * Every miss returns the identical 404; an IP that makes 15 bad requests in a minute is blocked for a minute.
//!  * Downloads are forced to `attachment` with `nosniff`, so a shared .html file can never run in the browser.
//!  * This is plain HTTP on the local network: anyone on that network who has the link can read the files in transit.
//!    That is the trade-off for "works in any browser with no app"; the UI says so.

use crate::error::{FluxError, Result};
use crate::node::Node;
use crate::send::SourceFile;
use crate::store::unix_now;
use rand::RngCore;
use serde::Serialize;
use std::collections::HashMap;
use std::net::{IpAddr, SocketAddr};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tokio::io::{AsyncReadExt, AsyncSeekExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio_util::sync::CancellationToken;

const TOKEN_ALPHABET: &[u8] = b"abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // 55 symbols, no look-alikes
const TOKEN_LEN: usize = 9;
const MAX_HEAD: usize = 8 * 1024;
const BAD_LIMIT: u32 = 15;

struct Entry {
    files: Vec<SourceFile>,
    total: u64,
    created: u64,
    expires: Instant,
    expires_unix: u64,
    max_downloads: Option<u32>,
    downloads: u32,
    in_flight: u32,
}

struct Running {
    port: u16,
    cancel: CancellationToken,
}

#[derive(Default)]
struct Bad {
    count: u32,
    since: Option<Instant>,
    blocked_until: Option<Instant>,
}

#[derive(Default)]
pub(crate) struct ShareHub {
    state: Mutex<State>,
}
#[derive(Default)]
struct State {
    entries: HashMap<String, Entry>,
    server: Option<Running>,
    bad: HashMap<IpAddr, Bad>,
}

#[derive(Serialize, Clone, Debug)]
pub struct ShareInfo {
    pub token: String,
    pub port: u16,
    pub created: u64,
    pub expires_at: u64,
    pub file_count: u32,
    pub total_bytes: u64,
    pub names: Vec<String>,
    pub max_downloads: Option<u32>,
    pub downloads: u32,
}

fn new_token() -> String {
    let mut out = String::with_capacity(TOKEN_LEN);
    let mut b = [0u8; 1];
    while out.len() < TOKEN_LEN {
        rand::rngs::OsRng.fill_bytes(&mut b);
        if b[0] < 220 { // 220 = 55 * 4: rejection sampling, every symbol equally likely
            out.push(TOKEN_ALPHABET[(b[0] % 55) as usize] as char);
        }
    }
    out
}

fn well_formed(t: &str) -> bool {
    t.len() == TOKEN_LEN && t.bytes().all(|c| TOKEN_ALPHABET.contains(&c))
}

impl ShareHub {
    fn info(&self, port: u16, token: &str, e: &Entry) -> ShareInfo {
        ShareInfo {
            token: token.to_string(), port, created: e.created, expires_at: e.expires_unix, file_count: e.files.len() as u32, total_bytes: e.total,
            names: e.files.iter().take(20).map(|f| f.rel.clone()).collect(), max_downloads: e.max_downloads, downloads: e.downloads,
        }
    }
    /// Drop expired entries; close the listener once nothing is shared any more.
    fn reap(&self) {
        let mut st = self.state.lock().unwrap();
        let now = Instant::now();
        st.entries.retain(|_, e| e.expires > now);
        st.bad.retain(|_, b| b.blocked_until.is_some_and(|t| t > now) || b.since.is_some_and(|s| now.duration_since(s) < Duration::from_secs(60)));
        if st.entries.is_empty() {
            if let Some(r) = st.server.take() {
                r.cancel.cancel();
            }
        }
    }
    fn blocked(&self, ip: IpAddr) -> bool {
        self.state.lock().unwrap().bad.get(&ip).and_then(|b| b.blocked_until).is_some_and(|t| t > Instant::now())
    }
    fn record_bad(&self, ip: IpAddr) {
        let mut st = self.state.lock().unwrap();
        let b = st.bad.entry(ip).or_default();
        let now = Instant::now();
        if b.since.is_none_or(|s| now.duration_since(s) > Duration::from_secs(60)) {
            b.since = Some(now);
            b.count = 0;
        }
        b.count += 1;
        if b.count >= BAD_LIMIT {
            b.blocked_until = Some(now + Duration::from_secs(60));
        }
    }
}

impl Node {
    /// Create a share link for the given files. Starts the listener if it is not already running.
    pub async fn create_share(self: &Arc<Self>, files: Vec<SourceFile>, ttl: Duration, max_downloads: Option<u32>) -> Result<ShareInfo> {
        if files.is_empty() {
            return Err(FluxError::InvalidPath("nothing to share".into()));
        }
        if ttl.is_zero() || ttl > Duration::from_secs(7 * 24 * 3600) {
            return Err(FluxError::Limit("a link can last from 1 second to 7 days".into()));
        }
        let token = new_token();
        {
            // Insert first: a non-empty table stops the reaper from closing the listener under us.
            let mut st = self.shares.state.lock().unwrap();
            if st.entries.len() >= 50 {
                return Err(FluxError::Limit("too many active links; revoke some first".into()));
            }
            st.entries.insert(token.clone(), Entry {
                total: files.iter().map(|f| f.size).sum(), files, created: unix_now(), expires: Instant::now() + ttl, expires_unix: unix_now() + ttl.as_secs(),
                max_downloads, downloads: 0, in_flight: 0,
            });
        }
        let port = match self.ensure_share_server().await {
            Ok(p) => p,
            Err(e) => {
                self.shares.state.lock().unwrap().entries.remove(&token);
                return Err(e);
            }
        };
        let st = self.shares.state.lock().unwrap();
        let e = st.entries.get(&token).ok_or_else(|| FluxError::Storage("link vanished".into()))?;
        Ok(self.shares.info(port, &token, e))
    }

    pub fn list_shares(&self) -> Vec<ShareInfo> {
        self.shares.reap();
        let st = self.shares.state.lock().unwrap();
        let port = st.server.as_ref().map(|r| r.port).unwrap_or(0);
        let mut v: Vec<ShareInfo> = st.entries.iter().map(|(t, e)| self.shares.info(port, t, e)).collect();
        v.sort_by_key(|s| s.created);
        v
    }

    pub fn revoke_share(&self, token: &str) -> bool {
        let found = self.shares.state.lock().unwrap().entries.remove(token).is_some();
        self.shares.reap();
        found
    }

    /// Port of the running share listener, if any (None when no link is active).
    pub fn share_port(&self) -> Option<u16> {
        self.shares.state.lock().unwrap().server.as_ref().map(|r| r.port)
    }

    async fn ensure_share_server(self: &Arc<Self>) -> Result<u16> {
        if let Some(p) = self.share_port() {
            return Ok(p);
        }
        let listener = match TcpListener::bind(self.limits.share_bind).await {
            Ok(l) => l,
            Err(_) => TcpListener::bind(SocketAddr::new(self.limits.share_bind.ip(), 0)).await?, // preferred port busy
        };
        let port = listener.local_addr()?.port();
        let cancel = CancellationToken::new();
        {
            let mut st = self.shares.state.lock().unwrap();
            if let Some(r) = &st.server {
                return Ok(r.port); // another task won the race; ours is dropped
            }
            st.server = Some(Running { port, cancel: cancel.clone() });
        }
        let node = self.clone();
        tokio::spawn(async move {
            let sem = Arc::new(tokio::sync::Semaphore::new(64));
            let mut tick = tokio::time::interval(Duration::from_secs(1));
            loop {
                tokio::select! {
                    _ = cancel.cancelled() => break,
                    _ = tick.tick() => node.shares.reap(),
                    r = listener.accept() => {
                        let Ok((tcp, peer)) = r else { continue };
                        let Ok(permit) = sem.clone().try_acquire_owned() else { continue };
                        let node = node.clone();
                        tokio::spawn(async move { let _p = permit; let _ = tokio::time::timeout(Duration::from_secs(3600), serve(node, tcp, peer.ip())).await; });
                    }
                }
            }
        });
        Ok(port)
    }
}

// ------------------------------------------------------------------------------------------------ HTTP

fn html_escape(s: &str) -> String {
    s.chars().fold(String::new(), |mut o, c| {
        match c { '&' => o.push_str("&amp;"), '<' => o.push_str("&lt;"), '>' => o.push_str("&gt;"), '"' => o.push_str("&quot;"), '\'' => o.push_str("&#39;"), c => o.push(c) }
        o
    })
}

fn pct(s: &str) -> String {
    s.bytes().map(|b| if b.is_ascii_alphanumeric() || b"-._~".contains(&b) { (b as char).to_string() } else { format!("%{b:02X}") }).collect()
}

fn ascii_name(s: &str) -> String {
    s.chars().map(|c| if c.is_ascii_graphic() && c != '"' && c != '\\' || c == ' ' { c } else { '_' }).collect()
}

fn size_text(b: u64) -> String {
    let (mut v, mut i) = (b as f64, 0);
    while v >= 1024.0 && i < 4 { v /= 1024.0; i += 1; }
    format!("{:.1} {}", v, ["B", "KB", "MB", "GB", "TB"][i]).replace(".0 ", " ")
}

const SEC_HEADERS: &str = "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n";

async fn respond(s: &mut TcpStream, status: &str, extra: &str, body: &[u8], head_only: bool) {
    let h = format!("HTTP/1.1 {status}\r\n{SEC_HEADERS}{extra}Content-Length: {}\r\n\r\n", body.len());
    let _ = s.write_all(h.as_bytes()).await;
    if !head_only { let _ = s.write_all(body).await; }
    let _ = s.flush().await;
}

async fn not_found(s: &mut TcpStream) {
    respond(s, "404 Not Found", "Content-Type: text/plain; charset=utf-8\r\n", b"Not found", false).await;
}

async fn read_head(s: &mut TcpStream, limit: Duration) -> Option<String> {
    let mut buf = Vec::with_capacity(1024);
    let mut chunk = [0u8; 1024];
    loop {
        let n = tokio::time::timeout(limit, s.read(&mut chunk)).await.ok()?.ok()?;
        if n == 0 { return None }
        buf.extend_from_slice(&chunk[..n]);
        if buf.windows(4).any(|w| w == b"\r\n\r\n") { break }
        if buf.len() > MAX_HEAD { return None }
    }
    String::from_utf8(buf).ok()
}

/// "bytes=a-b" | "bytes=a-" | "bytes=-n" (single range only). Err = unsatisfiable/invalid.
fn parse_range(h: &str, size: u64) -> std::result::Result<(u64, u64), ()> {
    let spec = h.trim().strip_prefix("bytes=").ok_or(())?;
    if spec.contains(',') || size == 0 { return Err(()) }
    let (a, b) = spec.split_once('-').ok_or(())?;
    let (start, end) = if a.is_empty() {
        let n: u64 = b.parse().map_err(|_| ())?;
        if n == 0 { return Err(()) }
        (size.saturating_sub(n), size - 1)
    } else {
        let start: u64 = a.parse().map_err(|_| ())?;
        let end = if b.is_empty() { size - 1 } else { b.parse::<u64>().map_err(|_| ())?.min(size - 1) };
        (start, end)
    };
    if start > end || start >= size { Err(()) } else { Ok((start, end)) }
}

async fn serve(node: Arc<Node>, mut s: TcpStream, ip: IpAddr) {
    let blocked = node.shares.blocked(ip);
    // Always read the request before answering (closing with unread data makes the OS reset the connection, so the
    // client would never see our reply). A blocked address only gets a short window to send it.
    let Some(head) = read_head(&mut s, Duration::from_secs(if blocked { 2 } else { 10 })).await else { return };
    if blocked {
        respond(&mut s, "429 Too Many Requests", "Retry-After: 60\r\nContent-Type: text/plain\r\n", b"Too many requests", false).await;
        return;
    }
    let mut lines = head.split("\r\n");
    let mut req = lines.next().unwrap_or("").split(' ');
    let (method, target, ver) = (req.next().unwrap_or(""), req.next().unwrap_or(""), req.next().unwrap_or(""));
    if !ver.starts_with("HTTP/1.") || !target.starts_with('/') { return }
    if method != "GET" && method != "HEAD" {
        respond(&mut s, "405 Method Not Allowed", "Allow: GET, HEAD\r\n", b"", false).await;
        return;
    }
    let head_only = method == "HEAD";
    let range_hdr = lines.filter_map(|l| l.split_once(':')).find(|(k, _)| k.eq_ignore_ascii_case("range")).map(|(_, v)| v.trim().to_string());
    let path = target.split(['?', '#']).next().unwrap_or("");
    let segs: Vec<&str> = path.trim_start_matches('/').split('/').collect();
    let (token, idx) = match segs.as_slice() {
        [t] if well_formed(t) => (*t, None),
        [t, i] if well_formed(t) && !i.is_empty() && i.len() <= 6 && i.bytes().all(|c| c.is_ascii_digit()) => (*t, i.parse::<usize>().ok()),
        _ => { node.shares.record_bad(ip); not_found(&mut s).await; return }
    };

    // -------- landing page
    if idx.is_none() {
        let page = {
            let st = node.shares.state.lock().unwrap();
            st.entries.get(token).filter(|e| e.expires > Instant::now()).map(|e| {
                let mins = e.expires.saturating_duration_since(Instant::now()).as_secs().div_ceil(60);
                let rows: String = e.files.iter().enumerate().map(|(i, f)| format!(
                    "<a class=f href=\"/{token}/{i}\"><span class=n>{}</span><span class=s>{}</span><span class=d>Download</span></a>", html_escape(&f.rel), size_text(f.size))).collect();
                format!("<!doctype html><html lang=en><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\"><title>Shared files</title>\
<style>body{{font:16px/1.5 system-ui,sans-serif;margin:0;background:#f3f5f8;color:#0b0f1a}}main{{max-width:640px;margin:0 auto;padding:28px 18px}}h1{{font-size:24px;margin:0 0 4px}}p{{color:#667085;margin:0 0 20px}}\
.f{{display:flex;gap:12px;align-items:center;background:#fff;border:1px solid #e5e8ed;border-radius:14px;padding:14px 16px;margin-bottom:10px;text-decoration:none;color:inherit}}.n{{flex:1;overflow-wrap:anywhere;font-weight:600}}.s{{color:#667085;font-size:13px}}\
.d{{background:#0b0f1a;color:#fff;border-radius:99px;padding:8px 16px;font-size:14px;font-weight:600}}small{{color:#667085}}</style>\
<main><h1>{} file{} shared with you</h1><p>Total {} · this link expires in about {} minute{}</p>{rows}<small>Shared with FLUX. Only people on the same network can open this link.</small></main></html>",
                    e.files.len(), if e.files.len() == 1 { "" } else { "s" }, size_text(e.total), mins, if mins == 1 { "" } else { "s" })
            })
        };
        match page {
            Some(p) => respond(&mut s, "200 OK", "Content-Type: text/html; charset=utf-8\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'\r\n", p.as_bytes(), head_only).await,
            None => { node.shares.record_bad(ip); not_found(&mut s).await }
        }
        return;
    }

    // -------- file download
    let idx = idx.unwrap_or(usize::MAX);
    let picked = {
        let mut st = node.shares.state.lock().unwrap();
        match st.entries.get_mut(token).filter(|e| e.expires > Instant::now()) {
            Some(e) if idx < e.files.len() => {
                let full_possible = range_hdr.is_none();
                if full_possible {
                    if let Some(max) = e.max_downloads {
                        if e.downloads + e.in_flight >= max { None } else { e.in_flight += 1; Some(e.files[idx].clone()) }
                    } else { e.in_flight += 1; Some(e.files[idx].clone()) }
                } else if e.max_downloads.is_some_and(|m| e.downloads >= m) { None } else { Some(e.files[idx].clone()) }
            }
            _ => None,
        }
    };
    let Some(f) = picked else { node.shares.record_bad(ip); not_found(&mut s).await; return };
    let counted = range_hdr.is_none();
    let release = |ok: bool| {
        let mut st = node.shares.state.lock().unwrap();
        if let Some(e) = st.entries.get_mut(token) {
            if counted {
                e.in_flight = e.in_flight.saturating_sub(1);
                if ok {
                    e.downloads += 1;
                    if e.max_downloads.is_some_and(|m| e.downloads >= m) { e.expires = Instant::now(); } // limit reached: link ends
                }
            }
        }
    };
    let mut file = match tokio::fs::File::open(&f.abs).await {
        Ok(x) => x,
        Err(_) => { release(false); respond(&mut s, "410 Gone", "Content-Type: text/plain\r\n", b"This file is no longer available", head_only).await; return }
    };
    let size = match file.metadata().await {
        Ok(m) if m.len() == f.size && crate::send::mtime_of(&m) == f.mtime => m.len(),
        _ => { release(false); respond(&mut s, "410 Gone", "Content-Type: text/plain\r\n", b"This file changed after it was shared", head_only).await; return }
    };
    let (start, end, partial) = match range_hdr.as_deref().map(|h| parse_range(h, size)) {
        None => (0, size.saturating_sub(1), false),
        Some(Ok((a, b))) => (a, b, true),
        Some(Err(())) => { respond(&mut s, "416 Range Not Satisfiable", &format!("Content-Range: bytes */{size}\r\n"), b"", head_only).await; return }
    };
    let len = if size == 0 { 0 } else { end - start + 1 };
    let name = f.rel.rsplit('/').next().unwrap_or("file");
    let mut extra = format!("Content-Type: application/octet-stream\r\nContent-Disposition: attachment; filename=\"{}\"; filename*=UTF-8''{}\r\nAccept-Ranges: bytes\r\n", ascii_name(name), pct(name));
    if partial { extra.push_str(&format!("Content-Range: bytes {start}-{end}/{size}\r\n")); }
    let status = if partial { "206 Partial Content" } else { "200 OK" };
    let h = format!("HTTP/1.1 {status}\r\n{SEC_HEADERS}{extra}Content-Length: {len}\r\n\r\n");
    if s.write_all(h.as_bytes()).await.is_err() { release(false); return }
    if head_only || len == 0 { release(!head_only); return }
    if start > 0 && file.seek(std::io::SeekFrom::Start(start)).await.is_err() { release(false); return }
    let mut left = len;
    let mut buf = vec![0u8; 256 * 1024];
    let mut ok = true;
    while left > 0 {
        let want = left.min(buf.len() as u64) as usize;
        match file.read(&mut buf[..want]).await {
            Ok(0) | Err(_) => { ok = false; break }
            Ok(n) => {
                if tokio::time::timeout(Duration::from_secs(60), s.write_all(&buf[..n])).await.map_or(true, |r| r.is_err()) { ok = false; break }
                left -= n as u64;
            }
        }
    }
    let _ = s.flush().await;
    release(ok && left == 0);
}
