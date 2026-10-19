import { useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import {
  AlertTriangle,
  ArrowRight,
  Check,
  ChevronRight,
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
import { useViewportVisibility } from '../hooks/useViewportVisibility';
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

/** 记录标题：多角度模式 > 动作名 > 能力名 > 原始 actionId。列表和详情共用同一口径。 */
function recordTitle(record: GenerationRecord) {
  const capabilityId = typeof record.params.capabilityId === 'string' ? record.params.capabilityId : '';
  return multiAngleLabel(record.params.multiAngleMode)
    ?? ACTION_LABEL[record.actionId]
    ?? ACTION_LABEL[capabilityId]
    ?? record.actionId;
}

const promptText = (record: GenerationRecord) => record.effectivePrompt || record.prompt || '未记录提示词';

const valueSummary = (value: unknown): string => {
  if (typeof value === 'string' && value.startsWith('data:')) return '二进制参考素材';
  if (Array.isArray(value)) return `${value.length} 项`;
  if (value && typeof value === 'object') return `${Object.keys(value).length} 个字段`;
  return String(value);
};

type AssetThumbSize = 'sm' | 'md' | 'lg';

function AssetThumb({
  assetId,
  label,
  fallbackType,
  size = 'md',
  onPreview,
}: {
  assetId: string;
  label?: string;
  fallbackType?: 'image' | 'video';
  size?: AssetThumbSize;
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
  const iconSize = size === 'sm' ? 14 : size === 'lg' ? 24 : 20;
  const media = (
    <span className="record-asset__media">
      {url
        ? <img src={url} alt={displayLabel} />
        : kind === 'video' ? <Video size={iconSize} /> : <ImageIcon size={iconSize} />}
    </span>
  );
  return (
    <figure className={`record-asset is-${size}`} title={label ?? asset?.name ?? metadata?.name ?? assetId}>
      {kind === 'image' && url ? (
        <button
          type="button"
          className="record-asset__preview"
          aria-label={`预览图片：${displayLabel}`}
          onClick={(event) => { event.stopPropagation(); onPreview?.({ assetId, label: displayLabel }); }}
        >
          {media}
          <span className="record-asset__zoom" aria-hidden="true"><Maximize2 size={size === 'sm' ? 11 : 13} /></span>
        </button>
      ) : media}
      <figcaption>
        <strong>{displayLabel}</strong>
        <small>{kind === 'video' ? '视频' : '图片'} · {assetId.slice(0, 8)}</small>
      </figcaption>
    </figure>
  );
}

/** 列表行左侧的小缩略图：优先用结果素材，没有结果时退回状态图标。 */
function RecordThumb({ record, nodes }: { record: GenerationRecord; nodes: CanvasNode[] }) {
  const assetId = generationResultAssetIds(record, nodes)[0];
  // 缩略图跟着滚动进视口再读 IndexedDB：历史记录没有上限，一次性读几百个素材会拖慢抽屉。
  const { ref, visible } = useViewportVisibility<HTMLSpanElement>(`record-thumb:${record.id}`);
  const asset = useMediaStore((state) => assetId ? state.assets[assetId] : undefined);
  const upsertAsset = useMediaStore((state) => state.upsertAsset);
  const blob = asset?.thumbnailBlob ?? asset?.previewBlob;
  const url = useObjectUrl(blob);

  useEffect(() => {
    if (!assetId || asset || !visible) return;
    void getAsset(assetId).then((loaded) => {
      if (loaded) upsertAsset(loaded);
    }).catch(() => undefined);
  }, [asset, assetId, upsertAsset, visible]);

  const kind = asset?.kind ?? (recordKind(record) === 'video' ? 'video' : recordKind(record) === 'image' ? 'image' : 'storyboard');
  const Icon = kind === 'video' ? Video : kind === 'image' ? ImageIcon : Layers3;
  return (
    <span ref={ref} className={`generation-record-row__thumb ${url ? '' : `is-placeholder is-${kind}`}`}>
      {url
        ? <img src={url} alt="" loading="lazy" decoding="async" />
        : <Icon size={18} aria-hidden="true" />}
    </span>
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
                  size="sm"
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
              {resultAssetIds.map((assetId) => (
                <AssetThumb key={assetId} assetId={assetId} size="lg" onPreview={onPreview} />
              ))}
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

/**
 * 记录详情：桌面按「主栏（提示词 / 链路 / 错误）+ 侧栏（执行信息 / 参数）」两栏排，
 * 窄屏自动落成单栏。详情只活在弹窗里，不再挤在 440px 的抽屉右侧。
 */
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
      <div className="generation-record-detail__main">
        <section className="record-detail-block">
          <header><FileText size={14} /><strong>提示词</strong></header>
          <p>{promptText(record)}</p>
          {record.effectivePrompt !== record.prompt && (
            <details><summary>查看原始提示词</summary><p>{record.prompt || '未记录'}</p></details>
          )}
        </section>

        <Provenance record={record} nodes={nodes} nodeLabel={nodeLabel} onPreview={onPreview} />

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

      <aside className="generation-record-detail__side">
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
      </aside>
    </div>
  );
}

/**
 * 记录详情弹窗：点列表行才出现，尺寸按窗口算，不再和列表抢抽屉宽度。
 * 用 portal 挂到 body，避免被画布/抽屉的 overflow 裁掉；Esc、遮罩点击、关闭按钮都能关。
 */
function GenerationRecordDialog({
  record,
  title,
  nodes,
  nodeLabel,
  onClose,
  onPreview,
}: {
  record: GenerationRecord;
  title: string;
  nodes: CanvasNode[];
  nodeLabel: (id: string) => string;
  onClose: () => void;
  onPreview: (target: ImagePreviewTarget) => void;
}) {
  const dialogRef = useRef<HTMLDivElement>(null);
  const closeRef = useRef<HTMLButtonElement>(null);
  const bodyRef = useRef<HTMLDivElement>(null);
  const meta = STATUS_META[record.status];

  useEffect(() => {
    closeRef.current?.focus();
    if (bodyRef.current) bodyRef.current.scrollTop = 0;
  }, []);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        // 图片全屏预览叠在弹窗之上：这一次 Esc 归它，避免一层键关掉两层。
        if (document.querySelector('.record-image-preview')) return;
        event.stopPropagation();
        onClose();
        return;
      }
      if (event.key !== 'Tab' || !dialogRef.current) return;
      const focusable = [...dialogRef.current.querySelectorAll<HTMLElement>(
        'button:not(:disabled), [href], input:not(:disabled), select:not(:disabled), textarea:not(:disabled), summary, [tabindex]:not([tabindex="-1"])',
      )];
      if (!focusable.length) return;
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };
    addEventListener('keydown', onKeyDown, true);
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      removeEventListener('keydown', onKeyDown, true);
      document.body.style.overflow = previousOverflow;
    };
  }, [onClose]);

  return createPortal(
    <div
      className="generation-record-modal"
      data-state="open"
      onMouseDown={(event) => { if (event.target === event.currentTarget) onClose(); }}
    >
      <div
        ref={dialogRef}
        className="generation-record-dialog"
        role="dialog"
        aria-modal="true"
        aria-label={`生成记录详情：${title}`}
        data-state="open"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <header className="generation-record-dialog__bar">
          <div className="generation-record-dialog__title">
            <span className="eyebrow">GENERATION RECORD</span>
            <h2>{title}</h2>
            <p>
              {record.producerNodeId ? `由「${nodeLabel(record.producerNodeId)}」提交` : '由画布上下文提交'}
              <i aria-hidden="true">·</i>
              {formatTime(record.startedAt)}
            </p>
          </div>
          <div className="generation-record-dialog__tools">
            <span className={`record-status is-${meta.tone}`}>{meta.label}</span>
            <button ref={closeRef} type="button" className="generation-record-dialog__close" aria-label="关闭生成记录详情" onClick={onClose}>
              <X size={18} />
            </button>
          </div>
        </header>
        <div ref={bodyRef} className="generation-record-dialog__body">
          <RecordDetail record={record} nodes={nodes} nodeLabel={nodeLabel} onPreview={onPreview} />
        </div>
      </div>
    </div>,
    document.body,
  );
}

