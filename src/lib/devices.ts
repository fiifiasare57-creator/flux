import { useMemo } from "react";
import type { DeviceType } from "./api";
import { useFlux } from "./store";

export interface Target { fingerprint: string; name: string; type: DeviceType; online: boolean; paired: boolean; addr?: string; queueable: boolean }

/** Every device a file can be sent to, built from real discovery + pairing state (never invented). */
export function useTargets(): Target[] {
  const { info } = useFlux();
  return useMemo(() => {
    if (!info) return [];
    const out: Target[] = info.nearby.map((n) => {
      const t = info.trusted.find((x) => x.fingerprint === n.fingerprint);
      return { fingerprint: n.fingerprint, name: t?.name ?? n.name, type: n.device_type, online: true, paired: n.paired, addr: n.addrs[0], queueable: false };
    });
    for (const t of info.trusted) if (!out.some((c) => c.fingerprint === t.fingerprint)) {
      const phone = t.device_type === "phone" || t.device_type === "tablet";
      out.push({ fingerprint: t.fingerprint, name: t.name, type: t.device_type, online: t.online, paired: true, addr: t.last_addr ?? undefined, queueable: phone });
    }
    return out.sort((a, b) => Number(b.online) - Number(a.online) || a.name.localeCompare(b.name));
  }, [info]);
}
