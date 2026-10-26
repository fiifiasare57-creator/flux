import { useState } from "react";
import { ChevronDown, ChevronRight, Clock } from "lucide-react";
import { useFlux } from "../lib/store";
import { api } from "../lib/api";
import { bytes, fullTime } from "../lib/format";
import { Confirm, Empty } from "../components/ui";
import { HistoryRow, QueuedRow, TransferRow } from "../components/transfers";

type Filter = "all" | "completed" | "failed";

export default function Transfers() {
  const { live, history, refreshHistory, toast, info } = useFlux();
  const [filter, setFilter] = useState<Filter>("all");
  const [open, setOpen] = useState<string | null>(null);
  const [confirm, setConfirm] = useState(false);
  const shown = history.filter((h) => filter === "all" || (filter === "completed" ? h.outcome === "completed" : h.outcome !== "completed"));
  return (
    <div className="page">
      <div className="page-head"><div><h1>Transfers</h1><p className="lead">Live transfers and your history. History shows what happened, not that files still exist.</p></div>
        <button className="btn ghost" disabled={!history.length} onClick={() => setConfirm(true)}>Clear history</button></div>

      <h3 className="sec">In progress</h3>
      {live.length === 0 && !info?.queued.length ? <div className="muted pad">Nothing is being transferred right now.</div> : <div className="card list">{info?.queued.map((q) => <QueuedRow key={q.transfer_id} q={q} />)}{live.map((t) => <TransferRow key={t.transfer_id} t={t} />)}</div>}

      <div className="page-head tight"><h3 className="sec">History</h3>
        <div className="tabs small" role="tablist">{(["all", "completed", "failed"] as Filter[]).map((f) => <button key={f} role="tab" aria-selected={filter === f} className={filter === f ? "on" : ""} onClick={() => setFilter(f)}>{f === "failed" ? "Not completed" : f[0].toUpperCase() + f.slice(1)}</button>)}</div></div>
      {shown.length === 0 ? <Empty icon={<Clock size={26} />} title="No transfers to show" body="Sent and received files will appear here with their real outcome." /> : (
        <div className="card list">
          {shown.slice(0, 200).map((h) => {
            const key = h.id + h.timestamp + h.direction;
            return (
              <div key={key}>
                <button className="row-btn" aria-expanded={open === key} onClick={() => setOpen(open === key ? null : key)}>
                  {open === key ? <ChevronDown size={16} /> : <ChevronRight size={16} />}<HistoryRow h={h} />
                </button>
                {open === key && (
                  <div className="detail">
                    <div className="kv"><span>When</span><b>{fullTime(h.timestamp)}</b></div>
                    <div className="kv"><span>Direction</span><b>{h.direction === "sent" ? "Sent" : "Received"} · {h.peer_name}</b></div>
                    <div className="kv"><span>Size</span><b>{h.file_count} file(s), {bytes(h.total_bytes)}</b></div>
                    {h.error && <div className="kv"><span>Reason</span><b className="error-text">{h.error}</b></div>}
                    <ul className="file-list">{h.files.slice(0, 20).map((f, i) => <li key={i}><span>{f}</span></li>)}</ul>
                    {h.file_count > 20 && <div className="hint">…and {h.file_count - 20} more</div>}
                    {h.direction === "received" && h.outcome === "completed" && <button className="btn sm" onClick={() => void api.openReceiveFolder().catch((e) => toast("error", String(e)))}>Open receive folder</button>}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}
      {confirm && <Confirm title="Clear transfer history?" danger confirmLabel="Clear history" body={<p>This removes the list of past transfers from this device. It does not delete any files.</p>}
        onCancel={() => setConfirm(false)} onConfirm={async () => { await api.clearHistory(); await refreshHistory(); setConfirm(false); toast("info", "History cleared."); }} />}
    </div>
  );
}
