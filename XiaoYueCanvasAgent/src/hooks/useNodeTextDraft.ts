import { useCallback, useEffect, useRef, useState } from 'react';

interface UseNodeTextDraftOptions {
  nodeId: string;
  value: string;
  onChange: (value: string) => void;
  delay?: number;
}

export function useNodeTextDraft({
  nodeId,
  value,
  onChange,
  delay = 240,
}: UseNodeTextDraftOptions) {
  const [draft, setDraft] = useState(value);
  const draftRef = useRef(value);
  const externalRef = useRef(value);
  const nodeIdRef = useRef(nodeId);
  const focusedRef = useRef(false);
  const composingRef = useRef(false);
  const timerRef = useRef<number | null>(null);
  const onChangeRef = useRef(onChange);
  onChangeRef.current = onChange;

  const clearTimer = useCallback(() => {
    if (timerRef.current === null) return;
    window.clearTimeout(timerRef.current);
    timerRef.current = null;
  }, []);

  const flush = useCallback(() => {
    clearTimer();
    if (draftRef.current === externalRef.current) return;
    const next = draftRef.current;
    externalRef.current = next;
    onChangeRef.current(next);
  }, [clearTimer]);

  const schedule = useCallback(() => {
    clearTimer();
    timerRef.current = window.setTimeout(() => {
      timerRef.current = null;
      flush();
    }, delay);
  }, [clearTimer, delay, flush]);

  const setValue = useCallback((next: string, immediate = false) => {
    if (draftRef.current === next) return;
    draftRef.current = next;
    setDraft(next);
    if (immediate) {
      flush();
      return;
    }
    if (!composingRef.current) schedule();
  }, [flush, schedule]);

  const onFocus = useCallback(() => {
    focusedRef.current = true;
  }, []);

  const onBlur = useCallback(() => {
    focusedRef.current = false;
    flush();
  }, [flush]);

  const onCompositionStart = useCallback(() => {
    composingRef.current = true;
    clearTimer();
  }, [clearTimer]);

  const onCompositionEnd = useCallback(() => {
    composingRef.current = false;
    schedule();
  }, [schedule]);

  useEffect(() => {
    const nodeChanged = nodeIdRef.current !== nodeId;
    nodeIdRef.current = nodeId;
    externalRef.current = value;
    if (!nodeChanged && focusedRef.current) return;
    clearTimer();
    draftRef.current = value;
    setDraft(value);
  }, [clearTimer, nodeId, value]);

  useEffect(() => () => {
    if (timerRef.current !== null) window.clearTimeout(timerRef.current);
    if (draftRef.current !== externalRef.current) onChangeRef.current(draftRef.current);
  }, []);

  return {
    value: draft,
    setValue,
    flush,
    onFocus,
    onBlur,
    onCompositionStart,
    onCompositionEnd,
  };
}
