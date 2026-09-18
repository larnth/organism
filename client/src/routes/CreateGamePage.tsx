import { useRef, useState } from "react";
import { updateLobby } from "../api/client";
import type { Session } from "../api/contracts";
import { useSession } from "../useSession";
import type { SessionLoader } from "../useSession";
import "../styles/canvas.css";

export const modernGameUrl = (gameId: string) => `/modern/?game=${encodeURIComponent(gameId)}`;
export const signInUrl = (destination: string) => `/login?redirect=${encodeURIComponent(destination)}`;
const navigateToGame = (gameId: string) => { window.location.assign(modernGameUrl(gameId)); };

export function CreateGamePage({ loadSession, write = updateLobby, onCreated = navigateToGame }: {
  loadSession?: SessionLoader; write?: typeof updateLobby; onCreated?: (gameId: string) => void;
}) {
  const { session, error, retry } = useSession(loadSession);
  if (error) return <main className="empty-state"><h1>Account details unavailable</h1><p role="alert">{error}</p><button onClick={retry}>Try again</button></main>;
  if (!session) return <main className="empty-state"><h1>Preparing your table…</h1></main>;
  if (!session.player) return <main className="empty-state"><h1>A table starts with you.</h1><a className="button primary" href={signInUrl("/modern/?view=create")}>Sign in to create a game</a><a href="/organism">Back to ORGANISM</a></main>;
  return <CreateDraft session={session} write={write} onCreated={onCreated} />;
}

function CreateDraft({ session, write, onCreated }: { session: Session; write: typeof updateLobby; onCreated: (gameId: string) => void }) {
  const [name, setName] = useState("");
  const [count, setCount] = useState(Number(session.defaults["player-count"]));
  const [rings, setRings] = useState(Number(session.defaults["ring-count"]));
  const [description, setDescription] = useState(String(session.defaults.description ?? ""));
  const [visibility, setVisibility] = useState(String(session.defaults.visibility));
  const [pending, setPending] = useState(false);
  const busy = useRef(false);
  const [error, setError] = useState<string>();
  const password = useRef<HTMLInputElement>(null);
  async function launch() {
    if (busy.current) return;
    if (!name || name.length > session.limits.gameNameMaxLength || /^[\u0000-\u0020]|[\u0000-\u0020]$/.test(name) || name.startsWith("$") || /[/\\?#%\n\r\t]/.test(name)) {
      setError("Choose a name without leading/trailing spaces or / \\ ? # % characters. It cannot start with $."); return;
    }
    const admission = password.current?.value ?? "";
    if (visibility === "private" && !admission) { setError("Enter a lobby password for a private game."); return; }
    if (password.current) password.current.value = "";
    const invocation = { ...session.defaults, "player-count": count, "ring-count": rings,
      players: Array.from({ length: count }, (_, index) => index === 0 ? session.player! : ""), description, visibility,
      ...(visibility === "private" ? { "lobby-password": admission } : {}),
    };
    busy.current = true; setPending(true); setError(undefined);
    try {
      const result = await write(name, "configure", { invocation, createOnly: true });
      if (result.status !== "waiting" || !result.lobby?.member) throw new Error("The server did not confirm your lobby. Check the game list before trying again.");
      onCreated(result.gameId);
    } catch (failure) { setError(`${failure instanceof Error ? failure.message : "The lobby could not be created."}${visibility === "private" ? " Re-enter the lobby password before retrying." : ""}`); }
    finally { busy.current = false; setPending(false); }
  }
  return <main className="workflow">
    <header className="workflow-header"><a className="wordmark" href="/organism"><span>O</span><strong>ORGANISM</strong></a><span className="section-label">Create game</span></header>
    <form className="card create-card" onSubmit={event => { event.preventDefault(); void launch(); }}>
      <div className="section-label">New table</div><h1>Create your game</h1><p className="muted">Choose your table settings. Nothing is shared until you launch the lobby.</p>
      <div className="form-grid">
        <label className="full">Game name<input value={name} maxLength={session.limits.gameNameMaxLength} required disabled={pending} onChange={event => setName(event.target.value)} /></label>
        <label>Players<select aria-label="Players" value={count} disabled={pending} onChange={event => setCount(Number(event.target.value))}>{session.limits.playerCounts.map(value => <option key={value}>{value}</option>)}</select><small>You take seat 1 as {session.player}. Other seats start open.</small></label>
        <label>Field size<select value={rings} disabled={pending} onChange={event => setRings(Number(event.target.value))}>{session.limits.ringCounts.map(value => <option key={value} value={value}>{value} rings</option>)}</select></label>
        <fieldset className="full" disabled={pending}><legend>Who can join?</legend><label><input type="radio" name="visibility" checked={visibility === "open"} onChange={() => setVisibility("open")} />Open game</label><label><input type="radio" name="visibility" checked={visibility === "private"} onChange={() => setVisibility("private")} />Private game</label></fieldset>
        {visibility === "private" && <label>Lobby password<input aria-label="Lobby password" type="password" ref={password} autoComplete="new-password" required disabled={pending} /><small>Share separately. Never included in the invite link.</small></label>}
        <label className="full">Description (optional)<textarea rows={3} value={description} disabled={pending} onChange={event => setDescription(event.target.value)} /></label>
      </div>
      {error && <p role="alert">{error}</p>}
      <footer className="workflow-footer"><a href="/organism">Cancel</a><button className="primary" disabled={pending} type="submit">{pending ? "Launching…" : "Launch lobby"}</button></footer>
    </form>
  </main>;
}
