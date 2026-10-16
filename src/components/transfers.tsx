import { Archive, Ban, Check, File as FileIcon, FileText, Film, Image as ImageIcon, Pause, Play, RotateCw, X } from "lucide-react";
import { useFlux, type Live } from "../lib/store";
import { bytes, eta, timeAgo, baseName } from "../lib/format";
import { api, type HistoryEntry, type Queued } from "../lib/api";

export function KindIcon({ name, size = 20 }: { name: string; size?: number }) {
  const ext = name.split(".").pop()?.toLowerCase() ?? "";
  if (["jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "svg"].includes(ext)) return <ImageIcon size={size} />;
  if (["mp4", "mov", "mkv", "avi", "webm", "m4v"].includes(ext)) return <Film size={size} />;
  if (["zip", "rar", "7z", "tar", "gz"].includes(ext)) return <Archive size={size} />;
  if (["pdf", "doc", "docx", "txt", "md", "ppt", "pptx", "xls", "xlsx"].includes(ext)) return <FileText size={size} />;
  return <FileIcon size={size} />;
}

const STATE_TEXT: Record<string, string> = {
  awaiting_approval: "Waiting for approval…", reconnecting: "Connection lost, reconnecting…", paused: "Paused",
  failed: "Failed", cancelled: "Cancelled", rejected: "Declined", completed: "Completed",
};

export function TransferRow({ t }: { t: Live }) {
  const { pause, resume, cancel, cancelIncoming, dismiss, speedOf } = useFlux();
  const sent = t.direction === "sent";
  const title = t.files_total > 1 ? `${t.files_total} files` : baseName(t.file_name || "") || "Transfer";
  const pct = t.bytes_total > 0 ? Math.min(100, Math.floor((t.bytes_done / t.bytes_total) * 100)) : 0;
  const speed = speedOf(t);
  const remaining = speed ? (t.bytes_total - t.bytes_done) / speed : NaN;
  const who = sent ? `Sending to ${t.peer_name}` : `Receiving from ${t.peer_name}`;
  const active = t.state === "active";
  const sub = active ? who : `${who} · ${STATE_TEXT[t.state] ?? t.state}`;
  return (
    <div className="t-row">
      <div className="thumb"><KindIcon name={t.file_name || "x"} /></div>
      <div className="t-main">
        <div className="t-title">{title}{t.files_total > 1 && t.file_name ? <span className="muted"> · {baseName(t.file_name)}</span> : null}</div>
        <div className="t-sub">{sub}</div>
        {(active || t.state === "paused" || t.state === "reconnecting") && t.bytes_total > 0 && (
          <>
            <div className="bar" role="progressbar" aria-valuenow={pct} aria-valuemin={0} aria-valuemax={100} aria-label={`${title} progress`}><span style={{ width: `${pct}%` }} /></div>
            <div className="t-meta"><span className="accent">{pct}%</span><span>{bytes(t.bytes_done)} / {bytes(t.bytes_total)}</span>{speed && <span>{bytes(speed)}/s {Number.isFinite(remaining) ? `· ${eta(remaining)}` : ""}</span>}</div>
          </>
        )}
        {t.state === "failed" && t.error && <div className="error-text small">{t.error}</div>}
      </div>
      <div className="t-actions">
        {sent && active && <button className="icon-btn round" aria-label="Pause transfer" title="Pause (keeps what was already sent)" onClick={() => pause(t.transfer_id)}><Pause size={16} /></button>}
        {sent && t.state === "paused" && <button className="icon-btn round" aria-label="Resume transfer" title="Resume" onClick={() => resume(t.transfer_id)}><Play size={16} /></button>}
        {sent && t.state === "failed" && <button className="icon-btn round" aria-label="Retry transfer" title="Retry (resumes from verified data)" onClick={() => resume(t.transfer_id)}><RotateCw size={16} /></button>}
        {sent && !["completed", "cancelled", "rejected"].includes(t.state) && <button className="icon-btn round" aria-label="Cancel transfer" title="Cancel" onClick={() => cancel(t.transfer_id)}><X size={16} /></button>}
        {!sent && active && <button className="icon-btn round" aria-label="Cancel incoming transfer" title="Cancel (the sender is told; partial data is deleted)" onClick={() => cancelIncoming(t.transfer_id)}><X size={16} /></button>}
        {((!sent && !active) || ["completed", "cancelled", "rejected"].includes(t.state)) && <button className="icon-btn round" aria-label="Dismiss" title="Dismiss" onClick={() => dismiss(t.transfer_id)}><X size={16} /></button>}
      </div>
    </div>
  );
}

export function OutcomeBadge({ o }: { o: HistoryEntry["outcome"] }) {
  if (o === "completed") return <span className="badge ok"><Check size={13} /> Completed</span>;
  if (o === "rejected") return <span className="badge muted"><Ban size={13} /> Declined</span>;
  if (o === "cancelled") return <span className="badge muted"><Ban size={13} /> Cancelled</span>;
  return <span className="badge bad"><X size={13} /> Failed</span>;
}

export function HistoryRow({ h, compact }: { h: HistoryEntry; compact?: boolean }) {
  const first = h.files[0] ?? "";
  const title = h.file_count > 1 ? `${first ? baseName(first) : "Files"} + ${h.file_count - 1} more` : baseName(first) || "Transfer";
  return (
    <div className="h-row">
      <div className="thumb sm"><KindIcon name={first} size={18} /></div>
      <div className="h-main">
        <div className="t-title">{title}</div>
        <div className="t-sub">{h.direction === "sent" ? "Sent to" : "Received from"} {h.peer_name}{compact ? "" : ` · ${bytes(h.total_bytes)}`}</div>
      </div>
      <div className="h-end">{compact ? <span className="muted small">{timeAgo(h.timestamp)}</span> : <><OutcomeBadge o={h.outcome} /><span className="muted small">{timeAgo(h.timestamp)}</span></>}</div>
    </div>
  );
}

/** Files queued on this PC for a paired phone. They are delivered only when the phone checks in and accepts. */
export function QueuedRow({ q }: { q: Queued }) {
  const { refreshInfo, toast } = useFlux();
  const first = q.names[0] ?? "";
  const title = q.file_count > 1 ? `${q.file_count} files` : baseName(first) || "Files";
  const delivering = q.state === "claimed";
  return (
    <div className="t-row">
      <div className="thumb"><KindIcon name={first || "x"} /></div>
      <div className="t-main">
        <div className="t-title">{title}{q.file_count > 1 && first ? <span className="muted"> · {baseName(first)}</span> : null}</div>
        <div className="t-sub">{delivering ? `Delivering to ${q.peer_name}…` : `Queued · waiting for ${q.peer_name} to receive`} · {bytes(q.total_bytes)}</div>
        {!delivering && <div className="t-sub">The phone gets them when FLUX is open on it. Nothing is sent until the phone accepts.</div>}
      </div>
      <div className="t-actions">
        <button className="icon-btn round" aria-label={`Cancel ${title}`} title="Cancel" onClick={() => { api.cancelQueued(q.transfer_id).then(() => refreshInfo()).catch((e) => toast("error", String(e))); }}><X size={16} /></button>
      </div>
    </div>
  );
}
