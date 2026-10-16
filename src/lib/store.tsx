import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { getCurrentWebview } from "@tauri-apps/api/webview";
import { api, events, inTauri, pickFiles, type AppInfo, type HistoryEntry, type Incoming, type Progress } from "./api";

export type Page = "share" | "devices" | "activity" | "settings";
export interface Live extends Progress {
  samples: { t: number; b: number }[];
  updatedAt: number;
}
export interface Toast { id: number; kind: "info" | "error" | "success"; text: string }

interface Ctx {
  page: Page; go: (p: Page) => void;
  info: AppInfo | null; engineError: string | null;
  live: Live[]; history: HistoryEntry[];
  incoming: Incoming | null; respond: (accept: boolean) => Promise<void>;
  staged: string[]; stage: (paths: string[]) => void; unstage: () => void; dragging: boolean;
  sendTo: (fp: string, paths?: string[]) => Promise<void>;
  pause: (id: string) => void; cancelIncoming: (id: string) => void; resume: (id: string) => void; cancel: (id: string) => void; dismiss: (id: string) => void;
  speedOf: (l: Live) => number | null;
  refreshInfo: () => Promise<void>; refreshHistory: () => Promise<void>;
  toasts: Toast[]; toast: (kind: Toast["kind"], text: string) => void;
}
const C = createContext<Ctx | null>(null);
export const useFlux = () => {
  const c = useContext(C);
  if (!c) throw new Error("FluxProvider missing");
  return c;
};

const TERMINAL = new Set(["completed", "failed", "cancelled", "rejected"]);
const errText = (e: unknown) => (typeof e === "string" ? e : e instanceof Error ? e.message : "Something went wrong.");

