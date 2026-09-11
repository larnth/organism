import type { CatchUpResponse, GameProjection } from "./contracts";

export type Requester = (
  input: RequestInfo | URL,
  init?: RequestInit,
) => Promise<Response>;

export class GameApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message);
    this.name = "GameApiError";
  }
}

async function readError(response: Response): Promise<string> {
  try {
    const body = (await response.json()) as { error?: unknown };
    return typeof body.error === "string" ? body.error : `request-failed-${response.status}`;
  } catch {
    return `request-failed-${response.status}`;
  }
}

export async function fetchGame(
  gameId: string,
  request: Requester = fetch,
): Promise<GameProjection> {
  const response = await request(
    `/api/v1/organism/games/${encodeURIComponent(gameId)}`,
    {
      credentials: "same-origin",
      headers: { accept: "application/json" },
    },
  );

  if (!response.ok) {
    throw new GameApiError(await readError(response), response.status);
  }

  return (await response.json()) as GameProjection;
}

export async function fetchCatchUp(
  gameId: string,
  afterRevision: number,
  request: Requester = fetch,
): Promise<CatchUpResponse> {
  const response = await request(
    `/api/v1/organism/games/${encodeURIComponent(gameId)}?afterRevision=${afterRevision}`,
    {
      credentials: "same-origin",
      headers: { Accept: "application/json" },
    },
  );
  if (!response.ok) {
    throw new GameApiError(await readError(response), response.status);
  }
  return (await response.json()) as CatchUpResponse;
}

export async function submitCommand(
  gameId: string,
  actionId: string,
  expectedRevision: number,
  request: Requester = fetch,
  commandId: string = crypto.randomUUID(),
): Promise<GameProjection> {
  const response = await request(
    `/api/v1/organism/games/${encodeURIComponent(gameId)}/commands`,
    {
      method: "POST",
      credentials: "same-origin",
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ actionId, expectedRevision, commandId }),
    },
  );
  if (!response.ok) {
    throw new GameApiError(await readError(response), response.status);
  }
  return (await response.json()) as GameProjection;
}

export function gameSocketUrl(gameId: string, location: URL = new URL(window.location.href)) {
  const protocol = location.protocol === "https:" ? "wss:" : "ws:";
  return `${protocol}//${location.host}/ws/organism/play/${encodeURIComponent(gameId)}`;
}
