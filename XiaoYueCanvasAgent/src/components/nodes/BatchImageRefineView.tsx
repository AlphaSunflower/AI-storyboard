import { memo } from 'react';
import { Images } from 'lucide-react';
import type { CanvasNode } from '../../types';
import type { ReferenceImagePoolResult } from '../../canvas/referenceImagePool';
import { GenFooter } from './ImageGenNode';
import { BatchRefineFields, useBatchRefine } from './batchRefinePanel';
import { NodeFloatPanel, NodeSurface, NodeSurfaceEmpty } from './nodePanel';

/**
 * 旧 batchImageRefine 节点视图（存量数据兼容）：内容与合并后「图片生成」的批量完善模式共用，
 * 只是这里自带 footer。新画布请用图片生成节点的「批量完善」模式。
 */
export const BatchImageRefineView = memo(function BatchImageRefineView({
  node,
  updateNode,
  referencePool,
}: {
  node: CanvasNode;
  updateNode: (nodeId: string, patch: Partial<CanvasNode['data']>) => void;
  referencePool: ReferenceImagePoolResult;
}) {
  const state = useBatchRefine({ node, updateNode, referencePool });

  return (
    <div className="batch-image-refine-view">
      <NodeSurface state="empty" className="batch-image-refine-surface">
        <NodeSurfaceEmpty icon={<Images size={30} />} label="批量完善图片" hint="连接多张图片，逐张套用同一批提示词" />
      </NodeSurface>
      <NodeFloatPanel nodeId={node.id} className="batch-image-refine-panel">
        <BatchRefineFields state={state} referencePool={referencePool} />
        <GenFooter
          nodeId={node.id}
          kind="imageGen"
          data={node.data}
          updateNode={updateNode}
          referenceCount={1}
          genText="批量完善图片"
          useCallbacks={{
            generating: state.busy,
            onGenerate: () => state.submit(),
          }}
        />
      </NodeFloatPanel>
    </div>
  );
});
