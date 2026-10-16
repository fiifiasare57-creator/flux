import { useState } from "react";
import { Bell, Clock, Monitor, Send, Settings as Cog, WifiOff, Wifi } from "lucide-react";
import logo from "./assets/logo.png";
import { useFlux, type Page } from "./lib/store";
import { IncomingDialog } from "./components/dialogs";
import { Toasts } from "./components/ui";
import Share from "./pages/Share";
import Devices from "./pages/Devices";
import Activity from "./pages/Activity";
import Settings from "./pages/Settings";

const NAV: { id: Page; label: string; icon: JSX.Element }[] = [
  { id: "share", label: "Share", icon: <Send size={19} /> },
  { id: "devices", label: "Devices", icon: <Monitor size={19} /> },
  { id: "activity", label: "Activity", icon: <Clock size={19} /> },
  { id: "settings", label: "Settings", icon: <Cog size={19} /> },
];

export default function App() {
  const { page, go, info, engineError, live, dragging } = useFlux();
  const [navFocus] = useState(0);
  void navFocus;
  const active = live.filter((l) => ["active", "awaiting_approval", "reconnecting"].includes(l.state)).length;
  return (
    <div className="shell">
      <aside className="nav">
        <div className="brand"><img src={logo} alt="" width={34} /><span>FLUX</span></div>
        <nav aria-label="Main">
          {NAV.map((n) => (
            <button key={n.id} className={`nav-item ${page === n.id ? "on" : ""}`} aria-current={page === n.id ? "page" : undefined} onClick={() => go(n.id)}>
              {n.icon}<span>{n.label}</span>
              {n.id === "activity" && active > 0 && <em className="count" aria-label={`${active} active`}>{active}</em>}
            </button>
          ))}
        </nav>
        <div className="nav-foot">
          <img src={logo} alt="" width={22} />
          <div><b>FLUX</b><div className="muted small">Fast. Secure. Connected.</div><div className="muted small">v{info?.version ?? "—"}</div></div>
        </div>
      </aside>
      <div className="main">
        <header className="topbar">
          {engineError ? <span className="status bad"><WifiOff size={14} /> Engine unavailable</span>
            : info && info.lan_addrs.length && !(info.lan_addrs.length === 1 && info.lan_addrs[0].startsWith("127.")) ? <span className="status"><i className="dot green" /> <Wifi size={14} /> Ready on local network</span>
            : <span className="status warn"><i className="dot amber" /> No local network detected</span>}
          <div className="top-end"><Bell size={18} aria-hidden /><span className="avatar" aria-hidden>{(info?.settings.device_name ?? "F")[0]?.toUpperCase()}</span></div>
        </header>
        <main className="content" id="content">
          {engineError ? <div className="engine-error"><h2>FLUX engine isn't running</h2><p>{engineError}</p></div>
            : page === "share" ? <Share /> : page === "devices" ? <Devices /> : page === "activity" ? <Activity /> : <Settings />}
        </main>
      </div>
      {dragging && <div className="drop-veil" aria-hidden><div>Drop to add files</div></div>}
      <IncomingDialog />
      <Toasts />
    </div>
  );
}
