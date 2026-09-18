import { useEffect, useId, useRef } from "react";
import type { ReactNode } from "react";

export function DetailsDialog({ open, onClose, children }: { open: boolean; onClose: () => void; children: ReactNode }) {
  const dialog = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  useEffect(() => {
    const element = dialog.current;
    if (!element || !open) return;
    const previous = document.activeElement as HTMLElement | null;
    if (!element.open) element.showModal();
    return () => { if (element.open) element.close(); previous?.focus(); };
  }, [open]);
  return <dialog ref={dialog} className="game-details" aria-labelledby={titleId}
    onCancel={event => { event.preventDefault(); onClose(); }} onClose={onClose}>
    <header className="details-header"><div><div className="section-label">Your table</div><h2 id={titleId}>Game details</h2></div>
      <button type="button" aria-label="Close game details" onClick={onClose}>×</button></header>
    <div className="details-content">{children}</div>
  </dialog>;
}
