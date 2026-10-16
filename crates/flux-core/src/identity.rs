//! Device identity: a long-lived self-signed ECDSA P-256 certificate. The SHA-256 of the
//! certificate is the device's *fingerprint* and is what peers pin and trust.
//! Discovery never confers trust; only a pinned fingerprint does.

use crate::error::{FluxError, Result};
use sha2::{Digest, Sha256};
use std::fs;
use std::io::Write;
use std::path::Path;

pub fn fingerprint_of(cert_der: &[u8]) -> String {
    hex::encode(Sha256::digest(cert_der))
}

#[derive(Clone)]
pub struct Identity {
    pub(crate) cert_der: Vec<u8>,
    pub(crate) key_pkcs8: Vec<u8>,
    fingerprint: String,
}

impl std::fmt::Debug for Identity {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "Identity({})", &self.fingerprint[..12])
    }
}

impl Identity {
    pub fn generate() -> Result<Self> {
        let tls = |e: rcgen::Error| FluxError::Tls(e.to_string());
        let key = rcgen::KeyPair::generate().map_err(tls)?;
        let mut params =
            rcgen::CertificateParams::new(vec!["flux.local".to_string()]).map_err(tls)?;
        params.distinguished_name.push(rcgen::DnType::CommonName, "FLUX device");
        let cert = params.self_signed(&key).map_err(tls)?;
        Ok(Self {
            fingerprint: fingerprint_of(cert.der()),
            cert_der: cert.der().to_vec(),
            key_pkcs8: key.serialize_der(),
        })
    }

    pub fn fingerprint(&self) -> &str {
        &self.fingerprint
    }

    /// Load from `dir`, or create and persist a new identity.
    /// NOTE (v0.1): the private key is stored as a file in the app-data directory
    /// (0600 on Unix). Windows DPAPI protection is planned; see docs/security.md.
    pub fn load_or_create(dir: &Path) -> Result<Self> {
        fs::create_dir_all(dir)?;
        let (cp, kp) = (dir.join("cert.der"), dir.join("key.pk8"));
        if cp.exists() && kp.exists() {
            let cert_der = fs::read(&cp)?;
            return Ok(Self { fingerprint: fingerprint_of(&cert_der), cert_der, key_pkcs8: fs::read(&kp)? });
        }
        let id = Self::generate()?;
        write_private(&kp, &id.key_pkcs8)?;
        write_private(&cp, &id.cert_der)?;
        Ok(id)
    }
}

fn write_private(path: &Path, data: &[u8]) -> Result<()> {
    let tmp = path.with_extension("tmp");
    let mut opts = fs::OpenOptions::new();
    opts.write(true).create(true).truncate(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        opts.mode(0o600);
    }
    let mut f = opts.open(&tmp)?;
    f.write_all(data)?;
    f.sync_all()?;
    drop(f);
    fs::rename(tmp, path)?;
    Ok(())
}
