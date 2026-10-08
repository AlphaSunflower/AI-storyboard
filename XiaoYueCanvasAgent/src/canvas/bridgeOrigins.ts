export const DEFAULT_BRIDGE_ORIGINS = [
  'http://localhost:3000',
  'http://127.0.0.1:3000',
  'http://localhost:5173',
  'http://127.0.0.1:5173',
] as const;

export function parseBridgeOrigins(value?: string): string[] {
  const configured = (value ?? '')
    .split(',')
    .map((origin) => origin.trim())
    .filter(Boolean);
  return configured.length > 0 ? configured : [...DEFAULT_BRIDGE_ORIGINS];
}

export function isBridgeOriginAllowed(origin: string, allowedOrigins: string[]): boolean {
  return !origin || allowedOrigins.includes(origin);
}
