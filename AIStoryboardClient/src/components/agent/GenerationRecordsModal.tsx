import { useEffect, useState } from 'react';
import { DS } from './ChatComposer';
import { assetUrl } from '../../config';
import { ImagePreviewModal } from './ImagePreviewModal';
import {
  parseReferenceUrls,
  parseResultUrls,
  parseParams,
  useGenerationRecordsStore,
  type GenerationFilters,
} from '../../stores/generationRecordsStore';
import type { GenerationRecord } from '../../api/agent';
import {
  X, History, Download, ArrowRight, Copy, RefreshCw, RotateCcw, ChevronLeft,
  Calendar, Clock, AlertTriangle, Image as ImageIcon, Film, Eye,
} from 'lucide-react';

/** 生成类型标签映射 */
const GEN_TYPE_LABEL: Record<string, string> = {
  text_to_image: '文生图',
  image_to_image: '图生图',
  text_to_video: '文生视频',
  ref_to_video: '参考生视频',
  multi_image_to_image: '多图生图',
  first_frame_to_video: '首帧生视频',
  first_last_frame_to_video: '首尾帧生视频',
  storyboard_video: '分镜视频',
  storyboard_image: '分镜图片',
};

const GEN_TYPE_OPTIONS = [
  { value: '', label: '全类型' },
  ...Object.entries(GEN_TYPE_LABEL).map(([value, label]) => ({ value, label })),
];

const STATUS_META: Record<string, { label: string; color: string; bg: string }> = {
  completed: { label: '成功', color: '#16a34a', bg: 'rgba(22,163,74,0.1)' },
  succeeded: { label: '成功', color: '#16a34a', bg: 'rgba(22,163,74,0.1)' },
  failed: { label: '失败', color: '#d92d20', bg: 'rgba(217,45,32,0.1)' },
};

function genTypeLabel(t?: string | null): string {
  return (t && GEN_TYPE_LABEL[t]) || (t === 'video' ? '视频' : t === 'image' ? '图片' : '其他');
}

function statusMeta(status: string | null) {
  if (!status) return { label: '未知', color: '#6b7280', bg: 'rgba(107,114,128,0.1)' };
  if (STATUS_META[status]) return STATUS_META[status];
  if (['queued', 'generating', 'running'].includes(status)) {
    return { label: '生成中', color: '#1d4ed8', bg: 'rgba(29,78,216,0.1)' };
  }
  return { label: status, color: '#6b7280', bg: 'rgba(107,114,128,0.1)' };
}

function formatDate(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const p = (n: number) => String(n).padStart(2, '0');
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

function formatDuration(ms: number | null | undefined): string {
  if (ms == null) return '—';
  const sec = Math.max(0, Math.round(ms / 1000));
  if (sec < 60) return `${sec} 秒`;
  return `${Math.floor(sec / 60)} 分 ${sec % 60} 秒`;
}

function resultUrls(record: GenerationRecord): string[] {
  const parsed = parseResultUrls(record.resultUrls);
  if (parsed.length) return parsed;
  return record.url ? [record.url] : [];
}

function refUrls(record: GenerationRecord) {
  return parseReferenceUrls(record.referenceUrls);
}

function isRefBased(record: GenerationRecord): boolean {
  return ['image_to_image', 'ref_to_video', 'multi_image_to_image', 'first_frame_to_video', 'first_last_frame_to_video'].includes(record.genType ?? '');
}

/** 分镜队列来源的生成记录：来自 storyboard_video_queue / storyboard_image_task，重试会二次入队，隐藏「重试」。 */
function isStoryboardQueueRecord(record: GenerationRecord): boolean {
  return record.genType === 'storyboard_video' || record.genType === 'storyboard_image';
}

function MediaThumb({ url, type, onClick }: { url: string; type: 'image' | 'video'; onClick?: () => void }) {
  const src = assetUrl(url);
  const common: React.CSSProperties = {
    width: '100%', height: '100%', objectFit: 'cover', display: 'block',
  };
  return (
    <div
      onClick={onClick}
      style={{
        position: 'relative', width: '100%', height: '100%',
        background: 'var(--color-surface-soft)', borderRadius: 8, overflow: 'hidden',
        cursor: onClick ? 'zoom-in' : 'default',
      }}
    >
      {type === 'video' ? (
        <video src={src} style={common} muted preload="metadata" />
      ) : (
        <img src={src} alt="" style={common} loading="lazy" />
      )}
    </div>
  );
}

function RefArrow({ label }: { label?: string }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexShrink: 0 }}>
      <span style={{ fontSize: 11, color: 'var(--color-muted)', whiteSpace: 'nowrap' }}>{label ?? ''}</span>
      <ArrowRight size={14} strokeWidth={2} color={DS.brand} />
    </div>
  );
}

