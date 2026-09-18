# Learning videos, turn ownership, and bot results

No account reset or game recreation is needed.

## What changes for players

### Learn to play

The empty black panels were video players whose MP4 files were missing from the deployment. All nine action examples and the example game now have their original videos. Each example has a real still-image preview and native playback controls. Reduced-motion settings prevent automatic playback; a failed video shows an explanation and a link to the complete rules instead of an empty box.

The original video files were recovered from `https://playorganism.io/video/`. Their origins, checksums, and sizes are recorded in `resources/public/video/manifest.json`. Action posters are first frames extracted from those videos, not replacement illustrations. The existing playthrough poster is unchanged. The MP4s are explicitly allowed through the repository's general video ignore rule and included in the application archive.

### Whose turn is it?

Your current turn shows **YOUR TURN**, the relevant board highlights, and the action controls. A seated player waiting for someone else sees **WAITING** and that player's name. An observer sees **WATCHING**. Neither waiting players nor observers can open starting-piece placement, select action sources/destinations, or use turn/undo controls.

Replay remains available, but it is read-only. **Return to live game** returns to the current board. Provisional placement and source/payment selections clear when the player, turn, round, replay cursor, or game result changes.

The client-side guards improve the interaction; they do not replace server authorization. A regression verifies that a waiting account, observer, or anonymous sender cannot submit another player's state or forged victory through the existing state handler. This is not a claim that all legacy command-validation issues have been redesigned.

A separate reconnect race was also fixed: when both accounts loaded a game at the same time, one database result could replace the other's live connection registration. Both browsers now retain their subscriptions and receive the next turn without refreshing.

### Bot simulations

A winning position and a recorded result are different engine states. Previously, the bot runner stopped at the former, leaving the UI at **Declare victory**. Bot runners now continue through the existing engine's integrity/victory transition, save the winner and final history entry, mark the game complete, schedule the normal rating update, and broadcast the authoritative result.

Observers see **GAME OVER — <winner> wins!** without clicking anything. Finished games expose no further legal choices, and the result survives reopening the game. Mixed human/bot games receive full authoritative bot snapshots rather than trying to reconstruct them from choice keys.

Winning conditions and tie-breaking rules are unchanged. Human-turn confirmation is unchanged; the automatic completion repair here targets bot runners.

## Verification and local review history

The server was rehearsed on loopback with a separate disposable MongoDB database.

- Two separate signed-in accounts created and joined a real game, completed both initial placements and turn handoffs, and reloaded successfully; an independent observer stayed read-only.
- All ten videos decoded and played; all ten posters loaded. Reduced motion, missing-video fallback, and 320/390/1440px layouts were checked.
- A real generated simulation completed automatically. The winner was visible after reopening and separately verified in MongoDB's game record and final history record.
- The backend suite passed 201 tests / 1,138 assertions; all 77 modern-client tests and four real-browser scenarios passed. TypeScript checking, client builds, and the application archive passed. All ten packaged videos and ten posters matched their source bytes.
- A scoped independent hosted review returned a pass with no material security or logic findings. Optional suggestions were older-Safari media-query compatibility and an additional server-authority regression; the authority regression was added.

The restored examples are available at `/organism/learn`. `/organism/generate` creates and saves a new simulation; it is not a harmless preview request.

The completed local review game is `/organism/play/generate-tide-spark-crystal` (winner: oroboros). Disposable rehearsal accounts and two-player test tables were removed after verification; this completed bot game remains available for review.

## Repeating the checks

Use Java 21, the project's installed npm dependencies, Google Chrome, and a disposable local MongoDB database. Set `MONGO_DATABASE` and `MONGO_HOST` explicitly when starting the server. Run these browser tests only against loopback and disposable data: they register accounts and create games.

With the dedicated local server running and the ORGANISM release bundle rebuilt:

```sh
npx shadow-cljs release organism
npm run test:e2e:legacy --prefix client
lein with-profile +project/test test
npm test --prefix client
npm run typecheck --prefix client
npm run build --prefix client
lein uberjar
```

`ORGANISM_TEST_URL` can select another loopback server. The browser configuration rejects non-loopback URLs. Test output and screenshots are under `target/legacy-browser-results/`; disposable accounts use `probe-a-` / `probe-b-` prefixes and test tables use `turn-ownership-`. Remove their records only from the explicitly isolated rehearsal database after testing; keep a completed bot game if needed for manual review.
