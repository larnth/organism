import { beforeEach, describe, expect, it, vi } from "vitest";
import landingScript from "../../../resources/public/js/landing.js?raw";

function startLanding() {
  // Execute only this checked-in static asset in the test document, never input.
  new Function("document", landingScript)(document);
}

function primaryClick(options: MouseEventInit = {}) {
  const event = new MouseEvent("click", { bubbles: true, cancelable: true, ...options });
  document.getElementById("landing-play")!.dispatchEvent(event);
  return event;
}

describe("landing account choice", () => {
  beforeEach(() => {
    document.body.innerHTML = `
      <button id="landing-play" aria-expanded="false">Let’s play</button>
      <dialog id="landing-account-dialog"><button id="landing-dialog-close">Close</button></dialog>`;
  });

  it("enhances a normal play click into the native account dialog", () => {
    const dialog = document.querySelector("dialog")!;
    const showModal = vi.fn();
    dialog.showModal = showModal;
    startLanding();
    expect(primaryClick().defaultPrevented).toBe(true);
    expect(showModal).toHaveBeenCalledOnce();
    expect(document.getElementById("landing-play")).toHaveAttribute("aria-expanded", "true");
    expect(document.activeElement).toBe(document.getElementById("landing-dialog-close"));
  });

  it("restores the play control when the native dialog closes", () => {
    const dialog = document.querySelector("dialog")!;
    dialog.showModal = vi.fn();
    startLanding();
    primaryClick();
    dialog.dispatchEvent(new Event("close"));
    expect(document.getElementById("landing-play")).toHaveAttribute("aria-expanded", "false");
    expect(document.activeElement).toBe(document.getElementById("landing-play"));
  });

  it("keeps normal link fallback if native dialogs are unsupported", () => {
    Object.defineProperty(document.querySelector("dialog"), "showModal", { value: undefined });
    startLanding();
    expect(primaryClick().defaultPrevented).toBe(false);
  });

  it("does not hijack modified or non-primary navigation", () => {
    const showModal = vi.fn();
    document.querySelector("dialog")!.showModal = showModal;
    startLanding();
    for (const options of [{ metaKey: true }, { ctrlKey: true }, { shiftKey: true }, { altKey: true }, { button: 1 }]) {
      expect(primaryClick(options).defaultPrevented).toBe(false);
    }
    expect(showModal).not.toHaveBeenCalled();
  });

  it("is safe on the signed-in page without an account dialog", () => {
    document.body.innerHTML = "<main>Welcome back.</main>";
    expect(startLanding).not.toThrow();
  });
});
