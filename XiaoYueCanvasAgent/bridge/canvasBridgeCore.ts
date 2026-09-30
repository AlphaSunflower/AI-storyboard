import { randomUUID } from 'node:crypto';
import {
  BRIDGE_COMMAND_TIMEOUT_MS,
  BRIDGE_BROWSER_HEARTBEAT_TIMEOUT_MS,
  BRIDGE_PROTOCOL_VERSION,
  BRIDGE_TAB_STALE_MS,
  type BridgeHealth,
  type BridgeRegisterPayload,
  type BridgeResultPayload,
  type CanvasBridgeRequest,
  type CanvasBridgeResponse,
} from '../src/canvas/bridgeProtocol.ts';

interface PendingCommand {
  requestId: string;
  method: string;
  params: Record<string, unknown>;
  source: CanvasBridgeRequest['source'];
  dryRun?: boolean;
  expectedRevision?: number;
  finish: (response: CanvasBridgeResponse) => void;
}

interface TabState {
  tabId: string;
  projectId: string;
  projectTitle: string;
  canvasId: string;
  canvasTitle: string;
  lastSeen: number;
}

export class CanvasBridgeCore {
  private readonly tabs = new Map<string, TabState>();
  private readonly tabByCanvas = new Map<string, string>();
  private readonly pendingByTab = new Map<string, PendingCommand[]>();
  private readonly pendingById = new Map<string, PendingCommand>();

  private canvasKey(projectId: string, canvasId: string) {
    return `${projectId}:${canvasId}`;
  }

  private isFresh(tab: TabState) {
    return Date.now() - tab.lastSeen <= BRIDGE_TAB_STALE_MS;
  }

  private isResponsive(tab: TabState) {
    return Date.now() - tab.lastSeen <= BRIDGE_BROWSER_HEARTBEAT_TIMEOUT_MS;
  }

  register(payload: BridgeRegisterPayload) {
    if (!payload.tabId || !payload.projectId || !payload.canvasId) {
      return { ok: false as const, error: { code: 'INVALID_ARGUMENTS', message: '缺少 tabId、projectId 或 canvasId' } };
    }
    const previous = this.tabs.get(payload.tabId);
    if (previous) this.tabByCanvas.delete(this.canvasKey(previous.projectId, previous.canvasId));
    const tab = {
      tabId: payload.tabId,
      projectId: payload.projectId,
      projectTitle: payload.projectTitle?.trim() || '未命名项目',
      canvasId: payload.canvasId,
      canvasTitle: payload.canvasTitle?.trim() || '未命名画布',
      lastSeen: Date.now(),
    };
    this.tabs.set(tab.tabId, tab);
    const key = this.canvasKey(tab.projectId, tab.canvasId);
    const activeTabId = this.tabByCanvas.get(key);
    const active = activeTabId ? this.tabs.get(activeTabId) : undefined;
    if (!active || Date.now() - active.lastSeen > 5_000) this.tabByCanvas.set(key, tab.tabId);
    return { ok: true as const };
  }

  unregister(tabId: string) {
    const tab = this.tabs.get(tabId);
    if (!tab) return;
    this.tabs.delete(tab.tabId);
    const key = this.canvasKey(tab.projectId, tab.canvasId);
    if (this.tabByCanvas.get(key) === tab.tabId) {
      const fallback = [...this.tabs.values()]
        .filter((candidate) => candidate.projectId === tab.projectId && candidate.canvasId === tab.canvasId)
        .sort((left, right) => right.lastSeen - left.lastSeen)[0];
      if (fallback) this.tabByCanvas.set(key, fallback.tabId);
      else this.tabByCanvas.delete(key);
    }
    this.pendingByTab.delete(tab.tabId);
  }

  health(): BridgeHealth {
    return {
      ok: true,
      version: BRIDGE_PROTOCOL_VERSION,
      projects: [...this.tabByCanvas.values()]
        .flatMap((tabId) => {
          const tab = this.tabs.get(tabId);
          return tab && this.isFresh(tab) ? [{
            projectId: tab.projectId,
            projectTitle: tab.projectTitle,
            canvasId: tab.canvasId,
            canvasTitle: tab.canvasTitle,
            lastSeen: tab.lastSeen,
            responsive: this.isResponsive(tab),
          }] : [];
        }),
    };
  }

  projects() {
    return [...new Set([...this.tabs.values()].map((tab) => tab.projectId))];
  }

  poll(tabId: string) {
    const tab = this.tabs.get(tabId);
    if (tab) tab.lastSeen = Date.now();
    const queue = this.pendingByTab.get(tabId) ?? [];
    const command = queue.shift();
    if (command) this.pendingByTab.set(tabId, queue);
    else this.pendingByTab.delete(tabId);
    return command
      ? {
          command: {
            requestId: command.requestId,
            method: command.method,
            params: command.params,
            source: command.source,
            dryRun: command.dryRun,
            expectedRevision: command.expectedRevision,
          },
        }
      : {};
  }

