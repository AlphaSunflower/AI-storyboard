import { useEffect, useMemo, useRef, useState } from 'react';
import { Images } from 'lucide-react';
import type { CanvasNode } from '../../types';
import type { ReferenceImagePoolResult } from '../../canvas/referenceImagePool';
import { buildBatchRefineRequests, summarizeBatchGeneration } from '../../canvas/batchImageRefine';
import { submitBatchImageRefine } from '../../services/jobRunner';
import { useNodeTextDraft } from '../../hooks/useNodeTextDraft';
import { useCanvasStore } from '../../store/canvasStore';
import { jobAdapter } from '../../services/capabilities';

/**
 * 批量完善图片的共享逻辑：公共提示词 + 单图覆盖 + 批次状态 + 提交。
 * 合并后的「图片生成」节点（batchRefine 模式）与旧 batchImageRefine 节点共用。
 */
export function useBatchRefine({ node, updateNode, referencePool }: {
  node: CanvasNode;
  updateNode: (nodeId: string, patch: Partial<CanvasNode['data']>) => void;
  referencePool: ReferenceImagePoolResult;
}) {
  const [overrides, setOverrides] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [feedback, setFeedback] = useState<string | null>(null);
  const failureRef = useRef<string | null>(null);
  const generationRecords = useCanvasStore((state) => state.generationRecords);
  const updateGenerationRecord = useCanvasStore((state) => state.updateGenerationRecord);
  const commonPromptDraft = useNodeTextDraft({
    nodeId: `${node.id}:prompt`,
    value: String(node.data.prompt ?? ''),
    onChange: (value) => updateNode(node.id, { prompt: value }),
  });
  const generationSummary = useMemo(
    () => summarizeBatchGeneration(generationRecords, node.id),
    [generationRecords, node.id],
  );
  const statusTone = busy
    ? 'busy'
    : generationSummary.state === 'error' ? 'error'
      : generationSummary.state === 'completed' ? 'success'
        : generationSummary.state === 'running' || generationSummary.state === 'submitted' ? 'busy'
          : feedback ? 'error' : 'idle';
  const statusMessage = busy
    ? '提交中…请稍候'
    : generationSummary.state !== 'idle' ? generationSummary.message : feedback ?? '';

  useEffect(() => {
    if (!jobAdapter.getJob || generationSummary.state === 'idle' || generationSummary.state === 'completed' || generationSummary.state === 'error') return;
    const activeRecords = generationRecords.filter((record) =>
      record.producerNodeId === node.id
      && record.batchId === generationSummary.batchId
      && record.jobId
      && (record.status === 'queued' || record.status === 'running'));
    if (!activeRecords.length) return;
    let cancelled = false;
    const poll = async () => {
      await Promise.all(activeRecords.map(async (record) => {
        try {
          const snapshot = await jobAdapter.getJob!(record.jobId!);
          if (cancelled || snapshot.status === record.status) return;
          updateGenerationRecord({
            ...record,
            status: snapshot.status,
            error: snapshot.error,
            completedAt: ['completed', 'failed', 'cancelled', 'interrupted'].includes(snapshot.status) ? Date.now() : record.completedAt,
          });
        } catch {
          // SSE 仍是主通道；单次轮询失败不能清空任务。
        }
      }));
    };
    const timer = window.setInterval(() => { void poll(); }, 3000);
    void poll();
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [generationRecords, generationSummary.batchId, generationSummary.state, node.id, updateGenerationRecord]);

  const items = useMemo(() => referencePool.entries.slice(0, 12).map((entry, order) => ({
    itemId: entry.assetId,
    nodeId: entry.sourceNodeId,
    assetId: entry.assetId,
    order,
  })), [referencePool.entries]);

  const submit = () => {
    if (!items.length) {
      setFeedback('没有可处理的图片');
      return;
    }
    try {
      const requests = buildBatchRefineRequests(items, commonPromptDraft.value, overrides);
      const gatewayParams = node.data.gatewayParams && typeof node.data.gatewayParams === 'object'
        ? node.data.gatewayParams as Record<string, unknown>
        : {};
      failureRef.current = null;
      setBusy(true);
      setFeedback(null);
      void submitBatchImageRefine(node.id, requests, (message) => {
        failureRef.current = message;
        setFeedback(message);
      }, {
        ...gatewayParams,
        model: node.data.model,
        size: node.data.size,
        quality: node.data.quality,
        style: node.data.style,
        aspectRatio: node.data.aspectRatio,
        n: node.data.n,
      })
        .then(({ jobIds }) => {
          if (!failureRef.current) setFeedback(`已提交 ${jobIds.length} 个改图任务`);
        })
        .catch((error) => {
          const message = error instanceof Error ? error.message : '批量改图提交失败';
          failureRef.current = message;
          setFeedback(message);
        })
        .finally(() => setBusy(false));
    } catch (error) {
      const message = error instanceof Error ? error.message : '批量改图参数无效';
      failureRef.current = message;
      setFeedback(message);
    }
  };

  return {
    commonPromptDraft,
    overrides,
    setOverrides,
    items,
    busy,
    feedback,
    statusMessage,
    statusTone,
    submit,
  };
}

export function BatchRefineFields({ state, referencePool, showStatus = true }: {
  state: ReturnType<typeof useBatchRefine>;
  referencePool: ReferenceImagePoolResult;
  showStatus?: boolean;
}) {
  return (
    <div className="batch-image-refine-view">
      <div className="batch-refine-content">
        <div className="batch-refine-summary"><Images size={15} /><strong>{state.items.length}/12 张图片</strong></div>
        <textarea
          className="text-node-editor nodrag nowheel"
          aria-label="批量完善公共提示词"
          placeholder="公共完善要求，例如：统一为白天、提升清晰度、保持角色一致"
          value={state.commonPromptDraft.value}
          onChange={(event) => state.commonPromptDraft.setValue(event.target.value)}
          onFocus={state.commonPromptDraft.onFocus}
          onBlur={state.commonPromptDraft.onBlur}
          onCompositionStart={state.commonPromptDraft.onCompositionStart}
          onCompositionEnd={state.commonPromptDraft.onCompositionEnd}
        />
        <div className="batch-refine-items">
          {state.items.map((item) => {
            const entry = referencePool.entries.find((candidate) => candidate.assetId === item.assetId);
            return (
              <label key={item.itemId}>
                <span>#{item.order + 1} {entry?.sourceNodeName ?? item.nodeId}</span>
                <input
                  value={state.overrides[item.itemId] ?? ''}
                  placeholder="单图附加或覆盖要求"
                  onChange={(event) => state.setOverrides((current) => ({ ...current, [item.itemId]: event.target.value }))}
                />
              </label>
            );
          })}
        </div>
        {state.feedback && <small className="node-upload-error nodrag">{state.feedback}</small>}
      </div>
      {showStatus && state.statusMessage && (
        <div className="batch-refine-status" data-tone={state.statusTone} role="status">
          {state.statusMessage}
        </div>
      )}
    </div>
  );
}
