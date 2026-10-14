export const AGENT_ASSET_DRAG_MIME = 'application/x-ai-storyboard-agent-asset';

export type AgentAssetDragKind = 'image' | 'video' | 'document';

export interface AgentAssetDragPayload {
  url: string;
  name: string;
  kind: AgentAssetDragKind;
}

export function serializeAgentAssetDragPayload(payload: AgentAssetDragPayload): string {
  return JSON.stringify(payload);
}

function isKind(value: unknown): value is AgentAssetDragKind {
  return value === 'image' || value === 'video' || value === 'document';
}

export function parseAgentAssetDragPayload(raw: string): AgentAssetDragPayload | null {
  try {
    const value = JSON.parse(raw) as Partial<AgentAssetDragPayload>;
    return isKind(value.kind)
      && typeof value.url === 'string' && value.url
      && typeof value.name === 'string'
      ? { url: value.url, name: value.name, kind: value.kind }
      : null;
  } catch {
    return null;
  }
}
