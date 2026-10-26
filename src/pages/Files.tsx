import { useEffect, useMemo, useState } from "react";
import { FolderOpen, Link2, Search } from "lucide-react";
import { api, type ReceivedFile } from "../lib/api";
import { useFlux, errText } from "../lib/store";
import { bytes, timeAgo } from "../lib/format";
import { KindIcon } from "../components/transfers";
import { Empty } from "../components/ui";

type Kind = "all" | "photos" | "videos" | "docs" | "other";
const kindOf = (n: string): Kind => {
  const e = n.split(".").pop()?.toLowerCase() ?? "";
  if (["jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "svg"].includes(e)) return "photos";
  if (["mp4", "mov", "mkv", "avi", "webm", "m4v"].includes(e)) return "videos";
  if (["pdf", "doc", "docx", "txt", "md", "ppt", "pptx", "xls", "xlsx", "csv"].includes(e)) return "docs";
  return "other";
};
const CHIPS: { id: Kind; label: string }[] = [{ id: "all", label: "All" }, { id: "photos", label: "Photos" }, { id: "videos", label: "Videos" }, { id: "docs", label: "Documents" }, { id: "other", label: "Other" }];

export default function Files() {
  const { info, history, refreshInfo, toast, startFlow } = useFlux();
  const [files, setFiles] = useState<ReceivedFile[] | null>(null);
  const [q, setQ] = useState("");
  const [kind, setKind] = useState<Kind>("all");
  const [sort, setSort] = useState<"new" | "name" | "size">("new");
  const load = () => { api.listReceived().then(setFiles).catch((e) => { setFiles([]); toast("error", errText(e)); }); };
  useEffect(load, [history.length]); // eslint-disable-line react-hooks/exhaustive-deps
  const shown = useMemo(() => {
    const f = (files ?? []).filter((x) => (kind === "all" || kindOf(x.name) === kind) && x.rel.toLowerCase().includes(q.toLowerCase()));
    return [...f].sort((a, b) => sort === "name" ? a.name.localeCompare(b.name) : sort === "size" ? b.size - a.size : b.modified - a.modified);
  }, [files, q, kind, sort]);
  const shares = info?.shares ?? [];
  return (
    <div className="page">
      <header className="page-title"><h1>Files</h1><p className="lead">Everything FLUX has received on this computer, and the links you are sharing.</p></header>

      <section className="card" aria-label="Active share links">
        <div className="card-head"><h3><Link2 size={16} /> Shared links</h3><button className="btn sm" onClick={() => void startFlow("share")}>New link</button></div>
        {shares.length === 0 ? <p className="hint pad">No active links. Create a short link to share files with anyone on your Wi-Fi.</p> : shares.map((s) => (
          <div className="t-row" key={s.token}>
            <div className="thumb"><Link2 size={18} /></div>
            <div className="t-main"><div className="t-title mono">{s.url.replace("http://", "")}</div>
              <div className="t-sub">{s.file_count} file{s.file_count === 1 ? "" : "s"} · {bytes(s.total_bytes)} · {s.downloads} download{s.downloads === 1 ? "" : "s"}{s.max_downloads ? ` of ${s.max_downloads}` : ""} · expires in {Math.max(1, Math.round((s.expires_at * 1000 - Date.now()) / 60000))} min</div></div>
            <div className="t-actions">
              <button className="btn sm" onClick={() => void navigator.clipboard.writeText(s.url).then(() => toast("info", "Link copied."))}>Copy</button>
              <button className="btn sm" onClick={() => { void api.revokeShare(s.token).then(() => { toast("info", "Link stopped."); void refreshInfo(); }); }}>Stop</button>
            </div>
          </div>
        ))}
      </section>

      <section className="card" aria-label="Received files">
        <div className="card-head"><h3>Received files</h3>
          <button className="btn sm" onClick={() => void api.openReceiveFolder().catch((e) => toast("error", errText(e)))}><FolderOpen size={14} /> Open folder</button></div>
        <div className="toolbar">
          <label className="search"><Search size={15} /><input placeholder="Search received files" value={q} onChange={(e) => setQ(e.target.value)} aria-label="Search received files" /></label>
          <div className="chips" role="group" aria-label="Filter by type">{CHIPS.map((c) => <button key={c.id} className={kind === c.id ? "chipb on" : "chipb"} aria-pressed={kind === c.id} onClick={() => setKind(c.id)}>{c.label}</button>)}</div>
          <select value={sort} onChange={(e) => setSort(e.target.value as typeof sort)} aria-label="Sort files"><option value="new">Newest</option><option value="name">Name</option><option value="size">Size</option></select>
        </div>
        {files === null ? <p className="hint pad">Loading…</p> : shown.length === 0 ? (
          <Empty icon={<FolderOpen size={24} />} title={files.length ? "No matching files" : "No received files yet"} body={files.length ? "Try a different search or filter." : "Files that arrive from your phone or other computers are listed here."} />
        ) : shown.slice(0, 300).map((f) => (
          <div className="t-row" key={f.rel}>
            <div className="thumb"><KindIcon name={f.name} /></div>
            <div className="t-main"><div className="t-title">{f.name}</div><div className="t-sub">{f.rel !== f.name ? f.rel.slice(0, f.rel.length - f.name.length) + " · " : ""}{bytes(f.size)} · {timeAgo(f.modified)}</div></div>
            <div className="t-actions">
              <button className="btn sm" onClick={() => void api.openReceived(f.rel).catch((e) => toast("error", errText(e)))}>Open</button>
              <button className="btn sm" onClick={() => void api.revealReceived(f.rel).catch((e) => toast("error", errText(e)))}>Show in folder</button>
            </div>
          </div>
        ))}
        <p className="hint pad">FLUX never opens received files by itself, and won't open programs or scripts. Use Show in folder for those.</p>
      </section>
    </div>
  );
}
