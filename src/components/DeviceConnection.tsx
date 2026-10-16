import { useEffect, useRef, useState, type ReactNode } from "react";
import laptopImg from "../assets/devices/laptop.webp";
import phoneImg from "../assets/devices/phone.webp";
import spec from "../assets/devices/devices.json";
import logo from "../assets/logo.png";
import { useFlux } from "../lib/store";
import { bytes } from "../lib/format";

/** Where the display is inside each product photo (measured), minus the bezel, and the design size of the content. */
const KINDS = {
  laptop: { src: laptopImg, s: spec.laptop, inset: { l: 0.016, r: 0.016, t: 0.046, b: 0.008 }, design: { w: 960, h: 600 }, radius: "0.5% / 0.8%", alt: "Dark graphite laptop showing the FLUX desktop app" },
  phone: { src: phoneImg, s: spec.phone, inset: { l: 0.022, r: 0.022, t: 0.012, b: 0.012 }, design: { w: 390, h: 868 }, radius: "13.5% / 6%", alt: "Black smartphone showing the FLUX mobile app" },
} as const;

function Device({ kind, children, label }: { kind: keyof typeof KINDS; children: ReactNode; label: string }) {
  const k = KINDS[kind], r = k.s.screen;
  const box = { left: r.left + r.width * k.inset.l, top: r.top + r.height * k.inset.t, width: r.width * (1 - k.inset.l - k.inset.r), height: r.height * (1 - k.inset.t - k.inset.b) };
  const ref = useRef<HTMLDivElement>(null);
  const [scale, setScale] = useState(0.3);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const fit = () => setScale((el.clientWidth * box.width) / k.design.w);
    fit();
    const ro = new ResizeObserver(fit);
    ro.observe(el);
    return () => ro.disconnect();
  }, [box.width, k.design.w]);
  return (
    <div className={`device ${kind}`} ref={ref} style={{ aspectRatio: `${k.s.w} / ${k.s.h}` }} role="img" aria-label={label}>
      <img src={k.src} alt="" width={k.s.w} height={k.s.h} decoding="async" draggable={false} />
      <div className="screen" aria-hidden="true" style={{ left: `${box.left * 100}%`, top: `${box.top * 100}%`, width: `${box.width * 100}%`, height: `${box.height * 100}%`, borderRadius: k.radius }}>
        <div className="screen-inner" style={{ width: k.design.w, height: k.design.h, transform: `scale(${scale})` }}>{children}</div>
        <div className="glare" />
      </div>
    </div>
  );
}

const NAV = ["Share", "Devices", "Activity", "Settings"];

/** The real desktop app, miniaturised: same nav, same status line, same device names. */
function LaptopScreen({ ready, names }: { ready: boolean; names: string[] }) {
  return (
    <div className="ms-desktop">
      <div className="ms-side"><div className="ms-brand"><img src={logo} alt="" /><b>FLUX</b></div>{NAV.map((n, i) => <div key={n} className={i === 0 ? "ms-nav on" : "ms-nav"}>{n}</div>)}</div>
      <div className="ms-main">
        <div className="ms-top"><i className={ready ? "dot green" : "dot gray"} />{ready ? "Ready on local network" : "No local network detected"}</div>
        <div className="ms-h1">Share</div>
        <div className="ms-drop">Drop files here or click to browse</div>
        <div className="ms-sec">Nearby Devices</div>
        <div className="ms-cards">{names.length ? names.slice(0, 3).map((n) => <div key={n} className="ms-card">{n}</div>) : <div className="ms-empty">No devices yet</div>}</div>
      </div>
    </div>
  );
}