  result(payload: BridgeResultPayload) {
    const pending = payload.requestId ? this.pendingById.get(payload.requestId) : undefined;
    if (pending) pending.finish(payload.response);
  }

  command(request: Pick<CanvasBridgeRequest, 'source' | 'projectId' | 'canvasId' | 'method' | 'params' | 'dryRun' | 'expectedRevision'>) {
    const tabId = this.resolveTab(request.projectId, request.canvasId);
    if (typeof tabId !== 'string') {
      return Promise.resolve({ version: 1 as const, id: '', ok: false as const, error: tabId });
    }
    return this.dispatch(tabId, request.source, request.method, request.params, request.dryRun, request.expectedRevision);
  }

  private resolveTab(projectId?: string, canvasId?: string): string | { code: string; message: string } {
    if (canvasId && !projectId) {
      return { code: 'INVALID_ARGUMENTS', message: '指定 canvasId 时必须同时指定 projectId' };
    }
    if (projectId && canvasId) {
      const tabId = this.tabByCanvas.get(this.canvasKey(projectId, canvasId));
      const tab = tabId ? this.tabs.get(tabId) : undefined;
      if (!tab || !this.isFresh(tab)) return { code: 'CANVAS_NOT_OPEN', message: '目标画布当前未在浏览器中打开' };
      if (!this.isResponsive(tab)) {
        return {
          code: 'BROWSER_NOT_RESPONDING',
          message: '目标画布已打开，但浏览器页面未在响应画布命令，请重新打开或刷新画布。',
        };
      }
      return tab.tabId;
    }
    if (projectId) {
      const matches = [...this.tabByCanvas.values()]
        .map((tabId) => this.tabs.get(tabId))
        .filter((tab): tab is TabState => Boolean(tab) && this.isFresh(tab!) && tab!.projectId === projectId);
      if (!matches.length) return { code: 'PROJECT_NOT_OPEN', message: '目标项目当前未在浏览器中打开' };
      if (matches.length > 1) return { code: 'AMBIGUOUS_CANVAS', message: '该项目打开了多个画布，请指定 canvasId' };
      if (!this.isResponsive(matches[0])) {
        return {
          code: 'BROWSER_NOT_RESPONDING',
          message: '目标画布已打开，但浏览器页面未在响应画布命令，请重新打开或刷新画布。',
        };
      }
      return matches[0].tabId;
    }
    const tabs = [...this.tabByCanvas.values()]
      .map((tabId) => this.tabs.get(tabId))
      .filter((tab): tab is TabState => Boolean(tab) && this.isFresh(tab!));
    if (!tabs.length) return { code: 'PROJECT_NOT_OPEN', message: '没有打开的项目' };
    if (tabs.length > 1) return { code: 'AMBIGUOUS_PROJECT', message: '多个画布已打开，请指定 projectId 和 canvasId' };
    if (!this.isResponsive(tabs[0])) {
      return {
        code: 'BROWSER_NOT_RESPONDING',
        message: '目标画布已打开，但浏览器页面未在响应画布命令，请重新打开或刷新画布。',
      };
    }
    return tabs[0].tabId;
  }

  private dispatch(
    tabId: string,
    source: CanvasBridgeRequest['source'],
    method: unknown,
    params: unknown,
    dryRun: unknown,
    expectedRevision: unknown,
  ): Promise<CanvasBridgeResponse> {
    const requestId = randomUUID();
    return new Promise((resolve) => {
      const state: { settled: boolean; timer?: ReturnType<typeof setTimeout> } = { settled: false };
      const finish = (response: CanvasBridgeResponse) => {
        if (state.settled) return;
        state.settled = true;
        if (state.timer !== undefined) clearTimeout(state.timer);
        this.pendingById.delete(requestId);
        resolve(response);
      };
      const command: PendingCommand = {
        requestId,
        method: typeof method === 'string' ? method : '',
        params: (params && typeof params === 'object' && !Array.isArray(params) ? params : {}) as Record<string, unknown>,
        source,
        dryRun: dryRun === true,
        expectedRevision: typeof expectedRevision === 'number' ? expectedRevision : undefined,
        finish,
      };
      this.pendingById.set(requestId, command);
      const queue = this.pendingByTab.get(tabId) ?? [];
      queue.push(command);
      this.pendingByTab.set(tabId, queue);
      state.timer = setTimeout(() => finish({
        version: 1,
        id: requestId,
        ok: false,
        error: { code: 'BRIDGE_TIMEOUT', message: '浏览器未在超时时间内响应命令' },
      }), BRIDGE_COMMAND_TIMEOUT_MS);
    });
  }
}
