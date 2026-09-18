import { useEffect, useLayoutEffect, useRef, useState } from "react";

import { fetchGame, GameApiError, submitCommand, submitUndo, updateLobby } from "./api/client";
import type { GameProjection, LegalAction } from "./api/contracts";
import { useLiveGame } from "./useLiveGame";
import { ObserveGamePage } from "./routes/ObserveGamePage";
import { LobbyPage } from "./routes/LobbyPage";
import { CreateGamePage } from "./routes/CreateGamePage";
import { DiscussionProvider } from "./components/Discussion";
import "./styles/canvas.css";

type Loader = (gameId: string) => Promise<GameProjection>;

type ActionSubmitter = (
  gameId: string,
  actionId: string | string[],
  expectedRevision: number,
  commandId: string,
  instanceId?: string,
) => Promise<GameProjection>;

const sendAction: ActionSubmitter = (game, action, revision, id, instanceId) => submitCommand(game, action, revision, fetch, id, instanceId);
type PendingCommand = { expectedRevision: number; commandId: string; instanceId?: string } & ({ operation: "undo" } | { actionId: string | string[] });

function storedCommand(key: string | null, instanceId?: string): PendingCommand | null {
  if (!key) return null;
  try {
    const value = JSON.parse(sessionStorage.getItem(key) ?? "null");
    if (!value || !Number.isSafeInteger(value.expectedRevision) || value.expectedRevision < 0 || typeof value.commandId !== "string" || !value.commandId) return null;
    if (value.instanceId !== instanceId) return null;
    if (value.operation === "undo") return { operation: "undo", expectedRevision: value.expectedRevision, commandId: value.commandId, instanceId };
    if ((typeof value.actionId === "string" && value.actionId.length > 0) || (Array.isArray(value.actionId) && value.actionId.length >= 2 && value.actionId.length <= 3 && value.actionId.every((id: unknown) => typeof id === "string" && id.length > 0))) return { actionId: value.actionId, expectedRevision: value.expectedRevision, commandId: value.commandId, instanceId };
  } catch { /* Storage may be unavailable in a restricted browser. */ }
  return null;
}
function retainCommand(key: string | null, command: PendingCommand | null) {
  if (!key) return;
  try { if (command) sessionStorage.setItem(key, JSON.stringify(command)); else sessionStorage.removeItem(key); }
  catch { /* In-memory retry remains available. No credentials are stored. */ }
}

