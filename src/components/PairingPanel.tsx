import { Copy, RefreshCw } from "lucide-react";
import { useEffect, useState } from "react";
import { Device, LaptopScreen, PhoneScreen, usePhoneLink } from "./DeviceConnection";
import { useFlux } from "../lib/store";

/** The primary Home feature: phone | real QR + typed code | this computer. Every status shown is real. */
export default function PairingPanel() {
  const { pairing, pairingError, newPairing, toast } = useFlux();
  const k = usePhoneLink(true);
  const [left, setLeft] = useState(0);
  useEffect(() => {
    const t = setInterval(() => setLeft(pairing ? Math.max(0, Math.round((pairing.expiresAt - Date.now()) / 1000)) : 0), 500);
    return () => clearInterval(t);
  }, [pairing]);
  if (!k) return null;
  const { info, phone, active, pct, state, ready, names } = k;
  const connected = state === "connected" || state === "sending" || state === "receiving";
  return (
    <section className="card pair" aria-label="Pair your device">
      <div className="pair-col pair-phone">
        <Device kind="phone" label={phone ? `${phone.name}: ${connected ? "connected" : "not connected"}` : "No phone paired"}>
          <PhoneScreen pcName={info.settings.device_name} paired={!!phone} online={!!phone?.online} progress={active && pct !== null ? { pct, file: active.file_name || "" } : null} />
        </Device>
        <div className="pair-who"><i className={connected ? "dot green" : "dot gray"} /><b>{phone ? phone.name : "Phone"}</b></div>
        <div className="pair-state">{phone ? (connected ? "Connected" : "Paired · not connected right now") : "Not paired yet"}</div>
      </div>

      <div className="pair-col pair-center">
        <h2>Pair Your Device</h2>
        <p className="muted">Open FLUX on your phone, tap <b>Scan QR code</b> and point it here. No camera? Type the code instead.</p>
        {pairing ? (
          <>
            <div className="qr" aria-label="Pairing QR code"><img src={pairing.qr} alt="QR code to pair a phone with this computer" width={200} height={200} /></div>
            <div className="code-row">
              <span className="code mono" aria-label={`Pairing code ${pairing.code.split("").join(" ")}`}>{pairing.code}</span>
              <button className="icon-btn" aria-label="Copy the pairing code" onClick={() => void navigator.clipboard.writeText(pairing.code).then(() => toast("info", "Code copied."))}><Copy size={15} /></button>
            </div>
            <div className={`pair-wait ${left === 0 ? "expired" : ""}`} role="status">{left > 0 ? `Waiting for connection… code expires in ${Math.floor(left / 60)}:${String(left % 60).padStart(2, "0")}` : "Code expired, making a new one…"}</div>
            <div className="row gap center">
              <button className="btn sm" onClick={() => void newPairing()}><RefreshCw size={13} /> New code</button>
              <button className="btn sm" onClick={() => void navigator.clipboard.writeText(pairing.uri).then(() => toast("info", "Full pairing link copied."))}><Copy size={13} /> Copy link</button>
            </div>
          </>
        ) : (
          <div className="empty-mini">{pairingError ?? "Creating a secure one-time code…"}{pairingError && <button className="btn sm" onClick={() => void newPairing()}>Try again</button>}</div>
        )}
      </div>

      <div className="pair-col pair-laptop">
        <Device kind="laptop" label={`${info.settings.device_name}: ${ready ? "ready" : "no local network"}`}><LaptopScreen ready={ready} names={names} /></Device>
        <div className="pair-who"><i className={ready ? "dot green" : "dot amber"} /><b>{info.settings.device_name}</b></div>
        <div className="pair-state">{ready ? "Ready on local network" : "No local network detected"}</div>
        <div className="pair-os">{/Windows/i.test(navigator.userAgent) ? "Windows" : /Mac/i.test(navigator.userAgent) ? "macOS" : "Desktop"}</div>
      </div>
    </section>
  );
}
