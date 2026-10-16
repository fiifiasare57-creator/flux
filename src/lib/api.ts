import { invoke } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";
import { open } from "@tauri-apps/plugin-dialog";

export type DeviceType = "desktop" | "laptop" | "phone" | "tablet" | "unknown";
export type TState = "awaiting_approval" | "active" | "reconnecting" | "paused" | "completed" | "failed" | "cancelled" | "rejected";
export type Outcome = "completed" | "failed" | "cancelled" | "rejected";

export interface Settings { device_name: string; receive_dir: string; require_approval_for_trusted: boolean; discoverable: boolean }
export interface Trusted { fingerprint: string; name: string; device_type: DeviceType; paired_at: number; last_addr: string | null; online: boolean; last_seen: number | null }
export interface Queued { transfer_id: string; peer_fingerprint: string; peer_name: string; file_count: number; total_bytes: number; names: string[]; created: number; state: "queued" | "claimed" }
export interface Nearby { fingerprint: string; name: string; device_type: DeviceType; addrs: string[]; paired: boolean }
export interface AppInfo {
  fingerprint: string; port: number; version: string; settings: Settings; trusted: Trusted[];
  nearby: Nearby[]; queued: Queued[]; lan_addrs: string[]; discovery_active: boolean;
}
export interface Progress {
  transfer_id: string; direction: "sent" | "received"; peer_name: string; state: TState; file_name: string;
  files_done: number; files_total: number; bytes_done: number; bytes_total: number; error: string | null;
}
export interface HistoryEntry {
  id: string; timestamp: number; direction: "sent" | "received"; peer_name: string; peer_fingerprint: string;
  files: string[]; file_count: number; total_bytes: number; outcome: Outcome; error: string | null;
}
export interface Incoming {
  request_id: string; peer_name: string; peer_fingerprint: string; trusted: boolean; file_count: number;
  files: { name: string; size: number }[]; total_bytes: number; destination: string;
}
export interface PatchSettings { device_name?: string; receive_dir?: string; require_approval_for_trusted?: boolean; discoverable?: boolean }

export const inTauri = typeof window !== "undefined" && "__TAURI_INTERNALS__" in window;

export const api = {
  appInfo: () => invoke<AppInfo>("app_info"),
  updateSettings: (patch: PatchSettings) => invoke<Settings>("update_settings", { patch }),
  history: () => invoke<HistoryEntry[]>("history"),
  clearHistory: () => invoke<void>("clear_history"),
  beginPairing: () => invoke<{ uri: string; expires_in_secs: number }>("begin_pairing"),
  pairWith: (uri: string) => invoke<Trusted>("pair_with", { uri }),
  revoke: (fingerprint: string) => invoke<boolean>("revoke", { fingerprint }),
  sendTo: (fingerprint: string, paths: string[]) => invoke<string>("send_to", { fingerprint, paths }),
  queueSend: (fingerprint: string, paths: string[]) => invoke<string>("queue_send", { fingerprint, paths }),
  cancelQueued: (id: string) => invoke<boolean>("cancel_queued", { id }),
  resume: (id: string) => invoke<void>("resume_transfer", { id }),
  pause: (id: string) => invoke<void>("pause_transfer", { id }),
  cancel: (id: string) => invoke<void>("cancel_transfer", { id }),
  forgetJob: (id: string) => invoke<void>("forget_job", { id }),
  cancelIncoming: (id: string) => invoke<boolean>("cancel_incoming", { id }),
  respond: (requestId: string, accept: boolean) => invoke<void>("respond_incoming", { requestId, accept }),
  openReceiveFolder: () => invoke<void>("open_receive_folder"),
};

export const events = {
  progress: (cb: (p: Progress) => void): Promise<UnlistenFn> => listen<Progress>("transfer-progress", (e) => cb(e.payload)),
  peersChanged: (cb: () => void): Promise<UnlistenFn> => listen("peers-changed", () => cb()),
  incoming: (cb: (i: Incoming) => void): Promise<UnlistenFn> => listen<Incoming>("incoming-request", (e) => cb(e.payload)),
  incomingClosed: (cb: (id: string) => void): Promise<UnlistenFn> => listen<string>("incoming-closed", (e) => cb(e.payload)),
};

export async function pickFiles(): Promise<string[]> {
  const r = await open({ multiple: true, directory: false, title: "Choose files to send" });
  return Array.isArray(r) ? r : r ? [r] : [];
}
export async function pickFolders(): Promise<string[]> {
  const r = await open({ multiple: true, directory: true, title: "Choose folders to send" });
  return Array.isArray(r) ? r : r ? [r] : [];
}
export async function pickDirectory(title: string): Promise<string | null> {
  const r = await open({ multiple: false, directory: true, title });
  return typeof r === "string" ? r : null;
}
