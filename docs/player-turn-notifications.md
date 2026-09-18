# Turn notifications

ORGANISM game tabs now call attention to a live player's turn without requiring browser notification permission.

- When your turn begins, the tab favicon turns green and the title changes to **● YOUR TURN — _game name_**.
- While the tab is in the background, its title alternates with the normal game title so the change is visible among other tabs.
- A short, gentle ding plays once when a live update hands the turn to you.
- The ding is enabled after your first click or key press in the site. Browsers intentionally block pages from playing sound before a person interacts with them, so a newly opened untouched tab may show the visual notification without making sound.
- Waiting players use the dormant favicon. Observers remain neutral and are never notified as though they had a turn.
- Opening or reconnecting to a game that is already waiting on you updates the tab immediately but does not replay the ding. This avoids repeated alerts after refreshes or network reconnects.
- Notifications stop when the turn passes or the game ends.

Email, push, and operating-system notifications are not part of this first version.
