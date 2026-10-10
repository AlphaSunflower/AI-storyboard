import {
  SIDE_CHAT_CONTEXT_REQUEST_MESSAGE,
  SIDE_CHAT_CONTEXT_UPDATE_MESSAGE,
  type SideChatContextItem,
} from './embeddedAppMessages';

export function currentSideChatId(): string | null {
  if (typeof window === 'undefined') return null;
  return new URLSearchParams(window.location.search).get('sideChatId');
}

export function requestSideChatSelectionContext(conversationId: string) {
  if (window.parent === window) return;
  window.parent.postMessage({
    type: SIDE_CHAT_CONTEXT_REQUEST_MESSAGE,
    conversationId,
  }, window.location.origin);
}

export function postSideChatSelectionUpdate(
  conversationId: string,
  selections: SideChatContextItem[],
) {
  if (window.parent === window) return;
  window.parent.postMessage({
    type: SIDE_CHAT_CONTEXT_UPDATE_MESSAGE,
    conversationId,
    selections,
  }, window.location.origin);
}
