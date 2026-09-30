export function isExternalImageSource(source: string | undefined): boolean {
  if (!source) return false;
  try {
    const parsed = new URL(source, window.location.origin);
    return /^https?:$/.test(parsed.protocol) && parsed.origin !== window.location.origin;
  } catch {
    return false;
  }
}
