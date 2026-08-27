import { useEffect, useState } from 'react';

interface UrlEntry {
  url: string;
  references: number;
  releaseTimer: number | null;
}

const urlCache = new WeakMap<Blob, UrlEntry>();
const RELEASE_DELAY_MS = 1200;

const acquireObjectUrl = (blob: Blob) => {
  let entry = urlCache.get(blob);
  if (!entry) {
    entry = { url: URL.createObjectURL(blob), references: 0, releaseTimer: null };
    urlCache.set(blob, entry);
  }
  if (entry.releaseTimer !== null) {
    window.clearTimeout(entry.releaseTimer);
    entry.releaseTimer = null;
  }
  entry.references += 1;
  return entry.url;
};

const releaseObjectUrl = (blob: Blob) => {
  const entry = urlCache.get(blob);
  if (!entry) return;
  entry.references = Math.max(0, entry.references - 1);
  if (entry.references || entry.releaseTimer !== null) return;
  entry.releaseTimer = window.setTimeout(() => {
    const current = urlCache.get(blob);
    if (!current || current.references) return;
    URL.revokeObjectURL(current.url);
    urlCache.delete(blob);
  }, RELEASE_DELAY_MS);
};

export function useObjectUrl(blob: Blob | undefined, enabled = true) {
  const [url, setUrl] = useState<string | null>(null);

  useEffect(() => {
    if (!blob || !enabled) { setUrl(null); return; }
    const next = acquireObjectUrl(blob);
    setUrl(next);
    return () => releaseObjectUrl(blob);
  }, [blob, enabled]);

  return url;
}
