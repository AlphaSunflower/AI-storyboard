import { useEffect, useState } from 'react';

/** 视口宽度 ≤ 该值视为手机端 */
const MOBILE_BREAKPOINT = 768;

/**
 * 检测当前视口是否为手机端宽度。
 * 监听 resize 事件，阈值 768px。
 */
export function useIsMobile(): boolean {
  const [isMobile, setIsMobile] = useState(() => window.innerWidth <= MOBILE_BREAKPOINT);

  useEffect(() => {
    const check = () => setIsMobile(window.innerWidth <= MOBILE_BREAKPOINT);
    window.addEventListener('resize', check);
    return () => window.removeEventListener('resize', check);
  }, []);

  return isMobile;
}
