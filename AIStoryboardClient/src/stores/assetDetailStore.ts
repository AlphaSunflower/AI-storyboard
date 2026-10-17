import { create } from 'zustand';
import type { AgentAsset } from '../api/agent';

/**
 * 打开素材详情弹窗需要的最小上下文。
 *
 * 有两个入口，能给的东西不一样：
 * - 生成中心的会话资产网格手上有完整记录（`asset`），直接给；
 * - 聊天里点一张图只有 URL，记录由弹窗自己回头按 URL 查一次。
 * 所以 `asset` 是可空的，`known` 用来兜住「查不到记录但调用方知道一点信息」的情况
 * （例如工具槽位带着提示词）。
 */
export interface AssetDetailTarget {
  url: string;
  /** 已知的生成记录；为空时弹窗按 URL 去会话资产里反查。 */
  asset?: AgentAsset | null;
  /** 同一次生成的其他结果 URL（消息卡片/工具槽位带的），用于舞台缩略图条。 */
  urls?: string[] | null;
  /** 记录查不到时的兜底信息。 */
  known?: {
    prompt?: string | null;
    model?: string | null;
    createdAt?: string | null;
  } | null;
  /** 调用方已经知道这是图还是视频时直接给出，省得靠扩展名猜。 */
  kind?: 'image' | 'video';
}

interface AssetDetailState {
  target: AssetDetailTarget | null;
  openAssetDetail: (target: AssetDetailTarget) => void;
  closeAssetDetail: () => void;
}

/**
 * 素材详情弹窗的宿主状态。
 *
 * 做成全局而不是各页面各自持有：会话里的图片/视频、生成中心的网格、工具槽位
 * 都要弹同一个窗，各自复制一份状态就会出现「同时弹出两个详情」。
 * 页面里只需要挂一个 `<AssetDetailHost />`。
 */
export const useAssetDetailStore = create<AssetDetailState>((set) => ({
  target: null,
  openAssetDetail: (target) => set({ target }),
  closeAssetDetail: () => set({ target: null }),
}));

/** 非组件环境（工具函数、store 回调）里打开详情。 */
export function openAssetDetail(target: AssetDetailTarget): void {
  useAssetDetailStore.getState().openAssetDetail(target);
}

/** 关掉当前详情弹窗。 */
export function closeAssetDetail(): void {
  useAssetDetailStore.getState().closeAssetDetail();
}
