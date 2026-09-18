# Modern Lobby Product Contract

## Goal

Replace the legacy “edit a game until it happens to become playable” flow with two explicit stages:

1. **Create game** — the owner chooses the table settings in private draft state.
2. **Lobby** — the owner launches a joinable room, takes seat one, and starts play only after the roster is full and every human player is ready.

“Launch lobby” and “Start game” are deliberately different actions.

## Player journey

### Create game

The signed-in creator chooses:

- game name;
- player count;
- field/ring count;
- optional table description;
- **Open** visibility, discoverable and joinable by any signed-in player; or
- **Private** visibility, omitted from public discovery and protected by a lobby password.

Standard ORGANISM rules remain fixed at three organisms to win with no mutations. The create surface should state this rather than rendering obsolete rule controls.

Launching the lobby:

- validates all settings before persistence;
- generates the stable room identity/invite code;
- seats the creator in seat one under their authenticated account identity;
- creates `player-count - 1` empty seats;
- starts the creator as not ready;
- replaces the draft URL with the stable lobby URL and renders the lobby without creating game state.

### Lobby

Every member sees:

- room name, visibility, field size, and invite code/link;
- the complete seat roster with ready, not-ready, and open states;
- one ready/not-ready control for their own human seat;
- lobby chat that continues into the game discussion history;
- one concise launch blocker.

The owner additionally sees:

- Copy Invite and Share;
- pre-start settings editing;
- Remove/Kick for every other occupied human seat;
- Start Game when all launch invariants pass.

Joining never starts the game. The owner explicitly starts it.

## Visibility and password rules

- Open rooms appear in public open-game discovery and accept authenticated seat claims while seats remain.
- Private rooms do not appear in public discovery. Their invite URL/code may identify the room, but joining requires the room password.
- Store only a slow password hash and password-policy metadata. Never persist or return the plaintext lobby password.
- Password verification grants admission to the room; it does not change account identity or seat authority.
- Lobby visibility and its password are fixed after launch in this implementation.
- Rate-limit password attempts per room and requester.

## Authority matrix

| Action | Visitor | Signed-in nonmember | Seated player | Owner |
|---|---:|---:|---:|---:|
| View open room preview | Yes | Yes | Yes | Yes |
| View redacted private preview | Yes | Yes | Yes | Yes |
| View private roster/chat | No | No | Yes | Yes |
| Claim one empty seat | No | Yes | Already seated | Already seated |
| Toggle own readiness | No | No | Yes | Yes |
| Post chat | No | No | Yes | Yes |
| Edit settings | No | No | No | Pre-start only |
| Remove another player | No | No | No | Pre-start only |
| Start game | No | No | No | When launch-ready |

All predicates must be enforced by the server, not only hidden in the UI. Chat author, seat identity, and owner authority come from the authenticated session.

## Lifecycle

`draft → lobby → active → complete`

- **Draft** is creator-private and may be entirely client-local until Launch Lobby.
- **Lobby** has a stable room ID, invite code, roster, readiness, chat, visibility, and password policy.
- **Active** locks lobby settings and roster operations, preserves chat, and owns canonical game state.
- **Complete** is read-only except for existing history/replay behavior.

Game name/invite identity is locked after lobby creation. “Edit game” in the lobby means edit allowed settings; it must not rename the room, reconnect to a second WebSocket room, overwrite the roster, or recreate game state.

## Launch invariants

The server enables Start Game only when:

- the caller is the room owner;
- lifecycle is `lobby`;
- the configured number of seats exists;
- every seat is occupied by one unique registered account or registered bot;
- every human seat is ready;
- bots are treated as ready;
- current settings pass the canonical board invocation validator.

A failed start returns one safe, specific blocker and makes no mutation.

## Roster and readiness commands

Use narrow mutation messages at the server boundary:

- authenticated seat claim through the join route;
- `lobby-kick` for owner removal;
- `lobby-ready` for the authenticated member's own readiness;
- roster-neutral `create` for allowed owner settings edits;
- `trigger-creation` for owner start;
- `chat` for membership-scoped discussion.

Each accepted mutation is serialized by the server, persisted, and broadcast only to current lobby members. A settings update is roster-neutral.

Readiness resets to false for all human members when the owner changes the ring count. Description edits do not reset readiness.

## Kick behavior

- The owner cannot kick themselves; they may cancel/archive the lobby through the existing deletion workflow.
- A removed player loses lobby membership immediately, receives a removal event, and cannot chat or ready.
- The vacated seat becomes open and the lobby remains in review state.
- No kick, seat, ready, password, or settings mutation is accepted after game start.

## Chat behavior

- Lobby and game use one chronological discussion stream.
- Only current room members may post before start.
- The server derives authorship from the session and applies the existing message length rule.
- The initial lobby projection includes recent history; realtime events append accepted messages.
- Removing/leaving a member does not rewrite old messages.

## UX direction under review

Disposable interactive comparisons are outside the repository at:

- `/private/tmp/organism-lobby-review-20260915/lobby-deck/index.html`
- `/private/tmp/organism-lobby-review-20260915/field-room/index.html`

Both established the charcoal, warm-white, lime, rounded-control direction. Lobby Deck is now the implemented production direction; Field Room remains a discarded visual reference.

## Acceptance

Before release, prove with two isolated authenticated browser contexts:

1. owner creates an open room and lands in seat one;
2. second account joins one empty seat and cannot claim another;
3. joining the final seat does not start play;
4. each human toggles only their own readiness;
5. non-owner cannot edit, kick, or start;
6. owner kick immediately removes membership and reopens the seat;
7. start stays disabled with one blocker until roster and readiness are complete;
8. owner starts exactly once and every connected browser moves to active play;
9. private room is absent from public discovery and rejects missing/wrong passwords;
10. lobby chat is membership-scoped, identity-derived, live in both browsers, and present after game start;
11. desktop and approximately 390px layouts have no horizontal overflow;
12. user-facing creation/lobby documentation is updated.

## Guardrails

Do not change game rules, legal choices, board geometry, account authentication, or active-game chat semantics as part of this lobby redesign. Do not commit, push, or deploy without explicit approval.