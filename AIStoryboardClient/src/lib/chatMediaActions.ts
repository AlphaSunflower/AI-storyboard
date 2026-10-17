import { useAgentStore } from '../stores/agentStore';
import { useProjectStore } from '../stores/projectStore';
import { notify } from '../stores/toastStore';
import { assetUrl } from '../config';
import { copyImageToClipboard, copyPlainText } from './chatBlockExport';
import { openCanvasSite } from '../components/agent/apps/chatAppDefinitions';

export type ChatMediaKind = 'image' | 'video';
/** 复制要区分的三种来源：只有图片能走位图，视频和文档只能给带时效的链接。 */
export type MediaAssetKind = ChatMediaKind | 'document';

/**
 * 复制一条素材。
 *
 * 图片优先写位图进剪贴板（粘到别处就是图本身），浏览器不给权限时退回链接；
 * 视频和文档没有位图形态，只能复制链接，并在提示里说明链接会过期。
 */
export async function copyMediaAsset(url: string, kind: MediaAssetKind): Promise<void> {
  const absolute = assetUrl(url);
  if (kind === 'image') {
    const result = await copyImageToClipboard(absolute);
    if (result === 'failed') {
      notify({ title: '复制失败', description: '浏览器不允许写入剪贴板', variant: 'error' });
      return;
    }
    notify({
      title: result === 'bitmap' ? '已复制图片' : '已复制图片链接',
      description: result === 'bitmap' ? undefined : '链接带时效，过期后需要重新复制',
      variant: 'info',
    });
    return;
  }
  const copied = await copyPlainText(absolute);
  notify({
    title: copied ? '已复制链接' : '复制失败',
    description: copied ? '链接带时效，过期后需要重新复制' : '浏览器不允许写入剪贴板',
    variant: copied ? 'info' : 'error',
  });
}

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
