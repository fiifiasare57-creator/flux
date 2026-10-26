import { ArrowDownToLine, ArrowUpFromLine, ChevronRight, Database, Plus, Zap } from "lucide-react";
import logo from "../assets/logo.png";
import { useFlux } from "../lib/store";
import { bytes, timeAgo } from "../lib/format";
import { TypeIcon } from "./devices";

/** Monochrome mountain artwork (decorative). */
function Mountains() {
  return (
    <svg className="mtn" viewBox="0 0 320 90" preserveAspectRatio="none" aria-hidden="true">
      <defs><linearGradient id="mg" x1="0" x2="0" y1="0" y2="1"><stop offset="0" stopColor="#fff" stopOpacity=".16" /><stop offset="1" stopColor="#fff" stopOpacity="0" /></linearGradient></defs>
      <path d="M0 90 L0 62 L48 30 L84 56 L132 14 L186 60 L232 34 L282 64 L320 44 L320 90Z" fill="url(#mg)" />
      <path d="M0 90 L0 74 L58 52 L104 72 L156 40 L212 74 L262 56 L320 78 L320 90Z" fill="#fff" fillOpacity=".06" />
    </svg>
  );
}

export default function RightPanel({ onAddDevice }: { onAddDevice: () => void }) {
  const { info, history, go } = useFlux();
  if (!info) return null;
  const done = history.filter((h) => h.outcome === "completed");
  const sent = done.filter((h) => h.direction === "sent").reduce((n, h) => n + h.file_count, 0);
  const received = done.filter((h) => h.direction === "received").reduce((n, h) => n + h.file_count, 0);
  const total = done.reduce((n, h) => n + h.total_bytes, 0);
  const activeDevices = new Set([...info.nearby.map((n) => n.fingerprint), ...info.trusted.filter((t) => t.online).map((t) => t.fingerprint)]).size;
  const ready = info.lan_addrs.length > 0 && !(info.lan_addrs.length === 1 && info.lan_addrs[0].startsWith("127."));
  const devices = [...info.trusted].sort((a, b) => Number(b.online) - Number(a.online) || (b.last_seen ?? 0) - (a.last_seen ?? 0)).slice(0, 3);
  return (
    <aside className="rp" aria-label="Overview">
      <div className="brand-card">
        <div className="bc-logo"><img src={logo} alt="" /><span>FLUX</span></div>
        <p>Your files. Your devices.<br />Seamlessly.</p>
        <Mountains />
        <div className="bc-status" role="status"><i className={ready ? "dot green" : "dot amber"} />{ready ? "Ready on your network" : "No network detected"}</div>
      </div>

      <div className="card rp-card">
        <h3>Quick Stats</h3>
        <div className="stats-grid">
          <div className="stat"><span className="stat-ico"><ArrowUpFromLine size={16} /></span><small>Files Sent</small><b>{sent}</b></div>
          <div className="stat"><span className="stat-ico"><ArrowDownToLine size={16} /></span><small>Files Received</small><b>{received}</b></div>
          <div className="stat"><span className="stat-ico"><Database size={16} /></span><small>Total Transferred</small><b>{bytes(total)}</b></div>
          <div className="stat"><span className="stat-ico"><Zap size={16} /></span><small>Active Devices</small><b>{activeDevices}</b></div>
        </div>
        {history.length === 0 && <p className="hint">Stats count your completed transfers. None yet.</p>}
      </div>

      <div className="card rp-card">
        <h3>Recent Devices</h3>
        {devices.length === 0 ? <p className="hint">No paired devices yet.</p> : devices.map((d) => (
          <button key={d.fingerprint} className="rd" onClick={() => go("devices")}>
            <span className="rd-ico"><TypeIcon t={d.device_type} size={20} /></span>
            <span className="rd-t"><b>{d.name}</b><small><i className={d.online ? "dot green" : "dot gray"} />{d.online ? "Connected" : d.last_seen ? `Last seen ${timeAgo(d.last_seen)}` : "Not connected"}</small></span>
            <ChevronRight size={15} />
          </button>
        ))}
        <button className="btn add" onClick={onAddDevice}><Plus size={15} /> Add Device</button>
      </div>

      <div className="quote"><Zap size={18} /><p>“Better connections,<br />bigger possibilities.”</p><img src={logo} alt="" /></div>
    </aside>
  );
}
