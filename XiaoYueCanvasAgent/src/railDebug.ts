/**
 * 临时诊断面板（拿到结论后整个文件删除，连同 main.tsx 里那行 import）。
 *
 * 背景：同一页面同一份 CSS，Chrome 有动画、Edge 没有，而 Edge 的启动参数、组策略、
 * 偏好设置、扩展脚本全查不出问题。剩下只能看运行时状态，但拿不到那台浏览器的 DevTools，
 * 所以把读数渲染到页面上。
 *
 * 判据用**过渡事件**而不是逐帧采样：`transitionend.elapsedTime` 直接给出过渡真实跑了多久，
 * 不受标签页节流影响（后台标签页的 rAF 会被降到 1fps，逐帧采样会整段漏掉过渡）。
 *
 * 用法：访问 `/canvas/?railDebug=1`，鼠标移到左侧工具栏上，看右下角读数：
 * - reduce = true            → 触发了 styles.css:487 那条全局兜底，过渡被压成 0.01ms
 * - 左栏过渡 = ... / 0.01ms   → 同上，偏好被改
 * - 过渡事件 = 0 次          → 过渡压根没启动
 * - 过渡事件 ≥1 次、耗时≈0.22s → 过渡正常跑了，问题在渲染/观感，不在 CSS
 */
export function installRailDebug() {
  if (!new URLSearchParams(window.location.search).has('railDebug')) return;

  const box = document.createElement('div');
  box.id = 'rail-debug';
  box.style.cssText = [
    'position:fixed', 'right:12px', 'bottom:12px', 'z-index:99999',
    'max-width:min(620px,calc(100vw - 24px))', 'padding:10px 12px', 'border-radius:10px',
    // 配色守卫生效：这个项目里界面只允许白灰阶，调试面板也不例外。
    'background:#0d0d0d', 'color:#eeeeee', 'border:1px solid #3a3a3a',
    'font:11px/1.6 Consolas,monospace', 'white-space:pre-wrap', 'pointer-events:none',
    'box-shadow:0 8px 24px rgba(0,0,0,.5)',
  ].join(';');
  document.body.appendChild(box);

  const counts = { run: 0, start: 0, end: 0, cancel: 0 };
  let lastElapsed: number | null = null;
  let lastProperty = '';
  const trail: number[] = [];
  let bound: Element | null = null;

  const railElement = () => document.querySelector('.canvas-tool-island');

  const bind = (rail: Element) => {
    if (bound === rail) return;
    bound = rail;
    rail.addEventListener('transitionrun', () => { counts.run += 1; });
    rail.addEventListener('transitionstart', () => { counts.start += 1; });
    rail.addEventListener('transitioncancel', () => { counts.cancel += 1; });
    rail.addEventListener('transitionend', (event) => {
      counts.end += 1;
      const detail = event as TransitionEvent;
      lastElapsed = detail.elapsedTime;
      lastProperty = detail.propertyName;
    });
  };

  /** 把「声明时长」翻译成一句能直接照做的结论——这一行不依赖事件、也不依赖帧率。 */
  const verdict = (style: CSSStyleDeclaration | null): string => {
    if (!style) return '找不到 .canvas-tool-island（先进入一张画布）';
    const raw = style.transitionDuration;
    if (!raw) return '没有声明 transition-duration';
    if (!style.transitionProperty.includes('width')) return `过渡属性里没有 width（实际 ${style.transitionProperty}）`;
    const ms = Number.parseFloat(raw) * (raw.endsWith('ms') ? 1 : 1000);
    if (ms < 1) return `过渡被压成 ${raw} → 有东西把动画偏好设成了 reduce（styles.css:487 的全局兜底生效）`;
    return `过渡声明正常（width / ${raw}）——CSS 没问题，问题在渲染或观感`;
  };

  const render = () => {
    const rail = railElement();
    const style = rail ? getComputedStyle(rail) : null;
    box.textContent = [
      '左栏动画诊断 (?railDebug)',
      `【结论】${verdict(style)}`,
      `系统偏好 prefers-reduced-motion: reduce = ${window.matchMedia('(prefers-reduced-motion: reduce)').matches}`,
      `过渡声明 = ${style ? `${style.transitionProperty} / ${style.transitionDuration}` : '(无)'}`,
      `过渡事件 = run ${counts.run} / start ${counts.start} / end ${counts.end} / cancel ${counts.cancel}`,
      `最近一次过渡实测 = ${lastElapsed === null ? '(还没跑过；把鼠标移到工具栏上，且标签页要在前台)' : `${lastProperty} ${lastElapsed}s`}`,
      `宽度轨迹(仅前台标签页准) = ${trail.length ? trail.join(' → ') : '(无)'}`,
    ].join('\n');
  };

  const tick = () => {
    const rail = railElement();
    if (rail) {
      bind(rail);
      const width = Math.round(Number.parseFloat(getComputedStyle(rail).width));
      if (trail[trail.length - 1] !== width) {
        trail.push(width);
        if (trail.length > 24) trail.shift();
      }
    }
    render();
    window.requestAnimationFrame(tick);
  };
  window.requestAnimationFrame(tick);
}