export function GenerationRecordsPanel({ onClose, present = true }: { onClose: () => void; present?: boolean }) {
  const records = useCanvasStore((state) => state.generationRecords);
  const nodes = useCanvasStore((state) => state.nodes);
  const rootRef = useRef<HTMLElement>(null);
  const [status, setStatus] = useState('all');
  const [kind, setKind] = useState('all');
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [previewTarget, setPreviewTarget] = useState<ImagePreviewTarget | null>(null);

  const filtered = useMemo(() => [...records]
    .filter((record) => status === 'all' || record.status === status)
    .filter((record) => kind === 'all' || recordKind(record) === kind)
    .sort((a, b) => b.startedAt - a.startedAt), [kind, records, status]);
  // 详情从全量记录里找，不跟着筛选走：弹窗开着时改筛选不应该把详情抽空。
  const selected = records.find((record) => record.id === selectedId) ?? null;
  const nodeLabel = (id: string) => nodes.find((node) => node.id === id)?.data.label ?? (id ? id.slice(0, 12) : '未关联节点');

  useEffect(() => {
    if (selectedId && !records.some((record) => record.id === selectedId)) setSelectedId(null);
  }, [records, selectedId]);

  /**
   * 点抽屉以外的任何地方都收起它——包括右上角并排的「任务队列」：
   * 两个入口共用一条工具带，开着溯源的抽屉再点任务队列时必须让位。
   * 例外：右上角「生成记录」入口自己负责开/关（否则 pointerdown 先关、click 又开，永远关不掉）；
   * 详情弹窗与图片全屏预览都 portal 到 body，属于抽屉内部，不能被当成「外面」。
   */
  useEffect(() => {
    const dismiss = (event: PointerEvent) => {
      const target = event.target;
      if (!(target instanceof Element)) return;
      if (rootRef.current?.contains(target)) return;
      if (target.closest('.generation-record-modal, .generation-records-trigger, .record-image-preview')) return;
      onClose();
    };
    document.addEventListener('pointerdown', dismiss, true);
    return () => document.removeEventListener('pointerdown', dismiss, true);
  }, [onClose]);

  return (
    <>
      <aside ref={rootRef} className="floating-drawer generation-records-panel motion-presence" data-present={present}>
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
            const active = record.status === 'queued'
              || record.status === 'running'
              || record.materializationStatus === 'materializing';
            const progress = generationRecordProgress(record, nodes);
            return (
              <button
                key={record.id}
                type="button"
                className={`generation-record-row ${selectedId === record.id ? 'is-active' : ''}`}
                aria-haspopup="dialog"
                onClick={() => setSelectedId(record.id)}
              >
                <RecordThumb record={record} nodes={nodes} />
                <span className="generation-record-row__body">
                  <span className="generation-record-row__head">
                    <strong>{recordTitle(record)}</strong>
                    <span className={`record-status is-${meta.tone}`}>
                      <StatusIcon size={11} className={record.status === 'running' ? 'is-spinning' : ''} />{meta.label}
                    </span>
                  </span>
                  <span className="generation-record-row__prompt">{promptText(record)}</span>
                  {active && (
                    <span
                      className="generation-record-progress"
                      role="progressbar"
                      aria-label="生成进度"
                      aria-valuemin={0}
                      aria-valuemax={100}
                      aria-valuenow={Math.round(progress * 100)}
                    >
                      <i><em style={{ width: `${Math.max(3, progress * 100)}%` }} /></i>
                      <span>{Math.round(progress * 100)}%</span>
                    </span>
                  )}
                  <span className="generation-record-row__meta">
                    <span>{formatTime(record.startedAt)}</span>
                    <span>{nodeLabel(record.producerNodeId)}</span>
                    <span>{record.model || '默认模型'}</span>
                    <span>{results.length ? `${results.length} 个结果` : record.materializationStatus}</span>
                  </span>
                </span>
                <ChevronRight className="generation-record-row__chevron" size={15} aria-hidden="true" />
              </button>
            );
          })}
        </div>
      </aside>
      {selected && (
        <GenerationRecordDialog
          key={selected.id}
          record={selected}
          title={recordTitle(selected)}
          nodes={nodes}
          nodeLabel={nodeLabel}
          onClose={() => setSelectedId(null)}
          onPreview={setPreviewTarget}
        />
      )}
      {previewTarget && <ImagePreviewOverlay target={previewTarget} onClose={() => setPreviewTarget(null)} />}
    </>
  );
}
