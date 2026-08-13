/**
 * 任务中心：进行中任务聚合（纯函数，无 React 依赖——便于独立验证）。
 * 输入两个 store 的瞬态状态，输出任务列表（只显示进行中，顺序固定：智能体视频 → 分镜视频 → 分镜图片 → 智能体交流 → 脚本）。
 */
export interface TaskItem {
  id: string;
  kind: 'image' | 'video' | 'agent' | 'script';
  title: string;
  hint?: string;
  progress?: number; // 0-100（分镜视频轮询进度）
  sceneId?: string;  // 分镜任务：点击跳转选中该分镜
}

export interface TaskSource {
  generatingImage: Record<string, boolean>;
  generatingVideo: Record<string, boolean>;
  videoProgress: Record<string, number>;
  scenes: { id: string; sceneNumber?: number }[];
  scriptStatus: 'idle' | 'generating' | 'done' | 'error';
  streaming: boolean;
  workflowHint: string;
  agentVideoTask: { taskId: string; status: 'queued' | 'running'; waitingSec: number } | null;
}

export function buildTasks(src: TaskSource): TaskItem[] {
  const list: TaskItem[] = [];
  // 智能体视频异步任务（优先展示：耗时最长）
  if (src.agentVideoTask) {
    list.push({
      id: 'agent-video',
      kind: 'video',
      title: '智能体视频生成中',
      hint: `${src.agentVideoTask.status === 'queued' ? '排队中' : '生成中'} · 已等待约 ${src.agentVideoTask.waitingSec} 秒`,
    });
  }
  // 分镜视频
  for (const [sceneId, v] of Object.entries(src.generatingVideo)) {
    if (!v) continue;
    const scene = src.scenes.find((s) => s.id === sceneId);
    if (!scene) continue; // 场景已删但任务未清：跳过空场景号项
    list.push({
      id: `video-${sceneId}`,
      kind: 'video',
      title: `分镜 ${scene.sceneNumber ?? ''} 视频生成中`,
      progress: src.videoProgress[sceneId] || 0,
      sceneId,
    });
  }
  // 分镜图片
  for (const [sceneId, v] of Object.entries(src.generatingImage)) {
    if (!v) continue;
    const scene = src.scenes.find((s) => s.id === sceneId);
    if (!scene) continue; // 场景已删但任务未清：跳过空场景号项
    list.push({ id: `image-${sceneId}`, kind: 'image', title: `分镜 ${scene.sceneNumber ?? ''} 图片生成中`, sceneId });
  }
  // 智能体交流/图片生成（流式进行中，且非视频轮询——视频轮询已单独成项）
  if (src.streaming && !src.agentVideoTask) {
    list.push({ id: 'agent-chat', kind: 'agent', title: '智能体生成中', hint: src.workflowHint || '正在回复…' });
  }
  // 脚本生成
  if (src.scriptStatus === 'generating') {
    list.push({ id: 'script', kind: 'script', title: 'AI 正在生成分镜…' });
  }
  return list;
}
