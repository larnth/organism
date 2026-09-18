import { beforeEach, describe, expect, it } from "vitest";
import registrationScript from "../../../resources/public/js/registration.js?raw";

let events: EventTarget;

function startRegistration() {
  new Function("document", "window", registrationScript)(document, events);
}

function input(id: string) {
  return document.getElementById(id) as HTMLInputElement;
}

function change(id: string, value: string, type = "input") {
  input(id).value = value;
  input(id).dispatchEvent(new Event(type, { bubbles: true }));
}

function submit() {
  const event = new Event("submit", { bubbles: true, cancelable: true });
  document.querySelector("form")!.dispatchEvent(event);
  return event;
}

function fillValid() {
  change("player", "Moss And Moon");
  change("password", "only lowercase words");
  change("password-confirm", "only lowercase words");
}

function feedback(id: string) {
  return document.getElementById(`${id}-feedback`)!;
}

beforeEach(() => {
  events = new EventTarget();
  document.body.innerHTML = `
    <form id="registration-form" method="POST" action="/register">
      <input type="hidden" name="__anti-forgery-token" value="test-csrf">
      <input type="hidden" name="redirect" value="/organism/play">
      <input id="player" name="player" required pattern="[\\p{L}\\p{N}][\\p{L}\\p{N} _.\\-]{0,31}" data-max-length="32">
      <p id="player-feedback" aria-live="polite"></p>
      <input id="password" name="password" type="password" required minlength="12" data-max-length="200">
      <button type="button" data-reveal="password" aria-controls="password" aria-pressed="false" hidden>Show</button>
      <p id="password-feedback" aria-live="polite"></p><p id="password-caps" hidden>Caps Lock is on.</p>
      <input id="password-confirm" name="password-confirm" type="password" required>
      <button type="button" data-reveal="password-confirm" aria-controls="password-confirm" aria-pressed="false" hidden>Show</button>
      <p id="password-confirm-feedback" aria-live="polite">Enter the same password again.</p><p id="password-confirm-caps" hidden>Caps Lock is on.</p>
      <button id="create-account" type="submit"><span class="button-label">Create account</span></button>
    </form>`;
});

