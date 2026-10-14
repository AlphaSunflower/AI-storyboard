import { useAgentStore } from '../stores/agentStore';
import { useProjectStore } from '../stores/projectStore';
import { notify } from '../stores/toastStore';
import { openCanvasSite } from '../components/agent/apps/chatAppDefinitions';

export type ChatMediaKind = 'image' | 'video';

/** 参考素材引用：把这张图/视频放回输入卡，下一条消息直接带上。 */
export function quoteAsReference(kind: ChatMediaKind, url: string): void {
  const state = useAgentStore.getState();
  if (kind === 'image') {
    state.addRefImageUrls([url]);
    notify({ title: '已引用为参考图', description: '已加入输入框参考素材', variant: 'info' });
    return;
  }
  state.setPickedReferenceMedia([...new Set([...state.pickedReferenceVideos, url])], state.pickedReferenceAudios);
  notify({ title: '已引用为参考视频', description: '已加入输入框参考素材', variant: 'info' });
}

/** 去画布编辑：新窗口打开画布网站，并把这张媒体挂到画布编辑会话上。 */
export function openInCanvas(kind: ChatMediaKind, url: string): void {
  const state = useAgentStore.getState();
  const projectId = useProjectStore.getState().currentProject?.id;
  if (!projectId) {
    notify({ title: '未选择项目', description: '请先选择项目再进入画布编辑', variant: 'info' });
    return;
  }
  // 已经在画布子站里（画布小窗内嵌对话）：直接进当前画布编辑会话，不再开新窗口
  if (state.canvasEditContext && window.self !== window.top) {
    state.addCanvasMediaRefs([{ kind, url, source: 'upload' }]);
    notify({ title: '已加入画布媒体', description: '这张素材会随本轮对话进入画布', variant: 'info' });
    return;
  }
  const canvasId = state.canvasEditContext?.canvasId
    ?? state.messages.reduce<string | undefined>(
      (found, message) => message.metadata?.canvasId ?? found,
      undefined,
    );
  openCanvasSite({ projectId, canvasId, media: [url] });
  notify({ title: '已在新窗口打开画布', description: '这张素材已带入画布编辑会话', variant: 'info' });
}
