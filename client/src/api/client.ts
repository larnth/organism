import type { CatchUpResponse, GameProjection, JsonValue, LobbyCommand } from "./contracts";
import { decodeCatchUp, decodeChat, decodeProjection, decodeSession } from "./decode";

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

async function readData<T>(response: Response, decode: (value: unknown) => T): Promise<T> {
  if (!response.ok) throw new GameApiError(await readError(response), response.status);
  try {
    return decode(await response.json());
  } catch {
    throw new GameApiError("Invalid game data received. Reconnect to refresh the table.", 502);
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
  return readData(response, (value) => decodeProjection(value, gameId));
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
  return readData(response, (value) => decodeCatchUp(value, gameId));
}

export async function submitCommand(
  gameId: string,
  actionId: string | string[],
  expectedRevision: number,
  request: Requester = fetch,
  commandId: string = crypto.randomUUID(),
  expectedInstanceId?: string,
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
      body: JSON.stringify({ actionId, expectedRevision, commandId, expectedInstanceId }),
    },
  );
  return readData(response, (value) => decodeProjection(value, gameId));
}

export function gameSocketUrl(gameId: string, location: URL = new URL(window.location.href)) {
  const protocol = location.protocol === "https:" ? "wss:" : "ws:";
  return `${protocol}//${location.host}/api/v1/organism/games/${encodeURIComponent(gameId)}/events`;
}

async function post<T>(url: string, body: unknown, decode: (data: unknown) => T, request: Requester) {
  return readData(await request(url, {
    method: "POST", credentials: "same-origin",
    headers: { Accept: "application/json", "Content-Type": "application/json" },
    body: JSON.stringify(body),
  }), decode);
}

export async function fetchHistory(gameId: string, cursor: number, request: Requester = fetch) {
  return readData(await request(`/api/v1/organism/games/${encodeURIComponent(gameId)}/history/${cursor}`, {
    credentials: "same-origin", headers: { Accept: "application/json" },
  }), value => {
    const projection = decodeProjection(value, gameId);
    if (projection.historyCursor !== cursor || projection.viewer.canAct || projection.viewer.canUndo || projection.legalActions.length) throw new Error("Invalid history");
    return projection;
  });
}

export function submitUndo(gameId: string, expectedRevision: number, commandId: string, request: Requester = fetch, expectedInstanceId?: string) {
  return post(`/api/v1/organism/games/${encodeURIComponent(gameId)}/commands`, { operation: "undo", expectedRevision, commandId, expectedInstanceId }, value => decodeProjection(value, gameId), request);
}

export function postChat(gameId: string, message: string, clientId: string, request: Requester = fetch, expectedInstanceId?: string) {
  return post(`/api/v1/organism/games/${encodeURIComponent(gameId)}/chat`, { message, clientId, expectedInstanceId }, value => decodeChat(value, clientId, message), request);
}

export function updateLobby(gameId: string, operation: LobbyCommand["operation"], body: Record<string, JsonValue>, request: Requester = fetch) {
  const suffix = operation === "configure" ? "" : `/${operation}`;
  return post(`/api/v1/organism/lobbies/${encodeURIComponent(gameId)}${suffix}`, body, value => decodeProjection(value, gameId), request);
}

export async function fetchSession(request: Requester = fetch) {
  return readData(await request("/api/v1/organism/session", { credentials: "same-origin", headers: { Accept: "application/json" } }), decodeSession);
}
