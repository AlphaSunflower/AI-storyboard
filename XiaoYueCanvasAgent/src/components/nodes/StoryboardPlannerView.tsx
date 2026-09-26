import { memo, useState } from 'react';
import { Clapperboard, FileText, LoaderCircle } from 'lucide-react';
import type { CanvasNode } from '../../types';
import type { ReferenceImagePoolResult } from '../../canvas/referenceImagePool';
import { submitScriptDrafting, submitStoryboardFinalize } from '../../services/jobRunner';
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
  const [finalizing, setFinalizing] = useState(false);
  const [feedback, setFeedback] = useState<string | null>(null);
  const scriptDraft = useNodeTextDraft({
    nodeId: `${id}:script`,
    value: String(data.scriptText ?? data.prompt ?? ''),
    onChange: (value) => updateNode(id, { scriptText: value, prompt: value }),
  });
  const script = scriptDraft.value;
  const shots = Array.isArray(data.shots) ? data.shots : [];
  const pseudoShots = Array.isArray(data.pseudoShots) ? data.pseudoShots : [];
  const productionRunId = typeof data.productionRunId === 'string' ? data.productionRunId : '';
  const productionStage = typeof data.productionStage === 'string' ? data.productionStage : '';
  const useStagedFinalize = Boolean(productionRunId);
  const finalizeBusy = finalizing || productionStage === 'FINALIZING';
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
  const statusMessage = finalizeBusy
    ? '正在逐镜生成正式分镜…'
    : productionStage === 'PSEUDO_REVIEW' && pseudoShots.length > 0
      ? `伪分镜待确认，共 ${pseudoShots.length} 个镜头`
      : planRecord?.status === 'failed' || planRecord?.status === 'interrupted'
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
        {planRecord?.status === 'completed' && useStagedFinalize ? '重新分析剧情' : buttonState.label}
      </button>
      {productionRunId && pseudoShots.length > 0 && !shots.length && (
        <div className="storyboard-pseudo-summary nodrag">
          <div className="storyboard-pseudo-summary__head">
            <span>结构化伪分镜</span>
            <span>{pseudoShots.length} 镜</span>
          </div>
          <div className="storyboard-pseudo-summary__list">
            {pseudoShots.slice(0, 3).map((shot) => (
              <span key={shot.shotId}>{shot.shotOrdinal}. {shot.narrativePurpose || shot.action}</span>
            ))}
            {pseudoShots.length > 3 ? <span>…另有 {pseudoShots.length - 3} 镜</span> : null}
          </div>
          <button
            className="node-upload node-upload--generate nodrag"
            disabled={finalizeBusy}
            onClick={() => {
              if (finalizeBusy) return;
              setFinalizing(true);
              setFeedback(null);
              void submitStoryboardFinalize(productionRunId, id, setFeedback)
                .catch((error) => setFeedback(error instanceof Error ? error.message : '正式分镜生成失败'))
                .finally(() => setFinalizing(false));
            }}
          >
            {finalizeBusy ? <LoaderCircle className="is-spinning" size={14} /> : <Clapperboard size={14} />}
            确认伪分镜并生成正式镜头
          </button>
        </div>
      )}
      {productionRunId && productionStage === 'PLOT_REVIEW' && !pseudoShots.length && (
        <div className="storyboard-pseudo-summary nodrag">
          <div className="storyboard-pseudo-summary__head">
            <span>剧情审查</span>
            <span>{data.productionAssessment?.complete ? '结构完整' : '需要扩写'}</span>
          </div>
          <div className="storyboard-pseudo-summary__list">
            <span>{data.productionAssessment?.summary || '请确认优化后的剧情稿'}</span>
            {data.productionAssessment?.expansionPlan ? (
              <span>扩写方案：{data.productionAssessment.expansionPlan}</span>
            ) : null}
          </div>
          <div className="storyboard-pseudo-actions">
            {data.expandedScript ? (
              <button
                className="node-upload node-upload--generate nodrag"
                disabled={finalizeBusy}
                onClick={() => {
                  setFinalizing(true);
                  void submitStoryboardFinalize(productionRunId, id, setFeedback, 'APPLY_EXPANSION')
                    .catch((error) => setFeedback(error instanceof Error ? error.message : '剧情确认失败'))
                    .finally(() => setFinalizing(false));
                }}
              >
                采纳扩写并生成伪分镜
              </button>
            ) : null}
            <button
              className="node-upload node-upload--generate nodrag"
              disabled={finalizeBusy}
              onClick={() => {
                setFinalizing(true);
                void submitStoryboardFinalize(productionRunId, id, setFeedback, 'KEEP_ORIGINAL')
                  .catch((error) => setFeedback(error instanceof Error ? error.message : '剧情确认失败'))
                  .finally(() => setFinalizing(false));
              }}
            >
              保留原稿并生成伪分镜
            </button>
          </div>
        </div>
      )}
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
