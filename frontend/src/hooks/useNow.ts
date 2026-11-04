import { useSyncExternalStore } from 'react';

// One shared 1-second ticker for every live countdown on the page.
let now = Date.now();
const subscribers = new Set<() => void>();
let timer: number | null = null;

function subscribe(callback: () => void): () => void {
  subscribers.add(callback);
  if (timer === null) {
    now = Date.now();
    timer = window.setInterval(() => {
      now = Date.now();
      subscribers.forEach((notify) => notify());
    }, 1000);
  }
  return () => {
    subscribers.delete(callback);
    if (!subscribers.size && timer !== null) {
      window.clearInterval(timer);
      timer = null;
    }
  };
}

function getSnapshot(): number {
  return now;
}

/** Current time in ms, re-rendering the caller every second. */
export function useNow(): number {
  return useSyncExternalStore(subscribe, getSnapshot, getSnapshot);
}
