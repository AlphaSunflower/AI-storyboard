export function buildSideChatFrameUrl(conversationId: string, projectId: string): string {
  return `/chat?${new URLSearchParams({
    embed: '1',
    sideChatId: conversationId,
    projectId,
  }).toString()}`;
}

export function shouldShowFirstRunHero(
  sideChatMode: boolean,
  messageCount: number,
  streaming: boolean,
  waitingHumanInput: boolean,
  waitingVideoPlan: boolean,
): boolean {
  if (sideChatMode) return false;
  return messageCount === 0 && !streaming && !waitingHumanInput && !waitingVideoPlan;
}
