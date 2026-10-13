import { memo, useState } from 'react';
import { NodeToolbar, Position } from '@xyflow/react';
import type { CanvasNode } from '../../types';
import { EMPTY_REFERENCE_IMAGE_POOL, buildReferenceImagePool } from '../../canvas/referenceImagePool';
import { submitVideoGeneration } from '../../services/jobRunner';
import { VIDEO_GENERATION_KIND, resolveNodeMode } from '../../canvas/generationModes';
import {
  GenerationComposer,
  GenerationPreviewCard,
  useGenerationSettings,
  useOrderedReferenceEntries,
} from './generationPanel';

/**
 * videoGeneration 节点 panel：预览卡 + composer（模式 chips / 参考素材 / 提示词 / 设置行 / 发送）。
 * 四个模式共用一套外壳，真实差异只有参考角色映射与提交参数。
 */
export const VideoGenNode = memo(function VideoGenNode({ id, data, updateNode, textDraft, setTextDraft, referencePool, deleteReference, draftFeedback, setDraftFeedback }: {
  id: string;
  data: CanvasNode['data'];
  /** composer 由 React Flow 默认规则显示（单选本节点时），selected 仅用于测试注入。 */
  selected?: boolean;
  updateNode: (id: string, patch: Record<string, unknown>) => void;
  textDraft: string | undefined;
  setTextDraft: (v: string) => void;
  referencePool?: ReturnType<typeof buildReferenceImagePool> | null;
  deleteReference: (entry: { number: number; sourceNodeId: string }) => void;
  draftFeedback: string | null;
  setDraftFeedback: (msg: string | null) => void;
}) {
  const pool = referencePool ?? EMPTY_REFERENCE_IMAGE_POOL;
  const mode = resolveNodeMode(VIDEO_GENERATION_KIND, data) ?? 'text2video';
  const orderedEntries = useOrderedReferenceEntries(id, pool);
  const settings = useGenerationSettings({
    kind: VIDEO_GENERATION_KIND,
    nodeId: id,
    data,
    updateNode,
    referenceCount: pool.count,
  });

  const generate = () => {
    const text = String(textDraft ?? data.prompt ?? '');
    // 文生视频不消费参考素材；其余模式把引用端口上的素材按顺序交给网关
    const entries = mode === 'text2video' ? [] : orderedEntries;
    const referenceRoles = entries.map((entry, index) => ({
      assetId: entry.assetId,
      role: mode === 'firstLastFrame'
        ? (index === 0 ? 'FIRST_FRAME' : 'LAST_FRAME')
        : 'REFERENCE',
    }));
    void submitVideoGeneration(text, id, (msg) => setDraftFeedback(msg), {
      sourceAssetIds: entries.map((entry) => entry.assetId),
      sourceNodeIds: entries.map((entry) => entry.sourceNodeId),
      params: {
        actionId: 'generate',
        videoMode: mode,
        model: data.model,
        aspectRatio: data.aspectRatio,
        resolution: data.resolution ?? data.size,
        size: data.size,
        duration: data.duration,
        referenceRoles,
      },
    }).catch((error) => setDraftFeedback(error instanceof Error ? error.message : '生视频提交失败'));
  };

  return (
    <div className="gen-node video-gen-view">
      <GenerationPreviewCard
        nodeId={id}
        media="video"
        generating={Boolean(data.isGenerating)}
        progress={typeof data.progressPercent === 'number' ? data.progressPercent : undefined}
        emptyLabel="输入提示词生成视频"
      />
      <NodeToolbar
        nodeId={id}
        position={Position.Bottom}
        align="center"
        offset={14}
        className="gen-toolbar nodrag nowheel"
        onPointerDown={(event) => event.stopPropagation()}
      >
        <GenerationComposer
          nodeId={id}
          kind={VIDEO_GENERATION_KIND}
          mode={mode}
          onModeChange={(next) => updateNode(id, { mode: next })}
          pool={pool}
          onDeleteRef={deleteReference}
          textDraft={String(textDraft ?? data.prompt ?? '')}
          setTextDraft={setTextDraft}
          placeholder="请输入视频描述…"
          showStrip={mode !== 'firstLastFrame' && Boolean(pool.count > 0)}
          settings={settings}
          generating={Boolean(data.isGenerating)}
          genLabel="生成视频"
          onGenerate={generate}
        />
      </NodeToolbar>
      {draftFeedback && <small className="node-upload-error nodrag">{draftFeedback}</small>}
    </div>
  );
});