export function GenerationRecordsModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const {
    filters, setFilters, resetFilters, page, size, total, records, loading, error,
    activeId, detail, detailLoading, loadRecords, goTo, openDetail, closeDetail,
    refetch, retry,
  } = useGenerationRecordsStore();
  const [preview, setPreview] = useState<{ url: string; type: 'image' | 'video' } | null>(null);
  const [refetchTried, setRefetchTried] = useState(false);

  useEffect(() => {
    if (open) void loadRecords();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, filters, page]);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      window.removeEventListener('keydown', onKey);
      document.body.style.overflow = prev;
    };
  }, [open, onClose]);

  useEffect(() => {
    setRefetchTried(false);
  }, [activeId]);

  const totalPages = Math.max(1, Math.ceil(total / size));
  const handleRefetch = async (id: string) => {
    await refetch(id);
    setRefetchTried(true);
  };

  if (!open) return null;

  const showDetail = Boolean(activeId && detail);

  return (
    <>
      <div
        onClick={onClose}
        style={{
          position: 'fixed', inset: 0, zIndex: 400,
          background: 'rgba(20,20,19,0.48)',
          display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 16,
        }}
      >
        <div
          onClick={(e) => e.stopPropagation()}
          style={{
            width: 'min(1080px, 96vw)', maxWidth: '96vw', height: 'min(84vh, 780px)',
            maxHeight: '90vh', background: 'white', borderRadius: 16,
            boxShadow: '0 16px 64px rgba(0,0,0,0.28)', display: 'flex', flexDirection: 'column',
            overflow: 'hidden',
          }}
        >
          {/* 头部 */}
          <div style={{
            display: 'flex', justifyContent: 'space-between', alignItems: 'center',
            padding: '16px 20px 12px', borderBottom: '1px solid var(--color-hairline)', flexShrink: 0,
          }}>
            <span style={{ fontSize: 18, fontWeight: 700, color: DS.ink, display: 'inline-flex', alignItems: 'center', gap: 8 }}>
              <History size={18} strokeWidth={2} /> 生成记录{total > 0 ? `（${total}）` : ''}
            </span>
            <button
              onClick={onClose}
              aria-label="关闭生成记录"
              style={{
                width: 34, height: 34, border: 'none', borderRadius: '50%',
                background: 'var(--color-surface-soft)', color: 'var(--color-muted)',
                cursor: 'pointer', fontSize: 16, display: 'flex', alignItems: 'center', justifyContent: 'center',
              }}
            ><X size={18} strokeWidth={2} /></button>
          </div>

          {/* 筛选栏 */}
          <div style={{
            display: 'flex', flexWrap: 'wrap', gap: 8, alignItems: 'center',
            padding: '10px 20px', borderBottom: '1px solid var(--color-hairline)', flexShrink: 0,
          }}>
            <label style={filterLabel}>类型</label>
            <select
              value={filters.genType}
              onChange={(e) => setFilters({ genType: e.target.value })}
              style={selectStyle}
            >
              {GEN_TYPE_OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
            </select>

            <label style={filterLabel}>状态</label>
            <div style={{ display: 'flex', gap: 4 }}>
              {(['all', 'success', 'failed'] as GenerationFilters['status'][]).map((s) => (
                <button
                  key={s}
                  onClick={() => setFilters({ status: s })}
                  style={chipStyle(filters.status === s)}
                >{s === 'all' ? '全部' : s === 'success' ? '成功' : '失败'}</button>
              ))}
            </div>

            <label style={filterLabel}><Calendar size={14} strokeWidth={2} style={{ verticalAlign: '-2px' }} /> 日期</label>
            <input
              type="date" value={filters.from}
              onChange={(e) => setFilters({ from: e.target.value })} style={dateStyle} aria-label="开始日期"
            />
            <span style={{ color: 'var(--color-muted)', fontSize: 12 }}>至</span>
            <input
              type="date" value={filters.to}
              onChange={(e) => setFilters({ to: e.target.value })} style={dateStyle} aria-label="结束日期"
            />

            <button
              onClick={resetFilters}
              style={{
                marginLeft: 'auto', border: '1px solid var(--color-hairline)', background: 'white',
                borderRadius: 8, padding: '5px 12px', fontSize: 13, color: DS.textSecondary, cursor: 'pointer',
              }}
            >重置</button>
          </div>

          {/* 主体 */}
          <div style={{ flex: 1, minHeight: 0, display: 'flex', overflow: 'hidden' }}>
            {/* 列表 */}
            <div style={{
              flex: showDetail ? 0.38 : 1, minWidth: 0,
              borderRight: showDetail ? '1px solid var(--color-hairline)' : 'none',
              overflowY: 'auto', padding: 14,
            }}>
              {loading && records.length === 0 && <Loading />}
              {!loading && records.length === 0 && (
                <Empty hasError={Boolean(error)} message={error} onRetry={loadRecords} />
              )}
              {records.length > 0 && (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                  {records.map((r) => (
                    <RecordCard
                      key={r.id}
                      record={r}
                      active={r.id === activeId}
                      onOpenDetail={() => void openDetail(r.id)}
                      onPreviewMedia={(url, type) => setPreview({ url, type })}
                    />
                  ))}
                </div>
              )}
            </div>

            {/* 详情 */}
            {showDetail && detail && (
              <div style={{ flex: 0.62, minWidth: 0, overflowY: 'auto', padding: 16 }}>
                <RecordDetail
                  record={detail}
                  detailLoading={detailLoading}
                  refetchTried={refetchTried}
                  onBack={() => closeDetail()}
                  onRefetch={() => void handleRefetch(detail.id)}
                  onRetry={() => void retry(detail.id)}
                  onPreviewMedia={(url, type) => setPreview({ url, type })}
                />
              </div>
            )}
          </div>

          {/* 分页 */}
          {totalPages > 1 && (
            <div style={{
              display: 'flex', justifyContent: 'center', gap: 8, padding: '8px 0 12px',
              borderTop: '1px solid var(--color-hairline)', flexShrink: 0,
            }}>
              <button disabled={page <= 1} onClick={() => void goTo(page - 1)} style={pageBtn}>上一页</button>
              <span style={{ fontSize: 13, color: 'var(--color-muted)', alignSelf: 'center' }}>{page} / {totalPages}</span>
              <button disabled={page >= totalPages} onClick={() => void goTo(page + 1)} style={pageBtn}>下一页</button>
            </div>
          )}
        </div>
      </div>
      <ImagePreviewModal url={preview?.url ?? null} type={preview?.type ?? 'image'} onClose={() => setPreview(null)} />
    </>
  );
}

