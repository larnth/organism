export type JsonPrimitive = string | number | boolean | null;
export type JsonValue =
  | JsonPrimitive
  | JsonValue[]
  | { [key: string]: JsonValue };

export interface Viewer {
  player: string | null;
  role: "player" | "observer";
  canAct: boolean;
  canUndo?: boolean;
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
  nextActions?: LegalAction[];
}

export interface GameProjection {
  instanceId?: string | null;
  historyCursor?: number;
  lobby?: LobbyProjection | null;
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
  | { type: "snapshot.unavailable"; version: 1 }
  | ChatCreatedEvent
  | GameDeletedEvent;

export interface CatchUpResponse {
  gameId: string;
  fromRevision: number;
  toRevision: number;
  events: ProjectionEvent[];
}

export interface ChatMessage {
  type?: string;
  id?: string;
  player: string;
  message: string;
  time: number;
  "client-id"?: string;
}
export interface Bot { name: string; description: string }
export interface LobbyProjection {
  owner: string | null;
  visibility: "open" | "private";
  member: boolean;
  readiness: Record<string, boolean>;
  canStart: boolean;
  bots?: string[];
  blocker?: string | null;
  availableBots?: (Bot & { player: string })[];
}
export interface Session {
  player: string | null;
  defaults: Record<string, JsonValue>;
  limits: { playerCounts: number[]; ringCounts: number[]; gameNameMaxLength: number; chatMaxLength: number };
  bots: Bot[];
}
export type LobbyCommand =
  | { operation: "configure"; body: { invocation: Record<string, JsonValue> } }
  | { operation: "join"; body: { index: number; password?: string } }
  | { operation: "ready"; body: { ready: boolean } }
  | { operation: "start"; body: Record<string, never> }
  | { operation: "seat"; body: { index: number; player: string } }
  | { operation: "kick"; body: { index: number } };
