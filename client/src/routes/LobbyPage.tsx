import { useRef, useState } from "react";
import type { GameProjection, JsonValue, LobbyCommand, Session } from "../api/contracts";
import { Discussion } from "../components/Discussion";
import { useSession } from "../useSession";
import type { SessionLoader } from "../useSession";
import { modernGameUrl, signInUrl } from "./CreateGamePage";

export type LobbySender = (operation: LobbyCommand["operation"], body: Record<string, JsonValue>) => Promise<unknown>;
export function LobbyPage({ projection, command, loadSession, connection = "connected" }: {
  projection: GameProjection; command: LobbySender; loadSession?: SessionLoader; connection?: "connected" | "reconnecting";
}) {
  const { session, error: sessionError, retry } = useSession(loadSession);
  const [pending, setPending] = useState(false);
  const busy = useRef(false);
  const [feedback, setFeedback] = useState("");
  const password = useRef<HTMLInputElement>(null);
  const lobby = projection.lobby;
  const viewer = projection.viewer.player;
  const owner = Boolean(viewer && lobby?.owner?.toLowerCase() === viewer.toLowerCase());
  const players = Array.isArray(projection.invocation.players) ? projection.invocation.players.filter((p): p is string => typeof p === "string") : [];
  const member = Boolean(lobby?.member);
  const ready = Boolean(viewer && lobby?.readiness[viewer.toLowerCase()]);
  const invite = new URL(modernGameUrl(projection.gameId), window.location.origin).href;
  async function run(operation: LobbyCommand["operation"], body: Record<string, JsonValue>) {
    if (busy.current) return;
    busy.current = true; setPending(true); setFeedback("");
    try {
      await command(operation, { ...body, ...(projection.instanceId ? { expectedInstanceId: projection.instanceId } : {}) });
      setFeedback(operation === "start" ? "Starting the game…" : "Table updated.");
    }
    catch (failure) { setFeedback(failure instanceof Error ? failure.message : "The table could not be updated. Please try again."); }
    finally { busy.current = false; setPending(false); }
  }
  return <main className="workflow">
    <header className="workflow-header"><a className="wordmark" href="/organism"><span>O</span><strong>ORGANISM</strong></a><span className="section-label">{connection === "reconnecting" ? "Reconnecting" : "Open lobby"}</span></header>
    <section className="lobby-hero"><div><div className="section-label">{lobby?.visibility === "private" ? "Private game" : "Open game"}</div><h1>{projection.gameId}</h1><p className="muted">{players.length} seats · {String(projection.invocation["ring-count"])}-ring field</p>{typeof projection.invocation.description === "string" && <p>{projection.invocation.description}</p>}</div>
      <div className="invite"><span className="section-label">Invite link</span><a href={invite}>{invite}</a><button onClick={() => {
        if (!navigator.clipboard) { setFeedback("Copy the invite link above using your browser."); return; }
        void navigator.clipboard.writeText(invite).then(() => setFeedback("Invite copied.")).catch(() => setFeedback("Copy failed. Select and copy the invite link above."));
      }}>Copy invite</button></div>
    </section>
    <div className="lobby-columns"><section className="card" aria-label="Lobby roster"><div className="section-label">Players</div><h2>{players.filter(Boolean).length} of {players.length} seats</h2>
      {owner && <details><summary>Edit settings</summary>{session ? <LobbySettings projection={projection} session={session} disabled={pending} save={body => run("configure", body)} /> : sessionError ? <p role="alert">{sessionError} <button onClick={retry}>Retry settings</button></p> : <p>Loading settings…</p>}</details>}
      {!viewer && <p><a href={signInUrl(modernGameUrl(projection.gameId))}>Sign in to join this table</a></p>}
      {!member && viewer && lobby?.visibility === "private" && <label>Lobby password<input type="password" ref={password} autoComplete="current-password" aria-label="Lobby password" /></label>}
      <ol className="roster">{players.map((player, index) => {
        const isBot = Boolean(lobby?.bots?.includes(player));
        const isOwner = Boolean(player && player.toLowerCase() === lobby?.owner?.toLowerCase());
        const isReady = isBot || Boolean(lobby?.readiness[player.toLowerCase()]);
        return <li className="seat" key={index}><span className="muted">{index + 1}</span><span className="avatar" aria-hidden="true">{player ? player[0].toUpperCase() : "+"}</span><div className="seat-identity"><strong>{player || "Open seat"}</strong><small>{isOwner ? "Lobby owner" : isBot ? "Bot" : player ? "Account seat" : "Invite a player"}</small></div>
          {player && <span className={`ready-pill ${isReady ? "is-ready" : ""}`}>{isReady ? "Ready" : "Waiting"}</span>}
          {!player && viewer && !member && <button disabled={pending} onClick={() => { const admission = password.current?.value; if (password.current) password.current.value = ""; void run("join", { index, ...(admission !== undefined ? { password: admission } : {}) }); }}>Join seat {index + 1}</button>}
          {owner && player && !isOwner && <button disabled={pending} onClick={() => void run("kick", { index })}>Remove {player}</button>}
          {owner && !player && Boolean(lobby?.availableBots?.length) && <form onSubmit={event => { event.preventDefault(); const value = new FormData(event.currentTarget).get("bot"); if (typeof value === "string" && value) void run("seat", { index, player: value }); }}>
            <select aria-label={`Bot for seat ${index + 1}`} name="bot" defaultValue="" required disabled={pending}><option value="" disabled>Choose a bot</option>{lobby?.availableBots?.map(bot => <option key={bot.player} value={bot.player}>{bot.name} — {bot.description}</option>)}</select><button disabled={pending}>Add bot to seat {index + 1}</button>
          </form>}
        </li>;
      })}</ol>
      <footer className="lobby-launch"><p>{lobby?.blocker ?? (lobby?.canStart ? "Everyone is ready. You can start the game." : "The owner starts after all players are ready.")}</p>
        {member && projection.viewer.role === "player" && <button disabled={pending} onClick={() => void run("ready", { ready: !ready })}>{ready ? "Not ready" : "Ready up"}</button>}
        {owner && <button className="primary" disabled={pending || !lobby?.canStart} onClick={() => void run("start", {})}>Start game</button>}
      </footer>
      <p role="status" className="feedback">{feedback}</p>
    </section><section className="card"><Discussion projection={projection} maxLength={session?.limits.chatMaxLength} /></section></div>
  </main>;
}

function LobbySettings({ projection, session, disabled, save }: { projection: GameProjection; session: Session; disabled: boolean; save: (body: Record<string, JsonValue>) => Promise<void> }) {
  const [rings, setRings] = useState(Number(projection.invocation["ring-count"]));
  const [description, setDescription] = useState(String(projection.invocation.description ?? ""));
  return <form onSubmit={event => { event.preventDefault(); void save({ invocation: { ...projection.invocation, "ring-count": rings, description, visibility: projection.lobby?.visibility ?? "open" } }); }}>
    <div className="form-grid"><label>Field size<select value={rings} disabled={disabled} onChange={event => setRings(Number(event.target.value))}>{session.limits.ringCounts.map(count => <option key={count} value={count}>{count} rings</option>)}</select></label><label className="full">Description<textarea value={description} disabled={disabled} onChange={event => setDescription(event.target.value)} /></label></div>
    <p className="muted">Seats and admission policy stay fixed. Changing settings resets readiness.</p><button disabled={disabled}>Save settings</button>
  </form>;
}