function RecordCard({
  record, active, onOpenDetail, onPreviewMedia,
}: {
  record: GenerationRecord;
  active: boolean;
  onOpenDetail: () => void;
  onPreviewMedia: (url: string, type: 'image' | 'video') => void;
}) {
  const results = resultUrls(record);
  const refs = refUrls(record);
  const refBased = isRefBased(record);
  const sm = statusMeta(record.status);
  const mediaType = record.type === 'video' ? 'video' : 'image';
  const primary = results[0];
  const firstRef = refs[0]?.url;

  return (
    <div style={{
      border: `1px solid ${active ? DS.brand : 'var(--color-hairline)'}`,
      borderRadius: 12, padding: 10, background: active ? 'rgba(29,78,216,0.04)' : 'white',
      display: 'flex', gap: 10, cursor: 'pointer', transition: 'border-color 0.15s',
    }}>
      {/* 缩略图区 */}
      <div style={{ flexShrink: 0, width: 96, height: 72, display: 'flex', alignItems: 'center', gap: 4 }}>
        {refBased && firstRef ? (
          <>
            <div style={{ width: 48, height: 72 }}>
              <MediaThumb url={firstRef} type={refs[0]?.type === 'video' ? 'video' : 'image'} onClick={() => onPreviewMedia(firstRef, refs[0]?.type === 'video' ? 'video' : 'image')} />
            </div>
            <div style={{ width: 18, height: 72, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
              <ArrowRight size={12} strokeWidth={2} color={DS.brand} />
            </div>
            <div style={{ width: 48, height: 72 }}>
              {primary ? (
                <MediaThumb url={primary} type={mediaType} onClick={() => onPreviewMedia(primary, mediaType)} />
              ) : <div style={thumbPlaceholder} />}
            </div>
          </>
        ) : (
          <div style={{ width: 96, height: 72 }}>
            {primary ? (
              <MediaThumb url={primary} type={mediaType} onClick={() => onPreviewMedia(primary, mediaType)} />
            ) : <div style={thumbPlaceholder}>{record.status === 'failed' ? '失败' : '生成中'}</div>}
          </div>
        )}
      </div>

      {/* 信息区 */}
      <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', gap: 3 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
          <span style={{ fontSize: 12, fontWeight: 600, color: DS.ink, display: 'inline-flex', alignItems: 'center', gap: 4 }}>
            {record.type === 'video' ? <Film size={12} strokeWidth={2} /> : <ImageIcon size={12} strokeWidth={2} />}
            {genTypeLabel(record.genType)}
          </span>
          {record.model && <Tag>{record.model}</Tag>}
          <span style={statusChip(sm.color, sm.bg)}>{sm.label}</span>
          {refs.length > 1 && <Tag>≤{refs.length} 个参考</Tag>}
        </div>
        <span style={{ fontSize: 12, color: 'var(--color-muted)' }}>
          <Clock size={12} strokeWidth={2} style={{ verticalAlign: '-2px', marginRight: 3 }} />
          {formatDate(record.createdAt)}
          {record.durationMs != null && ` · ${formatDuration(record.durationMs)}`}
        </span>
        <p style={{
          fontSize: 12, color: 'var(--color-muted-soft)', margin: 0, lineHeight: 1.4,
          overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
        }}>
          {record.prompt?.trim() || '—'}
        </p>
        <div style={{ display: 'flex', gap: 8, marginTop: 2 }}>
          <button onClick={(e) => { e.stopPropagation(); onOpenDetail(); }} style={actionBtn(DS.brand)}>
            <Eye size={12} strokeWidth={2} /> 详情
          </button>
          {primary && (
            <a
              href={assetUrl(primary)} download target="_blank" rel="noreferrer"
              onClick={(e) => e.stopPropagation()}
              style={actionBtn(DS.textSecondary)}
            ><Download size={12} strokeWidth={2} /> 下载</a>
          )}
        </div>
      </div>
    </div>
  );
}

function RecordDetail({
  record, detailLoading, refetchTried, onBack, onRefetch, onRetry, onPreviewMedia,
}: {
  record: GenerationRecord | null;
  detailLoading: boolean;
  refetchTried: boolean;
  onBack: () => void;
  onRefetch: () => void;
  onRetry: () => void;
  onPreviewMedia: (url: string, type: 'image' | 'video') => void;
}) {
  if (!record || detailLoading) return <Loading />;
  const results = resultUrls(record);
  const refs = refUrls(record);
  const refBased = isRefBased(record);
  const sm = statusMeta(record.status);
  const mediaType = record.type === 'video' ? 'video' : 'image';
  const params = parseParams(record.params);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      {/* 返回 */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <button onClick={onBack} style={actionBtn(DS.textSecondary)}><ChevronLeft size={14} strokeWidth={2} /> 返回列表</button>
      </div>

      {/* 标题 */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8, flexWrap: 'wrap' }}>
        <h2 style={{ fontSize: 18, fontWeight: 700, color: DS.ink, margin: 0 }}>
          {genTypeLabel(record.genType)} · {record.model || '未知模型'}
        </h2>
        <span style={statusChip(sm.color, sm.bg)}>{sm.label}</span>
      </div>

      {/* 状态/耗时/日期 */}
      <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap', fontSize: 13, color: 'var(--color-muted)' }}>
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
          <Clock size={14} strokeWidth={2} /> 耗时 {formatDuration(record.durationMs)}
        </span>
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
          <Calendar size={14} strokeWidth={2} /> {formatDate(record.createdAt)}
        </span>
        {record.sceneId && <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>场景 {record.sceneId.slice(0, 8)}</span>}
      </div>

      {/* 生成状况 */}
      <GenContent
        refs={refs} results={results} refBased={refBased} mediaType={mediaType}
        onPreviewMedia={onPreviewMedia}
      />

      {/* 提示词 */}
      <PromptBlock prompt={record.prompt} />

      {/* 参数 */}
      <ParamsBlock params={params} />

      {/* 失败原因 / 操作 */}
      {record.status === 'failed' && (
        <div style={{
          borderRadius: 10, padding: 12, background: 'rgba(217,45,32,0.06)',
          border: '1px solid rgba(217,45,32,0.16)', display: 'flex', flexDirection: 'column', gap: 8,
        }}>
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, color: '#d92d20', fontSize: 13, fontWeight: 600 }}>
            <AlertTriangle size={15} strokeWidth={2} /> 生成失败
          </span>
          {record.error && <p style={{ margin: 0, fontSize: 12, color: 'var(--color-muted)', wordBreak: 'break-word' }}>{record.error}</p>}
          <div style={{ display: 'flex', gap: 8, marginTop: 2 }}>
            {record.type === 'video' && record.taskId && !refetchTried && (
              <button onClick={onRefetch} style={actionBtn(DS.brand)}>
                <RefreshCw size={13} strokeWidth={2} /> 重新拉取
              </button>
            )}
            {!isStoryboardQueueRecord(record) && (record.type !== 'video' || !record.taskId || refetchTried) && (
              <button onClick={onRetry} style={actionBtn('#d92d20')}>
                <RotateCcw size={13} strokeWidth={2} /> 重试
              </button>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

function GenContent({
  refs, results, refBased, mediaType, onPreviewMedia,
}: {
  refs: { type: string; url: string | null; role?: string }[];
  results: string[];
  refBased: boolean;
  mediaType: 'image' | 'video';
  onPreviewMedia: (url: string, type: 'image' | 'video') => void;
}) {
  return (
    <div style={{
      border: '1px solid var(--color-hairline)', borderRadius: 12, padding: 12,
      display: 'flex', flexDirection: 'column', gap: 8,
    }}>
      <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--color-muted)' }}>生成状况</span>
      {refBased && refs.length > 0 ? (
        <div style={{ display: 'flex', gap: 12, alignItems: 'flex-start', flexWrap: 'wrap' }}>
          <MediaStrip
            title={`参考素材（${refs.length}）`}
            urls={refs.map((r) => ({ url: r.url ?? '', type: r.type }))}
            onPreviewMedia={onPreviewMedia}
          />
          <div style={{ alignSelf: 'center' }}><RefArrow /></div>
          <MediaStrip
            title={`生成结果（${results.length}）`}
            urls={results.map((u) => ({ url: u, type: mediaType }))}
            onPreviewMedia={onPreviewMedia}
          />
        </div>
      ) : (
        <MediaStrip
          title={`生成结果（${results.length}）`}
          urls={results.map((u) => ({ url: u, type: mediaType }))}
          onPreviewMedia={onPreviewMedia}
        />
      )}
      {results.length === 0 && refs.length === 0 && (
        <span style={{ fontSize: 13, color: 'var(--color-muted)' }}>暂无结果（生成中或失败）</span>
      )}
    </div>
  );
}

function MediaStrip({
  title, urls, onPreviewMedia,
}: {
  title: string;
  urls: { url: string; type: string }[];
  onPreviewMedia: (url: string, type: 'image' | 'video') => void;
}) {
  const shown = urls.filter((u) => u.url).slice(0, 8);
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 6, minWidth: 0 }}>
      <span style={{ fontSize: 12, color: 'var(--color-muted)' }}>{title}</span>
      <div style={{ display: 'flex', gap: 8, overflowX: 'auto', maxWidth: 460, paddingBottom: 2 }}>
        {shown.map((u, i) => (
          <div key={u.url + i} style={{ width: 86, height: 86, flexShrink: 0 }}>
            <MediaThumb
              url={u.url}
              type={u.type === 'video' ? 'video' : 'image'}
              onClick={() => onPreviewMedia(u.url, u.type === 'video' ? 'video' : 'image')}
            />
          </div>
        ))}
        {urls.length > shown.length && (
          <div style={{ width: 86, height: 86, flexShrink: 0, borderRadius: 8, background: 'var(--color-surface-soft)', display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'var(--color-muted)', fontSize: 13 }}>+{urls.length - shown.length}</div>
        )}
      </div>
    </div>
  );
}

function PromptBlock({ prompt }: { prompt: string | null }) {
  const [copied, setCopied] = useState(false);
  if (!prompt?.trim()) {
    return <EmptyBlock label="提示词" />;
  }
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(prompt);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch { /* ignore */ }
  };
  return (
    <div style={{ border: '1px solid var(--color-hairline)', borderRadius: 12, padding: 12 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 6 }}>
        <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--color-muted)' }}>提示词</span>
        <button onClick={copy} style={actionBtn(DS.textSecondary)}>
          <Copy size={13} strokeWidth={2} /> {copied ? '已复制' : '复制'}
        </button>
      </div>
      <p style={{ margin: 0, fontSize: 13, color: DS.ink, lineHeight: 1.6, whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{prompt}</p>
    </div>
  );
}

function ParamsBlock({ params }: { params: Record<string, unknown> }) {
  const entries = Object.entries(params).filter(([, v]) => v !== null && v !== undefined && v !== '');
  if (entries.length === 0) return <EmptyBlock label="参数" />;
  const labels: Record<string, string> = {
    size: '尺寸', quality: '质量', n: '数量', mode: '模式', resolution: '分辨率',
    aspectRatio: '画幅', duration: '时长', negativePrompt: '负向提示词', model: '模型',
  };
  return (
    <div style={{ border: '1px solid var(--color-hairline)', borderRadius: 12, padding: 12 }}>
      <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--color-muted)' }}>参数</span>
      <div style={{ marginTop: 6, display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(160px, 1fr))', gap: 8 }}>
        {entries.map(([k, v]) => (
          <div key={k} style={{
            background: 'var(--color-surface-soft)', borderRadius: 8, padding: '6px 10px',
            display: 'flex', flexDirection: 'column', gap: 2,
          }}>
            <span style={{ fontSize: 11, color: 'var(--color-muted)' }}>{labels[k] || k}</span>
            <span style={{ fontSize: 13, color: DS.ink, wordBreak: 'break-word' }}>{String(v)}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

function EmptyBlock({ label }: { label: string }) {
  return (
    <div style={{ border: '1px solid var(--color-hairline)', borderRadius: 12, padding: 12, display: 'flex', alignItems: 'center', gap: 8 }}>
      <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--color-muted)' }}>{label}</span>
      <span style={{ fontSize: 12, color: 'var(--color-muted-soft)' }}>暂无</span>
    </div>
  );
}

function Loading() {
  return <div style={{ padding: 40, textAlign: 'center', color: 'var(--color-muted)', fontSize: 14 }}>加载中…</div>;
}

function Empty({ hasError, message, onRetry }: { hasError: boolean; message: string | null; onRetry: () => void }) {
  return (
    <div style={{ padding: 48, textAlign: 'center', color: 'var(--color-muted)', fontSize: 14 }}>
      {hasError ? (
        <>
          <p style={{ margin: '0 0 10px' }}>{message || '加载失败'}</p>
          <button onClick={onRetry} style={actionBtn(DS.brand)}><RefreshCw size={13} strokeWidth={2} /> 重试</button>
        </>
      ) : '暂无生成记录'}
    </div>
  );
}

function Tag({ children }: { children: React.ReactNode }) {
  return (
    <span style={{
      fontSize: 11, padding: '1px 7px', borderRadius: 6, border: '1px solid var(--color-hairline)',
      color: 'var(--color-muted)', background: 'white', whiteSpace: 'nowrap',
    }}>{children}</span>
  );
}

function statusChip(color: string, bg: string): React.CSSProperties {
  return {
    fontSize: 11, padding: '1px 7px', borderRadius: 6, color, background: bg,
    fontWeight: 600, whiteSpace: 'nowrap',
  };
}

function chipStyle(active: boolean): React.CSSProperties {
  return {
    border: `1px solid ${active ? DS.brand : 'var(--color-hairline)'}`,
    background: active ? DS.brand : 'white', color: active ? 'white' : DS.textSecondary,
    borderRadius: 8, padding: '5px 10px', fontSize: 13, cursor: 'pointer',
  };
}

function actionBtn(color: string): React.CSSProperties {
  return {
    border: `1px solid ${color}`, background: 'transparent', color, borderRadius: 8,
    padding: '4px 10px', fontSize: 12, cursor: 'pointer', display: 'inline-flex',
    alignItems: 'center', gap: 4, textDecoration: 'none',
  };
}

const filterLabel: React.CSSProperties = {
  fontSize: 12, color: 'var(--color-muted)', display: 'inline-flex', alignItems: 'center', gap: 4,
};

const selectStyle: React.CSSProperties = {
  border: '1px solid var(--color-hairline)', borderRadius: 8, background: 'white',
  padding: '5px 8px', fontSize: 13, color: DS.ink, cursor: 'pointer',
};

const dateStyle: React.CSSProperties = {
  border: '1px solid var(--color-hairline)', borderRadius: 8, background: 'white',
  padding: '5px 8px', fontSize: 13, color: DS.ink,
};

const pageBtn: React.CSSProperties = {
  fontSize: 13, border: '1px solid var(--color-hairline)', borderRadius: 6,
  padding: '4px 12px', background: 'white', cursor: 'pointer',
  color: 'var(--color-ink)',
};

const thumbPlaceholder: React.CSSProperties = {
  width: '100%', height: '100%', borderRadius: 8, background: 'var(--color-surface-soft)',
  display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'var(--color-muted)', fontSize: 12,
};
