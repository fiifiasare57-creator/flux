import { useMemo, useState } from "react";
import { ArrowRight, FolderOpen, Radar, RefreshCw, Repeat, Upload, X } from "lucide-react";
import { useFlux } from "../lib/store";
import { api, pickFiles, pickFolders } from "../lib/api";
import { greeting, baseName } from "../lib/format";
import { DeviceCard, TypeIcon, type Card } from "../components/devices";
import { HistoryRow, QueuedRow, TransferRow } from "../components/transfers";
import { Confirm, Empty } from "../components/ui";
import { PairDialog } from "../components/dialogs";

export function useCards(): Card[] {
  const { info } = useFlux();
  return useMemo(() => {
    if (!info) return [];
    const cards: Card[] = info.nearby.map((n) => ({ fingerprint: n.fingerprint, name: info.trusted.find((t) => t.fingerprint === n.fingerprint)?.name ?? n.name, type: n.device_type, online: true, paired: n.paired, addr: n.addrs[0] }));
    for (const t of info.trusted) if (!cards.some((c) => c.fingerprint === t.fingerprint)) cards.push({ fingerprint: t.fingerprint, name: t.name, type: t.device_type, online: t.online, paired: true, addr: t.last_addr ?? undefined, queueable: t.device_type === "phone" || t.device_type === "tablet" });
    return cards.sort((a, b) => Number(b.online) - Number(a.online) || a.name.localeCompare(b.name));
  }, [info]);
}

