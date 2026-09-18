import type { CatchUpResponse, ChatMessage, GameEvent, GameProjection, LegalAction, Session } from "./contracts";

function object(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function integer(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0;
}

function jsonValue(value: unknown, depth = 0): boolean {
  if (depth > 40) return false;
  if (value === null || typeof value === "string" || typeof value === "boolean") return true;
  if (typeof value === "number") return Number.isFinite(value);
  if (Array.isArray(value)) return value.every((item) => jsonValue(item, depth + 1));
  return object(value) && Object.values(value).every((item) => jsonValue(item, depth + 1));
}

function action(value: unknown, depth = 0): value is LegalAction {
  return depth < 12 && object(value)
    && typeof value.actionId === "string" && value.actionId.length > 0
    && typeof value.kind === "string" && value.kind.length > 0
    && ["label", "actor"].every((key) => value[key] === undefined || typeof value[key] === "string")
    && ["targets", "options"].every((key) => value[key] === undefined || Array.isArray(value[key]))
    && (value.nextActions === undefined || (Array.isArray(value.nextActions)
      && value.nextActions.every((child) => action(child, depth + 1))));
}

function invalid(): never {
  throw new Error("Invalid game data received. Reconnect to refresh the table.");
}

export function decodeProjection(value: unknown, gameId: string): GameProjection {
  if (!object(value) || !jsonValue(value) || value.gameId !== gameId || !integer(value.revision)
    || !(value.instanceId === undefined || value.instanceId === null || (typeof value.instanceId === "string" && value.instanceId.length > 0))
    || !["waiting", "active", "completed"].includes(String(value.status))
    || !object(value.viewer) || !["player", "observer"].includes(String(value.viewer.role))
    || !(value.viewer.player === null || typeof value.viewer.player === "string")
    || typeof value.viewer.canAct !== "boolean"
    || !(value.viewer.canUndo === undefined || typeof value.viewer.canUndo === "boolean")
    || !object(value.invocation) || !(value.game === null || object(value.game))
    || !Array.isArray(value.legalActions) || !value.legalActions.every((item) => action(item))
    || !object(value.historySummary) || !integer(value.historySummary.entries)
    || !integer(value.historySummary.currentIndex) || !Array.isArray(value.chat)
    || !value.chat.every(chatMessage)) return invalid();
  if (value.historyCursor !== undefined && !integer(value.historyCursor)) return invalid();
  const invocation = value.invocation;
  if (invocation.players !== undefined && (!Array.isArray(invocation.players) || !invocation.players.every(p => typeof p === "string"))) return invalid();
  if (value.game !== null) {
    const game = value.game;
    if (game.rings !== undefined && (!Array.isArray(game.rings) || !game.rings.every(r => typeof r === "string"))) return invalid();
    if (game.adjacencies !== undefined && !coordinateMap(game.adjacencies, neighbors => Array.isArray(neighbors) && neighbors.every(coordinate))) return invalid();
    if (game.state !== undefined) {
      if (!object(game.state)) return invalid();
      const state = game.state;
      if (state.elements !== undefined && !coordinateMap(state.elements, element => object(element)
        && typeof element.player === "string" && ["eat", "grow", "move"].includes(String(element.type))
        && (element.food === undefined || integer(element.food)))) return invalid();
      if (state.food !== undefined && !coordinateMap(state.food, integer)) return invalid();
      if (state["player-turn"] !== undefined && !object(state["player-turn"])) return invalid();
    }
  }
  if (value.lobby !== undefined && value.lobby !== null) {
    const lobby = value.lobby;
    if (!object(lobby) || !(lobby.owner === null || typeof lobby.owner === "string")
      || !["open", "private"].includes(String(lobby.visibility)) || typeof lobby.member !== "boolean"
      || typeof lobby.canStart !== "boolean" || !object(lobby.readiness)
      || !Object.values(lobby.readiness).every((ready) => typeof ready === "boolean")
      || !(lobby.blocker === undefined || lobby.blocker === null || typeof lobby.blocker === "string")
      || !(lobby.bots === undefined || (Array.isArray(lobby.bots) && lobby.bots.every(v => typeof v === "string")))
      || !(lobby.availableBots === undefined || (Array.isArray(lobby.availableBots) && lobby.availableBots.every(v => bot(v) && typeof v.player === "string")))) return invalid();
  }
  return value as unknown as GameProjection;
}

export function decodeGameEvent(value: unknown, gameId: string): GameEvent {
  if (!object(value) || value.version !== 1) return invalid();
  if (value.type === "snapshot.unavailable") return { type: value.type, version: 1 };
  if (value.type === "snapshot" || value.type === "game.updated") {
    const projection = decodeProjection(value.projection, gameId);
    if (value.revision !== projection.revision) return invalid();
    return { type: value.type, version: 1, revision: projection.revision, projection };
  }
  if (value.gameId !== gameId) return invalid();
  if (value.type === "game.deleted") return { type: value.type, version: 1, gameId };
  if (value.type === "chat.created" && chatMessage(value.message)) {
    return value as unknown as GameEvent;
  }
  return invalid();
}

export function decodeCatchUp(value: unknown, gameId: string): CatchUpResponse {
  if (!object(value) || value.gameId !== gameId || !integer(value.fromRevision)
    || !integer(value.toRevision) || !Array.isArray(value.events)) return invalid();
  let previous = -1;
  const events = value.events.map((item) => {
    const event = decodeGameEvent(item, gameId);
    if (!("projection" in event) || event.revision < previous || event.revision > Number(value.toRevision)) return invalid();
    previous = event.revision;
    return event;
  });
  return { gameId, fromRevision: value.fromRevision, toRevision: value.toRevision, events };
}

function coordinate(value: unknown): boolean {
  return Array.isArray(value) && value.length === 2 && typeof value[0] === "string" && integer(value[1]);
}
function coordinateMap(value: unknown, valid: (value: unknown) => boolean): boolean {
  // json-safe emits {} for an empty Clojure map, and coordinate pairs once
  // vector keys exist. Preserve both wire representations without accepting
  // arbitrary nonempty string-keyed objects as board coordinates.
  return (object(value) && Object.keys(value).length === 0)
    || (Array.isArray(value) && value.every(entry => Array.isArray(entry) && entry.length === 2 && coordinate(entry[0]) && valid(entry[1])));
}

function chatMessage(value: unknown): value is ChatMessage {
  return object(value) && typeof value.player === "string" && typeof value.message === "string"
    && typeof value.time === "number" && Number.isFinite(value.time)
    && (value.id === undefined || (typeof value.id === "string" && value.id.length > 0))
    && (value["client-id"] === undefined || typeof value["client-id"] === "string");
}

export function decodeChat(value: unknown, clientId: string, text: string): ChatMessage {
  if (!object(value) || !chatMessage(value.message) || !value.message.id
    || value.message["client-id"] !== clientId || value.message.message !== text) return invalid();
  return value.message;
}

function bot(value: unknown): value is Record<string, unknown> {
  return object(value) && typeof value.name === "string" && typeof value.description === "string";
}

export function decodeSession(value: unknown): Session {
  if (!object(value) || !jsonValue(value) || !(value.player === null || typeof value.player === "string")
    || !object(value.defaults) || !object(value.limits) || !Array.isArray(value.bots) || !value.bots.every(bot)) return invalid();
  const limits = value.limits;
  if (!["playerCounts", "ringCounts"].every(key => Array.isArray(limits[key]) && limits[key].length > 0 && limits[key].every(v => integer(v) && v > 0))
    || !integer(limits.gameNameMaxLength) || limits.gameNameMaxLength < 1
    || !integer(limits.chatMaxLength) || limits.chatMaxLength < 1
    || !(limits.playerCounts as unknown[]).includes(value.defaults["player-count"])
    || !(limits.ringCounts as unknown[]).includes(value.defaults["ring-count"])
    || !Array.isArray(value.defaults.players) || !value.defaults.players.every(v => typeof v === "string")
    || !["open", "private"].includes(String(value.defaults.visibility))) return invalid();
  return value as unknown as Session;
}