function TableApp({
  gameId,
  initialProjection,
  loadGame = fetchGame,
  submitAction = sendAction,
  pollInterval = 5000,
}: {
  gameId: string | null;
  initialProjection?: GameProjection;
  loadGame?: Loader;

  submitAction?: ActionSubmitter;
  pollInterval?: number;
}) {
  const live = useLiveGame(gameId, initialProjection, loadGame, pollInterval);
  const pending = useRef<PendingCommand | null>(null);
  const sending = useRef(false);
  const [actionState, setActionState] = useState<"ready" | "submitting" | "unresolved">("ready");
  const [feedback, setFeedback] = useState<string>();
  const deadlines = useRef(new Set<ReturnType<typeof setTimeout>>());
  const instanceId = live.projection?.instanceId ?? undefined;
  const storageKey = live.projection?.viewer.player ? `organism:pending:${encodeURIComponent(gameId ?? "")}:${encodeURIComponent(live.projection.viewer.player)}${instanceId ? `:${encodeURIComponent(instanceId)}` : ""}` : null;
  // Never paint a replacement table with the previous owner's retry controls.
  useLayoutEffect(() => {
    const recovered = storedCommand(storageKey, instanceId);
    pending.current = recovered;
    sending.current = false;
    setActionState(recovered ? "unresolved" : "ready");
    setFeedback(recovered ? "An earlier action still needs confirmation. Retry it safely before making another choice." : undefined);
  }, [storageKey, instanceId, live.scope]);
  useEffect(() => () => { for (const timer of deadlines.current) clearTimeout(timer); }, []);
  const send = async (command: PendingCommand) => {
    if (!gameId || sending.current) return;
    sending.current = true;
    pending.current = command;
    retainCommand(storageKey, command);
    setActionState("submitting");
    setFeedback("Applying action…");
    const started = live.fence();
    const life = live.lifecycle.current;
    let timeout: ReturnType<typeof setTimeout> | undefined;
    try {
      const request = "operation" in command
        ? submitUndo(gameId, command.expectedRevision, command.commandId, fetch, command.instanceId)
        : command.instanceId
          ? submitAction(gameId, command.actionId, command.expectedRevision, command.commandId, command.instanceId)
          : submitAction(gameId, command.actionId, command.expectedRevision, command.commandId);
      const result = await Promise.race([request, new Promise<never>((_, reject) => {
        timeout = setTimeout(() => reject(new Error("Confirmation timeout")), 15000);
        deadlines.current.add(timeout);
      })]);
      if (life !== live.lifecycle.current) return;
      if (started === live.generation.current || result.revision > (live.head.current?.revision ?? -1)) live.accept(result);
      pending.current = null;
      setActionState("ready");
      retainCommand(storageKey, null);
      setFeedback("Action confirmed.");
    } catch (error) {
      if (life !== live.lifecycle.current) return;
      const definite = error instanceof GameApiError && error.status >= 400 && error.status < 500 && error.message !== "command-in-progress";
      if (definite) {
        const refreshed = await live.refresh();
        if (life !== live.lifecycle.current) return;
        if (!refreshed) {
          setActionState("unresolved");
          setFeedback("The current account could not be confirmed. Reconnect before retrying this action.");
          return;
        }
        pending.current = null;
        retainCommand(storageKey, null);
        setFeedback(error.status === 409 ? "The board changed. Your choices are being refreshed." : error.message.replaceAll("-", " "));
        setActionState("ready");
      } else {
        setActionState("unresolved");
        setFeedback("Confirmation was interrupted. Retry the same action to find out whether it was accepted.");
      }
    } finally {
      if (life === live.lifecycle.current) sending.current = false;
      if (timeout !== undefined) { clearTimeout(timeout); deadlines.current.delete(timeout); }
    }
  };
  const handleAction = (actions: LegalAction[]) => {
    const projection = live.head.current;
    if (!projection?.viewer.canAct || projection.status !== "active" || pending.current || !actions.length) return;
    void send({ actionId: actions.length === 1 ? actions[0].actionId : actions.map(a => a.actionId), expectedRevision: projection.revision, commandId: crypto.randomUUID(), instanceId: projection.instanceId ?? undefined });
  };

  if (live.projection) {
    return <DiscussionProvider key={live.scope} projection={live.projection}>
      {live.projection.status === "waiting" && live.projection.lobby ? <LobbyPage key={live.scope} projection={live.projection} connection={live.connection} command={async (operation, body) => {
      if (!gameId || live.head.current?.status !== "waiting") throw new Error("This table has already started.");
      const started = live.fence();
      const life = live.lifecycle.current;
      try {
        const result = await updateLobby(gameId, operation, body);
        if (life === live.lifecycle.current && (started === live.generation.current || result.revision > (live.head.current?.revision ?? -1))) live.accept(result);
        else if (life === live.lifecycle.current) await live.refresh();
      } catch (error) { if (life === live.lifecycle.current) await live.refresh(); throw error; }
    }} /> : (
      <ObserveGamePage
        key={live.scope}
        projection={live.projection}
        connectionState={live.connection}
        onAction={handleAction}
        actionState={actionState === "ready" ? "ready" : "submitting"}
        onUndo={() => {
          if (live.head.current?.viewer.canUndo && !pending.current) void send({ operation: "undo", expectedRevision: live.head.current.revision, commandId: crypto.randomUUID(), instanceId: live.head.current.instanceId ?? undefined });
        }}
        feedback={<div className="table-feedback" role="status">{feedback}{live.error && <p>{live.error}</p>}
          {actionState === "unresolved" && <button onClick={() => pending.current && void send(pending.current)}>Retry same action</button>}
        </div>}
      />
    )}
    </DiscussionProvider>;
  }

  return (
    <main className="empty-state">
      <div className="empty-state__mark" aria-hidden="true">O</div>
      <div className="section-label">ORGANISM</div>
      {!live.error && !live.missing ? <h1>Preparing the table…</h1> : null}
      {live.missing ? (
        <>
          <h1>That game could not be found.</h1>
          <p>Check the game link or return to the game list.</p>
          <a href="/organism/play">Games &amp; tables</a>
        </>
      ) : null}
      {live.error && !live.missing ? (
        <>
          <h1>Connection interrupted.</h1>
          <p>{live.error}</p>
          <button type="button" onClick={() => void live.refresh()}>Try again</button>
        </>
      ) : null}
    </main>
  );
}

export function App(props: React.ComponentProps<typeof TableApp> & { view?: string | null }) {
  return props.view === "create" ? <CreateGamePage /> : <TableApp key={props.gameId} {...props} />;
}