export default function Share() {
  const { info, live, history, staged, stage, unstage, sendTo, go, refreshInfo, toast } = useFlux();
  const cards = useCards();
  const [pairing, setPairing] = useState(false);
  const [unpair, setUnpair] = useState<Card | null>(null);
  const queue = live.filter((l) => !(l.direction === "received" && ["completed", "cancelled", "rejected"].includes(l.state)));
  const activeCount = live.filter((l) => ["active", "awaiting_approval", "reconnecting"].includes(l.state)).length + (info?.queued.length ?? 0);
  const quick = useMemo(() => {
    if (!info) return null;
    const recent = history.find((h) => info.trusted.some((t) => t.fingerprint === h.peer_fingerprint));
    const fp = recent?.peer_fingerprint ?? [...info.trusted].sort((a, b) => b.paired_at - a.paired_at)[0]?.fingerprint;
    return cards.find((c) => c.fingerprint === fp) ?? null;
  }, [info, history, cards]);

  const choose = async (folders: boolean) => { try { stage(folders ? await pickFolders() : await pickFiles()); } catch (e) { toast("error", String(e)); } };

  return (
    <div className="share">
      <section className="share-main">
        <h1>{greeting()}</h1>
        <p className="lead">Share files, photos and more — wirelessly, across your devices.</p>

        <div className={`dropzone ${staged.length ? "has" : ""}`}>
          {staged.length === 0 ? (
            <>
              <div className="dz-icon"><Upload size={26} /></div>
              <div className="dz-title">Drop files here or click to browse</div>
              <div className="muted">Photos, videos, documents, folders and more</div>
              <div className="row gap center">
                <button className="btn dark" onClick={() => void choose(false)}><FolderOpen size={16} /> Select Files</button>
                <button className="btn ghost" onClick={() => void choose(true)}>Select Folder</button>
              </div>
            </>
          ) : (
            <>
              <div className="dz-title">{staged.length} item{staged.length === 1 ? "" : "s"} ready to send</div>
              <div className="chips">
                {staged.slice(0, 6).map((p) => <span key={p} className="chip-file" title={p}>{baseName(p)}</span>)}
                {staged.length > 6 && <span className="chip-file">+{staged.length - 6} more</span>}
              </div>
              <div className="muted">Choose a device below to send. Files are sent unchanged; nothing is compressed.</div>
              <div className="row gap center">
                <button className="btn ghost" onClick={() => void choose(false)}>Add more files</button>
                <button className="btn ghost" onClick={unstage}><X size={14} /> Clear</button>
              </div>
            </>
          )}
        </div>

        <div className="sec-head">
          <div><h3><Radar size={18} /> Nearby Devices</h3><div className="muted small">Devices running FLUX on your network</div></div>
          <button className="link" onClick={() => void refreshInfo()}><RefreshCw size={14} /> Refresh</button>
        </div>
        {cards.length === 0 ? (
          <Empty icon={<Radar size={26} />} title="No devices found yet" body="Open FLUX on another device on the same Wi-Fi or network. If nothing appears, pair it with a code instead, or check Devices → Troubleshooting.">
            <div className="row gap center"><button className="btn dark" onClick={() => setPairing(true)}>Pair a device</button><button className="btn ghost" onClick={() => go("devices")}>Troubleshoot</button></div>
          </Empty>
        ) : (
          <div className="dev-grid">
            {cards.map((c) => <DeviceCard key={c.fingerprint} d={c} hasStaged={staged.length > 0} onSend={() => void sendTo(c.fingerprint, staged)} onUnpair={c.paired ? () => setUnpair(c) : undefined} />)}
          </div>
        )}

        <div className="sec-head"><div><h3>Recent Transfers</h3><div className="muted small">Your latest file transfers</div></div><button className="link" onClick={() => go("activity")}>View all</button></div>
        {history.length === 0 ? <div className="muted pad">Nothing yet. Completed and failed transfers will be listed here.</div>
          : <div className="card list">{history.slice(0, 3).map((h) => <HistoryRow key={h.id + h.timestamp + h.direction} h={h} />)}</div>}
      </section>

      <aside className="share-side">
        <div className="card">
          <div className="card-title"><TypeIcon t={quick?.type ?? "phone"} size={26} /><div><b>Quick Send</b><div className="muted small">{quick ? "Send to your most recent device" : "Pair a device to enable Quick Send"}</div></div></div>
          {quick ? (
            <button className="quick" disabled={!quick.online} onClick={() => void sendTo(quick.fingerprint, staged)}>
              <TypeIcon t={quick.type} size={22} /><span>{quick.name}</span><em className="muted small">{quick.online ? "" : "Offline"}</em><ArrowRight size={16} />
            </button>
          ) : <button className="btn ghost sm" onClick={() => setPairing(true)}>Pair a device</button>}
        </div>

        <div className="card">
          <div className="card-title"><Repeat size={22} /><b>Transfer Queue</b><span className="pill">{activeCount} active</span></div>
          {queue.length === 0 && !info?.queued.length ? <div className="muted pad-sm">No transfers in progress.</div> : <>{info?.queued.map((q) => <QueuedRow key={q.transfer_id} q={q} />)}{queue.map((t) => <TransferRow key={t.transfer_id} t={t} />)}</>}
        </div>

        <div className="card">
          <div className="card-title"><b>Recent Activity</b><button className="link right" onClick={() => go("activity")}>View all</button></div>
          {history.length === 0 ? <div className="muted pad-sm">No activity yet.</div> : history.slice(0, 5).map((h) => <HistoryRow key={"a" + h.id + h.timestamp + h.direction} h={h} compact />)}
        </div>

        <div className="card flow" aria-disabled="true">
          <div className="flow-mark"><Repeat size={22} /></div>
          <div><b>FLUX Flow <span className="pill">Coming soon</span></b><div className="muted small">Send collections, organize your files, and automate transfers.</div></div>
        </div>
      </aside>

      {pairing && <PairDialog onClose={() => setPairing(false)} />}
      {unpair && <Confirm title={`Unpair ${unpair.name}?`} danger confirmLabel="Unpair" body={<p>{unpair.name} will no longer be trusted. Its transfers will need your approval each time.</p>}
        onCancel={() => setUnpair(null)} onConfirm={async () => { await api.revoke(unpair.fingerprint); setUnpair(null); await refreshInfo(); toast("info", `${unpair.name} was unpaired.`); }} />}
    </div>
  );
}
