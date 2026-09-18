import { useCallback, useEffect, useRef, useState } from "react";
import { fetchGame, GameApiError, gameSocketUrl } from "./api/client";
import type { GameProjection } from "./api/contracts";
import { decodeGameEvent } from "./api/decode";

export type GameLoader = (gameId: string) => Promise<GameProjection>;

// HTTP is the current-cookie authority. Socket payloads only invalidate it:
// their identity was captured at connection time and their metadata may be old.
export function useLiveGame(gameId: string | null, initial: GameProjection | undefined,
  loadGame: GameLoader = fetchGame, pollInterval = 5000) {
  const [projection, setProjection] = useState(initial);
  const head = useRef(initial);
  const generation = useRef(0);
  const lifecycle = useRef(0);
  const terminal = useRef(false);
  const resetTransport = useRef<(reconnect: boolean) => void>(() => undefined);
  const [scope, setScope] = useState(0);
  const [error, setError] = useState<string>();
  const [missing, setMissing] = useState(!gameId);
  const [connection, setConnection] = useState<"connected" | "reconnecting">("connected");
  const accept = useCallback((candidate: GameProjection) => {
    if (terminal.current || candidate.gameId !== gameId || candidate.historyCursor !== undefined) return;
    if (head.current && candidate.viewer.player !== head.current.viewer.player) return;
    if (head.current?.instanceId && candidate.instanceId !== head.current.instanceId) return;
    if (head.current && candidate.revision < head.current.revision) return;
    generation.current++;
    head.current = candidate;
    setProjection(candidate);
    setMissing(false);
  }, [gameId]);
  const fence = useCallback(() => ++generation.current, []);
  const refresh = useCallback(async () => {
    if (!gameId || terminal.current) return false;
    const started = generation.current;
    const life = lifecycle.current;
    try {
      const candidate = await loadGame(gameId);
      if (life !== lifecycle.current || started !== generation.current) return false;
      if (head.current && (candidate.viewer.player !== head.current.viewer.player
        || (head.current.instanceId && candidate.instanceId !== head.current.instanceId))) {
        lifecycle.current++;
        head.current = undefined;
        setScope(value => value + 1);
        resetTransport.current(true);
      }
      accept(candidate);
      setConnection("connected");
      setError(undefined);
      return true;
    } catch (failure) {
      if (life !== lifecycle.current || started !== generation.current) return false;
      if (failure instanceof GameApiError && [401, 403, 404, 410].includes(failure.status)) {
        terminal.current = true;
        lifecycle.current++;
        generation.current++;
        head.current = undefined;
        setProjection(undefined);
        setScope(value => value + 1);
        setMissing(true);
        setError(undefined);
        resetTransport.current(false);
        return false;
      }
      setConnection("reconnecting");
      setError(failure instanceof Error ? failure.message : "Connection interrupted. Retrying…");
      return false;
    }
  }, [gameId, loadGame, accept]);

  useEffect(() => {
    lifecycle.current++;
    generation.current++;
    terminal.current = false;
    head.current = initial;
    setProjection(initial);
    setMissing(!gameId);
    setError(undefined);
    if (!gameId || initial) return;
    let alive = true;
    let socketGeneration = 0;
    let socket: WebSocket | undefined;
    let retryTimer: ReturnType<typeof setTimeout>;
    let pollTimer: ReturnType<typeof setTimeout>;
    const poll = async () => {
      await refresh();
      if (alive && !terminal.current) pollTimer = setTimeout(poll, pollInterval);
    };
    const connect = () => {
      if (!alive || terminal.current || typeof WebSocket === "undefined") return;
      const ownGeneration = ++socketGeneration;
      const current = () => alive && ownGeneration === socketGeneration;
      const disconnected = () => {
        if (!current()) return;
        socketGeneration++;
        socket?.close();
        setConnection("reconnecting");
        retryTimer = setTimeout(connect, Math.min(pollInterval, 3000));
      };
      try {
        socket = new WebSocket(gameSocketUrl(gameId));
        socket.onopen = () => { if (current()) void refresh(); };
        socket.onmessage = event => {
          if (!current()) return;
          try {
            const data = decodeGameEvent(JSON.parse(String(event.data)), gameId);
            if (data.type === "snapshot.unavailable") {
              setConnection("reconnecting");
              setError("The table is temporarily unavailable. Reconnecting…");
            }
            // Never adopt a connection-captured recipient or regress metadata
            // using an equal-revision frame delivered after an HTTP response.
            void refresh();
          } catch {
            setConnection("reconnecting");
            setError("Invalid game data received. Reconnecting…");
            void refresh();
          }
        };
        socket.onclose = disconnected;
        socket.onerror = disconnected;
      } catch { disconnected(); }
    };
    resetTransport.current = reconnect => {
      socketGeneration++;
      clearTimeout(retryTimer);
      socket?.close();
      if (reconnect) connect();
      else clearTimeout(pollTimer);
    };
    const foreground = () => { if (!document.hidden) void refresh(); };
    window.addEventListener("focus", foreground);
    document.addEventListener("visibilitychange", foreground);
    void poll();
    connect();
    return () => {
      alive = false;
      lifecycle.current++;
      socketGeneration++;
      resetTransport.current = () => undefined;
      window.removeEventListener("focus", foreground);
      document.removeEventListener("visibilitychange", foreground);
      clearTimeout(retryTimer);
      clearTimeout(pollTimer);
      socket?.close();
    };
  }, [gameId, initial, refresh, accept, pollInterval]);
  return { projection, head, accept, fence, generation, lifecycle, scope, refresh, connection, error, missing };
}
