import { useEffect, useRef, useState } from 'react';
import { registerMediaCandidate, updateMediaCandidate } from '../utils/mediaBudget';

type VisibilityListener = (visible: boolean) => void;

const listeners = new WeakMap<Element, VisibilityListener>();
let sharedObserver: IntersectionObserver | null = null;

const observer = () => {
  if (sharedObserver) return sharedObserver;
  sharedObserver = new IntersectionObserver((entries) => {
    entries.forEach((entry) => listeners.get(entry.target)?.(entry.isIntersecting));
  }, { root: null, rootMargin: '360px' });
  return sharedObserver;
};

export function useViewportVisibility<T extends Element>(id: string, forceActive = false) {
  const ref = useRef<T>(null);
  const [visible, setVisible] = useState(forceActive);
  const [budgetActive, setBudgetActive] = useState(forceActive);

  useEffect(() => {
    if (forceActive) { setVisible(true); return; }
    const element = ref.current;
    if (!element || typeof IntersectionObserver === 'undefined') { setVisible(true); return; }
    const instance = observer();
    listeners.set(element, setVisible);
    instance.observe(element);
    return () => {
      instance.unobserve(element);
      listeners.delete(element);
    };
  }, [forceActive]);

  useEffect(() => registerMediaCandidate({
    id,
    selected: forceActive,
    visible,
    distance: Number.POSITIVE_INFINITY,
    listener: setBudgetActive,
    measureDistance: () => {
      const element = ref.current;
      if (!element) return Number.POSITIVE_INFINITY;
      const rect = element.getBoundingClientRect();
      return Math.hypot(rect.left + rect.width / 2 - window.innerWidth / 2, rect.top + rect.height / 2 - window.innerHeight / 2);
    },
  }), [id]);

  useEffect(() => {
    const element = ref.current;
    if (!element) return;
    const rect = element.getBoundingClientRect();
    const distance = Math.hypot(rect.left + rect.width / 2 - window.innerWidth / 2, rect.top + rect.height / 2 - window.innerHeight / 2);
    updateMediaCandidate(id, { selected: forceActive, visible, distance });
  }, [forceActive, id, visible]);

  return { ref, visible: forceActive || (visible && budgetActive) };
}
