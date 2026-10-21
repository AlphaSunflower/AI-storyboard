/**
 * 仅开发环境：把 >200ms 的长任务打到 console。
 *
 * <p>排查"接口 8ms 但页面卡住"这类现象用。接口时间短、Network 里却一直"待处理"，
 * 通常不是服务端慢，而是**主线程被某个长任务占住**：响应早就回来了，JS 没空处理，
 * DevTools 就一直显示 pending。这个探针把占用时长和发生时刻直接打出来，
 * 不用再靠猜，也不用开 Performance 录制。
 *
 * <p>生产环境不安装（`import.meta.env.DEV` 才调用），零运行时开销。
 */
let probeEnabled = false;

export function installDevLongTaskLogger(): void {
  if (typeof PerformanceObserver === 'undefined') return;
  probeEnabled = true;
  try {
    const observer = new PerformanceObserver((list) => {
      for (const entry of list.getEntries()) {
        if (entry.duration < 200) continue;
        const attribution = (entry as PerformanceEntry & {
          attribution?: Array<{ containerType?: string; containerName?: string; containerSrc?: string }>;
        }).attribution?.[0];
        console.warn(
          `[perf] 长任务 ${Math.round(entry.duration)}ms（起点 ${Math.round(entry.startTime)}ms）`,
          {
            domNodes: document.querySelectorAll('*').length,
            heapMB: (performance as Performance & { memory?: { usedJSHeapSize: number } }).memory
              ? Math.round((performance as Performance & { memory: { usedJSHeapSize: number } })
                .memory.usedJSHeapSize / 1048576)
              : undefined,
            // 任务发生在哪个容器里：主窗口通常是 window；如果是 iframe，这里能直接看出来
            container: attribution
              ? `${attribution.containerType ?? ''} ${attribution.containerName ?? ''} ${attribution.containerSrc ?? ''}`.trim()
              : 'unknown',
          },
        );
      }
    });
    observer.observe({ entryTypes: ['longtask'] });
    console.info('[perf] 长任务探针已开启：>200ms 的主线程占用会打印为 [perf] 长任务 …');
  } catch {
    // 浏览器不支持 longtask 就算了：这只是诊断探针，不影响功能
  }
}

/** 开发环境的阶段标记：配合长任务日志定位"卡在哪一段"。未安装探针时是空操作。 */
export function perfMark(label: string): void {
  if (!probeEnabled) return;
  console.warn(`[perf] ${label} @${Math.round(performance.now())}ms`);
}

/**
 * React `<Profiler>` 的 onRender 回调（开发期）：只打慢渲染。
 *
 * <p>长任务只能说明"这 7 秒在主线程"，不能说明是哪棵子树。把每条消息包一层
 * Profiler 之后，`[perf] render msg:assistant:xxxx 6800ms mount` 会直接点名。
 */
export function logProfilerRender(
  id: string,
  phase: string,
  actualDuration: number,
): void {
  if (!probeEnabled) return;
  if (actualDuration < 80) return;
  console.warn(`[perf] render ${id} ${phase} ${Math.round(actualDuration)}ms`);
}
