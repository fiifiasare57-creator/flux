import { useState } from "react";
import { Copy, RefreshCw, ShieldCheck, Trash2 } from "lucide-react";
import { useFlux } from "../lib/store";
import { api } from "../lib/api";
import { host, shortFp, timeAgo } from "../lib/format";
import { TypeIcon } from "../components/devices";
import { Confirm, Empty } from "../components/ui";
import { PairDialog } from "../components/dialogs";
import DeviceConnection from "../components/DeviceConnection";

export default function Devices() {
  const { info, refreshInfo, toast } = useFlux();
  const [pairing, setPairing] = useState(false);
  const [revoke, setRevoke] = useState<{ fp: string; name: string } | null>(null);
  if (!info) return null;
  const unpaired = info.nearby.filter((n) => !n.paired);
  return (
    <div className="page">
      <div className="page-head"><div><h1>Devices</h1><p className="lead">Pair devices you trust. Discovered devices are never trusted automatically.</p></div>
        <button className="btn dark" onClick={() => setPairing(true)}>Pair a device</button></div>

      <div className="card devices-hero"><DeviceConnection waitingForScan={pairing} /></div>

      <h3 className="sec">Paired devices</h3>
      {info.trusted.length === 0 ? <Empty icon={<ShieldCheck size={26} />} title="No paired devices" body="Pair a device once, and it can send to you without a prompt each time (you can change this in Settings)."><button className="btn dark" onClick={() => setPairing(true)}>Pair a device</button></Empty> : (
        <div className="card list">
          {info.trusted.map((t) => (
            <div className="d-row" key={t.fingerprint}>
              <div className="thumb"><TypeIcon t={t.device_type} size={22} /></div>
              <div className="d-main"><div className="t-title">{t.name}</div><div className="t-sub"><i className={t.online ? "dot green" : "dot gray"} /> {t.online ? "Connected" : t.last_seen ? `Last seen ${timeAgo(t.last_seen)}` : "Not connected"} · paired {timeAgo(t.paired_at)}{t.last_addr ? ` · ${host(t.last_addr)}` : ""}</div><div className="t-sub mono">{shortFp(t.fingerprint)}</div></div>
              <button className="btn ghost sm" onClick={() => setRevoke({ fp: t.fingerprint, name: t.name })}><Trash2 size={14} /> Unpair</button>
            </div>
          ))}
        </div>
      )}

      <h3 className="sec">On your network, not paired</h3>
      {unpaired.length === 0 ? <div className="muted pad">No other FLUX devices found. They can still send you files, but you'll be asked to approve each transfer.</div> : (
        <div className="card list">{unpaired.map((n) => (
          <div className="d-row" key={n.fingerprint}><div className="thumb"><TypeIcon t={n.device_type} size={22} /></div>
            <div className="d-main"><div className="t-title">{n.name} <span className="chip">Unverified</span></div><div className="t-sub mono">{shortFp(n.fingerprint)} · {host(n.addrs[0])}</div></div></div>))}</div>
      )}

      <h3 className="sec">Troubleshooting</h3>
      <div className="card pad-card">
        <div className="kv"><span>This device</span><b>{info.settings.device_name}</b></div>
        <div className="kv"><span>Listening on</span><b className="mono">{info.lan_addrs.join(", ")}</b></div>
        <div className="kv"><span>Discovery</span><b>{info.discovery_active ? "Advertising on the local network" : "Not advertising (off in Settings, or unavailable)"}</b></div>
        <div className="kv"><span>Fingerprint</span><b className="mono">{shortFp(info.fingerprint)} <button className="icon-btn" aria-label="Copy fingerprint" onClick={() => void navigator.clipboard.writeText(info.fingerprint).then(() => toast("info", "Fingerprint copied."))}><Copy size={14} /></button></b></div>
        <p className="muted">If devices don't appear: make sure both are on the same Wi-Fi/network; allow FLUX through Windows Firewall on <b>Private</b> networks; turn off VPNs; avoid guest or "client isolation" Wi-Fi (they block device-to-device traffic). Pairing with a code works even when discovery doesn't, as long as the devices can reach each other.</p>
        <button className="btn" onClick={() => void refreshInfo()}><RefreshCw size={15} /> Rescan</button>
      </div>

      {pairing && <PairDialog onClose={() => setPairing(false)} />}
      {revoke && <Confirm title={`Unpair ${revoke.name}?`} danger confirmLabel="Unpair" body={<p>This device will lose its trusted status. It can still ask to send you files, but you'll have to approve each one.</p>}
        onCancel={() => setRevoke(null)} onConfirm={async () => { await api.revoke(revoke.fp); setRevoke(null); await refreshInfo(); toast("info", `${revoke.name} was unpaired.`); }} />}
    </div>
  );
}
