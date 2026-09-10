export function parseResultUrls(value: string | null): string[] {
  if (!value) return [];
  try {
    const parsed = JSON.parse(value);
    if (!Array.isArray(parsed)) return [];
    return parsed
      .map((item) => {
        if (typeof item === 'string') return item;
        return item && typeof item === 'object' && typeof item.url === 'string' ? item.url : '';
      })
      .filter(Boolean);
  } catch {
    return [];
  }
}
