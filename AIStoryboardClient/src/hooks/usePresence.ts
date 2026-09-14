import { useEffect, useState } from 'react';

/**
 * Keeps a disclosure mounted long enough to play its exit transition.
 * `visible` drives the open class; `mounted` controls whether it may render.
 */
export function usePresence(open: boolean, duration = 180) {
  const [mounted, setMounted] = useState(open);
  const [visible, setVisible] = useState(open);
  const [closing, setClosing] = useState(false);

  useEffect(() => {
    let frame = 0;
    let nextFrame = 0;
    let timer = 0;

    if (open) {
      setClosing(false);
      setMounted(true);
      // Wait for the mounted node to paint one frame before applying `.is-open`.
      // A single frame can be batched with mount and skip the CSS transition.
      frame = window.requestAnimationFrame(() => {
        nextFrame = window.requestAnimationFrame(() => setVisible(true));
      });
    } else {
      setVisible(false);
      setClosing(true);
      timer = window.setTimeout(() => {
        setMounted(false);
        setClosing(false);
      }, duration);
    }

    return () => {
      window.cancelAnimationFrame(frame);
      window.cancelAnimationFrame(nextFrame);
      window.clearTimeout(timer);
    };
  }, [duration, open]);

  return { mounted, visible, closing };
}
