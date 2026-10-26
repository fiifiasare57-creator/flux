import { useState } from "react";
import { Copy, RefreshCw, ShieldAlert, ShieldCheck } from "lucide-react";
import { api } from "../lib/api";
import { useFlux, errText } from "../lib/store";
import { bytes, shortFp } from "../lib/format";
import { Dialog } from "./ui";
import DeviceConnection from "./DeviceConnection";

export function PairDialog({ onClose }: { onClose: () => void }) {
  const { toast, refreshInfo, pairing, newPairing } = useFlux();
  const [tab, setTab] = useState<"show" | "enter">("show");
  const [paste, setPaste] = useState("");
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const left = pairing ? Math.max(0, Math.round((pairing.expiresAt - Date.now()) / 1000)) : 0;

  const pair = async () => {
    setBusy(true); setFailure(null);
    const v = paste.trim();
    try {
      const d = v.toLowerCase().startsWith("flux://") ? await api.pairWith(v) : await api.pairWithCode(v);
      toast("success", `Paired with ${d.name}.`); await refreshInfo(); onClose();
    } catch (e) { setFailure(errText(e)); } finally { setBusy(false); }
  };

  return (
    <Dialog title="Pair a device" onClose={onClose} wide>
      <div className="tabs" role="tablist">
        <button role="tab" aria-selected={tab === "show"} className={tab === "show" ? "on" : ""} onClick={() => setTab("show")}>Show a code</button>
        <button role="tab" aria-selected={tab === "enter"} className={tab === "enter" ? "on" : ""} onClick={() => setTab("enter")}>Enter a code</button>
      </div>
      {tab === "show" ? (
        <div className="dialog-body">
          <DeviceConnection waitingForScan compact />
          <p className="muted">On a phone: open the FLUX Android app and tap <b>Scan QR code</b>, or type the code. On another computer: open FLUX → Pair a device → Enter a code. It works once and expires quickly.</p>
          {pairing ? (
            <div className="pair-show">
              <img src={pairing.qr} alt="Pairing QR code" width={200} height={200} />
              <div className="pair-side">
                <div className={`countdown ${left === 0 ? "expired" : ""}`}>{left > 0 ? `Expires in ${Math.floor(left / 60)}:${String(left % 60).padStart(2, "0")}` : "Code expired, making a new one…"}</div>
                <div className="code-row"><span className="code mono">{pairing.code}</span><button className="icon-btn" aria-label="Copy the code" onClick={() => void navigator.clipboard.writeText(pairing.code).then(() => toast("info", "Code copied."))}><Copy size={15} /></button></div>
                <div className="row gap">
                  <button className="btn" onClick={() => void navigator.clipboard.writeText(pairing.uri).then(() => toast("info", "Full pairing link copied."))}><Copy size={15} /> Copy full link</button>
                  <button className="btn ghost" onClick={() => void newPairing()}><RefreshCw size={15} /> New code</button>
                </div>
              </div>
            </div>
          ) : <p className="muted">Creating a secure one-time code…</p>}
        </div>
      ) : (
        <div className="dialog-body">
          <p className="muted">Type the 9-character code shown on the other device (like <b className="mono">K7M-2QX-9R4</b>), or paste its full pairing link. The other device must be open on this network.</p>
          <input className="mono code-input" placeholder="K7M-2QX-9R4" value={paste} onChange={(e) => setPaste(e.target.value)} aria-label="Pairing code or link" autoFocus onKeyDown={(e) => { if (e.key === "Enter" && paste.trim() && !busy) void pair(); }} />
          {failure && <p className="error-text">{failure}</p>}
          <div className="dialog-actions">
            <button className="btn ghost" onClick={onClose}>Cancel</button>
            <button className="btn primary" disabled={busy || !paste.trim()} onClick={() => void pair()}>{busy ? "Pairing…" : "Pair"}</button>
          </div>
        </div>
      )}
    </Dialog>
  );
}

export function IncomingDialog() {
  const { incoming, respond } = useFlux();
  if (!incoming) return null;
  const i = incoming;
  return (
    <Dialog title="Incoming transfer" labelledBy="incoming-title">
      <div className="dialog-body">
        <div className={`who ${i.trusted ? "ok" : "warn"}`}>
          {i.trusted ? <ShieldCheck size={20} /> : <ShieldAlert size={20} />}
          <div>
            <div className="who-name">{i.peer_name}</div>
            <div className="hint">{i.trusted ? "Paired device" : "Not paired. This name is reported by the sender and is unverified."}</div>
            <div className="hint mono">{shortFp(i.peer_fingerprint)}</div>
          </div>
        </div>
        <div className="meta-line">{i.file_count} file{i.file_count === 1 ? "" : "s"} · {bytes(i.total_bytes)}</div>
        <ul className="file-list">{i.files.slice(0, 8).map((f, k) => <li key={k}><span>{f.name}</span><span className="muted">{bytes(f.size)}</span></li>)}</ul>
        {i.file_count > 8 && <div className="hint">…and {i.file_count - 8} more</div>}
        <div className="hint">Saves to: <span className="mono">{i.destination}</span></div>
        <div className="hint">Nothing is downloaded until you accept. FLUX never opens received files automatically.</div>
      </div>
      <div className="dialog-actions">
        <button className="btn ghost" onClick={() => void respond(false)} autoFocus>Decline</button>
        <button className="btn primary" onClick={() => void respond(true)}>Accept</button>
      </div>
    </Dialog>
  );
}
