import { useState } from "react";
import { ChevronRight, Laptop, Monitor, MoreHorizontal, Smartphone, Tablet } from "lucide-react";
import type { DeviceType } from "../lib/api";
import { host } from "../lib/format";

export function TypeIcon({ t, size = 40 }: { t: DeviceType; size?: number }) {
  if (t === "phone") return <Smartphone size={size} strokeWidth={1.3} />;
  if (t === "tablet") return <Tablet size={size} strokeWidth={1.3} />;
  if (t === "laptop") return <Laptop size={size} strokeWidth={1.3} />;
  return <Monitor size={size} strokeWidth={1.3} />;
}

export interface Card { fingerprint: string; name: string; type: DeviceType; online: boolean; paired: boolean; addr?: string; queueable?: boolean }

export function DeviceCard({ d, onSend, onUnpair, hasStaged }: { d: Card; onSend: () => void; onUnpair?: () => void; hasStaged: boolean }) {
  const [menu, setMenu] = useState(false);
  return (
    <div className={`dev-card ${d.online ? "" : "off"}`}>
      {onUnpair && (
        <div className="dev-menu">
          <button className="icon-btn" aria-label={`More options for ${d.name}`} aria-expanded={menu} onClick={() => setMenu((m) => !m)}><MoreHorizontal size={18} /></button>
          {menu && <div className="menu" role="menu"><button role="menuitem" onClick={() => { setMenu(false); onUnpair(); }}>Unpair device…</button></div>}
        </div>
      )}
      <div className="dev-art"><TypeIcon t={d.type} size={52} /></div>
      <div className="dev-name">{d.name}</div>
      <div className="dev-status"><i className={d.online ? "dot green" : "dot gray"} />{d.online ? (d.queueable ? "Connected" : "Available") : "Offline"}{d.paired && <span className="chip">Paired</span>}</div>
      <div className="dev-meta">{d.type === "unknown" ? "Computer" : d.type[0].toUpperCase() + d.type.slice(1)}{d.addr ? ` · ${host(d.addr)}` : ""}</div>
      <button className="btn dark sm" disabled={!d.online && !d.queueable} onClick={onSend} title={d.online ? (hasStaged ? "Send the selected files" : "Choose files to send") : d.queueable ? "Not connected right now: files are queued and delivered when FLUX is open on the phone" : "This device isn't on the network right now"}>
        {hasStaged ? "Send selected" : "Send Files"} <ChevronRight size={14} />
      </button>
    </div>
  );
}
