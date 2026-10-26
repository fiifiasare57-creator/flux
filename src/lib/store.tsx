import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { getCurrentWebview } from "@tauri-apps/api/webview";
import QRCode from "qrcode";
import { api, events, inTauri, pickFiles, pickFolders, type AppInfo, type HistoryEntry, type Incoming, type Progress } from "./api";

export type Page = "home" | "files" | "transfers" | "devices" | "settings";
export interface Live extends Progress { samples: { t: number; b: number }[]; updatedAt: number }
export interface Toast { id: number; kind: "info" | "error" | "success"; text: string }
export interface PairingState { uri: string; code: string; qr: string; expiresAt: number }
export type Flow = { kind: "send" | "share"; paths: string[] } | null;

interface Ctx {
  page: Page; go: (p: Page) => void;
  info: AppInfo | null; engineError: string | null;
  live: Live[]; history: HistoryEntry[];
  incoming: Incoming | null; respond: (accept: boolean) => Promise<void>;
  dragging: boolean;
  pairing: PairingState | null; pairingError: string | null; newPairing: () => Promise<void>;
  flow: Flow; startFlow: (kind: "send" | "share", paths?: string[]) => Promise<void>; closeFlow: () => void;
  receiveOpen: boolean; setReceiveOpen: (v: boolean) => void;
  sendTo: (fp: string, paths: string[]) => Promise<boolean>;
  pause: (id: string) => void; resume: (id: string) => void; cancel: (id: string) => void; cancelIncoming: (id: string) => void; dismiss: (id: string) => void;
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
export const errText = (e: unknown) => (typeof e === "string" ? e : e instanceof Error ? e.message : "Something went wrong.");

export function FluxProvider({ children }: { children: ReactNode }) {
  const [page, go] = useState<Page>("home");
  const [info, setInfo] = useState<AppInfo | null>(null);
  const [engineError, setEngineError] = useState<string | null>(null);
  const [liveMap, setLiveMap] = useState<Record<string, Live>>({});
  const [history, setHistory] = useState<HistoryEntry[]>([]);
  const [incomingQ, setIncomingQ] = useState<Incoming[]>([]);
  const [dragging, setDragging] = useState(false);
  const [toasts, setToasts] = useState<Toast[]>([]);
  const [pairing, setPairing] = useState<PairingState | null>(null);
  const [pairingError, setPairingError] = useState<string | null>(null);
  const [flow, setFlow] = useState<Flow>(null);
  const [receiveOpen, setReceiveOpen] = useState(false);
  const toastId = useRef(0);
  const knownTrusted = useRef<Set<string> | null>(null);
  const makingPairing = useRef(false);

  const toast = useCallback((kind: Toast["kind"], text: string) => {
    const id = ++toastId.current;
    setToasts((t) => [...t.slice(-3), { id, kind, text }]);
    setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), kind === "error" ? 7000 : 4000);
  }, []);

  /** A real, single-use pairing offer: QR (full link) + short typed code. */
  const newPairing = useCallback(async () => {
    if (makingPairing.current) return;
    makingPairing.current = true;
    try {
      const o = await api.beginPairing();
      const qr = await QRCode.toDataURL(o.uri, { width: 260, margin: 1, errorCorrectionLevel: "M" });
      setPairing({ uri: o.uri, code: o.code, qr, expiresAt: Date.now() + o.expires_in_secs * 1000 });
      setPairingError(null);
    } catch (e) { setPairingError(errText(e)); } finally { makingPairing.current = false; }
  }, []);

  const refreshInfo = useCallback(async () => {
    try {
      const next = await api.appInfo();
      setInfo(next); setEngineError(null);
      const fps = new Set(next.trusted.map((t) => t.fingerprint));
      if (knownTrusted.current) {
        const added = next.trusted.filter((t) => !knownTrusted.current!.has(t.fingerprint));
        if (added.length) { toast("success", `Paired with ${added[0].name}.`); void newPairing(); } // the used code is dead: show a fresh one
      }
      knownTrusted.current = fps;
    } catch (e) { setEngineError(errText(e)); }
  }, [toast, newPairing]);
  const refreshHistory = useCallback(async () => { try { setHistory(await api.history()); } catch { /* engineError covers it */ } }, []);

  useEffect(() => {
    if (!inTauri) { setEngineError("FLUX is running in a plain browser, so there is no transfer engine. Open the FLUX desktop app instead."); return; }
    void refreshInfo(); void refreshHistory(); void newPairing();
    const poll = setInterval(() => { if (!document.hidden) void refreshInfo(); }, 2500);
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
        setTimeout(async () => {
          await refreshHistory(); await refreshInfo();
          setLiveMap((m) => {
            const cur = m[p.transfer_id];
            if (!cur || !TERMINAL.has(cur.state)) return m;
            if (cur.direction === "sent" && cur.state === "failed") return m; // can be retried
            const { [p.transfer_id]: _gone, ...rest } = m;
            if (cur.direction === "sent") void api.forgetJob(p.transfer_id);
            return rest;
          });
        }, 500);
      }
    }));
    return () => { clearInterval(poll); unsubs.forEach((u) => void u.then((f) => f())); };
  }, [refreshInfo, refreshHistory, newPairing]);

  // keep the pairing code fresh: replace it shortly before it expires
  useEffect(() => {
    if (!inTauri) return;
    const t = setInterval(() => { if (pairing && Date.now() >= pairing.expiresAt - 1500) void newPairing(); }, 1000);
    return () => clearInterval(t);
  }, [pairing, newPairing]);

  const startFlow = useCallback(async (kind: "send" | "share", paths?: string[]) => {
    try {
      let chosen = paths ?? [];
      if (!chosen.length) chosen = await pickFiles();
      if (!chosen.length) return;
      setFlow({ kind, paths: chosen });
    } catch (e) { toast("error", errText(e)); }
  }, [toast]);

  useEffect(() => {
    if (!inTauri) return;
    const un = getCurrentWebview().onDragDropEvent((e) => {
      const t = e.payload.type;
      if (t === "enter" || t === "over") setDragging(true);
      else if (t === "leave") setDragging(false);
      else if (t === "drop") { setDragging(false); if (e.payload.paths.length) void startFlow("send", e.payload.paths); }
    });
    return () => { void un.then((f) => f()); };
  }, [startFlow]);

  const sendTo = useCallback(async (fp: string, paths: string[]): Promise<boolean> => {
    try {
      const dev = info?.trusted.find((t) => t.fingerprint === fp);
      if (dev && (dev.device_type === "phone" || dev.device_type === "tablet")) {
        await api.queueSend(fp, paths); // phones don't listen: queued until the phone checks in and accepts
        toast("success", `Queued for ${dev.name}. It receives them when FLUX is open on the phone.`);
        await refreshInfo();
      } else { await api.sendTo(fp, paths); toast("info", "Sending…"); }
      return true;
    } catch (e) { toast("error", errText(e)); return false; }
  }, [toast, info, refreshInfo]);

  const act = useCallback((fn: (id: string) => Promise<unknown>) => (id: string) => { fn(id).catch((e) => toast("error", errText(e))); }, [toast]);
  const dismiss = useCallback((id: string) => { setLiveMap((m) => { const { [id]: _g, ...rest } = m; return rest; }); void api.forgetJob(id); }, []);
  const speedOf = useCallback((l: Live): number | null => {
    if (l.state !== "active" || l.samples.length < 2) return null;
    const a = l.samples[0], b = l.samples[l.samples.length - 1];
    const dt = (b.t - a.t) / 1000;
    if (dt < 1 || Date.now() - l.updatedAt > 3000) return null;
    const v = (b.b - a.b) / dt;
    return v > 0 ? v : null;
  }, []);
  const respond = useCallback(async (accept: boolean) => {
    const cur = incomingQ[0]; if (!cur) return;
    setIncomingQ((q) => q.slice(1));
    try { await api.respond(cur.request_id, accept); } catch (e) { toast("error", errText(e)); }
  }, [incomingQ, toast]);
  const live = useMemo(() => Object.values(liveMap).sort((a, b) => b.updatedAt - a.updatedAt), [liveMap]);

  const value: Ctx = {
    page, go, info, engineError, live, history, incoming: incomingQ[0] ?? null, respond, dragging, pairing, pairingError, newPairing,
    flow, startFlow, closeFlow: () => setFlow(null), receiveOpen, setReceiveOpen, sendTo,
    pause: act(api.pause), resume: act(api.resume), cancel: act(api.cancel), cancelIncoming: act(api.cancelIncoming), dismiss, speedOf,
    refreshInfo, refreshHistory, toasts, toast,
  };
  return <C.Provider value={value}>{children}</C.Provider>;
}

export { pickFolders };
