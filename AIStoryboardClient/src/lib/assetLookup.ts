import { agentApi, type AgentAsset } from '../api/agent';
import { useAgentStore } from '../stores/agentStore';
import { parseResultUrls } from '../stores/generationRecordsStore';

/** 会话资产分页上限（后端 size 钳制到 1~50）。 */
const LOOKUP_PAGE_SIZE = 50;

/**
 * 媒体地址的对象键：`/api/files/` 之后的部分，去掉签名与查询串。
 *
 * 会话资产接口返回前会给本地 URL 签名（`?e=…&s=…`），而聊天消息里的图片是
 * 生成当时写进正文的裸地址 —— 同一个文件，两种字符串。能跨过签名的只有对象键，
 * 口径与 asset-service 的 `objectKey()` 保持一致。
 */
export function mediaObjectKey(url: string | null | undefined): string | null {
  if (!url) return null;
  const clean = url.split('?')[0].split('#')[0].trim();
  const marker = '/api/files/';
  const index = clean.indexOf(marker);
  if (index >= 0) return clean.slice(index + marker.length);
  if (/^(images|videos|audios|documents|files)\//.test(clean)) return clean;
  return null;
}

/** 两个地址是不是同一个媒体对象：域名、端口、签名都不同，只有对象键靠得住。 */
export function sameMediaObject(
  left: string | null | undefined,
  right: string | null | undefined,
): boolean {
  if (!left || !right) return false;
  if (left === right) return true;
  const leftKey = mediaObjectKey(left);
  return leftKey !== null && leftKey === mediaObjectKey(right);
}

/** 这条记录是不是就是某个 URL（可能是它的主 URL，也可能是多图结果里的一张）。 */
export function assetMatchesUrl(asset: AgentAsset, url: string): boolean {
  if (!url) return false;
  if (sameMediaObject(asset.url, url)) return true;
  return parseResultUrls(asset.resultUrls ?? null).some((candidate) => sameMediaObject(candidate, url));
}

/** 信息量打分：同一个对象撞上多条记录时，优先选能回答「怎么生成的」那一条。 */
function assetInfoScore(asset: AgentAsset): number {
  let score = 0;
  if (asset.prompt?.trim()) score += 4;
  if (asset.params) score += 2;
  if (asset.model?.trim()) score += 1;
  return score;
}

/** 从一批记录里挑出这张素材：没有就返回 null。 */
export function pickBestAssetMatch(records: AgentAsset[], url: string): AgentAsset | null {
  const matches = records.filter((asset) => assetMatchesUrl(asset, url));
  if (matches.length === 0) return null;
  return matches.reduce((best, candidate) =>
    (assetInfoScore(candidate) > assetInfoScore(best) ? candidate : best));
}

/**
 * 找这张素材的生成记录。
 *
 * 两级：先看已经加载进 store 的那一页（用户既然在侧边栏看得见，多半就在里面，
 * 一次请求都不用发），再回头问一次会话资产接口的第一页。
 * 查不到就返回 null，由调用方降级成「只有 URL」的详情 —— 反查失败不该挡住看图。
 */
export async function resolveConversationAssetByUrl(
  conversationId: string | null | undefined,
  url: string,
): Promise<AgentAsset | null> {
  if (!url) return null;

  const loaded = useAgentStore.getState().assets?.records ?? [];
  const fromLoaded = pickBestAssetMatch(loaded, url);
  if (fromLoaded) return fromLoaded;

  if (!conversationId) return null;
  try {
    const response = await agentApi.listAssets(conversationId, 1, LOOKUP_PAGE_SIZE);
    return pickBestAssetMatch(response.data?.data?.records ?? [], url);
  } catch {
    return null;
  }
}
