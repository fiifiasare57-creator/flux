import { useState } from "react";
import { Clock } from "lucide-react";
import { useFlux } from "../lib/store";
import { greeting } from "../lib/format";
import PairingPanel from "../components/PairingPanel";
import QuickActions from "../components/QuickActions";
import RightPanel from "../components/RightPanel";
import { HistoryRow, QueuedRow, TransferRow } from "../components/transfers";
import { Empty } from "../components/ui";
import { PairDialog } from "../components/dialogs";

export default function Home() {
  const { info, history, live, go, startFlow } = useFlux();
  const [pair, setPair] = useState(false);
  const running = live.filter((l) => !(l.direction === "received" && ["completed", "cancelled", "rejected"].includes(l.state)));
  const queued = info?.queued ?? [];
  const nothing = !running.length && !queued.length && !history.length;
  return (
    <div className="home">
      <div className="home-main">
        <header className="page-title"><h1>Welcome to FLUX</h1><p className="lead">{greeting()}. Transfer files between your phone and computer with ease.</p></header>
        <PairingPanel />
        <QuickActions />
        <section className="card recent" aria-label="Recent transfers">
          <div className="card-head"><h3>Recent Transfers</h3><button className="link" onClick={() => go("transfers")}>View all</button></div>
          {nothing ? (
            <Empty icon={<Clock size={24} />} title="No transfers yet" body="Your recent file transfers will appear here.">
              <button className="btn dark" onClick={() => void startFlow("send")}>Send your first file</button>
            </Empty>
          ) : (
            <div className="rows">
              {queued.map((q) => <QueuedRow key={q.transfer_id} q={q} />)}
              {running.map((t) => <TransferRow key={t.transfer_id} t={t} />)}
              {history.slice(0, Math.max(0, 5 - running.length - queued.length)).map((h) => <HistoryRow key={h.id + h.timestamp + h.direction} h={h} />)}
            </div>
          )}
        </section>
      </div>
      <RightPanel onAddDevice={() => setPair(true)} />
      {pair && <PairDialog onClose={() => setPair(false)} />}
    </div>
  );
}
