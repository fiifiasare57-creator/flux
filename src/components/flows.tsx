import { useEffect, useState } from "react";
import QRCode from "qrcode";
import { Check, Copy, FolderOpen, Link2, Send, ShieldAlert } from "lucide-react";
import { api, pickDirectory, type ShareView } from "../lib/api";
import { useFlux, errText } from "../lib/store";
import { useTargets } from "../lib/devices";
import { baseName, bytes } from "../lib/format";
import { Dialog, Empty, Switch } from "./ui";
import { TypeIcon } from "./devices";
import { PairDialog } from "./dialogs";

function FileSummary({ paths }: { paths: string[] }) {
  return <div className="fsum"><b>{paths.length} item{paths.length === 1 ? "" : "s"}</b><span className="muted">{paths.slice(0, 3).map(baseName).join(", ")}{paths.length > 3 ? ` and ${paths.length - 3} more` : ""}</span></div>;
}

/** Choose which paired / nearby device receives the selected files. */
export function SendDialog() {
  const { flow, closeFlow, sendTo } = useFlux();
  const targets = useTargets();
  const [busy, setBusy] = useState<string | null>(null);
  const [pair, setPair] = useState(false);
  if (!flow || flow.kind !== "send") return null;
  const go = async (fp: string) => { setBusy(fp); const ok = await sendTo(fp, flow.paths); setBusy(null); if (ok) closeFlow(); };
  return (
    <>
      <Dialog title="Send files" onClose={closeFlow} labelledBy="send-title">
        <div className="dialog-body">
          <FileSummary paths={flow.paths} />
          <p className="muted small">Choose a device. Files are sent unchanged, and the receiver always has to accept.</p>
          {targets.length === 0 ? (
            <Empty icon={<Send size={24} />} title="No devices yet" body="Pair a phone or another computer first, or open FLUX on a device on this network.">
              <button className="btn dark" onClick={() => setPair(true)}>Pair a device</button>
            </Empty>
          ) : (
            <div className="targets" role="list">
              {targets.map((t) => {
                const can = t.online || t.queueable;
                return (
                  <button key={t.fingerprint} role="listitem" className="target" disabled={!can || busy !== null} onClick={() => void go(t.fingerprint)}
                    title={!t.online && t.queueable ? "Not connected right now. Files are queued and delivered when FLUX is open on the phone." : undefined}>
                    <span className="rd-ico"><TypeIcon t={t.type} size={22} /></span>
                    <span className="rd-t"><b>{t.name}</b><small><i className={t.online ? "dot green" : "dot gray"} />{t.online ? "Available" : t.queueable ? "Not connected · will queue" : "Offline"}{t.paired ? " · Paired" : " · Not paired"}</small></span>
                    <span className="target-go">{busy === t.fingerprint ? "Sending…" : t.queueable && !t.online ? "Queue" : "Send"}</span>
                  </button>
                );
              })}
            </div>
          )}
        </div>
      </Dialog>
      {pair && <PairDialog onClose={() => setPair(false)} />}
    </>
  );
}

const EXPIRY = [{ label: "15 minutes", s: 900 }, { label: "1 hour", s: 3600 }, { label: "24 hours", s: 86400 }];
const LIMITS: { label: string; n: number | null }[] = [{ label: "Unlimited", n: null }, { label: "1 download", n: 1 }, { label: "5 downloads", n: 5 }];

