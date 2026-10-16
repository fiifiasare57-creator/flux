export function bytes(n: number): string {
  if (!Number.isFinite(n) || n < 0) return "—";
  const u = ["B", "KB", "MB", "GB", "TB"];
  let i = 0, v = n;
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
  return `${v >= 100 || i === 0 ? v.toFixed(0) : v.toFixed(1)} ${u[i]}`;
}
export function timeAgo(unixSecs: number): string {
  const d = Math.max(0, Date.now() / 1000 - unixSecs);
  if (d < 45) return "just now";
  if (d < 3600) return `${Math.round(d / 60)}m ago`;
  if (d < 86400) return `${Math.round(d / 3600)}h ago`;
  if (d < 86400 * 30) return `${Math.round(d / 86400)}d ago`;
  return new Date(unixSecs * 1000).toLocaleDateString();
}
export function fullTime(unixSecs: number): string {
  return new Date(unixSecs * 1000).toLocaleString();
}
export function greeting(): string {
  const h = new Date().getHours();
  return h < 12 ? "Good morning" : h < 18 ? "Good afternoon" : "Good evening";
}
export function eta(secs: number): string {
  if (!Number.isFinite(secs) || secs < 0) return "";
  if (secs < 60) return `${Math.ceil(secs)}s left`;
  if (secs < 3600) return `${Math.ceil(secs / 60)}m left`;
  return `${(secs / 3600).toFixed(1)}h left`;
}
export function baseName(p: string): string {
  const parts = p.split(/[\\/]/).filter(Boolean);
  return parts[parts.length - 1] ?? p;
}
export function host(addr: string | undefined | null): string {
  return addr ? addr.replace(/:\d+$/, "") : "";
}
export function shortFp(fp: string): string {
  return fp.length > 16 ? `${fp.slice(0, 8)}…${fp.slice(-8)}` : fp;
}
