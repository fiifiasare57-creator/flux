//! Short-lived, single-use pairing tokens.
//!
//! The QR / pairing link carries: the host's *public* certificate fingerprint (so the scanning
//! device can authenticate the host and defeat man-in-the-middle), candidate addresses, and a
//! one-time token proving the scanner was given the code. The token is never a long-lived
//! secret: it expires, is consumed on first success, and the offer is burned after repeated
//! failures. Only a hash of the token is retained in memory.

use crate::error::{FluxError, Result};
use hmac::{Hmac, Mac};
use rand::RngCore;
use sha2::{Digest, Sha256};
use std::sync::Mutex;
use std::time::{Duration, Instant};
use url::Url;

pub const DEFAULT_TTL: Duration = Duration::from_secs(120);
const MAX_FAILURES: u32 = 5;

struct Active {
    token_hash: [u8; 32],
    code: String,
    expires: Instant,
    failures: u32,
}

#[derive(Default)]
pub struct PairingManager {
    active: Mutex<Option<Active>>,
}

/// 31 unambiguous characters (no I, L, O, 0, 1): 31^9 is about 44.6 bits.
const CODE_ALPHABET: &[u8] = b"ABCDEFGHJKMNPQRSTUVWXYZ23456789";

fn gen_code() -> String {
    let mut out = String::with_capacity(9);
    let mut b = [0u8; 1];
    while out.len() < 9 {
        rand::rngs::OsRng.fill_bytes(&mut b);
        if b[0] < 248 { // 248 = 31 * 8: rejection sampling keeps every character equally likely
            out.push(CODE_ALPHABET[(b[0] % 31) as usize] as char);
        }
    }
    out
}

/// "k7m-2qx-9r4" / "K7M 2QX 9R4" -> "K7M2QX9R4". None if it is not a valid code.
pub fn normalize_code(s: &str) -> Option<String> {
    let c: String = s.chars().filter(|c| c.is_ascii_alphanumeric()).map(|c| c.to_ascii_uppercase()).collect();
    (c.len() == 9 && c.bytes().all(|b| CODE_ALPHABET.contains(&b))).then_some(c)
}

pub fn format_code(c: &str) -> String {
    if c.len() == 9 { format!("{}-{}-{}", &c[0..3], &c[3..6], &c[6..9]) } else { c.to_string() }
}

pub fn mac(code: &str, role: &str, pc_fp: &str, client_fp: &str) -> Hmac<Sha256> {
    let mut m = <Hmac<Sha256> as Mac>::new_from_slice(code.as_bytes()).expect("hmac accepts any key length");
    m.update(format!("flux-pair-v1|{role}|{pc_fp}|{client_fp}").as_bytes());
    m
}

fn hash(token: &str) -> [u8; 32] {
    Sha256::digest(token.as_bytes()).into()
}

impl PairingManager {
    /// Create a fresh token, replacing any previous one (only one offer is live at a time).
    pub fn create(&self, ttl: Duration) -> String {
        self.create_pair(ttl).0
    }

    /// Fresh (long token for the QR link, short 9-character code for typing). Both die together.
    pub fn create_pair(&self, ttl: Duration) -> (String, String) {
        let mut raw = [0u8; 32];
        rand::rngs::OsRng.fill_bytes(&mut raw);
        let token = hex::encode(raw);
        let code = gen_code();
        *self.active.lock().unwrap() =
            Some(Active { token_hash: hash(&token), code: code.clone(), expires: Instant::now() + ttl, failures: 0 });
        (token, code)
    }

    /// Verify a client's proof of the short code (constant-time) and return our proof for the client to check.
    /// Single use; burned after repeated failures. The proofs are bound to both fingerprints, so a man in the
    /// middle (whose certificate differs) can neither produce nor reuse them.
    pub fn consume_code_proof(&self, client_proof_hex: &str, pc_fp: &str, client_fp: &str) -> Result<String> {
        let mut g = self.active.lock().unwrap();
        let fail = || FluxError::Pairing("invalid or expired pairing code".into());
        let Some(a) = g.as_mut() else { return Err(fail()) };
        if Instant::now() >= a.expires {
            *g = None;
            return Err(fail());
        }
        let given = hex::decode(client_proof_hex).unwrap_or_default();
        if mac(&a.code, "client", pc_fp, client_fp).verify_slice(&given).is_ok() {
            let server = hex::encode(mac(&a.code, "server", pc_fp, client_fp).finalize().into_bytes());
            *g = None; // single use
            return Ok(server);
        }
        a.failures += 1;
        if a.failures >= MAX_FAILURES {
            *g = None;
        }
        Err(fail())
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
    fn codes_are_nine_unambiguous_characters_and_normalise() {
        let m = PairingManager::default();
        let (_, c) = m.create_pair(DEFAULT_TTL);
        assert_eq!(c.len(), 9);
        assert!(c.bytes().all(|b| CODE_ALPHABET.contains(&b)));
        assert_eq!(normalize_code(&format_code(&c).to_lowercase()).as_deref(), Some(c.as_str()));
        assert!(normalize_code("K7M-2QX-9R").is_none() && normalize_code("K7M-2QX-9RO").is_none() && normalize_code("").is_none());
        let seen: std::collections::HashSet<String> = (0..200).map(|_| m.create_pair(DEFAULT_TTL).1).collect();
        assert!(seen.len() > 195, "codes must not repeat");
    }

    #[test]
    fn code_proofs_are_bound_to_both_fingerprints_single_use_and_burn() {
        let m = PairingManager::default();
        let (_, code) = m.create_pair(DEFAULT_TTL);
        let (pc, phone) = ("aa".repeat(32), "bb".repeat(32));
        let good = hex::encode(mac(&code, "client", &pc, &phone).finalize().into_bytes());
        // a proof made for a different PC certificate (man in the middle) is refused
        let mitm = hex::encode(mac(&code, "client", &"cc".repeat(32), &phone).finalize().into_bytes());
        assert!(m.consume_code_proof(&mitm, &pc, &phone).is_err());
        assert!(m.consume_code_proof("zz", &pc, &phone).is_err());
        let server = m.consume_code_proof(&good, &pc, &phone).unwrap();
        assert_eq!(server, hex::encode(mac(&code, "server", &pc, &phone).finalize().into_bytes()));
        assert!(m.consume_code_proof(&good, &pc, &phone).is_err(), "single use");
        let (_, code2) = m.create_pair(DEFAULT_TTL);
        for _ in 0..MAX_FAILURES { assert!(m.consume_code_proof("00", &pc, &phone).is_err()); }
        let good2 = hex::encode(mac(&code2, "client", &pc, &phone).finalize().into_bytes());
        assert!(m.consume_code_proof(&good2, &pc, &phone).is_err(), "burned after repeated failures");
    }

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
