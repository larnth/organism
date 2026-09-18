# Create an ORGANISM account

The registration page now uses the approved **Player dock** design: the landing page’s charcoal/lime palette, playful Eat/Grow/Move shapes, and a single clear form. On phones the form comes first, without the decorative side panel.

## Signing up

1. From the ORGANISM landing page, choose **Let’s play → Create account**.
2. Pick a player name. Use 1–32 characters, starting with a letter or number. Letters and numbers from other languages, spaces, dots, underscores, and hyphens are supported.
3. Choose a password with **12–200 characters**. A longer passphrase works well. There is no required mixture of capitals, digits, or symbols. A password containing only whitespace is not accepted.
4. Enter the same password again. **Passwords match** appears as soon as they agree. Editing either field checks the match again.
5. Choose **Create account**. You are signed in and returned to the game page you were heading to.

A player name’s format is checked while you fill in the form. Availability is checked by the server when you submit; a green format message does not reserve the name. Names are unique without regard to capitalization.

## Feedback and recovery

- **Show / Hide** works independently for each password field. Password managers can recognize the player name and new-password fields.
- The length requirement stays visible. Short or overlong passwords get an inline explanation; long pasted passwords are not silently truncated.
- Matching feedback updates as you type. Other invalid fields get an explanation after leaving the field or attempting to submit, rather than starting the page covered in errors.
- Invalid submissions stay on the page and focus the first problem. A valid submission changes the button to **Creating your account…** and prevents repeated clicks while the page loads.
- **Caps Lock is on** appears when the browser reports Caps Lock in a password field.
- If the server rejects the submission, your player name and return destination are kept. Both password fields are cleared deliberately; re-enter them after correcting the problem.
- A server error receives keyboard focus and stays visible until you change the relevant field. If the name is already yours, choose **Log in instead**.
- **Log in** keeps the complete original destination, including its query parameters. **Back to the game** returns to the landing page.

Keyboard users can use **Skip to registration**, Tab through the fields and Show/Hide controls, and submit with the keyboard. Messages have text and live announcements rather than relying on color alone. The layout wraps on narrow screens and respects reduced-motion preferences.

## Without JavaScript

The same form still posts normally. Browser-required fields and supported name/minimum-length checks remain available. The server always checks every name and password rule, including the maximum length and matching confirmation. Mismatches return the form with an explanation and the player name intact. Show/Hide controls are hidden when their enhancement is unavailable.

## Security and implementation

The browser reads the name pattern and password limits rendered by the server. Existing server-side validation, CSRF protection, hashing, atomic case-insensitive name claiming, and canonical account sessions remain authoritative. Passwords are never echoed into returned HTML or stored by the page in URLs, browser storage, or logs. Registration responses use `Cache-Control: no-store`.

Return destinations must be local paths. Backslashes and control characters are rejected as well as absolute and protocol-relative URLs, so browser URL normalization cannot turn an apparently local path into an external redirect.

The existing password length convention counts UTF-16 code units. Name validation counts Unicode regex characters instead; supplementary letters must not be limited using a 32-unit HTML `maxlength`. Browser whitespace checks match Clojure’s `blank?` semantics rather than JavaScript’s broader `trim()` definition.

Presentation and behavior:

- `resources/html/organism/register.html`
- `resources/public/css/registration.css`
- `resources/public/js/registration.js`
- `src/clj/organism/routes/home.clj` (policy, safe return paths, rendering, and error context)

The new stylesheet is standalone: it does not load the legacy Bulma/game styles. The new script is explicitly exempted from the generated-JavaScript ignore rule. No React or ClojureScript rebuild is needed to author these assets; they are packaged with the server resources.

## Verification

With MongoDB available locally and Java 21 selected:

    lein with-profile +project/test test
    npm test --prefix client
    npm run build --prefix client
    lein uberjar

Verified locally on 2026-09-15:

- 189 backend tests / 1,062 assertions passed, including registration error-context, policy parity, redirect, escaping, identity, and existing landing regressions.
- 77 client tests passed, including live matching, limit boundaries, Unicode and whitespace parity, autofill/change handling, pending/back navigation, Caps Lock, and error announcements.
- TypeScript, the modern-client production build, and the backend production package passed. Existing dependency logging/namespace warnings remain; no new test failures were present.
- Real browser acceptance passed 11 workflow checks and 20 responsive states at widths of 1440, 1024, 390, and 320 pixels, with no horizontal overflow.
- Real account creation, protected account access, separate login, case-insensitive duplicate rejection, missing-CSRF rejection, and JavaScript-disabled registration were exercised in an isolated local database.
- Bypassing client validation still produced server rejection for short, overlong, and mismatched passwords. Rejected forms retained the player name and cleared both passwords.
- Both disposable accounts were removed; the rehearsal database returned to zero players.

The initial reviewer connection failed without a verdict. A subsequent hosted independent static review passed with no reported security, logic, accessibility/feedback, or validation-parity findings. The reviewed source matches the release candidate. This review supplements the executed tests and browser acceptance; it is not a guarantee that every possible defect has been found.

The complete test/build gate was repeated before release. A non-root AMD64 image passed the same 11 workflow checks and 20 responsive states over HTTPS with isolated MongoDB 8.0.14 and Secure/HTTP-only cookies.
