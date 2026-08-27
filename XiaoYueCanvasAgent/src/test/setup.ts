import 'fake-indexeddb/auto';

if (!globalThis.URL.createObjectURL) globalThis.URL.createObjectURL = () => `blob:test-${crypto.randomUUID()}`;
if (!globalThis.URL.revokeObjectURL) globalThis.URL.revokeObjectURL = () => undefined;
