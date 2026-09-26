import { useEffect, useMemo, useState } from 'react';
import {
  AlertTriangle,
  ArrowRight,
  Check,
  CircleStop,
  Clock3,
  FileText,
  History,
  Image as ImageIcon,
  Layers3,
  LoaderCircle,
  Maximize2,
  Video,
  X,
} from 'lucide-react';
import { useCanvasStore } from '../store/canvasStore';
import { useMediaStore } from '../store/mediaStore';
import { useObjectUrl } from '../hooks/useObjectUrl';
import { getAsset } from '../services/projectStorage';
import { generationRecordProgress, generationResultAssetIds } from '../canvas/generationRecords';
import { multiAngleLabel } from '../canvas/multiAngle';
import type { CanvasNode, GenerationRecord } from '../types';
import { ImagePreviewOverlay, type ImagePreviewTarget } from './ImagePreviewOverlay';

const STATUS_META: Record<GenerationRecord['status'], { label: string; tone: string; icon: typeof Check }> = {
  queued: { label: '排队中', tone: 'queued', icon: Clock3 },
  running: { label: '生成中', tone: 'running', icon: LoaderCircle },
  completed: { label: '已完成', tone: 'completed', icon: Check },
  failed: { label: '失败', tone: 'failed', icon: AlertTriangle },
  cancelled: { label: '已取消', tone: 'cancelled', icon: CircleStop },
  interrupted: { label: '已中断', tone: 'interrupted', icon: AlertTriangle },
};

const ACTION_LABEL: Record<string, string> = {
  'image.generate': '生成图片',
  'image.redraw': '图片重绘',
  'video.generate': '生成视频',
  'storyboard.plan': '分镜规划',
  'storyboard.outline': '剧情与伪分镜',
  'storyboard.finalize': '正式分镜生成',
  'storyboardGrid': '分镜画面生成',
  'agent.request': '设计助理生成',
  generate: '生成',
  generateImage: '生成图片',
  generateVideo: '生成视频',
  plan: '生成分镜',
  refineBatch: '批量重绘',
};

const paramLabel: Record<string, string> = {
  size: '尺寸',
  quality: '质量',
  n: '数量',
  mode: '模式',
  resolution: '分辨率',
  aspectRatio: '画幅',
  duration: '时长',
  negativePrompt: '负向提示词',
  model: '模型',
  style: '风格',
  genType: '生成类型',
};

const roleLabel = (role: string) => ({
  FIRST_FRAME: '首帧',
  LAST_FRAME: '尾帧',
  REFERENCE: '参考',
  source: '来源',
}[role] ?? role);

const formatTime = (value?: number) => value
  ? new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(value)
  : '未记录';

const formatDuration = (record: GenerationRecord) => {
  const end = record.completedAt ?? (record.status === 'queued' || record.status === 'running' ? Date.now() : record.startedAt);
  const ms = Math.max(0, end - record.startedAt);
  if (ms < 1000) return `${ms} ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)} 秒`;
  return `${Math.floor(ms / 60_000)} 分 ${Math.round((ms % 60_000) / 1000)} 秒`;
};

const recordKind = (record: GenerationRecord) => {
  const capabilityId = typeof record.params.capabilityId === 'string' ? record.params.capabilityId : '';
  if (capabilityId.startsWith('image.') || record.actionId.toLowerCase().includes('image')) return 'image';
  if (capabilityId.startsWith('video.') || record.actionId.toLowerCase().includes('video')) return 'video';
  if (capabilityId.startsWith('storyboard.') || record.actionId.includes('storyboard') || record.actionId === 'plan') return 'storyboard';
  return 'agent';
};

const valueSummary = (value: unknown): string => {
  if (typeof value === 'string' && value.startsWith('data:')) return '二进制参考素材';
  if (Array.isArray(value)) return `${value.length} 项`;
  if (value && typeof value === 'object') return `${Object.keys(value).length} 个字段`;
  return String(value);
};

