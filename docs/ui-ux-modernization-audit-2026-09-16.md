# ORGANISM UI/UX modernization audit

Date: 2026-09-16

## Scope and method

This audit covers the ORGANISM web app outside the already-modernized landing, registration, lobby, and board-first gameplay shell.

The review covered:

- desktop: 1280 × 633;
- phone: 390 × 844;
- logged-out routes;
- an active game as an observer.

Authenticated account, personal game-list, bot-list, and bot-editor routes were reviewed from source without creating an account or data.

The existing visual authority is the landing/gameplay/lobby system:

- near-black background (`#0c0f12`);
- warm-white text (`#f3f0e8`);
- muted gray supporting copy;
- lime accent (`#c9f27b`);
- rounded, restrained panels and controls;
- board-first or task-first information hierarchy;
- compact `O / ORGANISM` wordmark and clear status language.

## Executive summary

The core experience now has a strong theme, but several secondary routes still feel like a different application. The largest gap is not cosmetic: the public game lists and player game lists become visually tangled on a phone even though the document technically avoids horizontal overflow. Login, account settings, errors, rankings, and bot tooling each use a separate visual grammar.

Recommended order:

1. Rebuild the public and personal game-list cards on the modern shell.
2. Bring login and invite return flow into the approved Player dock system.
3. Modernize player profiles, rankings, account settings, and error states as one shared “community/account” family.
4. Reframe bot management as an advanced ORGANISM workspace with responsive drawers.
5. Give Learn a light theme-alignment pass rather than a redesign.
6. Make bot-sandbox generation an explicit action rather than a navigation GET.

## What is already cohesive

### Landing, registration, lobby, and gameplay

These surfaces should remain the design authority rather than being redesigned again.

- The landing owns a complete token set and responsive component grammar in `resources/public/css/landing.css:1-127`.
- Gameplay uses the same dark/warm-white/lime palette and a board-dominant shell in `resources/public/css/screen.css:256-420`.
- The game view at `/organism/play/SomeGame` was visually coherent at both audited viewport sizes. At 390 px it had no horizontal overflow, retained a legible board, and kept the observer state/action dock visible.
- The lobby has its own modern stylesheet and follows the same hierarchy.

### Learn

Learn is usable and responsive today. At 390 px it had no horizontal overflow; the rulebook, diagram, and videos stacked clearly. The video loading, poster, reduced-motion, native controls, and fallback behavior are deliberate in `resources/html/organism/learn.html:109-170`.

It needs alignment, not reconstruction.

## Findings and recommendations

### 1. Public Observe is the highest-priority visual defect

Severity: High
Category: Responsive UI / information hierarchy

Live evidence:

- At 1280 px, Observe is a sparse legacy list under a large gray pill banner.
- At 390 px, the game name, round, timestamps, and player chips collide and overlap in the first viewport.
- The page reports `scrollWidth === clientWidth`, so this is not a simple horizontal-overflow defect. The inline row composition itself does not reflow into a readable card.

Source cause:

- `active-game-card` renders one flow of inline spans with fixed padding and margins in `src/cljs/organism/components.cljs:486-573`.
- Observe sections and the header are also inline-styled in `src/cljs/organism/components.cljs:575-629`.
- The route loads only the broad legacy stylesheet in `resources/html/organism/observe.html:12-29`.

Recommendation:

- Replace the inline row with a semantic card grid using shared classes.
- Make the whole primary card area open the game.
- Use a stable hierarchy: game name and activity status; current turn; roster avatars/chips; last move; optional description.
- Stack cards at phone width; never compress every datum into one line.
- Use the same wordmark/topbar and color tokens as gameplay.
- Add purposeful empty, active, inactive, and completed states.

### 2. Player profiles and “My games” have the same mobile failure

Severity: High
Category: Responsive UI / navigation

Live evidence:

- `/organism/player/Chickyy` uses a very large player-color banner followed by OPEN and ACTIVE rows.
- At 390 px, the open game name, ring count, owner/player labels, and open-seat control overlap.
- Active-game metadata also collapses into a tight strip with little scan hierarchy.

Source cause:

- The route delegates to the shared legacy `player-games-page` at `src/cljs/organism/play.cljs:4314-4347`.
- The page and banner remain inline-styled in `src/cljs/organism/components.cljs:1056-1180`.
- Game sections reuse row-oriented legacy components rather than modern lobby cards.

Recommendation:

- Create one modern game-card component shared by Observe, public player profiles, and the signed-in game list.
- Keep player color as a compact avatar/accent, not a full-width dominant banner.
- Separate lifecycle status from action: `OPEN`, `YOUR TURN`, `WAITING`, `COMPLETE`.
- Give join/delete/keep actions explicit labels and safe placement rather than embedding them in a crowded metadata row.
- Add a standard ORGANISM back/home path and signed-in account affordance.

