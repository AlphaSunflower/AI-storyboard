import { memo, useState } from 'react';
import { Clapperboard, FileText, LoaderCircle } from 'lucide-react';
import type { CanvasNode } from '../../types';
import type { ReferenceImagePoolResult } from '../../canvas/referenceImagePool';
import { submitScriptDrafting } from '../../services/jobRunner';
import { useNodeTextDraft } from '../../hooks/useNodeTextDraft';
import { RichPromptInput } from '../RichPromptInput';
import { useCanvasStore } from '../../store/canvasStore';
import { resolveStoryboardPlanButton } from '../../canvas/storyboardGenerationState';

export const StoryboardPlannerView = memo(function StoryboardPlannerView({
  node,
  updateNode,
  referencePool,
}: {
  node: CanvasNode;
  updateNode: (nodeId: string, patch: Partial<CanvasNode['data']>) => void;
  referencePool: ReferenceImagePoolResult;
}) {
  const { id, data } = node;
  const [submitting, setSubmitting] = useState(false);
  const [feedback, setFeedback] = useState<string | null>(null);
  const scriptDraft = useNodeTextDraft({
    nodeId: `${id}:script`,
    value: String(data.scriptText ?? data.prompt ?? ''),
    onChange: (value) => updateNode(id, { scriptText: value, prompt: value }),
  });
  const script = scriptDraft.value;
  const shots = Array.isArray(data.shots) ? data.shots : [];
  const edges = useCanvasStore((state) => state.edges);
  const planRecord = useCanvasStore((state) => [...state.generationRecords]
    .reverse()
    .find((record) => record.producerNodeId === id && record.actionId === 'plan'));
  const hasScriptInput = edges.some((edge) => edge.target === id && edge.targetHandle === 'script');
  const scriptStatus = hasScriptInput
    ? '已接入剧本'
    : script.trim()
      ? '当前使用节点内剧本，未连接剧本节点'
      : '未接入剧本，请连接剧本或直接在下方输入';
  const buttonState = resolveStoryboardPlanButton(planRecord, submitting, Boolean(script.trim()));
  const statusMessage = planRecord?.status === 'failed' || planRecord?.status === 'interrupted'
    ? planRecord.error || '分镜规划失败'
    : planRecord?.status === 'completed'
      ? '结构化分镜已生成'
      : buttonState.busy
        ? '任务已提交，正在生成结构化分镜…'
        : feedback;
  const statusTone = planRecord?.status === 'failed' || planRecord?.status === 'interrupted'
    ? 'error'
    : planRecord?.status === 'completed'
      ? 'success'
      : buttonState.busy
        ? 'busy'
        : 'error';
  return (
    <div className="storyboard-planner-view">
      <div className={`storyboard-planner-status ${hasScriptInput ? 'is-ready' : 'is-missing'}`}>
        <FileText size={13} />
        <span>{scriptStatus}</span>
      </div>
      <RichPromptInput
        className="text-node-editor planner-prompt-input"
        value={scriptDraft.value}
        placeholder="输入或连接剧本，生成可编辑的结构化镜头…"
        onChange={scriptDraft.setValue}
        pool={referencePool}
        showStrip={referencePool.count > 0}
        minHeight={120}
        maxHeight={0}
      />
      <div className="storyboard-planner-meta">
        <span>{shots.length} 个镜头</span>
        {referencePool.count > 0 ? <span>参考图 {referencePool.count}</span> : null}
        {data.migrationReport ? <span>存在迁移报告</span> : null}
      </div>
      <button
        className="node-upload node-upload--generate nodrag"
        disabled={buttonState.disabled}
        aria-busy={buttonState.busy}
        onClick={() => {
          if (buttonState.busy) return;
          setSubmitting(true);
          setFeedback(null);
          void submitScriptDrafting(script, id, setFeedback, {
            sourceAssetIds: referencePool.assetIds,
            sourceNodeIds: referencePool.entries.map((entry) => entry.sourceNodeId),
          })
            .then(() => setFeedback(null))
            .catch((error) => setFeedback(error instanceof Error ? error.message : '分镜规划失败'))
            .finally(() => setSubmitting(false));
        }}
      >
        {buttonState.busy ? <LoaderCircle className="is-spinning" size={14} /> : <Clapperboard size={14} />}
        {buttonState.label}
      </button>
      {buttonState.busy && (
        <i className="storyboard-plan-progress" aria-hidden="true">
          <span style={{ width: `${Math.max(4, buttonState.progress)}%` }} />
        </i>
      )}
      {statusMessage && (
        <div className="storyboard-planner-feedback nodrag" data-tone={statusTone} role="status">
          <span>{statusMessage}</span>
        </div>
      )}
    </div>
  );
});
