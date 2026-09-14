export const STORYBOARD_REFERENCE_MESSAGE = 'ai-storyboard:reference-scenes';
export const EMBEDDED_APP_READY_MESSAGE = 'ai-storyboard:embedded-app-ready';
export const SCENE_DRAG_MIME = 'application/x-ai-storyboard-scenes';

export interface EmbeddedSceneReference {
  id: string;
  projectId: string;
  sceneNumber: number;
}

export interface StoryboardReferenceMessage {
  type: typeof STORYBOARD_REFERENCE_MESSAGE;
  projectId: string;
  scenes: EmbeddedSceneReference[];
}

export interface EmbeddedAppReadyMessage {
  type: typeof EMBEDDED_APP_READY_MESSAGE;
  key: 'canvas' | 'editor' | 'docs';
}

export function isStoryboardReferenceMessage(value: unknown): value is StoryboardReferenceMessage {
  if (!value || typeof value !== 'object') return false;
  const message = value as Partial<StoryboardReferenceMessage>;
  return message.type === STORYBOARD_REFERENCE_MESSAGE
    && typeof message.projectId === 'string'
    && Array.isArray(message.scenes);
}

export function isEmbeddedAppReadyMessage(value: unknown): value is EmbeddedAppReadyMessage {
  if (!value || typeof value !== 'object') return false;
  const message = value as Partial<EmbeddedAppReadyMessage>;
  return message.type === EMBEDDED_APP_READY_MESSAGE
    && (message.key === 'canvas' || message.key === 'editor' || message.key === 'docs');
}

export function buildSceneReviewPrompt(scenes: EmbeddedSceneReference[]): string {
  const sceneRefs = scenes.map((scene) => `分镜${scene.sceneNumber}`).join('、');
  return `请审查 ${sceneRefs}`;
}