### 3. Login breaks the account-entry experience

Severity: High
Category: Cohesion / responsive UI / conversion

Live evidence:

- Login is still a random-color oval with small monospace fields, while registration uses the approved expressive Player dock.
- At 390 px, the page measured 405 px of content against a 390 px viewport because the 640 px oval is not responsive.
- The invite return behavior works, but the visual transition from modern landing or lobby invite to this legacy form feels like leaving the product.

Source cause:

- The page has a fixed `width: 640px` oval and page-local styling in `resources/html/login.html:9-71`.
- Its hue is randomized on every load in `resources/html/login.html:97-102`.
- Login and registration already share redirect semantics through `resources/html/login.html:80-94` and `src/clj/organism/routes/home.clj:92-97,134-156`.

Recommendation:

- Reuse the registration Player dock shell, wordmark, typography, spacing, Show/Hide control, and inline error treatment.
- Preserve the existing redirect and registration handoff exactly.
- Add concise invite-aware copy when the return path is a lobby: “Log in to choose a seat.”
- Remove random presentation color; identity color belongs to a player after login, not to the authentication page.

### 4. Rankings are visually unstable and hard to scan

Severity: Medium
Category: Information design / accessibility

Live evidence:

- `/organism/players` is structurally readable on desktop but still looks like a debug/stat row.
- It opens with three lines of rating-model explanation before showing the players.
- The many unrelated colored pills compete equally with the player identity.

Source cause:

- Column hues are randomly generated when the client loads in `src/cljs/organism/components.cljs:633-640`.
- Every statistic becomes a colored inline pill in `src/cljs/organism/components.cljs:642-675,677-770`.
- The page uses the same oversized legacy banner as Observe.

Recommendation:

- Use a stable leaderboard/card or responsive table with a clear primary rating and compact secondary record.
- Keep Glicko uncertainty visible, but move the full model explanation behind “How ratings work.”
- Use semantic, deterministic styling; do not assign random hues to metrics.
- On phones, show player, rating, record, and status first; disclose lower-value counts rather than wrapping a wall of pills.

### 5. Account settings are a disconnected one-control page

Severity: Medium
Category: Cohesion / action feedback

Source evidence:

- Account settings use another large identity-color banner and page-local styling in `resources/html/account.html:8-98`.
- The only preference is a custom canvas color wheel plus lightness slider.
- Saving is debounced but has no visible pending, success, or failure status in `resources/html/account.html:230-245`.

Recommendation:

- Place settings in the same account/community shell as the player profile.
- Present “Player color” as one bounded settings card with a live token preview.
- Add a persistent status region: `Saving…`, `Saved`, or an actionable failure.
- Add a clear return destination to the user’s games, not only a name link to the landing page.
- Preserve the current server-owned player identity and CSRF behavior.

### 6. Error pages are raw framework output

Severity: Medium
Category: Recovery UX

Live evidence:

- An unknown route renders plain black text on white with “Error: 404” and “404 - Page not found.”
- It has no ORGANISM branding, explanation, or recovery action.

Source cause:

- `resources/html/error.html:1-20` has no product stylesheet or navigation.

Recommendation:

- Create a small branded error shell using the standard tokens and wordmark.
- Give 404 a useful action: return home, browse games, or learn to play.
- Keep technical detail out of ordinary user errors; retain request IDs or diagnostics only where they help support.

### 7. Bot management looks like a separate sci-fi developer tool

Severity: Medium
Category: Cohesion / advanced-workspace responsiveness

Source evidence:

- ORGANISM bot pages reuse the Journey bot client through `resources/html/organism/bots.html:11-29` and `resources/html/organism/bot_editor.html:11-30`.
- The bot visual system is navy/blue monospace (`#04040E`, `#7AAAE0`) rather than ORGANISM’s charcoal/lime system in `src/cljs/journey/bots.cljs:642-708`.
- The editor is a fixed three-column `100vw × 100vh` flex shell with hidden overflow in `src/cljs/journey/bots.cljs:1278-1283`.
- Its rails are fixed at 200 px and 280 px in `src/cljs/journey/bots.cljs:721-726,1187-1192`.
- The “add diagram” path uses a native `prompt()` in `src/cljs/journey/bots.cljs:1244-1247`.

Recommendation:

- Treat this as an advanced canvas workspace, not a normal settings page.
- Apply ORGANISM tokens and wordmark while preserving the graph editor’s functional color semantics.
- Keep the canvas dominant; make tile palette and properties inspector collapsible drawers.
- On phone, explicitly support review/minor edits or state that full graph editing requires a larger screen; do not squeeze both fixed rails around a tiny canvas.
- Replace native prompts with an in-product dialog and visible save/error state.
- Keep shared Journey behavior modular so ORGANISM theming does not regress Journey.

