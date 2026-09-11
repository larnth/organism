export type JsonPrimitive = string | number | boolean | null;
export type JsonValue =
  | JsonPrimitive
  | JsonValue[]
  | { [key: string]: JsonValue };

export interface Viewer {
  player: string | null;
  role: "player" | "observer";
  canAct: boolean;
}

export interface LegalAction {
  actionId: string;
  kind: string;
  label?: string;
  actor?: string;
  source?: JsonValue;
  targets?: JsonValue[];
  options?: JsonValue[];
  cost?: JsonValue;
  consequences?: JsonValue;
}

export interface GameProjection {
  gameId: string;
  revision: number;
  status: "waiting" | "active" | "completed";
  viewer: Viewer;
  invocation: { [key: string]: JsonValue };
  game: { [key: string]: JsonValue } | null;
  legalActions: LegalAction[];
  historySummary: {
    entries: number;
    currentIndex: number;
  };
  chat: JsonValue[];
}

export interface ProjectionEvent {
  type: "snapshot" | "game.updated";
  version: 1;
  revision: number;
  projection: GameProjection;
}

export interface ChatCreatedEvent {
  type: "chat.created";
  version: 1;
  gameId: string;
  message: JsonValue;
}

export interface GameDeletedEvent {
  type: "game.deleted";
  version: 1;
  gameId: string;
}

export type GameEvent =
  | ProjectionEvent
  | ChatCreatedEvent
  | GameDeletedEvent;

export interface CatchUpResponse {
  gameId: string;
  fromRevision: number;
  toRevision: number;
  events: ProjectionEvent[];
}
