// randomUUID is restricted to secure contexts in some browsers. The canvas
// also runs on a LAN over HTTP, so every runtime identifier needs a fallback.
export function createId(): string {
  const randomUuid = globalThis.crypto?.randomUUID;
  if (typeof randomUuid === 'function') return randomUuid.call(globalThis.crypto);

  const cryptoObject = globalThis.crypto;
  if (cryptoObject?.getRandomValues) {
    const bytes = new Uint8Array(16);
    cryptoObject.getRandomValues(bytes);
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    const hex = Array.from(bytes, (value) => value.toString(16).padStart(2, '0')).join('');
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
  }

  const timestamp = Date.now().toString(16).padStart(12, '0');
  const random = Math.random().toString(16).slice(2).padEnd(20, '0');
  return `${timestamp.slice(0, 8)}-${timestamp.slice(8)}-4${random.slice(0, 3)}-8${random.slice(3, 6)}-${random.slice(6, 18)}`;
}