### 8. Learn needs a small cohesion and contrast pass

Severity: Low
Category: Visual consistency / accessibility

Live evidence:

- Learn works at desktop and phone widths.
- Its page hierarchy is clear and the original artwork remains prominent.
- It lacks the shared wordmark/topbar and uses a flatter, grayer visual language than landing/gameplay.

Source evidence:

- The entire presentation is page-local CSS in `resources/html/organism/learn.html:9-80`.
- Supporting text frequently uses `#555`, `#666`, and `#777` on `#111`, which should be contrast-checked.
- Cards/media use 4 px radii while the rest of the current product uses softer rounded containers.

Recommendation:

- Preserve the content order and media behavior.
- Move colors/type/spacing into shared ORGANISM tokens.
- Add the standard wordmark/back treatment and a small “Start playing” action at the end.
- Increase muted-text contrast and align media framing with the current panel grammar.

### 9. “Bot sandbox” is navigation with a creation side effect

Severity: Medium
Category: UX safety / lifecycle clarity

Source evidence:

- The landing’s “Bot sandbox” link points directly to `/organism/generate` in `resources/html/organism/home.html:86-93`.
- The route is a GET in `src/clj/organism/routes/organism.clj:424-428`.
- The handler immediately creates and persists a bot game in `src/clj/organism/routes/organism.clj:354-397`.

Recommendation:

- Label the side effect explicitly: “Generate a bot game.”
- Prefer a small confirmation/setup surface and a CSRF-protected POST for creation.
- Keep GET safe and navigational where practical.
- Provide a clear way back from the generated game and explain that it runs automatically.

## Cross-surface design direction

### Shared application shell

Create one ORGANISM shell for all non-board routes:

- compact `O / ORGANISM` wordmark;
- optional page title and context;
- account/avatar when signed in;
- consistent max-width and responsive gutters;
- warm-white body copy, muted supporting copy, lime focus/accent;
- consistent focus states and 44 px touch targets.

Do not force board gameplay into a content-column shell; it already has the correct canvas-first structure.

### Shared card grammar

Use three reusable card families:

1. **Game card** — game identity, lifecycle/turn status, roster, recency, primary action.
2. **Player card/row** — avatar/color, name, rating/record, activity.
3. **Settings card** — label, explanation, control, persistent save state.

This removes the current duplicated inline-style implementations and gives desktop/mobile behavior one owner.

### Stable color semantics

- Lime: primary action, focus, success/readiness.
- Player colors: identity/avatar and small turn indicators.
- Warm-white: primary text.
- Muted gray: explanatory text only when contrast remains sufficient.
- Amber/pink/blue: limited thematic or game-action meaning, not random metric decoration.
- Red: destructive/error states.

### Motion and feedback

- Retain reduced-motion handling.
- Every asynchronous mutation should show pending/success/failure in a persistent local status region.
- Avoid random page colors that change hierarchy between visits.

## Proposed visual-review sequence

No production UI should be changed until the chosen direction is visually approved.

### Checkpoint 1: Community and game lists

Prototype one populated desktop and phone direction containing:

- Observe;
- My games/player profile;
- Rankings.

This is the best first checkpoint because one shared card/shell system resolves the largest visible inconsistency and the verified mobile defects.

### Checkpoint 2: Account entry and recovery

Prototype:

- login from a normal landing path;
- login from an invite path;
- account color settings;
- 404/not-found.

### Checkpoint 3: Advanced bot workspace

Prototype two states:

- bot catalogue/list;
- editor with palette and properties drawers at desktop and phone widths.

### Checkpoint 4: Learn alignment

Apply only the selected shared shell/tokens and contrast corrections; preserve its media structure.

## Acceptance criteria after visual approval

- No overlap or clipping at 390 px, 760 px, 1024 px, and a wide desktop viewport.
- `document.documentElement.scrollWidth === clientWidth` on ordinary content routes.
- Populated states with long game/player names remain readable.
- Keyboard focus is visible and every interactive control has a clear label.
- Login/register preserve safe invite return paths.
- Observe, player profile, and My games share one game-card implementation.
- Player/metric colors are deterministic and contrast-safe.
- Account and bot saves expose pending, success, and failure feedback.
- Bot editor remains usable with drawers open and closed, without shrinking the canvas below a meaningful size.
- Error pages always provide a recovery action.
- Generate-game creation is explicit and not triggered by harmless prefetch/navigation.

## Audit limitations

- Authenticated routes were not exercised live because no matching production credential was available in the browser vault.
- No account was created and no production state was mutated.
- No UI code, build, test, deployment, or production configuration was changed as part of this audit.
