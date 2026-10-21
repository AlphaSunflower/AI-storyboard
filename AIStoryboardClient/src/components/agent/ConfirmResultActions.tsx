import { useAgentStore } from '../../stores/agentStore';
import type { ConfirmResultInfo } from '../../api/agent';

/**
 * 结果确认动作（继续完善 / 满意完成）：渲染在**产生结果的那条助手消息**里。
 *
 * <p>它只负责动作，不再负责结果图——图由消息的产物层承载（媒体任务卡的预置位条，
 * 或兜底写进消息的 `sandboxAttachments`）。旧实现把图片画在一张全局卡片里，
 * 于是①同一会话两个结果互相顶掉；②点「满意完成/继续完善」把全局槽清空，
 * 而图片只存在那张卡里 → 用户看到"点一下图全没了"。
 */
export function ConfirmResultActions({ info, messageId }: {
  info: ConfirmResultInfo;
  messageId?: string;
}) {
  const refineAsset = useAgentStore((state) => state.refineAsset);
  const dismissConfirm = useAgentStore((state) => state.dismissConfirm);
  const streaming = useAgentStore((state) => state.streaming);
  if (!info) return null;

  if (info.kind === 'script') {
    return (
      <div style={{ fontSize: 14, color: 'var(--color-body)', lineHeight: 1.7, marginTop: 6 }}>
        {typeof info.sceneCount === 'number' && info.sceneCount > 0
          ? `已生成 ${info.sceneCount} 个分镜，请查看左侧分镜列表`
          : '分镜已确认，请查看左侧分镜列表'}
      </div>
    );
  }

  const actions = info.actions ?? [];
  if (actions.length === 0) return null;

  return (
    <div style={{ display: 'flex', gap: 8, marginTop: 8, flexWrap: 'wrap' }}>
      <button
        type="button"
        disabled={streaming}
        onClick={() => refineAsset(info, messageId)}
        style={{
          padding: '8px 16px', border: 'none', borderRadius: 'var(--rounded-md)',
          background: 'var(--color-primary)', color: 'var(--color-on-primary)', fontSize: 14,
          cursor: streaming ? 'not-allowed' : 'pointer', opacity: streaming ? 0.6 : 1,
        }}
      >
        继续完善
      </button>
      <button
        type="button"
        disabled={streaming}
        onClick={() => void dismissConfirm(messageId)}
        style={{
          padding: '8px 16px', border: '1px solid var(--color-hairline)', borderRadius: 'var(--rounded-md)',
          background: 'var(--color-panel)', color: 'var(--color-muted)', fontSize: 14,
          cursor: streaming ? 'not-allowed' : 'pointer', opacity: streaming ? 0.6 : 1,
        }}
      >
        满意完成
      </button>
    </div>
  );
}