describe("Player dock registration enhancement", () => {
  it("loads the actual source asset and enables inline rather than native validation", () => {
    startRegistration();
    expect(registrationScript).not.toBe("");
    expect(document.querySelector("form")).toHaveAttribute("novalidate");
    expect(input("player")).not.toHaveAttribute("aria-invalid", "true");
    expect(feedback("password").textContent).toBe("");
  });

  it("blocks empty submission and focuses the first problem", () => {
    startRegistration();
    expect(submit().defaultPrevented).toBe(true);
    expect(document.activeElement).toBe(input("player"));
    expect(feedback("player")).toHaveTextContent("Choose a player name");
  });

  it.each([11, 201])("blocks a %i-character password and explains the limit", length => {
    startRegistration(); fillValid();
    change("password", "x".repeat(length));
    change("password-confirm", "x".repeat(length));
    expect(submit().defaultPrevented).toBe(true);
    expect(document.activeElement).toBe(input("password"));
    expect(feedback("password")).toHaveTextContent(length < 12 ? "at least 12" : "no more than 200");
  });

  it.each([12, 200])("accepts a %i-character password without invented mixture rules", length => {
    startRegistration(); fillValid();
    change("password", "x".repeat(length));
    change("password-confirm", "x".repeat(length));
    expect(submit().defaultPrevented).toBe(false);
  });

  it("reads password limits from the server-rendered field, not duplicate constants", () => {
    input("password").minLength = 15;
    input("password").dataset.maxLength = "24";
    startRegistration(); fillValid();
    change("password", "x".repeat(14));
    change("password-confirm", "x".repeat(14));
    expect(submit().defaultPrevented).toBe(true);
    expect(feedback("password")).toHaveTextContent("at least 15");
    change("password", "x".repeat(25));
    change("password-confirm", "x".repeat(25));
    expect(submit().defaultPrevented).toBe(true);
    expect(feedback("password")).toHaveTextContent("no more than 24");
  });

  it("revalidates confirmation when either password field changes", () => {
    startRegistration(); fillValid();
    expect(feedback("password-confirm")).toHaveTextContent("Passwords match.");
    change("password", "a different passphrase");
    expect(feedback("password-confirm")).toHaveTextContent("don’t match");
    expect(submit().defaultPrevented).toBe(true);
    expect(document.activeElement).toBe(input("password-confirm"));
    change("password-confirm", "a different passphrase");
    expect(feedback("password-confirm")).toHaveTextContent("Passwords match.");
    expect(input("password-confirm")).toHaveAttribute("aria-invalid", "false");
    expect(submit().defaultPrevented).toBe(false);
  });

  it("validates autofill/change and rechecks values at submit without input events", () => {
    startRegistration();
    change("player", "Moss", "change");
    change("password", "a valid passphrase", "change");
    change("password-confirm", "a valid passphrase", "change");
    expect(feedback("password-confirm")).toHaveTextContent("Passwords match.");
    input("password").value = "another valid passphrase";
    expect(submit().defaultPrevented).toBe(true);
  });

  it.each([" ", "\u2003", "\u001c"])("rejects server-defined whitespace-only passwords (%j)", character => {
    startRegistration(); fillValid();
    change("password", character.repeat(12));
    change("password-confirm", character.repeat(12));
    expect(submit().defaultPrevented).toBe(true);
    expect(feedback("password")).toHaveTextContent("not only spaces");
  });

  it("preserves the server’s UTF-16 length and nonbreaking-space behavior", () => {
    startRegistration(); fillValid();
    change("password", "🌱".repeat(6));
    change("password-confirm", "🌱".repeat(6));
    expect(submit().defaultPrevented).toBe(false);
    events.dispatchEvent(new Event("pageshow"));
    change("password", "\u00a0".repeat(12));
    change("password-confirm", "\u00a0".repeat(12));
    expect(submit().defaultPrevented).toBe(false);
  });

  it.each(["_Moss", "a".repeat(33), "<script>"])("blocks invalid player names (%s)", name => {
    startRegistration(); fillValid(); change("player", name);
    expect(submit().defaultPrevented).toBe(true);
    expect(document.activeElement).toBe(input("player"));
  });

  it.each(["Élan-2", "ゲーム", "𐐀".repeat(32)])("allows server-supported Unicode names (%s)", name => {
    startRegistration(); fillValid(); change("player", name);
    expect(feedback("player")).toHaveTextContent("format looks good");
    expect(submit().defaultPrevented).toBe(false);
  });

  it("reveals only the selected field with accessible state and no lost value", () => {
    startRegistration(); fillValid();
    const button = document.querySelector<HTMLButtonElement>('[data-reveal="password"]')!;
    expect(button.hidden).toBe(false);
    button.click();
    expect(input("password").type).toBe("text");
    expect(input("password-confirm").type).toBe("password");
    expect(button).toHaveAttribute("aria-pressed", "true");
    expect(button).toHaveAccessibleName("Hide password");
    button.click();
    expect(input("password").type).toBe("password");
    expect(input("password").value).toBe("only lowercase words");
  });

  it("keeps normal POST fields, indicates pending state, and blocks duplicate submits", () => {
    startRegistration(); fillValid();
    const form = document.querySelector("form")!;
    expect(submit().defaultPrevented).toBe(false);
    expect(form.method).toBe("post");
    expect(new FormData(form).get("__anti-forgery-token")).toBe("test-csrf");
    expect(new FormData(form).get("redirect")).toBe("/organism/play");
    expect(form).toHaveAttribute("aria-busy", "true");
    expect(document.getElementById("create-account")).toBeDisabled();
    expect(document.getElementById("create-account")).toHaveTextContent("Creating your account");
    expect(submit().defaultPrevented).toBe(true);
    events.dispatchEvent(new Event("pageshow"));
    expect(document.getElementById("create-account")).not.toBeDisabled();
    expect(form).not.toHaveAttribute("aria-busy", "true");
    expect(submit().defaultPrevented).toBe(false);
  });

  it("announces server errors until the relevant field changes", () => {
    document.querySelector("form")!.insertAdjacentHTML("beforebegin", '<div id="registration-error" role="alert" tabindex="-1" data-error-field="player">That name is taken.</div>');
    startRegistration();
    expect(document.activeElement).toBe(document.getElementById("registration-error"));
    change("password", "a valid passphrase");
    expect(document.getElementById("registration-error")).not.toHaveAttribute("hidden");
    change("player", "New Name");
    expect(document.getElementById("registration-error")).toHaveAttribute("hidden");
  });

  it("shows Caps Lock feedback and clears it on blur", () => {
    startRegistration();
    input("password").dispatchEvent(new KeyboardEvent("keyup", { key: "A", modifierCapsLock: true }));
    expect(document.getElementById("password-caps")).not.toHaveAttribute("hidden");
    input("password").dispatchEvent(new Event("blur"));
    expect(document.getElementById("password-caps")).toHaveAttribute("hidden");
  });

  it("does nothing on unrelated pages", () => {
    document.body.innerHTML = "<main>Unrelated page</main>";
    expect(startRegistration).not.toThrow();
  });
});
