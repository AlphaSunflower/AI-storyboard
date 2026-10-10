import type { Options } from 'docx-preview';

export const DOCX_PREVIEW_OPTIONS: Partial<Options> = {
  className: 'document-docx',
  inWrapper: true,
  hideWrapperOnPrint: false,
  ignoreWidth: false,
  ignoreHeight: false,
  ignoreFonts: false,
  breakPages: true,
  debug: false,
  experimental: false,
  trimXmlDeclaration: true,
  renderHeaders: true,
  renderFooters: true,
  renderFootnotes: true,
  renderEndnotes: true,
  ignoreLastRenderedPageBreak: false,
  useBase64URL: true,
  renderChanges: false,
  renderComments: false,
  renderAltChunks: false,
};

const BLOCKED_TAGS = new Set(['script', 'iframe', 'object', 'embed', 'link', 'meta', 'base']);

function safeUrl(value: string, image: boolean): string | null {
  const url = value.trim();
  if (!url) return null;
  if (image && /^(?:data:image\/|blob:)/i.test(url)) return url;
  if (/^(?:https?:|mailto:|tel:|#)/i.test(url)) return url;
  if (!/^[a-z][a-z0-9+.-]*:/i.test(url)) return url;
  return null;
}

export function hardenOfficeDom(container: HTMLElement): void {
  container.querySelectorAll(Array.from(BLOCKED_TAGS).join(',')).forEach((node) => node.remove());
  container.querySelectorAll<HTMLElement>('*').forEach((element) => {
    Array.from(element.attributes).forEach((attribute) => {
      const name = attribute.name.toLowerCase();
      if (name.startsWith('on')) {
        element.removeAttribute(attribute.name);
        return;
      }
      if (name === 'srcset') {
        element.removeAttribute(attribute.name);
        return;
      }
      if (name === 'href') {
        const safe = safeUrl(attribute.value, false);
        if (safe == null) element.removeAttribute(attribute.name);
        else if (/^https?:/i.test(safe)) {
          element.setAttribute('target', '_blank');
          element.setAttribute('rel', 'noreferrer');
        }
        return;
      }
      if (name === 'src') {
        const safe = safeUrl(attribute.value, true);
        if (safe == null) element.removeAttribute(attribute.name);
      }
    });
  });
}
