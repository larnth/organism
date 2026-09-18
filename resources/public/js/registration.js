/* Progressive enhancement only: the normal CSRF-protected POST stays authoritative. */
(function () {
  "use strict";
  const form = document.getElementById("registration-form");
  if (!form) return;

  const player = document.getElementById("player");
  const password = document.getElementById("password");
  const confirmation = document.getElementById("password-confirm");
  const button = document.getElementById("create-account");
  const error = document.getElementById("registration-error");
  const fields = [player, password, confirmation];
  const minimum = password.minLength;
  const maximum = Number(password.dataset.maxLength);
  let namePattern;
  try {
    namePattern = new RegExp("^(?:" + player.pattern + ")$", "u");
  } catch (_) {
    return; // Keep native validation and normal submission in older browsers.
  }
  if (minimum < 1 || !Number.isFinite(maximum) || maximum < minimum) return;

  // Java Character.isWhitespace, used by clojure.string/blank?. JS \s/trim
  // would incorrectly reject nonbreaking spaces and miss U+001C–U+001F.
  const blankPassword = /^[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]*$/u;
  const touched = new Set();
  let attempted = false;
  let submitting = false;

  function playerMessage() {
    if (!player.value) return "Choose a player name.";
    if (!namePattern.test(player.value)) {
      return "Use 1–" + player.dataset.maxLength + " characters, starting with a letter or number. Only letters, numbers, spaces, dots, underscores, and hyphens are allowed.";
    }
    return "";
  }

  function passwordMessage() {
    if (!password.value) return "Choose a password.";
    if (blankPassword.test(password.value)) return "Choose a password, not only spaces.";
    // Java String.count and JS String.length both count UTF-16 code units.
    if (password.value.length < minimum) return "Use at least " + minimum + " characters.";
    if (password.value.length > maximum) return "Use no more than " + maximum + " characters.";
    return "";
  }

  function confirmationMessage() {
    if (!confirmation.value) return "Type your password again.";
    if (password.value !== confirmation.value) return "These passwords don’t match yet.";
    return "";
  }

  function feedback(input, message, success, neutral) {
    const node = document.getElementById(input.id + "-feedback");
    const showError = attempted || touched.has(input) || (input === confirmation && !!input.value);
    const valid = !!input.value && !message;
    const text = valid ? success : showError ? message : neutral;
    // Avoid repeating unchanged live-region announcements on every keystroke.
    if (node.textContent !== text) node.textContent = text;
    node.dataset.state = valid ? "valid" : showError && message ? "invalid" : "waiting";
    input.setAttribute("aria-invalid", String(Boolean(message && showError)));
    input.setCustomValidity(message);
  }

  function validate() {
    feedback(player, playerMessage(), "Player name format looks good.", "");
    feedback(password, passwordMessage(), "Password length looks good.", "");
    feedback(confirmation, confirmationMessage(), "Passwords match.", "Enter the same password again.");
    return fields.find(input => !input.validity.valid);
  }

  function changed(input) {
    if (error && (error.dataset.errorField === input.id ||
        (error.dataset.errorField === confirmation.id && input === password))) {
      error.hidden = true;
    }
    validate();
  }

  for (const input of fields) {
    input.addEventListener("input", () => changed(input));
    input.addEventListener("change", () => changed(input));
    input.addEventListener("blur", () => { touched.add(input); validate(); });
  }
  for (const input of [password, confirmation]) {
    const caps = document.getElementById(input.id + "-caps");
    const showCaps = event => { caps.hidden = !event.getModifierState("CapsLock"); };
    input.addEventListener("keydown", showCaps);
    input.addEventListener("keyup", showCaps);
    input.addEventListener("blur", () => { caps.hidden = true; });
  }
  for (const reveal of form.querySelectorAll("[data-reveal]")) {
    reveal.hidden = false;
    reveal.addEventListener("click", () => {
      const input = document.getElementById(reveal.dataset.reveal);
      const visible = input.type === "password";
      input.type = visible ? "text" : "password";
      reveal.textContent = visible ? "Hide" : "Show";
      reveal.setAttribute("aria-pressed", String(visible));
      reveal.setAttribute("aria-label", (visible ? "Hide " : "Show ") +
        (input === confirmation ? "confirmed password" : "password"));
    });
  }
  form.addEventListener("submit", event => {
    if (submitting) { event.preventDefault(); return; }
    attempted = true;
    const invalid = validate();
    if (invalid) {
      event.preventDefault();
      invalid.focus();
      return;
    }
    submitting = true;
    form.setAttribute("aria-busy", "true");
    button.disabled = true;
    button.querySelector(".button-label").textContent = "Creating your account…";
    // Do not replace this with fetch: retain browser POST, CSRF and redirect handling.
  });
  window.addEventListener("pageshow", () => {
    // Back/forward cache can restore the form after a previous pending submit.
    submitting = false;
    form.removeAttribute("aria-busy");
    button.disabled = false;
    button.querySelector(".button-label").textContent = "Create account";
    validate();
  });
  validate();
  form.noValidate = true;
  if (error) error.focus();
})();
