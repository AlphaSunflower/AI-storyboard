import type { AgentMessageMetadata } from '../api/agent';

type PendingCardMessage = {
  content?: string;
  metadata?: AgentMessageMetadata;
};

/**
 * Pending HITL/video-plan cards own their prompt/message copy. A message placeholder must only
 * render when it carries independent assistant-generated plan text.
 */
export function shouldHidePendingCardMessage(message: PendingCardMessage): boolean {
  const metadata = message.metadata;
  if (!metadata?.hitl?.pending
      || (metadata.kind !== 'hitl' && metadata.kind !== 'video-plan')) {
    return false;
  }

  const content = message.content?.trim() ?? '';
  if (!content) return true;

  const cardCopy = metadata.kind === 'video-plan'
    ? metadata.videoPlan?.message?.trim() ?? ''
    : metadata.hitl?.formContent?.trim() ?? '';
  return !!cardCopy && content === cardCopy;
}
