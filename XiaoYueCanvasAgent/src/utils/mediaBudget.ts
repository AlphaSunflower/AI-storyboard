import type { MediaQuality } from '../types';

interface MediaCandidate {
  id: string;
  selected: boolean;
  visible: boolean;
  distance: number;
  updatedAt: number;
  listener: (active: boolean) => void;
  measureDistance: () => number;
}

const candidates = new Map<string, MediaCandidate>();
const activeIds = new Set<string>();
const THUMBNAIL_BUDGET = 96;
const PREVIEW_BUDGET = 12;
let quality: MediaQuality = 'thumbnail';
let scheduled = false;

const reconcile = () => {
  scheduled = false;
  candidates.forEach((candidate) => { candidate.distance = candidate.measureDistance(); });
  const budget = quality === 'placeholder' ? 0 : quality === 'preview' ? PREVIEW_BUDGET : THUMBNAIL_BUDGET;
  const ranked = [...candidates.values()]
    .filter((candidate) => candidate.visible || candidate.selected)
    .sort((left, right) => Number(right.selected) - Number(left.selected) || left.distance - right.distance || right.updatedAt - left.updatedAt);
  const next = new Set(ranked.slice(0, budget).map((candidate) => candidate.id));
  ranked.filter((candidate) => candidate.selected).forEach((candidate) => next.add(candidate.id));
  candidates.forEach((candidate, id) => {
    const active = next.has(id);
    if (activeIds.has(id) === active) return;
    active ? activeIds.add(id) : activeIds.delete(id);
    candidate.listener(active);
  });
};

const schedule = () => {
  if (scheduled) return;
  scheduled = true;
  requestAnimationFrame(reconcile);
};

export function registerMediaCandidate(candidate: Omit<MediaCandidate, 'updatedAt'>) {
  candidates.set(candidate.id, { ...candidate, updatedAt: performance.now() });
  schedule();
  return () => {
    candidates.delete(candidate.id);
    activeIds.delete(candidate.id);
    schedule();
  };
}

export function updateMediaCandidate(id: string, patch: Partial<Pick<MediaCandidate, 'selected' | 'visible' | 'distance'>>) {
  const candidate = candidates.get(id);
  if (!candidate) return;
  candidates.set(id, { ...candidate, ...patch, updatedAt: performance.now() });
  schedule();
}

export function setMediaBudgetQuality(next: MediaQuality) {
  if (quality === next) return;
  quality = next;
  schedule();
}

export function refreshMediaBudget() {
  schedule();
}

export function mediaBudgetSnapshot() {
  return { active: activeIds.size, candidates: candidates.size, quality };
}
