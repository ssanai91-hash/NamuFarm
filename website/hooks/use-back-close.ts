"use client";

import { useEffect, useRef } from "react";

/**
 * Phone/browser "back" closes the top-most open popup instead of leaving the site.
 *
 * One sentinel history entry (same URL, state.namuModal) represents "a popup is open".
 * - Back while armed: the browser leaves the sentinel; the top popup's close handler runs.
 *   If that handler keeps the popup open (guard, or a nested view such as a playing video
 *   returning to its list), or other popups remain, the sentinel is pushed again.
 * - Closing by button/state: the sentinel is removed with history.back() so a later back
 *   press is never wasted. Our own back() is ignored by the popstate listener.
 * Sync is deferred, so closing one popup and opening another in the same moment
 * keeps a single sentinel.
 */
type Entry = { close: () => void };

const stack: Entry[] = [];
let armed = false;
let ignorePops = 0;
let scheduled = false;
let listening = false;

function sync() {
  scheduled = false;
  if (typeof window === "undefined") return;
  if (stack.length > 0 && !armed) {
    window.history.pushState({ ...(window.history.state ?? {}), namuModal: true }, "", window.location.href);
    armed = true;
  } else if (stack.length === 0 && armed) {
    armed = false;
    if (window.history.state?.namuModal) {
      ignorePops += 1;
      window.history.back();
    }
  }
}

function scheduleSync() {
  if (scheduled) return;
  scheduled = true;
  window.setTimeout(sync, 0);
}

function onPopState() {
  if (ignorePops > 0) { ignorePops -= 1; return; }
  if (!armed) return;
  armed = false; // The browser already moved off the sentinel.
  const top = stack[stack.length - 1];
  top?.close();
  scheduleSync(); // Re-arm if a popup is still open after the close handler.
}

export function useBackClose(open: boolean, close: () => void) {
  const closeRef = useRef(close);
  closeRef.current = close;
  useEffect(() => {
    if (!open || typeof window === "undefined") return;
    if (!listening) { window.addEventListener("popstate", onPopState); listening = true; }
    const entry: Entry = { close: () => closeRef.current() };
    stack.push(entry);
    scheduleSync();
    return () => {
      const index = stack.indexOf(entry);
      if (index >= 0) stack.splice(index, 1);
      scheduleSync();
    };
  }, [open]);
}
