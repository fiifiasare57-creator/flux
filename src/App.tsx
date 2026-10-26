import { Wifi, WifiOff } from "lucide-react";
import { useFlux } from "./lib/store";
import { IncomingDialog } from "./components/dialogs";
import { ReceiveDialog, SendDialog, ShareDialog } from "./components/flows";
import Sidebar from "./components/Sidebar";
import { Toasts } from "./components/ui";
import Home from "./pages/Home";
import Files from "./pages/Files";
import Transfers from "./pages/Transfers";
import Devices from "./pages/Devices";
import Settings from "./pages/Settings";

export default function App() {
  const { page, info, engineError, dragging } = useFlux();
  const ready = !!info && info.lan_addrs.length > 0 && !(info.lan_addrs.length === 1 && info.lan_addrs[0].startsWith("127."));
  return (
    <div className="app">
      <a className="skip" href="#content">Skip to content</a>
      <Sidebar />
      <div className="main-area">
        <header className="topbar" role="status">
          {engineError ? <span className="status bad"><WifiOff size={14} /> Engine unavailable</span>
            : ready ? <span className="status"><i className="dot green" /><Wifi size={14} /> Connected to local network</span>
            : <span className="status warn"><i className="dot amber" /> No local network detected</span>}
        </header>
        <main className="content" id="content">
          {engineError ? <div className="engine-error"><h2>FLUX engine isn't running</h2><p>{engineError}</p></div>
            : page === "home" ? <Home /> : page === "files" ? <Files /> : page === "transfers" ? <Transfers /> : page === "devices" ? <Devices /> : <Settings />}
        </main>
      </div>
      {dragging && <div className="drop-veil" aria-hidden><div>Drop to send these files</div></div>}
      <IncomingDialog /><SendDialog /><ShareDialog /><ReceiveDialog />
      <Toasts />
    </div>
  );
}
