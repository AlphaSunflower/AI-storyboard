import 'fake-indexeddb/auto';
import { installFetchMock } from './fetchMock';

// node/jsdom 环境无后端，mock /api/canvas/** 让 projectStorage 的网络调用返回确定性假响应。
installFetchMock();

// node/jsdom 环境无 localStorage，而 jobAuth.getJobToken() 读取它——补内存版 polyfill。
if (!globalThis.localStorage) {
  const store = new Map<string, string>();
  globalThis.localStorage = {
    getItem: (k: string) => store.get(k) ?? null,
    setItem: (k: string, v: string) => void store.set(k, String(v)),
    removeItem: (k: string) => void store.delete(k),
    clear: () => store.clear(),
    key: (i: number) => [...store.keys()][i] ?? null,
    get length() { return store.size; },
  } as Storage;
}

if (!globalThis.URL.createObjectURL) globalThis.URL.createObjectURL = () => `blob:test-${crypto.randomUUID()}`;
if (!globalThis.URL.revokeObjectURL) globalThis.URL.revokeObjectURL = () => undefined;