/** Create a short link anyone on the same Wi-Fi can open in a browser. */
export function ShareDialog() {
  const { flow, closeFlow, toast, refreshInfo } = useFlux();
  const [ttl, setTtl] = useState(3600);
  const [max, setMax] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [made, setMade] = useState<ShareView | null>(null);
  const [qr, setQr] = useState("");
  const [err, setErr] = useState<string | null>(null);
  useEffect(() => { if (made) void QRCode.toDataURL(made.url, { width: 220, margin: 1 }).then(setQr); }, [made]);
  if (!flow || flow.kind !== "share") return null;
  const create = async () => {
    setBusy(true); setErr(null);
    try { setMade(await api.createShare(flow.paths, ttl, max)); await refreshInfo(); } catch (e) { setErr(errText(e)); } finally { setBusy(false); }
  };
  const close = () => { setMade(null); setQr(""); setErr(null); closeFlow(); };
  const mins = made ? Math.max(1, Math.round((made.expires_at * 1000 - Date.now()) / 60000)) : 0;
  return (
    <Dialog title="Share a link" onClose={close} labelledBy="share-title" wide>
      <div className="dialog-body">
        <FileSummary paths={flow.paths} />
        {!made ? (
          <>
            <div className="opt-grid">
              <fieldset><legend>Link works for</legend>{EXPIRY.map((o) => <label key={o.s} className={ttl === o.s ? "opt on" : "opt"}><input type="radio" name="ttl" checked={ttl === o.s} onChange={() => setTtl(o.s)} />{o.label}</label>)}</fieldset>
              <fieldset><legend>Limit</legend>{LIMITS.map((o) => <label key={o.label} className={max === o.n ? "opt on" : "opt"}><input type="radio" name="max" checked={max === o.n} onChange={() => setMax(o.n)} />{o.label}</label>)}</fieldset>
            </div>
            <div className="notice"><ShieldAlert size={16} /><span>Anyone on <b>this Wi-Fi network</b> who has the link can download these files, in any browser. The link is not encrypted, so share it only on networks you trust. FLUX only serves these files while the link is active.</span></div>
            {err && <p className="error-text">{err}</p>}
            <div className="dialog-actions"><button className="btn ghost" onClick={close}>Cancel</button><button className="btn primary" disabled={busy} onClick={() => void create()}><Link2 size={15} /> {busy ? "Creating…" : "Create link"}</button></div>
          </>
        ) : (
          <>
            <div className="share-made">
              {qr && <img src={qr} alt="QR code for the share link" width={150} height={150} />}
              <div className="share-side">
                <div className="muted small">Your link</div>
                <div className="share-url mono" aria-label="Share link">{made.url.replace("http://", "")}</div>
                <div className="row gap">
                  <button className="btn dark sm" onClick={() => void navigator.clipboard.writeText(made.url).then(() => toast("info", "Link copied."))}><Copy size={13} /> Copy link</button>
                  <button className="btn sm" onClick={() => { void api.revokeShare(made.token).then(() => { toast("info", "Link stopped."); void refreshInfo(); close(); }); }}>Stop sharing</button>
                </div>
                <div className="hint"><Check size={12} /> {made.file_count} file{made.file_count === 1 ? "" : "s"} · {bytes(made.total_bytes)} · expires in about {mins} min{made.max_downloads ? ` · ${made.max_downloads} download${made.max_downloads === 1 ? "" : "s"} max` : ""}</div>
              </div>
            </div>
            <div className="dialog-actions"><button className="btn primary" onClick={close}>Done</button></div>
          </>
        )}
      </div>
    </Dialog>
  );
}

/** What "receiving" means in FLUX: it is always on while the app is open. Shows where files land and who may send. */
export function ReceiveDialog() {
  const { receiveOpen, setReceiveOpen, info, refreshInfo, toast, go } = useFlux();
  const [pair, setPair] = useState(false);
  if (!receiveOpen || !info) return null;
  const s = info.settings;
  const ready = info.lan_addrs.length > 0 && !(info.lan_addrs.length === 1 && info.lan_addrs[0].startsWith("127."));
  const close = () => setReceiveOpen(false);
  const save = (p: { require_approval_for_trusted?: boolean; receive_dir?: string }) => api.updateSettings(p).then(() => refreshInfo()).catch((e) => toast("error", errText(e)));
  return (
    <>
      <Dialog title="Receive files" onClose={close} labelledBy="recv-title">
        <div className="dialog-body">
          <div className={`who ${ready ? "ok" : "warn"}`}><div><div className="who-name">{ready ? "FLUX is ready to receive" : "No network detected"}</div><div className="hint">{ready ? "Devices on your network can send to this computer while FLUX is open. Unpaired devices always need your approval." : "Connect to Wi-Fi or a network to receive files."}</div></div></div>
          <div className="label">Saved to</div>
          <div className="row gap"><div className="path mono" title={s.receive_dir}>{s.receive_dir}</div>
            <button className="btn" onClick={async () => { const d = await pickDirectory("Choose where received files are saved"); if (d) await save({ receive_dir: d }); }}>Change…</button>
            <button className="btn ghost" aria-label="Open the receive folder" onClick={() => void api.openReceiveFolder().catch((e) => toast("error", errText(e)))}><FolderOpen size={15} /></button></div>
          <Switch checked={s.require_approval_for_trusted} label="Ask before accepting from paired devices" hint="Off: devices you paired can send without a prompt." onChange={(v) => void save({ require_approval_for_trusted: v })} />
          <div className="dialog-actions">
            <button className="btn ghost" onClick={() => { close(); go("files"); }}>View received files</button>
            <button className="btn primary" onClick={() => setPair(true)}>Pair a phone</button>
          </div>
        </div>
      </Dialog>
      {pair && <PairDialog onClose={() => setPair(false)} />}
    </>
  );
}
