//! Short-lived, single-use pairing tokens.
//!
//! The QR / pairing link carries: the host's *public* certificate fingerprint (so the scanning
//! device can authenticate the host and defeat man-in-the-middle), candidate addresses, and a
//! one-time token proving the scanner was given the code. The token is never a long-lived
//! secret: it expires, is consumed on first success, and the offer is burned after repeated
//! failures. Only a hash of the token is retained in memory.

use crate::error::{FluxError, Result};
use rand::RngCore;
use sha2::{Digest, Sha256};
use std::sync::Mutex;
use std::time::{Duration, Instant};
use url::Url;

pub const DEFAULT_TTL: Duration = Duration::from_secs(120);
const MAX_FAILURES: u32 = 5;

struct Active {
    token_hash: [u8; 32],
    expires: Instant,
    failures: u32,
}

#[derive(Default)]
pub struct PairingManager {
    active: Mutex<Option<Active>>,
}

fn hash(token: &str) -> [u8; 32] {
    Sha256::digest(token.as_bytes()).into()
}

impl PairingManager {
    /// Create a fresh token, replacing any previous one (only one offer is live at a time).
    pub fn create(&self, ttl: Duration) -> String {
        let mut raw = [0u8; 32];
        rand::rngs::OsRng.fill_bytes(&mut raw);
        let token = hex::encode(raw);
        *self.active.lock().unwrap() =
            Some(Active { token_hash: hash(&token), expires: Instant::now() + ttl, failures: 0 });
        token
    }

    /// Validate and consume a token. Single use; expired / wrong tokens fail identically.
    pub fn consume(&self, presented: &str) -> Result<()> {
        let mut g = self.active.lock().unwrap();
        let fail = || FluxError::Pairing("invalid or expired pairing code".into());
        let Some(a) = g.as_mut() else { return Err(fail()) };
        if Instant::now() >= a.expires {
            *g = None;
            return Err(fail());
        }
        if a.token_hash == hash(presented) {
            *g = None; // single use
            return Ok(());
        }
        a.failures += 1;
        if a.failures >= MAX_FAILURES {
            *g = None; // burn the offer after repeated guesses
        }
        Err(fail())
    }

    pub fn is_active(&self) -> bool {
        self.active.lock().unwrap().as_ref().is_some_and(|a| Instant::now() < a.expires)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PairingPayload {
    pub addrs: Vec<String>,
    pub fingerprint: String,
    pub token: String,
    pub host_name: String,
}

impl PairingPayload {
    pub fn to_uri(&self) -> String {
        let mut u = Url::parse("flux://pair").unwrap();
        {
            let mut q = u.query_pairs_mut();
            q.append_pair("v", "1");
            for a in &self.addrs {
                q.append_pair("a", a);
            }
            q.append_pair("fp", &self.fingerprint);
            q.append_pair("t", &self.token);
            q.append_pair("n", &self.host_name);
        }
        u.to_string()
    }

    pub fn parse(uri: &str) -> Result<Self> {
        let bad = |m: &str| FluxError::Pairing(format!("malformed pairing link: {m}"));
        let u = Url::parse(uri.trim()).map_err(|_| bad("not a URL"))?;
        if u.scheme() != "flux" || u.host_str() != Some("pair") {
            return Err(bad("wrong scheme"));
        }
        let (mut addrs, mut fp, mut token, mut name) = (vec![], None, None, String::new());
        for (k, v) in u.query_pairs() {
            match k.as_ref() {
                "a" => addrs.push(v.into_owned()),
                "fp" => fp = Some(v.into_owned()),
                "t" => token = Some(v.into_owned()),
                "n" => name = v.chars().take(64).collect(),
                _ => {}
            }
        }
        let fingerprint = fp.ok_or_else(|| bad("missing fingerprint"))?.to_ascii_lowercase();
        if fingerprint.len() != 64 || !fingerprint.chars().all(|c| c.is_ascii_hexdigit()) {
            return Err(bad("bad fingerprint"));
        }
        if addrs.is_empty() || addrs.len() > 16 {
            return Err(bad("bad address list"));
        }
        let token = token.ok_or_else(|| bad("missing token"))?;
        if token.len() > 128 {
            return Err(bad("bad token"));
        }
        Ok(Self { addrs, fingerprint, token, host_name: name })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn token_is_single_use() {
        let m = PairingManager::default();
        let t = m.create(DEFAULT_TTL);
        assert!(m.consume(&t).is_ok());
        assert!(m.consume(&t).is_err());
    }

    #[test]
    fn token_expires() {
        let m = PairingManager::default();
        let t = m.create(Duration::from_millis(1));
        std::thread::sleep(Duration::from_millis(10));
        assert!(m.consume(&t).is_err());
    }

    #[test]
    fn repeated_guesses_burn_the_offer() {
        let m = PairingManager::default();
        let t = m.create(DEFAULT_TTL);
        for _ in 0..MAX_FAILURES {
            assert!(m.consume("wrong").is_err());
        }
        assert!(m.consume(&t).is_err(), "correct token must fail once the offer is burned");
    }

    #[test]
    fn tokens_are_unique_and_long() {
        let m = PairingManager::default();
        let (a, b) = (m.create(DEFAULT_TTL), m.create(DEFAULT_TTL));
        assert_ne!(a, b);
        assert_eq!(a.len(), 64);
        assert!(m.consume(&a).is_err(), "replaced token must be dead");
    }

    #[test]
    fn payload_roundtrip_and_validation() {
        let p = PairingPayload { addrs: vec!["192.168.1.5:47800".into()], fingerprint: "ab".repeat(32), token: "tok".into(), host_name: "My PC & Co".into() };
        assert_eq!(PairingPayload::parse(&p.to_uri()).unwrap(), p);
        assert!(PairingPayload::parse("https://pair?x=1").is_err());
        assert!(PairingPayload::parse("flux://pair?a=1.2.3.4:1&fp=zz&t=x").is_err());
    }
}
