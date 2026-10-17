import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { useLocation } from 'react-router-dom';
import {
  Calendar, Check, ChevronLeft, ChevronRight, Clapperboard, Clock, Copy, Download, Ellipsis,
  Eraser, Expand, Frame, ImagePlus, LoaderCircle, Orbit, Paintbrush, Play, RotateCcw,
  Sparkles, Trash2, Wand2,
} from 'lucide-react';
import type { AgentAsset } from '../../api/agent';
import { agentApi } from '../../api/agent';
import { assetUrl } from '../../config';
import { useAgentStore } from '../../stores/agentStore';
import type { AssetDetailTarget } from '../../stores/assetDetailStore';
import { useProjectStore } from '../../stores/projectStore';
import { notify } from '../../stores/toastStore';
import { Tooltip } from '../common/Tooltip';
import { ConfirmDialog } from '../common/ConfirmDialog';
import { ContextMenuSurface, type ContextMenuItem } from '../common/ContextMenu';
import { resolveConversationAssetByUrl, sameMediaObject } from '../../lib/assetLookup';
import { copyMediaAsset, openInCanvas, quoteAsReference } from '../../lib/chatMediaActions';
import { UNIMPLEMENTED_MEDIA_ACTIONS } from '../../lib/mediaActionBar';
import { downloadMedia } from '../../lib/mediaDownload';
import { findProjectLibraryImport, importMediaToProjectLibrary, type ProjectLibraryProducer } from '../../lib/projectLibraryImport';
import {
  extractVideoFrame,
  frameFileName,
  frameLabel,
  frameMessageContent,
  probeVideoDurationSeconds,
  type VideoFramePosition,
} from '../../lib/videoFrameExtract';
import { ImagePreviewModal } from './ImagePreviewModal';
import { assetUrls, fileNameFromAssetUrl } from './assetBrowserModel';
import {
  assetDetailTitle,
  assetMediaKind,
  buildAssetMetaEntries,
  buildAssetParamEntries,
  formatAssetTime,
} from './assetDetailModel';
import './AssetDetailPane.css';

export interface AssetDetailPaneProps {
  target: AssetDetailTarget;
  onClose: () => void;
  /** 删除成功后由调用方刷新列表。 */
  onDeleted: () => void;
}

/** 导入状态的机读值：unknown = 还在查项目库里有没有这张。 */
type ImportState = 'unknown' | 'idle' | 'importing' | 'done';

interface DetailTool {
  label: string;
  icon: React.ReactNode;
  /** 已接通的动作。没有 run 的按钮按「暂未开放」处理，点击只给一句提示。 */
  run?: () => void;
  /** 主色实心按钮：一排里只允许一个。 */
  primary?: boolean;
}

/**
 * 尚未接通的能力直接读 `mediaActionBar` 的那张表：那边接通一个，这里就自动少一个，
 * 不会出现「聊天底栏已经能用、详情里还说暂未开放」的分叉。
 */
const UNIMPLEMENTED_TOOL_LABELS = [...UNIMPLEMENTED_MEDIA_ACTIONS];

const TOOL_ICONS: Record<string, React.ReactNode> = {
  智能改图: <Wand2 size={15} strokeWidth={1.8} />,
  智能超清: <Sparkles size={15} strokeWidth={1.8} />,
  局部重绘: <Paintbrush size={15} strokeWidth={1.8} />,
  消除笔: <Eraser size={15} strokeWidth={1.8} />,
  扩图: <Expand size={15} strokeWidth={1.8} />,
  多角度: <Orbit size={15} strokeWidth={1.8} />,
};

/**
 * 会话资产详情弹窗：左边是这张图/视频的舞台，右边是它的来历和后续动作。
 *
 * 原先这些信息散在三处：生成记录弹窗看提示词和参数、聊天气泡底栏改图、
 * 素材缩略图悬停看大图。详情把它们收进一个居中弹窗，点开一张素材就能答完
 * 「怎么生成的」和「接下来能做什么」。收藏按钮刻意不做：会话资产没有收藏位，
 * 多一个只会让人以为收藏之后会出现在别处。
 *
 * 做成弹窗而不是侧边栏里的一列：素材要看得清，需要一块和侧边栏无关的横向空间；
 * 挤在 380px 的侧边栏里，舞台和参数两边都只剩一条缝。
 */
