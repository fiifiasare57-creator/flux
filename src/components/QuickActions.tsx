import { ChevronRight, Download, Link2, Send, Smartphone } from "lucide-react";
import { useFlux } from "../lib/store";

export default function QuickActions() {
  const { startFlow, setReceiveOpen, go } = useFlux();
  const card = "qa";
  return (
    <div className="qa-row" role="group" aria-label="Quick actions">
      <button className={`${card} primary`} onClick={() => void startFlow("send")}>
        <span className="qa-ico"><Send size={20} /></span><span className="qa-t"><b>Send Files</b><small>Photos, videos, documents</small></span><ChevronRight size={16} />
      </button>
      <button className={card} onClick={() => setReceiveOpen(true)}>
        <span className="qa-ico"><Download size={20} /></span><span className="qa-t"><b>Receive Files</b><small>Accept files from your phone</small></span><ChevronRight size={16} />
      </button>
      <button className={card} onClick={() => void startFlow("share")}>
        <span className="qa-ico"><Link2 size={20} /></span><span className="qa-t"><b>Share Link</b><small>Short link for your Wi-Fi</small></span><ChevronRight size={16} />
      </button>
      <button className={card} onClick={() => go("devices")}>
        <span className="qa-ico"><Smartphone size={20} /></span><span className="qa-t"><b>Manage Devices</b><small>View connected devices</small></span><ChevronRight size={16} />
      </button>
    </div>
  );
}
