# The ORGANISM Play dock

The landing page at `/organism` uses the approved **Play dock** direction: charcoal, warm-white and lime colors, rounded menu controls, and the original Eat/Grow/Move silhouettes.

The full original cover is retained without cropping or image edits. The game is credited to **Ryan Spangler**, and the original artwork to **Wyn Tiedmers**. Clicking the cover or **Open the rulebook** opens the original PDF in a new tab.

## Finding your way around

When signed out:

- **Let’s play** opens a choice between **Log in** and **Create account**. Either choice returns you to the game list after signing in.
- **Watch a game** and **Learn to play** remain available without an account.
- **More to explore** opens the players list and bot sandbox destinations.

When signed in:

- Your account name links to your account settings.
- **Find a game** opens your games; **Create a game** opens table setup.
- **Watch a game**, **Learn to play**, the players list, and **Your bots** remain reachable.
- **Log out** ends the current account session.

The bot sandbox currently creates a new persistent all-bot game. It is not a read-only preview.

## Keyboard and small screens

- Tab through the menu, or use **Skip to the menu**.
- The account dialog contains keyboard focus. Escape or its close button dismisses it and restores focus to **Let’s play**.
- **More to explore** is a native disclosure that works without JavaScript.
- Without JavaScript or native-dialog support, **Let’s play** follows its normal login link instead.
- The layout stacks on phones, long account names wrap, and the artwork keeps its square proportions.
- Reduced-motion preferences disable the hover movement.

## Implementation and verification

The server chooses the visitor/signed-in variant from the authenticated session. Query parameters and browser JavaScript cannot select an account identity. Account names are HTML-escaped and account URL segments are encoded.

Presentation files:

- `resources/html/organism/home.html`
- `resources/html/organism/landing-icons.html`
- `resources/public/css/landing.css`
- `resources/public/js/landing.js`

The landing loads its own versioned assets rather than the legacy Bulma/game stylesheet. Handwritten `landing.js` is explicitly exempted from the generated-file ignore rule.

Verification on 2026-09-15:

- 181 backend tests / 939 assertions passed.
- 53 client tests passed; TypeScript and the modern-client production build passed.
- Real local registration and a separate login passed.
- Visitor layouts at 1440, 390, and 320 pixels and signed-in layouts at 1440 and 320 pixels had no horizontal overflow.
- Browser checks passed for the account dialog, focus containment/return, Escape, disclosure, reduced motion, and long account names.
- The cover is unchanged from Git HEAD (SHA-256 `b8072cfb4ed6799812837eaf8fc991aab2697e3fc92de4e74350ede87b080c48`).