function AssetThumb({
  assetId,
  label,
  fallbackType,
  compact = false,
  onPreview,
}: {
  assetId: string;
  label?: string;
  fallbackType?: 'image' | 'video';
  compact?: boolean;
  onPreview?: (target: ImagePreviewTarget) => void;
}) {
  const asset = useMediaStore((state) => state.assets[assetId]);
  const metadata = useMediaStore((state) => state.metadata[assetId]);
  const upsertAsset = useMediaStore((state) => state.upsertAsset);
  const blob = asset?.thumbnailBlob ?? asset?.previewBlob;
  const url = useObjectUrl(blob);

  useEffect(() => {
    if (asset) return;
    void getAsset(assetId).then((loaded) => {
      if (loaded) upsertAsset(loaded);
    }).catch(() => undefined);
  }, [asset, assetId, upsertAsset]);

  const kind = asset?.kind ?? metadata?.kind ?? fallbackType ?? 'image';
  const displayLabel = label ?? asset?.name ?? metadata?.name ?? assetId.slice(0, 10);
  const media = (
    <span className="record-asset__media">
      {url
        ? <img src={url} alt={displayLabel} />
        : kind === 'video' ? <Video size={compact ? 14 : 20} /> : <ImageIcon size={compact ? 14 : 20} />}
    </span>
  );
  return (
    <figure className={`record-asset ${compact ? 'is-compact' : ''}`} title={label ?? asset?.name ?? metadata?.name ?? assetId}>
      {kind === 'image' && url ? (
        <button
          type="button"
          className="record-asset__preview"
          aria-label={`预览图片：${displayLabel}`}
          onClick={(event) => { event.stopPropagation(); onPreview?.({ assetId, label: displayLabel }); }}
        >
          {media}
          <span className="record-asset__zoom" aria-hidden="true"><Maximize2 size={compact ? 11 : 13} /></span>
        </button>
      ) : media}
      <figcaption>
        <strong>{displayLabel}</strong>
        <small>{kind === 'video' ? '视频' : '图片'} · {assetId.slice(0, 8)}</small>
      </figcaption>
    </figure>
  );
}

function NodePill({ node, fallback }: { node?: CanvasNode; fallback: string }) {
  return (
    <span className="record-node-pill">
      <Layers3 size={13} />
      <span><strong>{node?.data.label ?? fallback}</strong><small>{node?.data.kind ?? 'canvas-node'}</small></span>
    </span>
  );
}

function Provenance({
  record,
  nodes,
  nodeLabel,
  onPreview,
}: {
  record: GenerationRecord;
  nodes: CanvasNode[];
  nodeLabel: (id: string) => string;
  onPreview: (target: ImagePreviewTarget) => void;
}) {
  const resultAssetIds = generationResultAssetIds(record, nodes);
  const producer = nodes.find((node) => node.id === record.producerNodeId);
  return (
    <section className="record-provenance">
      <header><span>生成链路</span><small>素材 → 节点 → 结果</small></header>
      <div className="record-provenance__flow">
        <div className="record-provenance__item">
          <span className="record-provenance__label">{record.sources.length ? `来源素材 ${record.sources.length}` : '无参考素材'}</span>
          {record.sources.length ? (
            <div className="record-asset-row">
              {record.sources.map((source) => (
                <AssetThumb
                  key={`${source.order}:${source.assetId}`}
                  assetId={source.assetId}
                  label={`${nodeLabel(source.nodeId)} · ${roleLabel(source.role)}`}
                  compact
                  onPreview={onPreview}
                />
              ))}
            </div>
          ) : <span className="record-flow-placeholder">本次使用文本或画布上下文</span>}
        </div>

        <ArrowRight className="record-flow-arrow" size={18} />

        <div className="record-provenance__item is-node">
          <span className="record-provenance__label">执行节点</span>
          <NodePill node={producer} fallback={record.producerNodeId || '画布任务'} />
          <span className="record-operation">{ACTION_LABEL[record.actionId] ?? record.actionId}</span>
        </div>

        <ArrowRight className="record-flow-arrow" size={18} />

        <div className="record-provenance__item">
          <span className="record-provenance__label">{resultAssetIds.length ? `结果素材 ${resultAssetIds.length}` : '等待结果'}</span>
          {resultAssetIds.length ? (
            <div className="record-asset-row">
              {resultAssetIds.map((assetId) => <AssetThumb key={assetId} assetId={assetId} compact onPreview={onPreview} />)}
            </div>
          ) : (
            <span className="record-flow-placeholder">
              {record.status === 'running' || record.status === 'queued' ? '生成中，结果会自动落到画布' : '没有可展示的媒体结果'}
            </span>
          )}
        </div>
      </div>
      {record.resultNodeIds.length > 0 && (
        <div className="record-result-nodes">
          <span>结果节点</span>
          {record.resultNodeIds.map((nodeId) => <NodePill key={nodeId} node={nodes.find((node) => node.id === nodeId)} fallback={nodeId} />)}
        </div>
      )}
    </section>
  );
}

