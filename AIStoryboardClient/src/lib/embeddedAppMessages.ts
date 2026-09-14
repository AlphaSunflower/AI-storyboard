export const STORYBOARD_REFERENCE_MESSAGE = 'ai-storyboard:reference-scenes';
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

export function isStoryboardReferenceMessage(value: unknown): value is StoryboardReferenceMessage {
  if (!value || typeof value !== 'object') return false;
  const message = value as Partial<StoryboardReferenceMessage>;
  return message.type === STORYBOARD_REFERENCE_MESSAGE
    && typeof message.projectId === 'string'
    && Array.isArray(message.scenes);
}

export function buildSceneReviewPrompt(scenes: EmbeddedSceneReference[]): string {
  const sceneRefs = scenes.map((scene) => `分镜${scene.sceneNumber}`).join('、');
  return `请审查 ${sceneRefs}`;
}
