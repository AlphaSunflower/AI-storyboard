import { useAgentStore } from '../../stores/agentStore';
import { useAssetDetailStore } from '../../stores/assetDetailStore';
import { AssetDetailPane } from './AssetDetailPane';

/**
 * 素材详情弹窗的宿主：每个页面挂一次。
 *
 * 会话里的图片/视频、生成中心的会话资产网格、工具槽位都通过
 * `openAssetDetail(target)` 弹同一个窗；各自持有状态就会出现两个详情叠在一起。
 */
export function AssetDetailHost() {
  const target = useAssetDetailStore((state) => state.target);
  const closeAssetDetail = useAssetDetailStore((state) => state.closeAssetDetail);

  if (!target) return null;

  return (
    <AssetDetailPane
      target={target}
      onClose={closeAssetDetail}
      onDeleted={() => {
        closeAssetDetail();
        // 删除后会话资产列表少了一张，网格要跟着刷新。
        void useAgentStore.getState().loadAssets();
      }}
    />
  );
}