/** The real Android home screen, miniaturised (header, paired-PC card, actions, live progress). */
function PhoneScreen({ pcName, paired, online, progress }: { pcName: string; paired: boolean; online: boolean; progress: { pct: number; file: string } | null }) {
  return (
    <div className="ms-phone">
      <div className="ms-hero"><div className="ms-brandrow"><img src={logo} alt="" /><b>FLUX</b></div><div className="ms-head">Your files,<br />in motion.</div></div>
      <div className="ms-pccard"><div className="ms-avatar">{paired ? pcName.slice(0, 1).toUpperCase() : "?"}</div>
        <div className="ms-pctext"><b>{paired ? pcName : "No PC paired yet"}</b><span>{paired ? "Trusted" : "Scan the PC's QR code"}</span></div>
        <em className={paired && online ? "ms-chip ok" : "ms-chip"}>{paired ? (online ? "● Online" : "○ Not reachable") : "Not paired"}</em></div>
      <div className={paired ? "ms-btn dark" : "ms-btn dark off"}>↑ Choose files to send</div>
      <div className="ms-btn">{paired ? "Pair a different PC" : "Scan QR code to pair"}</div>
      {progress && <div className="ms-prog"><b>{progress.pct}%</b><div className="ms-bar"><i style={{ width: `${progress.pct}%` }} /></div><span>{progress.file}</span></div>}
    </div>
  );
}

export type LinkState = "none" | "waiting" | "paired" | "connected" | "sending" | "receiving";

/** Phone <-> laptop visual. Every state below is derived from real application state, never decorative. */
export default function DeviceConnection({ waitingForScan = false, compact = false }: { waitingForScan?: boolean; compact?: boolean }) {
  const { info, live } = useFlux();
  if (!info) return null;
  const phones = info.trusted.filter((t) => t.device_type === "phone" || t.device_type === "tablet").sort((a, b) => (b.last_seen ?? 0) - (a.last_seen ?? 0) || b.paired_at - a.paired_at);
  const phone = phones[0];
  const active = phone ? live.find((l) => l.state === "active" && l.peer_name === phone.name) : undefined;
  const claimed = phone ? info.queued.find((q) => q.peer_fingerprint === phone.fingerprint && q.state === "claimed") : undefined;
  const waitingPickup = phone ? info.queued.find((q) => q.peer_fingerprint === phone.fingerprint && q.state === "queued") : undefined;
  const pct = active && active.bytes_total > 0 ? Math.floor((active.bytes_done / active.bytes_total) * 100) : null;
  let state: LinkState = "none";
  if (phone) state = active ? (active.direction === "sent" ? "sending" : "receiving") : claimed ? "sending" : phone.online ? "connected" : "paired";
  else if (waitingForScan) state = "waiting";
  const ready = info.lan_addrs.length > 0 && !(info.lan_addrs.length === 1 && info.lan_addrs[0].startsWith("127."));
  const labels: Record<LinkState, string> = {
    none: "No phone paired", waiting: "Waiting for your phone to scan…", paired: "Paired · not connected right now", connected: "Phone connected",
    sending: pct !== null ? `Sending to phone · ${pct}%` : "Sending to phone…", receiving: pct !== null ? `Receiving from phone · ${pct}%` : "Receiving from phone…",
  };
  const names = [...info.nearby.map((n) => n.name), ...info.trusted.filter((t) => !info.nearby.some((n) => n.fingerprint === t.fingerprint)).map((t) => t.name)];
  return (
    <div className={`dc ${compact ? "compact" : ""}`}>
      <div className="dc-phone"><Device kind="phone" label={phone ? `${phone.name}: ${state === "connected" ? "connected" : "not connected"}` : "No phone paired"}>
        <PhoneScreen pcName={info.settings.device_name} paired={!!phone} online={!!phone?.online} progress={active && pct !== null ? { pct, file: active.file_name || "" } : null} /></Device>
        <div className="dc-name">{phone ? phone.name : "Your phone"}</div>
        <div className="dc-sub">{phone ? (phone.online ? "Connected" : "Not connected right now") : "Not paired"}</div></div>
      <div className="dc-link" aria-live="polite">
        <div className={`dc-line ${state}`} aria-hidden="true" />
        <div className={`dc-pill ${state}`}>{labels[state]}</div>
        {waitingPickup && state !== "sending" && <div className="dc-note">{waitingPickup.file_count} file{waitingPickup.file_count === 1 ? "" : "s"} · {bytes(waitingPickup.total_bytes)} waiting for the phone</div>}
      </div>
      <div className="dc-laptop"><Device kind="laptop" label={`${info.settings.device_name}: ${ready ? "ready" : "no local network"}`}><LaptopScreen ready={ready} names={names} /></Device>
        <div className="dc-name">{info.settings.device_name}</div>
        <div className="dc-sub">{ready ? "Ready on local network" : "No local network detected"}</div></div>
    </div>
  );
}