function RecordDetail({
  record,
  nodes,
  nodeLabel,
  onPreview,
}: {
  record: GenerationRecord;
  nodes: CanvasNode[];
  nodeLabel: (id: string) => string;
  onPreview: (target: ImagePreviewTarget) => void;
}) {
  const params = Object.entries(record.params)
    .filter(([key, value]) => !['prompt', 'canvas', 'referenceImages', 'referenceVideos', 'capabilityId', 'actionId'].includes(key)
      && value !== undefined && value !== null && value !== '');

  return (
    <div className="generation-record-detail">
      <header className="generation-record-detail__head">
        <div>
          <span className="eyebrow">GENERATION RECORD</span>
          <h3>{ACTION_LABEL[record.actionId] ?? record.actionId}</h3>
          <p>{record.producerNodeId ? `由「${nodeLabel(record.producerNodeId)}」提交` : '由画布上下文提交'}</p>
        </div>
        <span className={`record-status is-${STATUS_META[record.status].tone}`}>{STATUS_META[record.status].label}</span>
      </header>

      <Provenance record={record} nodes={nodes} nodeLabel={nodeLabel} onPreview={onPreview} />

      <section className="record-detail-block">
        <header><FileText size={14} /><strong>提示词</strong></header>
        <p>{record.effectivePrompt || record.prompt || '未记录提示词'}</p>
        {record.effectivePrompt !== record.prompt && (
          <details><summary>查看原始提示词</summary><p>{record.prompt || '未记录'}</p></details>
        )}
      </section>

      <section className="record-detail-block">
        <header><History size={14} /><strong>执行信息</strong></header>
        <dl className="record-meta-grid">
          <div><dt>模型</dt><dd>{record.model || '默认模型'}</dd></div>
          <div><dt>任务 ID</dt><dd>{record.jobId || '尚未取得'}</dd></div>
          <div><dt>尝试次数</dt><dd>{record.attempt}</dd></div>
          <div><dt>提交时间</dt><dd>{formatTime(record.startedAt)}</dd></div>
          <div><dt>耗时</dt><dd>{formatDuration(record)}</dd></div>
          <div><dt>结果落地</dt><dd>{record.materializationStatus}</dd></div>
        </dl>
      </section>

      {params.length > 0 && (
        <section className="record-detail-block">
          <header><Layers3 size={14} /><strong>生成参数</strong></header>
          <dl className="record-param-grid">
            {params.map(([key, value]) => (
              <div key={key}><dt>{paramLabel[key] ?? key}</dt><dd title={valueSummary(value)}>{valueSummary(value)}</dd></div>
            ))}
          </dl>
        </section>
      )}

      {(record.error || record.materializationError) && (
        <section className="record-detail-error" role="alert">
          <AlertTriangle size={15} />
          <div>
            <strong>错误信息</strong>
            <p>{record.error || record.materializationError}</p>
          </div>
        </section>
      )}
    </div>
  );
}