export function FluxProvider({ children }: { children: ReactNode }) {
  const [page, go] = useState<Page>("share");
  const [info, setInfo] = useState<AppInfo | null>(null);
  const [engineError, setEngineError] = useState<string | null>(null);
  const [liveMap, setLiveMap] = useState<Record<string, Live>>({});
  const [history, setHistory] = useState<HistoryEntry[]>([]);
  const [incomingQ, setIncomingQ] = useState<Incoming[]>([]);
  const [staged, setStaged] = useState<string[]>([]);
  const [dragging, setDragging] = useState(false);
  const [toasts, setToasts] = useState<Toast[]>([]);
  const toastId = useRef(0);

  const toast = useCallback((kind: Toast["kind"], text: string) => {
    const id = ++toastId.current;
    setToasts((t) => [...t.slice(-3), { id, kind, text }]);
    setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), kind === "error" ? 7000 : 4000);
  }, []);

  const refreshInfo = useCallback(async () => {
    try { setInfo(await api.appInfo()); setEngineError(null); } catch (e) { setEngineError(errText(e)); }
  }, []);
  const refreshHistory = useCallback(async () => {
    try { setHistory(await api.history()); } catch { /* shown via engineError on info */ }
  }, []);

  useEffect(() => {
    if (!inTauri) {
      setEngineError("FLUX is running in a plain browser, so there is no transfer engine. Open the FLUX desktop app instead.");
      return;
    }
    void refreshInfo();
    void refreshHistory();
    // Presence and the outbox change without any event, so look every few seconds (only while the window is visible).
    const poll = setInterval(() => { if (!document.hidden) { void refreshInfo(); } }, 2500);
    const unsubs: Promise<() => void>[] = [];
    unsubs.push(events.peersChanged(() => void refreshInfo()));
    unsubs.push(events.incoming((i) => setIncomingQ((q) => [...q, i])));
    unsubs.push(events.incomingClosed((id) => setIncomingQ((q) => q.filter((x) => x.request_id !== id))));
    unsubs.push(events.progress((p) => {
      const now = Date.now();
      setLiveMap((m) => {
        const prev = m[p.transfer_id];
        const samples = [...(prev?.samples ?? []), { t: now, b: p.bytes_done }].filter((s) => now - s.t < 6000);
        return { ...m, [p.transfer_id]: { ...p, samples, updatedAt: now } };
      });
      if (TERMINAL.has(p.state)) {
        // History is written just after the final event; refresh, then retire finished rows.
        setTimeout(async () => {
          await refreshHistory();
          await refreshInfo();
          setLiveMap((m) => {
            const cur = m[p.transfer_id];
            if (!cur || !TERMINAL.has(cur.state)) return m;
            const keep = cur.direction === "sent" && cur.state === "failed"; // can be retried
            if (keep) return m;
            const { [p.transfer_id]: _gone, ...rest } = m;
            if (cur.direction === "sent") void api.forgetJob(p.transfer_id);
            return rest;
          });
        }, 500);
      }
    }));
    return () => { clearInterval(poll); unsubs.forEach((u) => void u.then((f) => f())); };
  }, [refreshInfo, refreshHistory]);

  const stage = useCallback((paths: string[]) => {
    if (!paths.length) return;
    setStaged((s) => Array.from(new Set([...s, ...paths])));
    go("share");
  }, []);

  useEffect(() => {
    if (!inTauri) return;
    const un = getCurrentWebview().onDragDropEvent((e) => {
      const t = e.payload.type;
      if (t === "enter" || t === "over") setDragging(true);
      else if (t === "leave") setDragging(false);
      else if (t === "drop") { setDragging(false); stage(e.payload.paths); }
    });
    return () => { void un.then((f) => f()); };
  }, [stage]);

  const sendTo = useCallback(async (fp: string, paths?: string[]) => {
    try {
      const chosen = paths && paths.length ? paths : await pickFiles();
      if (!chosen.length) return;
      const dev = info?.trusted.find((t) => t.fingerprint === fp);
      if (dev && (dev.device_type === "phone" || dev.device_type === "tablet")) {
        // Phones don't listen for connections: the files wait here until the phone checks in and accepts them.
        await api.queueSend(fp, chosen);
        toast("success", `Queued for ${dev.name}. It receives them when FLUX is open on the phone.`);
        await refreshInfo();
      } else {
        await api.sendTo(fp, chosen);
      }
      setStaged([]);
    } catch (e) { toast("error", errText(e)); }
  }, [toast, info, refreshInfo]);

  const act = useCallback((fn: (id: string) => Promise<void>) => (id: string) => { fn(id).catch((e) => toast("error", errText(e))); }, [toast]);
  const dismiss = useCallback((id: string) => {
    setLiveMap((m) => { const { [id]: _g, ...rest } = m; return rest; });
    void api.forgetJob(id);
  }, []);

  const speedOf = useCallback((l: Live): number | null => {
    if (l.state !== "active" || l.samples.length < 2) return null;
    const a = l.samples[0], b = l.samples[l.samples.length - 1];
    const dt = (b.t - a.t) / 1000;
    if (dt < 1 || Date.now() - l.updatedAt > 3000) return null;
    const v = (b.b - a.b) / dt;
    return v > 0 ? v : null;
  }, []);

  const respond = useCallback(async (accept: boolean) => {
    const cur = incomingQ[0];
    if (!cur) return;
    setIncomingQ((q) => q.slice(1));
    try { await api.respond(cur.request_id, accept); } catch (e) { toast("error", errText(e)); }
  }, [incomingQ, toast]);

  const live = useMemo(() => Object.values(liveMap).sort((a, b) => b.updatedAt - a.updatedAt), [liveMap]);

  const value: Ctx = {
    page, go, info, engineError, live, history, incoming: incomingQ[0] ?? null, respond,
    staged, stage, unstage: () => setStaged([]), dragging, sendTo,
    pause: act(api.pause), cancelIncoming: (id: string) => { api.cancelIncoming(id).catch((e) => toast("error", errText(e))); }, resume: act(api.resume), cancel: act(api.cancel), dismiss, speedOf,
    refreshInfo, refreshHistory, toasts, toast,
  };
  return <C.Provider value={value}>{children}</C.Provider>;
}
