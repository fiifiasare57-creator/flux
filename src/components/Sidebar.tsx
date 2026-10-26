import { ArrowLeftRight, Folder, Home, Monitor, Settings as Cog } from "lucide-react";
import logo from "../assets/logo.png";
import { useFlux, type Page } from "../lib/store";

const NAV: { id: Page; label: string; icon: JSX.Element }[] = [
  { id: "home", label: "Home", icon: <Home size={18} /> },
  { id: "files", label: "Files", icon: <Folder size={18} /> },
  { id: "transfers", label: "Transfers", icon: <ArrowLeftRight size={18} /> },
  { id: "devices", label: "Devices", icon: <Monitor size={18} /> },
  { id: "settings", label: "Settings", icon: <Cog size={18} /> },
];

export default function Sidebar() {
  const { page, go, info, live, engineError } = useFlux();
  // only real work counts: live transfers plus files queued for a phone
  const active = live.filter((l) => ["active", "awaiting_approval", "reconnecting"].includes(l.state)).length + (info?.queued.length ?? 0);
  const ready = !engineError && !!info && info.lan_addrs.length > 0 && !(info.lan_addrs.length === 1 && info.lan_addrs[0].startsWith("127."));
  return (
    <aside className="sb">
      <div className="sb-brand"><img src={logo} alt="FLUX" /><span>FLUX</span></div>
      <nav aria-label="Main" className="sb-nav">
        {NAV.map((n) => (
          <button key={n.id} className={`sb-item ${page === n.id ? "on" : ""}`} aria-current={page === n.id ? "page" : undefined} onClick={() => go(n.id)}>
            {n.icon}<span>{n.label}</span>
            {n.id === "transfers" && active > 0 && <em className="sb-badge" aria-label={`${active} active`}>{active}</em>}
          </button>
        ))}
      </nav>
      <div className="sb-foot" role="status">
        <div className="sb-foot-top"><i className={ready ? "dot green" : "dot amber"} /><b>{ready ? "FLUX Ready" : engineError ? "Engine unavailable" : "No network"}</b></div>
        <div className="sb-foot-sub">Fast. Secure. Seamless.</div>
      </div>
    </aside>
  );
}
