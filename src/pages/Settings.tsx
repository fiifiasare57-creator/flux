import { useEffect, useState } from "react";
import { Copy, FolderOpen } from "lucide-react";
import { useFlux } from "../lib/store";
import { api, pickDirectory, type PatchSettings } from "../lib/api";
import { Confirm, Switch } from "../components/ui";

export default function Settings() {
  const { info, refreshInfo, refreshHistory, toast } = useFlux();
  const [name, setName] = useState("");
  const [confirm, setConfirm] = useState(false);
  useEffect(() => { if (info) setName(info.settings.device_name); }, [info?.settings.device_name]);
  if (!info) return null;
  const s = info.settings;
  const save = async (p: PatchSettings, msg?: string) => {
    try { await api.updateSettings(p); await refreshInfo(); if (msg) toast("success", msg); } catch (e) { toast("error", String(e)); }
  };
  return (
    <div className="page narrow">
      <div className="page-head"><div><h1>Settings</h1><p className="lead">Control where files go and who can send to you.</p></div></div>

      <h3 className="sec">This device</h3>
      <div className="card pad-card">
        <label className="label" htmlFor="devname">Device name</label>
        <div className="row gap"><input id="devname" value={name} maxLength={64} onChange={(e) => setName(e.target.value)} />
          <button className="btn dark" disabled={name.trim() === s.device_name || !name.trim()} onClick={() => void save({ device_name: name }, "Device name saved. Other devices see it after FLUX restarts.")}>Save</button></div>
      </div>

      <h3 className="sec">Receiving</h3>
      <div className="card pad-card">
        <div className="label">Receive folder</div>
        <div className="row gap"><div className="path mono" title={s.receive_dir}>{s.receive_dir}</div>
          <button className="btn" onClick={async () => { const d = await pickDirectory("Choose where received files are saved"); if (d) await save({ receive_dir: d }, "Receive folder updated."); }}>Change…</button>
          <button className="btn ghost" aria-label="Open receive folder" onClick={() => void api.openReceiveFolder().catch((e) => toast("error", String(e)))}><FolderOpen size={15} /> Open</button></div>
        <Switch checked={s.require_approval_for_trusted} label="Ask before accepting from paired devices" hint="Unpaired devices always need your approval, whatever this is set to." onChange={(v) => void save({ require_approval_for_trusted: v })} />
        <Switch checked={s.discoverable} label="Visible to nearby devices" hint="Applies after FLUX restarts. When off, others can still pair using a code." onChange={(v) => void save({ discoverable: v }, "Saved. Restart FLUX to apply.")} />
      </div>

      <h3 className="sec">Diagnostics</h3>
      <div className="card pad-card">
        <div className="kv"><span>Version</span><b>{info.version}</b></div>
        <div className="kv"><span>Port</span><b>{info.port}</b></div>
        <div className="kv"><span>Addresses</span><b className="mono">{info.lan_addrs.join(", ")}</b></div>
        <div className="kv"><span>Device fingerprint</span><b className="mono wrap">{info.fingerprint} <button className="icon-btn" aria-label="Copy fingerprint" onClick={() => void navigator.clipboard.writeText(info.fingerprint).then(() => toast("info", "Fingerprint copied."))}><Copy size={14} /></button></b></div>
      </div>

      <h3 className="sec">Privacy</h3>
      <div className="card pad-card">
        <p className="muted">FLUX sends files directly between your devices over your local network, end-to-end encrypted. No account, no cloud upload. History stores only file names, sizes and outcomes — never file contents.</p>
        <button className="btn ghost" onClick={() => setConfirm(true)}>Clear transfer history</button>
      </div>
      {confirm && <Confirm title="Clear transfer history?" danger confirmLabel="Clear history" body={<p>This removes the list of past transfers. It does not delete any files.</p>} onCancel={() => setConfirm(false)} onConfirm={async () => { await api.clearHistory(); await refreshHistory(); setConfirm(false); toast("info", "History cleared."); }} />}
    </div>
  );
}