export function AssetDetailPane({ target, onClose, onDeleted }: AssetDetailPaneProps) {
  const projectId = useProjectStore((state) => state.currentProject?.id);
  const activeConversationId = useAgentStore((state) => state.activeConversationId);
  const { pathname } = useLocation();
  // 回写来源只影响项目库里的出处标记与幂等键；编辑器里导入的记成编辑器，别一律算聊天的。
  const producer: ProjectLibraryProducer = pathname.startsWith('/editor')
    ? 'storyboard-editor'
    : 'moon-chat';
  const { url } = target;
  /** 生成记录：调用方给了就用，没给（聊天里点开）就去会话资产里按 URL 反查一次。 */
  const [record, setRecord] = useState<AgentAsset | null>(target.asset ?? null);
  const [resolving, setResolving] = useState(!target.asset);
  const [activeUrl, setActiveUrl] = useState(url);
  const [lightbox, setLightbox] = useState(false);
  const [menuAnchor, setMenuAnchor] = useState<{ x: number; y: number } | null>(null);
  const [pendingDelete, setPendingDelete] = useState(false);
  const [pendingRegenerate, setPendingRegenerate] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [importState, setImportState] = useState<ImportState>('unknown');
  const [frameBusy, setFrameBusy] = useState(false);
  const [durationState, setDurationState] = useState<'loading' | 'ready' | 'failed'>('loading');
  const [durationSeconds, setDurationSeconds] = useState(0);
  const [customSeconds, setCustomSeconds] = useState(0);
  const moreRef = useRef<HTMLButtonElement>(null);
  const dialogRef = useRef<HTMLDivElement>(null);

  // 打开时把焦点收进弹窗、锁住背景滚动；关闭后把焦点还给点开它的那个元素
  // （网格格子、聊天里的图片都在，键盘用户按 Esc 关掉后应该留在原处）。
  useEffect(() => {
    const trigger = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    dialogRef.current?.focus();
    return () => {
      document.body.style.overflow = previousOverflow;
      if (trigger?.isConnected) trigger.focus();
    };
  }, []);

  // 调用方没给记录（聊天里的图片/视频）时回头查一次会话资产：提示词、模型、参数
  // 都只落在记录上，查不到就退回「只有 URL」的降级详情。
  useEffect(() => {
    if (target.asset) {
      setRecord(target.asset);
      setResolving(false);
      return undefined;
    }
    let cancelled = false;
    setRecord(null);
    setResolving(true);
    void resolveConversationAssetByUrl(activeConversationId, url).then((found) => {
      if (cancelled) return;
      setRecord(found);
      setResolving(false);
    });
    return () => { cancelled = true; };
  }, [activeConversationId, target.asset, url]);

  // 换一条素材就回到它自己的第一张结果，别把上一条的选中态带过来。
  useEffect(() => {
    setActiveUrl(url);
  }, [url, record?.id]);

  const results = useMemo(() => {
    if (record) {
      const fromRecord = assetUrls(record);
      if (fromRecord.length > 0) return fromRecord;
    }
    const fromTarget = (target.urls ?? []).filter(Boolean);
    return fromTarget.length > 0 ? fromTarget : [url];
  }, [record, target.urls, url]);

  /**
   * 把选中的那张对齐到记录里的地址。
   *
   * 记录里的 URL 是接口签过名的（`?e=…&s=…`），聊天里点进来的是消息正文里的裸地址，
   * 同一个文件两种字符串。不按对象键对齐的话，缩略图条会一张都不高亮，
   * 而且舞台一直在放未签名的地址（签名会过期，裸地址拿不到签名带来的稳定性）。
   */
  useEffect(() => {
    if (results.includes(activeUrl)) return;
    const matched = results.find((candidate) => sameMediaObject(candidate, activeUrl));
    if (matched) setActiveUrl(matched);
  }, [activeUrl, results]);

  const mediaKind = target.kind
    ?? (assetMediaKind(record?.type ?? null, activeUrl) === 'video' ? 'video' : 'image');
  const prompt = (record?.prompt ?? target.known?.prompt ?? '').trim();
  const paramEntries = useMemo(
    () => (record ? buildAssetParamEntries(record) : []),
    [record],
  );
  const metaEntries = useMemo(() => {
    if (record) return buildAssetMetaEntries(record);
    const createdAt = target.known?.createdAt;
    return createdAt ? [{ key: 'createdAt', label: '生成时间', value: formatAssetTime(createdAt) }] : [];
  }, [record, target.known?.createdAt]);
  const title = record
    ? assetDetailTitle(record, activeUrl)
    : (mediaKind === 'video' ? '视频' : '图片');
  const model = (record?.model ?? target.known?.model ?? '').trim();
  const resultIndex = Math.max(0, results.indexOf(activeUrl));
  /**
   * 空态文案分三种：正在反查、查到了但确实没记、压根没查到记录。
   * 把后两者混成一句会让用户以为「这张图没有提示词」，而真相是没匹配上记录。
   */
  const resolvingCopy = '正在读取生成信息…';
  const noRecordCopy = '没有找到这条素材的生成记录，只能按链接操作';
  const promptEmptyCopy = resolving ? resolvingCopy : (record ? '这条素材没有记录提示词' : noRecordCopy);
  const paramEmptyCopy = resolving ? resolvingCopy : (record ? '这条素材没有记录生成参数' : noRecordCopy);

  // 已经在项目库里的就不要再导一次：库端不按 URL 建索引，只能拿回写时留下的 sourceId 认。
  useEffect(() => {
    let cancelled = false;
    setImportState('unknown');
    if (!projectId) {
      setImportState('idle');
      return () => { cancelled = true; };
    }
    void findProjectLibraryImport({ projectId, url: activeUrl, assetId: record?.id })
      .then((existing) => {
        if (!cancelled) setImportState(existing ? 'done' : 'idle');
      });
    return () => { cancelled = true; };
  }, [projectId, activeUrl, record?.id]);

  // 视频时长只为「自定义帧」服务：拿不到就禁用滑杆，首帧/尾帧照常可用。
  // 失败要显式说出来：一直挂着「正在读取」会让用户以为再等等就能用。
  useEffect(() => {
    if (mediaKind !== 'video') return undefined;
    let cancelled = false;
    setDurationState('loading');
    setDurationSeconds(0);
    setCustomSeconds(0);
    void probeVideoDurationSeconds(assetUrl(activeUrl))
      .then((duration) => {
        if (cancelled) return;
        setDurationSeconds(duration);
        setCustomSeconds(duration / 2);
        setDurationState(duration > 0 ? 'ready' : 'failed');
      })
      .catch(() => {
        if (!cancelled) setDurationState('failed');
      });
    return () => { cancelled = true; };
  }, [activeUrl, mediaKind]);

  const stepResult = useCallback((delta: number) => {
    if (results.length < 2) return;
    const next = (resultIndex + delta + results.length) % results.length;
    setActiveUrl(results[next]);
  }, [resultIndex, results]);

  /**
   * Esc 只退最上面那一层：灯箱 → 确认框 → 详情弹窗。
   *
   * 必须在捕获阶段拦并掐断传播：生成中心的「Esc 关闭侧边栏」挂在 document 冒泡上，
   * 不拦的话按一下 Esc 会连侧边栏一起关掉（弹窗背后的网格也没了），
   * 而用户想关的只是这个弹窗。
   */
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      event.stopPropagation();
      if (lightbox) {
        setLightbox(false);
        return;
      }
      if (pendingDelete) {
        setPendingDelete(false);
        return;
      }
      if (pendingRegenerate) {
        setPendingRegenerate(false);
        return;
      }
      onClose();
    };
    document.addEventListener('keydown', onKeyDown, true);
    return () => document.removeEventListener('keydown', onKeyDown, true);
  }, [lightbox, pendingDelete, pendingRegenerate, onClose]);

  const handleDownload = () => {
    // downloadMedia 会去掉基名自带的后缀再补一个，这里直接给完整文件名即可。
    void downloadMedia(assetUrl(activeUrl), fileNameFromAssetUrl(activeUrl));
  };

  const handleRegenerate = () => {
    const label = mediaKind === 'image' ? '这张图片' : '这个视频';
    const content = mediaKind === 'image'
      ? '重新生成这张图片，保持相同的提示词与风格'
      : '重新生成这个视频，保持相同的提示词与参数';
    const opts = mediaKind === 'image' ? { picUrls: [activeUrl] } : { referenceVideos: [activeUrl] };
    void useAgentStore.getState().sendMessage(content, opts).catch(() => {
      notify({ title: '重新生成失败', description: `${label}重新提交失败，请稍后重试`, variant: 'error' });
    });
  };

  const importToLibrary = async () => {
    if (!projectId || importState === 'done' || importState === 'importing') return;
    setImportState('importing');
    try {
      await importMediaToProjectLibrary({
        projectId,
        producer,
        url: activeUrl,
        mediaType: mediaKind === 'video' ? 'VIDEO' : 'IMAGE',
        label: mediaKind === 'video' ? 'Moon 生成视频' : 'Moon 生成图片',
      });
      setImportState('done');
      notify({ title: '已导入项目库', description: '可以在项目库里继续使用这张素材', variant: 'success' });
    } catch (error) {
      setImportState('idle');
      notify({
        title: '导入失败',
        description: error instanceof Error ? error.message : '请稍后重试',
        variant: 'error',
      });
    }
  };

  const confirmDelete = async () => {
    // 没有记录就没有可删的行（聊天里的外部图片），菜单里也不会给出这一项。
    if (!record) return;
    setDeleting(true);
    try {
      // 一条记录只有这一张时直接删整条，避免留下一条空记录（与右键菜单同一口径）。
      await agentApi.deleteAsset(record.id, results.length > 1 ? resultIndex : undefined);
      notify({ title: '已删除', description: '这张素材已从会话资产移除', variant: 'success' });
      setPendingDelete(false);
      onDeleted();
    } catch (error) {
      notify({
        title: '删除失败',
        description: error instanceof Error ? error.message : '请稍后重试',
        variant: 'error',
      });
    } finally {
      setDeleting(false);
    }
  };

  const runFrameExtract = async (position: VideoFramePosition) => {
    if (frameBusy) return;
    setFrameBusy(true);
    const label = frameLabel(position);
    try {
      const blob = await extractVideoFrame(assetUrl(activeUrl), { position, customSeconds });
      const file = new File([blob], frameFileName(label), { type: 'image/png' });
      const response = await agentApi.uploadImage(
        file,
        useAgentStore.getState().activeConversationId ?? undefined,
        'video-frame',
        'image',
      );
      useAgentStore.getState().addLocalMediaMessage(frameMessageContent(label, response.data.data.url));
      notify({ title: `已提取${label}`, description: '结果已生成到对话中', variant: 'success' });
    } catch (error) {
      notify({
        title: '抽帧失败',
        description: error instanceof Error ? error.message : '请稍后重试',
        variant: 'error',
      });
    } finally {
      setFrameBusy(false);
    }
  };

  const menuItems: ContextMenuItem[] = [
    {
      label: mediaKind === 'image' ? '复制图片' : '复制视频链接',
      icon: <Copy size={14} strokeWidth={1.8} />,
      onClick: () => { void copyMediaAsset(activeUrl, mediaKind); },
    },
    {
      label: mediaKind === 'image' ? '用作参考图' : '用作参考视频',
      icon: mediaKind === 'image'
        ? <ImagePlus size={14} strokeWidth={1.8} />
        : <Clapperboard size={14} strokeWidth={1.8} />,
      onClick: () => quoteAsReference(mediaKind, activeUrl),
    },
    {
      label: importState === 'done'
        ? '已导入项目库'
        : importState === 'importing' ? '正在导入…' : '导入到项目库',
      icon: importState === 'done'
        ? <Check size={14} strokeWidth={1.8} />
        : importState === 'importing'
          ? <LoaderCircle size={14} strokeWidth={1.8} className="is-spinning" />
          : <Download size={14} strokeWidth={1.8} />,
      disabled: importState === 'done' || importState === 'importing' || !projectId,
      onClick: () => { void importToLibrary(); },
    },
    {
      label: '删除',
      icon: <Trash2 size={14} strokeWidth={1.8} />,
      danger: true,
      dividerBefore: true,
      // 删除按的是会话资产记录；聊天里点开的外部图片没有记录，这一项就不给。
      disabled: !record,
      onClick: () => setPendingDelete(true),
    },
  ];

  const tools: DetailTool[] = mediaKind === 'image'
    ? [
      { label: '重新生成', icon: <RotateCcw size={15} strokeWidth={1.8} />, run: handleRegenerate, primary: true },
      ...UNIMPLEMENTED_TOOL_LABELS.map((label) => ({ label, icon: TOOL_ICONS[label] })),
      { label: '去画布编辑', icon: <Frame size={15} strokeWidth={1.8} />, run: () => openInCanvas('image', activeUrl) },
      { label: '用作参考图', icon: <ImagePlus size={15} strokeWidth={1.8} />, run: () => quoteAsReference('image', activeUrl) },
    ]
    : [
      { label: '重新生成', icon: <RotateCcw size={15} strokeWidth={1.8} />, run: () => setPendingRegenerate(true), primary: true },
      { label: '去画布编辑', icon: <Frame size={15} strokeWidth={1.8} />, run: () => openInCanvas('video', activeUrl) },
      { label: '用作参考视频', icon: <Clapperboard size={15} strokeWidth={1.8} />, run: () => quoteAsReference('video', activeUrl) },
    ];

  // 弹窗挂在 body 上：生成中心的侧边栏带 backdrop-filter，会造出 containing block，
  // 留在它里面 position:fixed 会以侧边栏为参照，弹不到屏幕正中。
  return createPortal(
    <div
      className="asset-detail__backdrop"
      onPointerDown={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <div
        ref={dialogRef}
        className="asset-detail"
        role="dialog"
        aria-modal="true"
        aria-label={`${title} 素材详情`}
        tabIndex={-1}
      >
        <div
          className="asset-detail__stage"
          onKeyDown={(event) => {
            if (event.key === 'ArrowLeft') { event.preventDefault(); stepResult(-1); }
            if (event.key === 'ArrowRight') { event.preventDefault(); stepResult(1); }
          }}
        >
          <button type="button" className="asset-detail__back" onClick={onClose}>
            <ChevronLeft size={15} strokeWidth={2} />
            <span>返回</span>
          </button>

          <Tooltip label={mediaKind === 'image' ? '点击看大图' : '点击全屏播放'}>
            <button
              type="button"
              className="asset-detail__media"
              onClick={() => setLightbox(true)}
              aria-label={mediaKind === 'image' ? '放大预览这张图片' : '全屏播放这个视频'}
            >
              {mediaKind === 'video' ? (
                <video src={assetUrl(activeUrl)} muted playsInline preload="metadata" />
              ) : (
                <img src={assetUrl(activeUrl)} alt={prompt || title} draggable={false} />
              )}
            </button>
          </Tooltip>

          {mediaKind === 'video' && (
            <span className="asset-detail__play-badge" aria-hidden="true">
              <Play size={11} fill="currentColor" /> 视频
            </span>
          )}

          {results.length > 1 && (
            <div className="asset-detail__strip" role="tablist" aria-label="这次生成的结果">
              {results.map((resultUrl, index) => (
                <button
                  key={`${resultUrl}-${index}`}
                  type="button"
                  role="tab"
                  aria-selected={resultUrl === activeUrl}
                  aria-label={`第 ${index + 1} 张结果`}
                  className={`asset-detail__thumb${resultUrl === activeUrl ? ' is-active' : ''}`}
                  onClick={() => setActiveUrl(resultUrl)}
                >
                  {assetMediaKind(record?.type ?? null, resultUrl) === 'video'
                    ? <video src={assetUrl(resultUrl)} muted preload="metadata" />
                    : <img src={assetUrl(resultUrl)} alt="" />}
                </button>
              ))}
            </div>
          )}

          {results.length > 1 && (
            <div className="asset-detail__steppers">
              <Tooltip label="上一张">
                <button type="button" aria-label="上一张结果" onClick={() => stepResult(-1)}>
                  <ChevronLeft size={15} strokeWidth={2} />
                </button>
              </Tooltip>
              <span aria-live="polite">{`${resultIndex + 1} / ${results.length}`}</span>
              <Tooltip label="下一张">
                <button type="button" aria-label="下一张结果" onClick={() => stepResult(1)}>
                  <ChevronRight size={15} strokeWidth={2} />
                </button>
              </Tooltip>
            </div>
          )}
        </div>

      <div className="asset-detail__rail">
        <header className="asset-detail__head">
          <div className="asset-detail__head-copy">
            <strong title={title}>{title}</strong>
            <small>{model || (record ? '未记录模型' : (resolving ? '正在读取…' : '未记录生成信息'))}</small>
          </div>
          <div className="asset-detail__head-actions">
            <button type="button" className="asset-detail__button is-solid" onClick={handleDownload}>
              <Download size={14} strokeWidth={1.9} />
              <span>下载</span>
            </button>
            <button
              ref={moreRef}
              type="button"
              className={`asset-detail__button is-icon${menuAnchor ? ' is-active' : ''}`}
              aria-label="更多操作"
              aria-haspopup="menu"
              aria-expanded={menuAnchor !== null}
              onClick={() => {
                if (menuAnchor) { setMenuAnchor(null); return; }
                const rect = moreRef.current?.getBoundingClientRect();
                if (!rect) return;
                setMenuAnchor({ x: rect.right - 168, y: rect.bottom + 6 });
              }}
            >
              <Ellipsis size={15} strokeWidth={2} />
            </button>
          </div>
        </header>

        <div className="asset-detail__scroll">
          <div className="asset-detail__meta">
            {metaEntries.map((entry) => (
              <span key={entry.key}>
                {entry.key === 'durationMs' && <Clock size={12} strokeWidth={1.9} />}
                {entry.key === 'createdAt' && <Calendar size={12} strokeWidth={1.9} />}
                {entry.value}
              </span>
            ))}
          </div>

          <section className="asset-detail__block" aria-label="提示词">
            <div className="asset-detail__block-head">
              <span className="asset-detail__block-label">提示词</span>
              {prompt && (
                <button
                  type="button"
                  className="asset-detail__use-prompt"
                  onClick={() => {
                    useAgentStore.getState().injectComposerText(prompt);
                    notify({ title: '提示词已填入输入框', description: '按需改几句再发送', variant: 'info' });
                  }}
                >
                  <Wand2 size={13} strokeWidth={1.9} />
                  使用提示词
                </button>
              )}
            </div>
            {prompt
              ? <p className="asset-detail__prompt">{prompt}</p>
              : <p className="asset-detail__empty">{promptEmptyCopy}</p>}
          </section>

          <section className="asset-detail__block" aria-label="生成参数">
            <div className="asset-detail__block-head">
              <span className="asset-detail__block-label">参数</span>
            </div>
            {paramEntries.length > 0 ? (
              <dl className="asset-detail__params">
                {paramEntries.map((entry) => (
                  <div key={entry.key} className={entry.key === 'model' ? 'is-wide' : undefined}>
                    <dt>{entry.label}</dt>
                    <dd title={entry.value}>{entry.value}</dd>
                  </div>
                ))}
              </dl>
            ) : (
              <p className="asset-detail__empty">{paramEmptyCopy}</p>
            )}
          </section>

          <section className="asset-detail__block" aria-label="进一步改动">
            <div className="asset-detail__block-head">
              <span className="asset-detail__block-label">进一步改动</span>
            </div>
            <div className="asset-detail__tools">
              {tools.map((tool) => (
                <button
                  key={tool.label}
                  type="button"
                  className={`asset-detail__tool${tool.primary ? ' is-primary' : ''}`}
                  onClick={() => {
                    if (tool.run) { tool.run(); return; }
                    notify({
                      title: '未实现该功能',
                      description: `${tool.label}暂未开放`,
                      variant: 'info',
                      key: `media-action-unimplemented-${tool.label}`,
                    });
                  }}
                >
                  {tool.icon}
                  <span>{tool.label}</span>
                </button>
              ))}
            </div>

            {mediaKind === 'video' && (
              <div className="asset-detail__frames">
                <div className="asset-detail__frames-head">
                  <span>获取帧</span>
                  <small>输出 PNG 并生成到对话中</small>
                </div>
                <div className="asset-detail__frames-row">
                  <button type="button" disabled={frameBusy} onClick={() => void runFrameExtract('first')}>
                    首帧
                  </button>
                  <button type="button" disabled={frameBusy} onClick={() => void runFrameExtract('last')}>
                    尾帧
                  </button>
                </div>
                <label className="asset-detail__scrubber">
                  <span>自定义帧</span>
                  <b>{`${customSeconds.toFixed(1)}s`}</b>
                  <input
                    type="range"
                    min={0}
                    max={Math.max(0.1, durationSeconds || 1)}
                    step={0.1}
                    value={Math.min(customSeconds, Math.max(0.1, durationSeconds || 1))}
                    disabled={frameBusy || durationSeconds <= 0}
                    onChange={(event) => setCustomSeconds(Number(event.target.value))}
                  />
                  <button
                    type="button"
                    disabled={frameBusy || durationSeconds <= 0}
                    onClick={() => void runFrameExtract('custom')}
                  >
                    {frameBusy ? <LoaderCircle size={13} className="is-spinning" /> : null}
                    抽取
                  </button>
                </label>
                {durationState !== 'ready' && (
                  <small className="asset-detail__hint">
                    {durationState === 'loading'
                      ? '正在读取视频时长…'
                      : '读不到视频时长，自定义帧暂不可用'}
                  </small>
                )}
              </div>
            )}
          </section>
        </div>
        </div>

        {/* 灯箱、右键菜单、确认框都是自己 portal 到 body 的，挂在这里只是为了共享状态。 */}
        <ImagePreviewModal url={lightbox ? activeUrl : null} type={mediaKind} onClose={() => setLightbox(false)} />

        {menuAnchor && (
          <ContextMenuSurface
            x={menuAnchor.x}
            y={menuAnchor.y}
            items={menuItems}
            onClose={() => setMenuAnchor(null)}
          />
        )}

        <ConfirmDialog
          open={pendingDelete}
          danger
          busy={deleting}
          title="删除这条素材？"
          description={(
            <>
              删除后将从会话资产中移除，存储里的文件会一并删除，此操作不可撤销。
              {results.length === 1 && '该记录保留在生成记录中，状态标记为已删除。'}
            </>
          )}
          confirmLabel="删除"
          onConfirm={() => void confirmDelete()}
          onCancel={() => setPendingDelete(false)}
        />

        <ConfirmDialog
          open={pendingRegenerate}
          title="重新生成该视频？"
          description="会再次调用视频生成，可能产生新的额度消耗；原视频保持不动。"
          confirmLabel="确认重新生成"
          onConfirm={() => {
            setPendingRegenerate(false);
            handleRegenerate();
          }}
          onCancel={() => setPendingRegenerate(false)}
        />
      </div>
    </div>,
    document.body,
  );
}
