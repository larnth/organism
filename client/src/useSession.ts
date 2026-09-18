import { useEffect, useState } from "react";
import { fetchSession } from "./api/client";
import type { Session } from "./api/contracts";
export type SessionLoader = () => Promise<Session>;
export function useSession(load: SessionLoader = fetchSession) {
  const [session, setSession] = useState<Session>();
  const [error, setError] = useState<string>();
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    let active = true;
    setError(undefined);
    load().then(value => { if (active) setSession(value); }).catch(failure => {
      if (active) setError(failure instanceof Error ? failure.message : "Account details could not be loaded.");
    });
    return () => { active = false; };
  }, [load, attempt]);
  return { session, error, retry: () => setAttempt(value => value + 1) };
}
