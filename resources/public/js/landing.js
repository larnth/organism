/* Progressive enhancement only: navigation and identity remain server-owned. */
(() => {
  const play = document.getElementById("landing-play");
  const dialog = document.getElementById("landing-account-dialog");
  const close = document.getElementById("landing-dialog-close");
  if (!play || !dialog || !close || typeof dialog.showModal !== "function") return;

  play.addEventListener("click", (event) => {
    if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    event.preventDefault();
    if (!dialog.open) dialog.showModal();
    play.setAttribute("aria-expanded", "true");
    close.focus();
  });

  dialog.addEventListener("close", () => {
    play.setAttribute("aria-expanded", "false");
    play.focus();
  });

  dialog.addEventListener("click", (event) => {
    if (event.target !== dialog) return;
    const bounds = dialog.getBoundingClientRect();
    if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) {
      dialog.close();
    }
  });
})();