export function GenerationRecordsPanel({ onClose }: { onClose: () => void }) {
  const records = useCanvasStore((state) => state.generationRecords);
  const nodes = useCanvasStore((state) => state.nodes);
  const [status, setStatus] = useState('all');
  const [kind, setKind] = useState('all');
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [previewTarget, setPreviewTarget] = useState<ImagePreviewTarget | null>(null);

  const filtered = useMemo(() => [...records]
    .filter((record) => status === 'all' || record.status === status)
    .filter((record) => kind === 'all' || recordKind(record) === kind)
    .sort((a, b) => b.startedAt - a.startedAt), [kind, records, status]);
  const selected = filtered.find((record) => record.id === selectedId) ?? null;
  const nodeLabel = (id: string) => nodes.find((node) => node.id === id)?.data.label ?? (id ? id.slice(0, 12) : '未关联节点');

  useEffect(() => {
    if (selectedId && !records.some((record) => record.id === selectedId)) setSelectedId(null);
  }, [records, selectedId]);

  return (
    <>
      <aside className={`floating-drawer generation-records-panel ${selected ? 'is-detail-open' : ''}`}>
        <div className="drawer-heading">
          <div><span className="eyebrow">PROVENANCE</span><h2>生成记录</h2></div>
          <button className="icon-button" onClick={onClose} aria-label="关闭生成记录"><X size={17} /></button>
        </div>
        <div className="generation-records-filter">
          <label><span>状态</span>
            <select value={status} onChange={(event) => setStatus(event.target.value)}>
              <option value="all">全部</option>
              <option value="queued">排队中</option>
              <option value="running">生成中</option>
              <option value="completed">已完成</option>
              <option value="failed">失败</option>
              <option value="cancelled">已取消</option>
              <option value="interrupted">已中断</option>
            </select>
          </label>
          <label><span>类型</span>
            <select value={kind} onChange={(event) => setKind(event.target.value)}>
              <option value="all">全部</option>
              <option value="image">图片</option>
              <option value="video">视频</option>
              <option value="storyboard">分镜</option>
              <option value="agent">设计助理</option>
            </select>
          </label>
        </div>

        <div className="generation-records-body">
          <div className="generation-record-list" aria-label="生成记录列表">
            {!filtered.length && (
              <div className="generation-record-empty">
                <History size={22} />
                <strong>没有匹配的生成记录</strong>
                <span>提交真实生成任务后，这里会保留来源、状态和结果。</span>
              </div>
            )}
            {filtered.map((record) => {
              const meta = STATUS_META[record.status];
              const StatusIcon = meta.icon;
              const results = generationResultAssetIds(record, nodes);
              const capabilityId = typeof record.params.capabilityId === 'string' ? record.params.capabilityId : '';
              const recordLabel = multiAngleLabel(record.params.multiAngleMode)
                ?? ACTION_LABEL[record.actionId]
                ?? actionLabelForCapability(capabilityId)
                ?? record.actionId;
              const active = record.status === 'queued'
                || record.status === 'running'
                || record.materializationStatus === 'materializing';
              const progress = generationRecordProgress(record, nodes);
              return (
                <button
                  key={record.id}
                  className={`generation-record-card ${selectedId === record.id ? 'is-active' : ''}`}
                  onClick={() => setSelectedId(record.id)}
                >
                  <header>
                    <span className={`record-status is-${meta.tone}`}><StatusIcon size={12} className={record.status === 'running' ? 'is-spinning' : ''} />{meta.label}</span>
                    <small>{formatTime(record.startedAt)}</small>
                  </header>
                  <strong>{recordLabel}</strong>
                  <p>{record.effectivePrompt || record.prompt || '未记录提示词'}</p>
                  {active && (
                    <div
                      className="generation-record-progress"
                      role="progressbar"
                      aria-label="生成进度"
                      aria-valuemin={0}
                      aria-valuemax={100}
                      aria-valuenow={Math.round(progress * 100)}
                    >
                      <i><em style={{ width: `${Math.max(3, progress * 100)}%` }} /></i>
                      <span>{Math.round(progress * 100)}%</span>
                    </div>
                  )}
                  <footer>
                    <span>{nodeLabel(record.producerNodeId)}</span>
                    <span>{record.model || '默认模型'}</span>
                    <span>{results.length ? `${results.length} 个结果` : record.materializationStatus}</span>
                  </footer>
                </button>
              );
            })}
          </div>
          {selected && <RecordDetail record={selected} nodes={nodes} nodeLabel={nodeLabel} onPreview={setPreviewTarget} />}
        </div>
      </aside>
      {previewTarget && <ImagePreviewOverlay target={previewTarget} onClose={() => setPreviewTarget(null)} />}
    </>
  );
}

function actionLabelForCapability(capabilityId: string) {
  return ACTION_LABEL[capabilityId];
}
