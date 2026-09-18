# Modern Client Read API

The first modern-client endpoint is read-only:

    GET /api/v1/organism/games/:game-id
    Accept: application/json

It uses the existing signed Ring session. Anonymous callers receive an observer projection; signed-in participants receive their player identity and whether it is currently their turn.

## Successful response

The response contains:

- `gameId` — stable game key.
- `revision` — zero-based index of the current persisted history entry.
- `status` — `waiting`, `active`, or `completed`.
- `viewer` — session-derived `player`, `role`, and `canAct` fields.
- `invocation` — game settings and roster in deterministic JSON-safe form.
- `game` — canonical board/rules state in deterministic JSON-safe form.
- `legalActions` — recipient-scoped server-derived descriptors for the current player; always empty for observers, waiting players, and completed games. Clients must not render disabled copies of another player's controls or infer those controls from board state.
- `historySummary` — entry count and current index without exposing full history.
- `chat` — public chat lines without persistence IDs.

Maps whose Clojure keys are board coordinates are encoded as ordered key/value pairs. For example:

    [[["red", 0], {"player": "alice", "type": "eat"}]]

Each action descriptor contains:

- `actionId` — deterministic opaque identifier for one immediate canonical choice.
- `kind` and `label` — machine-readable phase and human-readable instruction.
- `actor` — session player allowed to submit the action.
- `source`, `targets`, and `options` — JSON-safe interaction hints for board and menu controls.
- `cost` and `consequences` — structured preview fields; absent information is represented by `null` or an empty collection rather than inferred by the client.
- `nextActions` — server-derived continuation descriptors. Move sources and eaters contain their legal destinations or food sources. Grow element choices contain payment choices, and each payment contains its legal growth destinations. Actions without a provisional continuation return an empty collection.

The current descriptor adapter covers introduction, organism and action selection, eat, grow, move, circulate, and pass phases. An action ID is derived from the phase and canonical choice value. The server can therefore recompute the current legal set and resolve the ID without accepting a replacement state or trusting client-side rule logic.

## Confirming Move and Eat actions

Choosing a movable component or eater is provisional in the modern client. The player may click that component again to clear it or click another projected component to replace it. No command is sent during those tentative selections.

After the player clicks a projected destination or food source, the client submits one command whose `actionId` contains both projected IDs. The server accepts a two-ID path only for `move-from` → `move-to` or `eat-to` → `eat-from`, revalidates both IDs against the current rules state, and persists the final result as one revision. An illegal source, illegal target, stale revision, or unsupported compound path is rejected without persisting the intermediate state.

## Completing a Grow action

Grow uses the engine's three explicit choice phases. The action panel first presents the legal element types. When each legal payment uses one distinct grow component, those components become the payment controls directly on the board; selecting one reveals only the server-projected `grow-to` spaces adjacent to that contributing component. A compound or otherwise ambiguous payment remains an explicit action-panel choice labeled by every contributing board coordinate and amount, and its destinations are adjacent to at least one contributing component. The player can move backward to change the payment or element type without changing the server revision. Selecting a destination submits the element, payment, and destination IDs as one command. The server re-derives all three legal choices, then persists one revision whose projection visibly removes the paid food and adds the selected element.

When the engine finds exactly one legal payment, it auto-applies that forced choice. The element descriptor then previews `grow-to` destinations directly, and confirmation submits the element and destination IDs as one atomic path; the server repeats the same forced transition while revalidating it.

## Missing game

Unknown game IDs return HTTP 404:

    {"error":"game-not-found","gameId":"missing"}

## Safety properties

The projection recursively removes database IDs and password fields. It never returns the in-memory WebSocket channel registry, complete game history, or the next game states behind legal choices. Legal-action generation remains server-owned; only descriptors are exposed.
