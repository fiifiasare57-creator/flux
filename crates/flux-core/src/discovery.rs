//! Local-network discovery via mDNS/DNS-SD (`_flux._tcp`). Discovery only produces *hints*:
//! the advertised fingerprint is unauthenticated, and is checked by pinned TLS on connect.
//! A discovered device is never trusted merely because it is discovered.

use crate::error::{FluxError, Result};
use crate::protocol::DeviceType;
use mdns_sd::{ServiceDaemon, ServiceEvent, ServiceInfo};
use std::net::IpAddr;
use tokio::sync::mpsc;

const SERVICE: &str = "_flux._tcp.local.";

#[derive(Debug, Clone)]
pub struct DiscoveredPeer {
    pub instance: String,
    pub name: String,
    pub fingerprint: String,
    pub device_type: DeviceType,
    pub addrs: Vec<String>,
}

#[derive(Debug, Clone)]
pub enum DiscoveryEvent {
    Found(DiscoveredPeer),
    Lost { instance: String },
}

/// Candidate LAN addresses (IPv4, non-loopback, non-link-local) as "ip:port".
pub fn lan_addrs(port: u16) -> Vec<String> {
    let mut v: Vec<String> = if_addrs::get_if_addrs()
        .unwrap_or_default()
        .into_iter()
        .filter_map(|i| match i.ip() {
            IpAddr::V4(ip) if !ip.is_loopback() && !ip.is_link_local() => Some(format!("{ip}:{port}")),
            _ => None,
        })
        .collect();
    if v.is_empty() {
        v.push(format!("127.0.0.1:{port}"));
    }
    v
}

pub struct Discovery {
    daemon: ServiceDaemon,
}

fn dtype_str(d: DeviceType) -> &'static str {
    match d { DeviceType::Desktop => "desktop", DeviceType::Laptop => "laptop", DeviceType::Phone => "phone", DeviceType::Tablet => "tablet", DeviceType::Unknown => "unknown" }
}
fn dtype_parse(s: &str) -> DeviceType {
    match s { "desktop" => DeviceType::Desktop, "laptop" => DeviceType::Laptop, "phone" => DeviceType::Phone, "tablet" => DeviceType::Tablet, _ => DeviceType::Unknown }
}
fn err(e: mdns_sd::Error) -> FluxError {
    FluxError::Io(std::io::Error::other(e.to_string()))
}

impl Discovery {
    pub fn new() -> Result<Self> {
        Ok(Self { daemon: ServiceDaemon::new().map_err(err)? })
    }

    pub fn advertise(&self, display_name: &str, port: u16, fingerprint: &str, dt: DeviceType) -> Result<()> {
        let instance = format!("flux-{}", &fingerprint[..12]); // no personal data in the instance name
        let host = format!("{instance}.local.");
        let name: String = display_name.chars().take(64).collect();
        let props = [("v", "1"), ("fp", fingerprint), ("dt", dtype_str(dt)), ("n", name.as_str())];
        let info = ServiceInfo::new(SERVICE, &instance, &host, "", port, &props[..]).map_err(err)?.enable_addr_auto();
        self.daemon.register(info).map_err(err)
    }

    pub fn browse(&self) -> Result<mpsc::UnboundedReceiver<DiscoveryEvent>> {
        let rx = self.daemon.browse(SERVICE).map_err(err)?;
        let (tx, out) = mpsc::unbounded_channel();
        std::thread::spawn(move || {
            while let Ok(ev) = rx.recv() {
                let msg = match ev {
                    ServiceEvent::ServiceResolved(info) => {
                        let fp = info.get_property_val_str("fp").unwrap_or("").to_ascii_lowercase();
                        if fp.len() != 64 || !fp.chars().all(|c| c.is_ascii_hexdigit()) { continue }
                        let port = info.get_port();
                        let addrs = info.get_addresses().iter().filter(|a| a.is_ipv4()).map(|a| format!("{a}:{port}")).collect();
                        DiscoveryEvent::Found(DiscoveredPeer {
                            instance: info.get_fullname().to_string(),
                            name: info.get_property_val_str("n").unwrap_or("Unnamed device").chars().filter(|c| !c.is_control()).take(64).collect(),
                            fingerprint: fp,
                            device_type: dtype_parse(info.get_property_val_str("dt").unwrap_or("")),
                            addrs,
                        })
                    }
                    ServiceEvent::ServiceRemoved(_, full) => DiscoveryEvent::Lost { instance: full },
                    _ => continue,
                };
                if tx.send(msg).is_err() { break }
            }
        });
        Ok(out)
    }

    pub fn shutdown(&self) {
        let _ = self.daemon.shutdown();
    }
}
