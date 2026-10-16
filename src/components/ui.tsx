import { useEffect, useRef, type ReactNode } from "react";
import { CheckCircle2, AlertCircle, Info, X } from "lucide-react";
import { useFlux } from "../lib/store";

export function Dialog({ title, onClose, children, wide, labelledBy = "dlg-title" }: { title: string; onClose?: () => void; children: ReactNode; wide?: boolean; labelledBy?: string }) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const prev = document.activeElement as HTMLElement | null;
    ref.current?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape" && onClose) onClose();
      if (e.key === "Tab" && ref.current) {
        const f = ref.current.querySelectorAll<HTMLElement>("button:not([disabled]), [href], input, textarea, select, [tabindex]:not([tabindex='-1'])");
        if (!f.length) return;
        const first = f[0], last = f[f.length - 1];
        if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
        else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
      }
    };
    document.addEventListener("keydown", onKey);
    return () => { document.removeEventListener("keydown", onKey); prev?.focus(); };
  }, [onClose]);
  return (
    <div className="scrim" onMouseDown={(e) => { if (e.target === e.currentTarget && onClose) onClose(); }}>
      <div className={`dialog ${wide ? "wide" : ""}`} role="dialog" aria-modal="true" aria-labelledby={labelledBy} ref={ref} tabIndex={-1}>
        <div className="dialog-head">
          <h2 id={labelledBy}>{title}</h2>
          {onClose && <button className="icon-btn" aria-label="Close" onClick={onClose}><X size={18} /></button>}
        </div>
        {children}
      </div>
    </div>
  );
}

export function Confirm({ title, body, confirmLabel, danger, onConfirm, onCancel }: { title: string; body: ReactNode; confirmLabel: string; danger?: boolean; onConfirm: () => void; onCancel: () => void }) {
  return (
    <Dialog title={title} onClose={onCancel}>
      <div className="dialog-body">{body}</div>
      <div className="dialog-actions">
        <button className="btn ghost" onClick={onCancel}>Cancel</button>
        <button className={`btn ${danger ? "danger" : "primary"}`} onClick={onConfirm}>{confirmLabel}</button>
      </div>
    </Dialog>
  );
}

export function Switch({ checked, onChange, label, hint }: { checked: boolean; onChange: (v: boolean) => void; label: string; hint?: string }) {
  return (
    <div className="row-setting">
      <div>
        <div className="label">{label}</div>
        {hint && <div className="hint">{hint}</div>}
      </div>
      <button role="switch" aria-checked={checked} aria-label={label} className={`switch ${checked ? "on" : ""}`} onClick={() => onChange(!checked)}><span /></button>
    </div>
  );
}

export function Toasts() {
  const { toasts } = useFlux();
  return (
    <div className="toasts" role="status" aria-live="polite">
      {toasts.map((t) => (
        <div key={t.id} className={`toast ${t.kind}`}>
          {t.kind === "success" ? <CheckCircle2 size={16} /> : t.kind === "error" ? <AlertCircle size={16} /> : <Info size={16} />}
          <span>{t.text}</span>
        </div>
      ))}
    </div>
  );
}

export function Empty({ icon, title, body, children }: { icon: ReactNode; title: string; body: string; children?: ReactNode }) {
  return (
    <div className="empty">
      <div className="empty-icon">{icon}</div>
      <div className="empty-title">{title}</div>
      <div className="empty-body">{body}</div>
      {children}
    </div>
  );
}
