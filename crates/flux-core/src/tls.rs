//! Mutually-authenticated TLS 1.3 using self-signed certificates and fingerprint pinning.
//!
//! * Client side: the server certificate must match a pinned fingerprint (from a pairing QR
//!   code or the trust store). Handshake signatures are verified with rustls' real algorithms,
//!   so the peer must prove possession of the private key.
//! * Server side: any client certificate is accepted at the TLS layer (unknown devices may
//!   *request* a transfer), but its fingerprint is surfaced to the application, which applies
//!   the trust / approval policy. TLS authenticates the peer; policy authorises it.

use crate::error::{FluxError, Result};
use crate::identity::{fingerprint_of, Identity};
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::crypto::{CryptoProvider, WebPkiSupportedAlgorithms};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, ServerName, UnixTime};
use rustls::server::danger::{ClientCertVerified, ClientCertVerifier};
use rustls::{ClientConfig, DigitallySignedStruct, DistinguishedName, ServerConfig, SignatureScheme};
use std::sync::Arc;
use std::time::Duration;
use tokio::net::TcpStream;
use tokio_rustls::{client, server, TlsAcceptor, TlsConnector};

fn provider() -> Arc<CryptoProvider> {
    Arc::new(rustls::crypto::ring::default_provider())
}

fn tls_err<E: std::fmt::Display>(e: E) -> FluxError {
    FluxError::Tls(e.to_string())
}

fn key_and_cert(id: &Identity) -> (Vec<CertificateDer<'static>>, PrivateKeyDer<'static>) {
    (
        vec![CertificateDer::from(id.cert_der.clone())],
        PrivateKeyDer::from(PrivatePkcs8KeyDer::from(id.key_pkcs8.clone())),
    )
}

#[derive(Debug)]
struct PinnedServer {
    /// None = accept any certificate (only used by short-code pairing, which authenticates via the code instead).
    fp: Option<String>,
    algs: WebPkiSupportedAlgorithms,
}

impl ServerCertVerifier for PinnedServer {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _name: &ServerName<'_>,
        _ocsp: &[u8],
        _now: UnixTime,
    ) -> std::result::Result<ServerCertVerified, rustls::Error> {
        if self.fp.as_ref().is_none_or(|f| fingerprint_of(end_entity.as_ref()) == *f) {
            Ok(ServerCertVerified::assertion())
        } else {
            Err(rustls::Error::General("peer fingerprint does not match pinned identity".into()))
        }
    }
    fn verify_tls12_signature(&self, m: &[u8], c: &CertificateDer<'_>, d: &DigitallySignedStruct)
        -> std::result::Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls12_signature(m, c, d, &self.algs)
    }
    fn verify_tls13_signature(&self, m: &[u8], c: &CertificateDer<'_>, d: &DigitallySignedStruct)
        -> std::result::Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(m, c, d, &self.algs)
    }
    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.algs.supported_schemes()
    }
}

#[derive(Debug)]
struct AnyClient {
    algs: WebPkiSupportedAlgorithms,
}

impl ClientCertVerifier for AnyClient {
    fn root_hint_subjects(&self) -> &[DistinguishedName] {
        &[]
    }
    fn verify_client_cert(&self, _e: &CertificateDer<'_>, _i: &[CertificateDer<'_>], _n: UnixTime)
        -> std::result::Result<ClientCertVerified, rustls::Error> {
        Ok(ClientCertVerified::assertion())
    }
    fn verify_tls12_signature(&self, m: &[u8], c: &CertificateDer<'_>, d: &DigitallySignedStruct)
        -> std::result::Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls12_signature(m, c, d, &self.algs)
    }
    fn verify_tls13_signature(&self, m: &[u8], c: &CertificateDer<'_>, d: &DigitallySignedStruct)
        -> std::result::Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(m, c, d, &self.algs)
    }
    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.algs.supported_schemes()
    }
}

pub fn acceptor(id: &Identity) -> Result<TlsAcceptor> {
    let p = provider();
    let algs = p.signature_verification_algorithms;
    let (certs, key) = key_and_cert(id);
    let cfg = ServerConfig::builder_with_provider(p)
        .with_protocol_versions(&[&rustls::version::TLS13])
        .map_err(tls_err)?
        .with_client_cert_verifier(Arc::new(AnyClient { algs }))
        .with_single_cert(certs, key)
        .map_err(tls_err)?;
    Ok(TlsAcceptor::from(Arc::new(cfg)))
}

fn connector(id: &Identity, expected_fp: Option<&str>) -> Result<TlsConnector> {
    let p = provider();
    let algs = p.signature_verification_algorithms;
    let (certs, key) = key_and_cert(id);
    let cfg = ClientConfig::builder_with_provider(p)
        .with_protocol_versions(&[&rustls::version::TLS13])
        .map_err(tls_err)?
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(PinnedServer { fp: expected_fp.map(|f| f.to_ascii_lowercase()), algs }))
        .with_client_auth_cert(certs, key)
        .map_err(tls_err)?;
    Ok(TlsConnector::from(Arc::new(cfg)))
}

pub type ClientStream = client::TlsStream<TcpStream>;
pub type ServerStream = server::TlsStream<TcpStream>;

/// Connect to `addr` ("host:port") and complete a TLS handshake pinned to `expected_fp`.
pub async fn connect(id: &Identity, addr: &str, expected_fp: &str, timeout: Duration) -> Result<ClientStream> {
    let fut = async {
        let tcp = TcpStream::connect(addr).await?;
        tcp.set_nodelay(true).ok();
        let name = ServerName::try_from("flux.local").map_err(tls_err)?;
        connector(id, Some(expected_fp))?.connect(name, tcp).await.map_err(tls_err)
    };
    tokio::time::timeout(timeout, fut).await.map_err(|_| FluxError::Timeout("connecting"))?
}

/// Fingerprint of the client certificate presented on an accepted connection.
pub fn client_fingerprint(s: &ServerStream) -> Result<String> {
    s.get_ref()
        .1
        .peer_certificates()
        .and_then(|c| c.first())
        .map(|c| fingerprint_of(c.as_ref()))
        .ok_or_else(|| FluxError::Tls("peer presented no certificate".into()))
}

/// Connect WITHOUT pinning and report the server's fingerprint. Only for short-code pairing: the caller MUST then
/// verify the server's proof of the code, which is bound to this fingerprint (that is what authenticates the server).
pub async fn connect_unpinned(id: &Identity, addr: &str, timeout: Duration) -> Result<(ClientStream, String)> {
    let fut = async {
        let tcp = TcpStream::connect(addr).await?;
        tcp.set_nodelay(true).ok();
        let name = ServerName::try_from("flux.local").map_err(tls_err)?;
        let s = connector(id, None)?.connect(name, tcp).await.map_err(tls_err)?;
        let fp = s.get_ref().1.peer_certificates().and_then(|c| c.first()).map(|c| fingerprint_of(c.as_ref()))
            .ok_or_else(|| FluxError::Tls("server presented no certificate".into()))?;
        Ok::<_, FluxError>((s, fp))
    };
    tokio::time::timeout(timeout, fut).await.map_err(|_| FluxError::Timeout("connecting"))?
}
