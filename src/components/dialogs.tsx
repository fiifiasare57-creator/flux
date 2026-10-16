import { useEffect, useState } from "react";
import QRCode from "qrcode";
import { Copy, RefreshCw, ShieldAlert, ShieldCheck } from "lucide-react";
import { api } from "../lib/api";
import { useFlux } from "../lib/store";
import { bytes, shortFp } from "../lib/format";
import { Dialog } from "./ui";
import DeviceConnection from "./DeviceConnection";

const err = (e: unknown) => (typeof e === "string" ? e : e instanceof Error ? e.message : "Something went wrong.");

export function PairDialog({ onClose }: { onClose: () => void }) {
  const { toast, refreshInfo } = useFlux();
  const [tab, setTab] = useState<"show" | "enter">("show");
  const [offer, setOffer] = useState<{ uri: string; qr: string; expiresAt: number } | null>(null);
  const [left, setLeft] = useState(0);
  const [paste, setPaste] = useState("");
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);

  const make = async () => {
    try {
      const o = await api.beginPairing();
      const qr = await QRCode.toDataURL(o.uri, { width: 232, margin: 1, errorCorrectionLevel: "M" });
      setOffer({ uri: o.uri, qr, expiresAt: Date.now() + o.expires_in_secs * 1000 });
    } catch (e) { setFailure(err(e)); }
  };
  useEffect(() => { if (tab === "show") void make(); }, [tab]);
  useEffect(() => {
    const t = setInterval(() => setLeft(offer ? Math.max(0, Math.round((offer.expiresAt - Date.now()) / 1000)) : 0), 500);
    return () => clearInterval(t);
  }, [offer]);
  // a successful pairing consumes the code; poll for the new trusted device
  useEffect(() => {
    if (tab !== "show" || !offer) return;
    const t = setInterval(() => void refreshInfo(), 2000);
    return () => clearInterval(t);
  }, [tab, offer, refreshInfo]);

  const pair = async () => {
    setBusy(true); setFailure(null);
    try {
      const d = await api.pairWith(paste.trim());
      toast("success", `Paired with ${d.name}.`);
      await refreshInfo();
      onClose();
    } catch (e) { setFailure(err(e)); } finally { setBusy(false); }
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
          <p className="muted">On the other computer, open FLUX → Devices → Pair a device → Enter a code, and paste this link. It works once and expires quickly. On a phone, open the FLUX Android app and tap Scan QR code.</p>
          {offer ? (
            <div className="pair-show">
              <img src={offer.qr} alt="Pairing QR code" width={232} height={232} />
              <div className="pair-side">
                <div className={`countdown ${left === 0 ? "expired" : ""}`}>{left > 0 ? `Expires in ${Math.floor(left / 60)}:${String(left % 60).padStart(2, "0")}` : "Code expired"}</div>
                <textarea readOnly className="mono" value={offer.uri} rows={5} aria-label="Pairing link" onFocus={(e) => e.currentTarget.select()} />
                <div className="row gap">
                  <button className="btn" onClick={() => void navigator.clipboard.writeText(offer.uri).then(() => toast("info", "Pairing link copied."))}><Copy size={15} /> Copy link</button>
                  <button className="btn ghost" onClick={() => void make()}><RefreshCw size={15} /> New code</button>
                </div>
              </div>
            </div>
          ) : <p className="muted">Creating a secure one-time code…</p>}
          {failure && <p className="error-text">{failure}</p>}
        </div>
      ) : (
        <div className="dialog-body">
          <p className="muted">Paste the pairing link shown on the other computer.</p>
          <textarea className="mono" rows={4} placeholder="flux://pair?…" value={paste} onChange={(e) => setPaste(e.target.value)} aria-label="Pairing link from the other device" />
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
        <ul className="file-list">
          {i.files.slice(0, 8).map((f, k) => <li key={k}><span>{f.name}</span><span className="muted">{bytes(f.size)}</span></li>)}
        </ul>
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
