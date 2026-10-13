import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { useAgentStore } from '../../stores/agentStore';
import { notify } from '../../stores/toastStore';
import { Tooltip } from '../common/Tooltip';
import { useChatAnnotationStore, type ChatAnnotation } from '../../stores/chatAnnotationStore';
import { AnnotationChip } from './AnnotationChip';
import { SceneSelectorModal } from './SceneSelectorModal';
import { MicButton } from './MicButton';
import { UploadMaterialMenu } from './UploadMaterialMenu';
import { ProjectLibraryPickerModal } from '../library/ProjectLibraryPickerModal';
import {
  DEFAULT_AGENT_APPROVAL_MODE,
  agentApi,
  sttStream,
  type ConversationAssetGroupBinding,
} from '../../api/agent';
import { FileText, Paperclip } from 'lucide-react';
import {
  PlusCircle, Package, Trash2, ChevronUp, ChevronDown, Plus, X,
  Minimize2, Info, PanelRightOpen, Frame, MessagesSquare,
} from 'lucide-react';
import type { SceneResponse } from '../../api/projects';
import { assetApi, type Asset, type AssetType } from '../../api/assets';
import { useProjectStore } from '../../stores/projectStore';
import { useDocumentPreviewStore } from '../../stores/documentPreviewStore';
import { useSideChatSelectionStore } from '../../stores/sideChatSelectionStore';
import { assetUrl } from '../../config';
import { buildSceneInspectPrompt, SCENE_DRAG_MIME, type EmbeddedSceneReference } from '../../lib/embeddedAppMessages';
import { buildAnnotationContext } from '../../lib/chatAnnotation';
import { buildSideChatSelectionContext } from '../../lib/sideChatSelection';
import { currentSideChatId } from '../../lib/sideChatSelectionSync';
import { filterCanvasReferenceUrls } from '../../lib/canvasEditMode';
import { usePresence } from '../../hooks/usePresence';
import { useAutoGrowTextarea } from '../../hooks/useAutoGrowTextarea';
import { isComposerCollapsed } from '../../lib/chatScrollState';
import './ChatComposer.css';
import { ApprovalModeSelector } from './ApprovalModeSelector';
import { ChatGenerationPreferences } from './ChatGenerationPreferences';
import { ImagePreviewModal } from './ImagePreviewModal';
import { SideChatContextChip } from './SideChatContextChip';
import { ChatStatusBar } from './ChatStatusBar';
import { ChatSlashMenu, type SlashCommandItem } from './ChatSlashMenu';
import { ConversationStatusCard } from './ConversationStatusCard';
import { agentChatTokens, type AgentChatTokens } from './agentChatTokens';
import { DEFAULT_CONTEXT_WINDOW_TOKENS, contextWindowTokensFor } from './contextWindow';
import {
  PROJECT_LIBRARY_DRAG_MIME,
  parseProjectLibraryDragPayload,
  type ProjectLibraryDragPayload,
} from '../../lib/projectLibraryDrag';

// 资产类型中文标签（与 AssetLibraryPanel 一致）
const ASSET_TYPE_LABEL: Record<AssetType, string> = { character: '人物', prop: '道具', scene: '场景' };

/** /chat 页设计 token（蓝色品牌 + 中性灰底，统一全局）：唯一真源见 agentChatTokens。 */
export const DS: AgentChatTokens = agentChatTokens;

const REFERENCE_PREVIEW_LIMIT = 5;

/**
 * DeepSeek 风格输入卡（780px 悬浮胶囊）：/chat 页 hero 居中与底部输入共用。
 * 逻辑复用 agentStore（参考图/pendingPicUrl/发送/流式禁用）。
 */
export interface CanvasModeCommand {
  /** 当前是否处于画布编辑模式 */
  active: boolean;
  /** 是否持有可恢复的画布目标（画布子站锁定场景） */
  restorable: boolean;
  onExit: () => void;
  onEnter: () => void;
}

export function ChatComposer({
  enableSideChat = true,
  canvasModeCommand,
  compact = false,
  onCollapsedChange,
  docked = false,
  onViewConversation,
}: {
  enableSideChat?: boolean;
  canvasModeCommand?: CanvasModeCommand;
  /** 消息区离开底部时的收缩态：只留一行输入框 + 发送按钮，聚焦输入框立即还原 */
  compact?: boolean;
  /** 收缩态变化回调：供页面把「回到底部」等外部元素对齐到输入卡当前右边缘 */
  onCollapsedChange?: (collapsed: boolean) => void;
  /** 智能体被挤成右下角浮窗输入框：收起计数器，并把「查看当前对话」挂进工具栏 */
  docked?: boolean;
  /** 查看当前对话（浮窗里展开聊天记录） */
  onViewConversation?: () => void;
} = {}) {
  const {
    streaming, waitingHumanInput, waitingVideoPlan, agentVideoTask,
    refImageUrls, uploadRefImages, sendMessage, interruptAndSend, stopGeneration,
    pendingPicUrls, cancelRefine,
    removeRefImageUrl,
    addRefImageUrls, setRefImageUrls,
    pendingAttachments, addPendingAttachments, removePendingAttachment,
    pickedAssetIds, clearPickedAssetIds, setPickedAssetIds, addLocalUserMessage,
    pickedReferenceVideos, pickedReferenceAudios, setPickedReferenceMedia, clearPickedReferenceMedia,
    canvasEditContext,
    canvasBinding,
    canvasBindingStatus,
    canvasExitPending,
    removeCanvasMediaRef,
    moveCanvasMediaRef,
    pendingApprovalMode,
    setApprovalMode,
    sideChat,
    openSideChat,
  } = useAgentStore();
  const pendingAnnotations = useChatAnnotationStore((state) => state.annotations);
  const storedSideChatSelections = useSideChatSelectionStore((state) => state.selections);
  const sideChatSelections = currentSideChatId() ? storedSideChatSelections : [];
  const [text, setText] = useState('');
  const [focused, setFocused] = useState(false);
  /** 卡片内任意控件获得焦点（含下拉按钮）都算「在用」，此时不收缩 */
  const [engaged, setEngaged] = useState(false);
  const [recording, setRecording] = useState(false);
  const [removeModalOpen, setRemoveModalOpen] = useState(false);
  const [previewMedia, setPreviewMedia] = useState<{ url: string; type: 'image' | 'video' } | null>(null);
  const [uploadError, setUploadError] = useState('');
  const [canvasMediaOpen, setCanvasMediaOpen] = useState(false);
  const [uploadDrafts, setUploadDrafts] = useState<Array<{
    file: File;
    preview: string;
    purpose: string;
  }>>([]);
  // pickedAssetIds 现在从 store 获取，持久化不丢失
  // 镜像 text 供回调读取（避免 useCallback 闭包陈旧）
  const textRef = useRef('');
  textRef.current = text;
  const fileRef = useRef<HTMLInputElement>(null);
  const dragDepthRef = useRef(0);
  const [dropActive, setDropActive] = useState(false);
  const [dragKind, setDragKind] = useState<'file' | 'scene' | 'library' | null>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const cardRef = useRef<HTMLDivElement>(null);
  const menuBtnRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const activeConversationId = useAgentStore((s) => s.activeConversationId);
  const conversations = useAgentStore((s) => s.conversations);
  const projectId = useProjectStore((s) => s.currentProject?.id);
  const activeConversation = conversations.find((conversation) => conversation.id === activeConversationId);
  const archived = activeConversation?.status === 'archived';
  const legacyOrchestration = activeConversation?.orchestrationMode === 'LEGACY';
  const approvalMode = activeConversation?.approvalMode ?? pendingApprovalMode ?? DEFAULT_AGENT_APPROVAL_MODE;
  const readOnly = archived || legacyOrchestration;
  // 上下文占用：优先后端权威值（末次模型调用 input + 模型窗口）；
  // 后端未升级（缺 contextTokens）时回退到旧的 inputTokens 估算。
  const messages = useAgentStore((s) => s.messages);
  const activeUsageDraft = useAgentStore((s) => s.generating[activeConversationId ?? '']?.usageDraft);
  const activeTurnContextWindow = useAgentStore(
    (s) => s.generating[activeConversationId ?? '']?.contextWindowTokens,
  );
  const contextBaseline = useMemo(
    () => [...messages].reverse().find((message) => message.metadata?.metrics)?.metadata?.metrics,
    [messages],
  );
  const contextUsageTokens = useMemo(() => {
    const live = activeUsageDraft?.contextTokens ?? 0;
    const base = contextBaseline?.contextTokens ?? 0;
    if (live || base) return Math.max(live, base);
    // 兼容旧后端：无 contextTokens 时退回累加口径
    return (contextBaseline?.inputTokens ?? 0) + (activeUsageDraft?.inputTokens ?? 0);
  }, [activeUsageDraft, contextBaseline]);
  const contextLimitTokens = useMemo(() => {
    const window = activeUsageDraft?.contextWindowTokens
      || activeTurnContextWindow
      || contextBaseline?.contextWindowTokens;
    return window || contextWindowTokensFor() || DEFAULT_CONTEXT_WINDOW_TOKENS;
  }, [activeTurnContextWindow, activeUsageDraft, contextBaseline]);
  // 尚无任何真实上下文数据（新会话、未发生模型调用）时不显示圆圈，避免误显静态默认窗口
  const hasContextData = !!activeUsageDraft || !!contextBaseline;
  /** 只读原因不同提示分开：旧编排会话并未归档，不能一律说「已归档」 */
  const readOnlyHint = archived
    ? '该会话已归档，仅可查看历史记录，请新建会话后继续。'
    : '该会话由旧版编排创建，仅可查看历史记录，请新建会话后继续。';
  // 流式语音识别状态：活跃会话句柄 + 已确认文本（增量追加，不清空）
  const sttRef = useRef<{ push: (p: Int16Array) => void; close: () => void; cancel: () => void } | null>(null);
  const confirmedRef = useRef(''); // 已确认片段累积（vosk 每条 text 是增量，片段间逗号分隔）
  const projectLibraryDropRef = useRef<(payload: ProjectLibraryDragPayload) => Promise<void>>(
    async () => undefined,
  );
  useAutoGrowTextarea(inputRef, text);

  // partial（预览全文）→ 显示「已确认 + 新片段」临时预览（新片段会随识别修正变化）
  const handleSttPartial = useCallback((t: string) => {
    const clean = t.replace(/\s+/g, '');
    if (!clean) return;
    const base = confirmedRef.current;
    if (!base) { setText(clean); return; }
    // partial 全文含历史：去掉已确认前缀，剩余作为新片段预览（逗号分隔）
    const rest = clean.startsWith(base) ? clean.slice(base.length) : clean;
    setText(rest ? `${base}，${rest}` : base);
  }, []);

  // vosk 确认片段（实测为增量，每段确认触发）→ 追加到已确认文本，片段间逗号分隔；停止后保留不清空
  const handleSttFinal = useCallback((t: string) => {
    const clean = t.replace(/\s+/g, '');
    if (!clean) return;
    const base = confirmedRef.current;
    if (base.includes(clean)) return; // 防重复（partial 预览已含）
    const next = base ? `${base}，${clean}` : clean;
    confirmedRef.current = next;
    setText(next);
  }, []);

  // 麦克风开关：开始录音 → 开流式识别会话（继续追加输入框已有内容）；停止录音 → 关流（不清空已识别文本）
  const handleMicToggle = useCallback((active: boolean) => {
    setRecording(active);
    if (active) {
      sttRef.current?.cancel();
      confirmedRef.current = textRef.current; // 输入框已有内容作为累积 base（继续追加）
      sttRef.current = sttStream(handleSttPartial, handleSttFinal);
    } else {
      sttRef.current?.close();
    }
  }, [handleSttPartial, handleSttFinal]);

  // 实时 PCM 块 → 推给后端流式识别
  const handlePcm = useCallback((pcm: Int16Array) => {
    sttRef.current?.push(pcm);
  }, []);

  // 组件卸载：中断活跃识别
  useEffect(() => () => { sttRef.current?.cancel(); }, []);

  // "+" 菜单状态
  const [menuOpen, setMenuOpen] = useState(false);
  const [sceneModalOpen, setSceneModalOpen] = useState(false);
  const [menuPos, setMenuPos] = useState<{ top: number; left: number } | null>(null);
  const menuPresence = usePresence(menuOpen && !!menuPos, 180);
  // ├─「上传资产」从已有资产库选择参考图状态（assetApi.list 拉取，选中资产图片作为参考图）
  const [assetOpen, setAssetOpen] = useState(false);
  const [assetList, setAssetList] = useState<Asset[]>([]);
  const [assetLoading, setAssetLoading] = useState(false);
  const [assetSelected, setAssetSelected] = useState<Set<string>>(new Set());
  const [assetError, setAssetError] = useState('');
  const [pendingGroup, setPendingGroup] = useState<import('../../api/library').AssetGroupSnapshot | null>(null);
  const [projectLibraryOpen, setProjectLibraryOpen] = useState(false);
  const [boundGroups, setBoundGroups] = useState<ConversationAssetGroupBinding[]>([]);
  // 斜杠命令：状态浮层卡 / 压缩提示（输入框上方）
  const [statusOpen, setStatusOpen] = useState(false);
  const [compressNotice, setCompressNotice] = useState('');
  const [compressing, setCompressing] = useState(false);

  // 切换会话清空草稿，避免不同会话之间串草稿。
  useEffect(() => {
    setText('');
    setStatusOpen(false);
    setCompressNotice('');
  }, [activeConversationId]);

  useEffect(() => {
    const raw = sessionStorage.getItem('moon.pendingGroupSnapshot');
    if (!raw) return;
    try {
      const snapshot = JSON.parse(raw) as import('../../api/library').AssetGroupSnapshot;
      setPendingGroup(snapshot);
      setText('请使用我选择的资产组设计并生成视频方案');
      sessionStorage.removeItem('moon.pendingGroupSnapshot');
    } catch {
      sessionStorage.removeItem('moon.pendingGroupSnapshot');
    }
  }, []);

  useEffect(() => {
    if (!activeConversationId) {
      setBoundGroups([]);
      return;
    }
    let cancelled = false;
    agentApi.listConversationAssetGroups(activeConversationId)
      .then((res) => {
        if (!cancelled) setBoundGroups(res.data.data ?? []);
      })
      .catch(() => {
        if (!cancelled) setBoundGroups([]);
      });
    return () => { cancelled = true; };
  }, [activeConversationId]);

  useEffect(() => {
    if (!canvasEditContext) return;
    const filtered = filterCanvasReferenceUrls(refImageUrls, canvasEditContext);
    if (filtered.length !== refImageUrls.length) setRefImageUrls(filtered);
  }, [canvasEditContext, refImageUrls, setRefImageUrls]);

  // 点击"继续完善"后聚焦输入框
  useEffect(() => {
    if (pendingPicUrls.length > 0) inputRef.current?.focus();
  }, [pendingPicUrls]);

  // 点击外部关闭菜单
  useEffect(() => {
    if (!menuOpen) return;
    const close = (e: MouseEvent) => {
      if (!menuBtnRef.current?.contains(e.target as Node) && !menuRef.current?.contains(e.target as Node)) setMenuOpen(false);
    };
    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, [menuOpen]);

  const busy = (streaming && !agentVideoTask) || !!waitingHumanInput || !!waitingVideoPlan
    || !!canvasBinding || canvasExitPending;
  const canInterruptSend = streaming && !agentVideoTask && !waitingHumanInput && !waitingVideoPlan
    && !canvasBinding && !canvasExitPending;
  const attachLocked = readOnly;
  const fileAttachments = pendingAttachments.filter((attachment) => attachment.kind === 'file');
  const referencePreviews = refImageUrls.map((url, index) => ({
    url,
    index,
    name: pendingAttachments.find((attachment) => attachment.url === url)?.name || `图 ${index + 1}`,
  }));
  const hasReferenceOverflow = referencePreviews.length > REFERENCE_PREVIEW_LIMIT;
  const visibleReferencePreviews = hasReferenceOverflow
    ? referencePreviews.slice(0, REFERENCE_PREVIEW_LIMIT - 1)
    : referencePreviews;
  const remainingReferenceCount = hasReferenceOverflow
    ? referencePreviews.length - visibleReferencePreviews.length
    : 0;
  // 输入框上方：当前会话待发注释，按消息分组各显示一枚「N 条注释」胶囊
  const pendingAnnotationGroups = useMemo(() => {
    if (!activeConversationId) return [] as ChatAnnotation[][];
    const groups = new Map<string, ChatAnnotation[]>();
    for (const annotation of pendingAnnotations) {
      if (annotation.conversationId !== activeConversationId) continue;
      const existing = groups.get(annotation.messageId);
      if (existing) existing.push(annotation);
      else groups.set(annotation.messageId, [annotation]);
    }
    return Array.from(groups.values());
  }, [pendingAnnotations, activeConversationId]);
  // 只有注释、没有文字时也允许发送：注释块本身就是内容
  const hasPendingAnnotations = pendingAnnotationGroups.length > 0;
  const hasSideChatSelections = sideChatSelections.length > 0;
  const canSend = !readOnly && (!busy || canInterruptSend)
    && (!!text.trim() || hasPendingAnnotations || hasSideChatSelections);

  // ── 斜杠命令（/chat 输入「/」列出「压缩上下文 / 状态」） ──
  // 命令模式：文本以「/」开头且尚未输入空格（与 Codex 一致）
  const slashQuery = useMemo(() => {
    const trimmedStart = text.trimStart();
    if (!trimmedStart.startsWith('/')) return null;
    const rest = trimmedStart.slice(1);
    return rest.includes(' ') || rest.includes('\n') ? null : rest;
  }, [text]);

  const runCompress = async () => {
    const conversationId = useAgentStore.getState().activeConversationId;
    if (!conversationId || compressing) return;
    setText('');
    setCompressing(true);
    try {
      const res = await agentApi.compactContext(conversationId);
      const data = res.data.data;
      if (data.compacted) {
        const detail = `${data.messagesBefore} 条 → ${data.messagesAfter} 条`;
        setCompressNotice(`上下文已压缩：${detail}`);
        notify({ title: '上下文已压缩', description: detail });
      } else if (data.status === 'UNAVAILABLE') {
        setCompressNotice('当前会话暂不支持压缩');
        notify({ title: '当前会话暂不支持压缩', variant: 'info' });
      } else {
        setCompressNotice('当前上下文不足，无需压缩');
        notify({ title: '上下文无需压缩', variant: 'info' });
      }
    } catch {
      setCompressNotice('压缩失败，请稍后重试');
      notify({ title: '压缩失败', description: '请稍后重试', variant: 'error' });
    } finally {
      setCompressing(false);
    }
  };

  const slashItems: SlashCommandItem[] = [
    {
      id: 'compress',
      icon: <Minimize2 size={16} strokeWidth={2} />,
      title: '压缩上下文',
      description: '压缩此对话的上下文，保留最近若干轮',
      disabled: !activeConversationId || messages.length === 0,
      disabledHint: '新对话暂无可压缩的上下文',
      run: () => { void runCompress(); },
    },
    {
      id: 'status',
      icon: <Info size={16} strokeWidth={2} />,
      title: '状态',
      description: '显示会话 ID、背景信息与额度',
      run: () => { setText(''); setStatusOpen(true); },
    },
    {
      id: 'side',
      icon: <PanelRightOpen size={16} strokeWidth={2} />,
      title: '侧边',
      description: '基于当前对话开启临时分支',
      visible: enableSideChat,
      disabled: !activeConversationId || messages.length === 0 || sideChat.loading,
      disabledHint: '先完成一轮对话后再开启侧边',
      run: () => {
        setText('');
        void openSideChat(
          activeConversationId ?? undefined,
          activeConversation?.title,
        );
      },
    },
    {
      // 画布子站：画布模式默认开启，通过 / 菜单显式进出（退出即回到普通 Moon 对话）
      id: canvasModeCommand?.active ? 'canvas-exit' : 'canvas-enter',
      icon: <Frame size={16} strokeWidth={2} />,
      title: canvasModeCommand?.active ? '退出画布模式' : '进入画布模式',
      description: canvasModeCommand?.active
        ? '离开画布编辑模式，回到普通 Moon 对话'
        : '回到当前画布的编辑模式',
      visible: !!canvasModeCommand?.restorable,
      run: () => {
        setText('');
        if (!canvasModeCommand) return;
        if (canvasModeCommand.active) canvasModeCommand.onExit();
        else canvasModeCommand.onEnter();
      },
    },
  ];

  // 没有任何匹配项时不进入命令模式（否则 "/随便写的文本" 会被吃掉 Enter 发不出去）
  const slashMenuVisible = slashQuery !== null && !readOnly && !busy && !statusOpen
    && slashItems.some((item) => item.visible !== false
      && (item.title.includes(slashQuery) || item.id.includes(slashQuery.toLowerCase())));

  const handleSend = () => {
    const rawText = text.trim();
    // 只有注释、没有文字时也能发：注释块本身就是内容
    if ((!rawText && !hasPendingAnnotations && !hasSideChatSelections)
      || readOnly || (busy && !canInterruptSend)) return;
    // 未选项目时 sendMessage 会拒绝发送：先拦下并保留已输入文字，避免清空后丢内容
    if (!projectId) {
      useAgentStore.setState({ streamError: '请先在左上角选择一个项目' });
      return;
    }
    // 该会话待发的消息内注释一次性随本条消息发给 AI：先校验内容，再消费，避免校验失败丢注释
    const annotationContent = buildAnnotationContext(pendingAnnotationGroups.flat(), rawText);
    const content = buildSideChatSelectionContext(sideChatSelections, annotationContent);
    if (!content) return;
    if (activeConversationId) {
      useChatAnnotationStore.getState().consumeConversation(activeConversationId);
    }
    setText('');
    if (canInterruptSend) {
      void interruptAndSend(content);
      return;
    }
    const groupUrls = pendingGroup?.items.map((item) =>
      item.objectKey.startsWith('http') ? item.objectKey : `/api/files/${item.objectKey}`) || [];
    if (pendingGroup) sessionStorage.setItem('moon.activeGroupId', pendingGroup.groupId);
    sendMessage(content, {
      picUrls: groupUrls.length ? groupUrls : undefined,
      assetIds: pickedAssetIds,
      assetGroupIds: pendingGroup ? [pendingGroup.groupId] : undefined,
      assetGroupSnapshotIds: pendingGroup ? [pendingGroup.snapshotId] : undefined,
      routingHint: pendingGroup ? 'intent-video' : undefined,
    });
    setPendingGroup(null);
    clearPickedAssetIds();
  };

  const handleFile = (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(e.target.files ?? []);
    if (readOnly || files.length === 0) return;
    setUploadDrafts(files.map((file) => ({
      file,
      preview: URL.createObjectURL(file),
      purpose: '',
    })));
    e.target.value = '';
  };

  const closeUploadDrafts = () => {
    uploadDrafts.forEach((d) => URL.revokeObjectURL(d.preview));
    setUploadDrafts([]);
    setUploadError('');
  };

  const handleUploadConfirm = async () => {
    const drafts = uploadDrafts;
    if (readOnly || drafts.length === 0) return;
    setUploadError('');
    try {
      await uploadRefImages(
        drafts.map((d) => d.file),
        drafts.map((d) => d.purpose.trim()),
      );
    } catch {
      // 失败保留草稿弹窗与文字，用户可直接重试（原 alert 打断且无处看原因）
      setUploadError('文件上传失败，请重试');
      return;
    }
    closeUploadDrafts();
  };

  const handleDragEnter = useCallback((e: React.DragEvent) => {
    if (readOnly) return;
    const hasFiles = e.dataTransfer.types.includes('Files');
    const hasScenes = e.dataTransfer.types.includes(SCENE_DRAG_MIME);
    const hasLibrary = e.dataTransfer.types.includes(PROJECT_LIBRARY_DRAG_MIME);
    if (!hasFiles && !hasScenes && !hasLibrary) return;
    e.preventDefault();
    dragDepthRef.current += 1;
    setDropActive(true);
    setDragKind(hasScenes ? 'scene' : hasLibrary ? 'library' : 'file');
  }, [readOnly]);

  const handleDragOver = useCallback((e: React.DragEvent) => {
    if (readOnly) return;
    if (!e.dataTransfer.types.includes('Files')
        && !e.dataTransfer.types.includes(SCENE_DRAG_MIME)
        && !e.dataTransfer.types.includes(PROJECT_LIBRARY_DRAG_MIME)) return;
    e.preventDefault();
    e.dataTransfer.dropEffect = 'copy';
  }, [readOnly]);

  const handleDragLeave = useCallback(() => {
    dragDepthRef.current = Math.max(0, dragDepthRef.current - 1);
    if (dragDepthRef.current === 0) {
      setDropActive(false);
      setDragKind(null);
    }
  }, []);

  const handleDrop = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    if (readOnly) return;
    dragDepthRef.current = 0;
    setDropActive(false);
    setDragKind(null);
    const libraryPayload = parseProjectLibraryDragPayload(
      e.dataTransfer.getData(PROJECT_LIBRARY_DRAG_MIME),
    );
    if (libraryPayload) {
      void projectLibraryDropRef.current(libraryPayload);
      return;
    }
    const scenePayload = e.dataTransfer.getData(SCENE_DRAG_MIME) || e.dataTransfer.getData('text/plain');
    if (scenePayload) {
      try {
        const references = JSON.parse(scenePayload) as EmbeddedSceneReference[];
        if (Array.isArray(references) && references.length > 0) {
          sendMessage(buildSceneInspectPrompt(references), { routingHint: 'intent-storyboard' });
          return;
        }
      } catch { /* 无效拖拽数据按普通文件拖拽处理 */ }
    }
    const files = Array.from(e.dataTransfer.files ?? []);
    if (files.length > 0) void uploadRefImages(files);
  }, [readOnly, sendMessage, uploadRefImages]);

  const openMenu = () => {
    if (readOnly) return;
    const btn = menuBtnRef.current;
    if (btn) {
      const r = btn.getBoundingClientRect();
      setMenuPos({ top: r.top - 88, left: r.left });
    }
    setMenuOpen(!menuOpen);
  };

  const handleSceneConfirm = (selected: SceneResponse[]) => {
    if (readOnly) return;
    const sceneRefs = selected.map((scene) => `分镜${scene.sceneNumber}`).join('、');
    sendMessage(`请查看 ${sceneRefs}`, { routingHint: 'intent-storyboard' });
    setSceneModalOpen(false);
  };

  // ──「上传资产」：从已有资产库选择参考图（assetApi.list 拉取 → 选中资产的图片作为参考图） ──
  const loadAssetList = async () => {
    setAssetLoading(true); setAssetError('');
    try {
      const projectId = useProjectStore.getState().currentProject?.id;
      const res = await assetApi.list(projectId);
      setAssetList(res.data.data ?? []);
    } catch (err) {
      setAssetError(err instanceof Error ? err.message : '获取资产列表失败');
    } finally {
      setAssetLoading(false);
    }
  };
  const handleAssetOpen = () => {
    if (readOnly) return;
    setAssetOpen(true);
    setAssetSelected(new Set(pickedAssetIds));
    void loadAssetList();
  };
  const handleAssetConfirm = async () => {
    if (readOnly) return;
    if (!useAgentStore.getState().activeConversationId) {
      await useAgentStore.getState().createConversation();
    }
    // 只选中资产 id；资产图片由后端在生成阶段按资产 id 读取，不混入参考图列表
    const picked = assetList.filter((a) => assetSelected.has(a.id));
    // 允许确认 0 项 = 清空已选资产（用户全部取消勾选后也能正常确认，而不是被禁用卡住）
    setPickedAssetIds(Array.from(assetSelected));
    if (picked.length) {
      addLocalUserMessage(`已选择资产：${picked.map((a) => a.name).join('、')}（${picked.length} 个）`);
    }
    setAssetOpen(false);
    setAssetSelected(new Set());
    setAssetError('');
  };

  const reloadBoundGroups = async (conversationId: string) => {
    const response = await agentApi.listConversationAssetGroups(conversationId);
    setBoundGroups(response.data.data ?? []);
  };

  const handleAssetGroupConfirm = async (groupIds: string[]) => {
    if (readOnly) return;
    let conversationId = useAgentStore.getState().activeConversationId;
    if (!conversationId) {
      await useAgentStore.getState().createConversation();
      conversationId = useAgentStore.getState().activeConversationId;
    }
    if (!conversationId) throw new Error('请先选择项目并创建对话');
    const currentIds = new Set(boundGroups.map((binding) => binding.groupId));
    const nextIds = new Set(groupIds);
    for (const groupId of nextIds) {
      if (!currentIds.has(groupId)) await agentApi.attachConversationAssetGroup(conversationId, groupId);
    }
    for (const groupId of currentIds) {
      if (!nextIds.has(groupId)) await agentApi.removeConversationAssetGroup(conversationId, groupId);
    }
    await reloadBoundGroups(conversationId);
    if (groupIds.length) addLocalUserMessage(`已关联 ${groupIds.length} 个资产组`);
  };

  projectLibraryDropRef.current = async (payload) => {
    if (readOnly) return;
    try {
      if (payload.kind === 'group') {
        await handleAssetGroupConfirm([
          ...new Set([...boundGroups.map((binding) => binding.groupId), payload.groupId]),
        ]);
        return;
      }
      if (payload.mediaType === 'IMAGE') {
        addRefImageUrls([payload.url]);
        addPendingAttachments([{
          url: payload.url,
          name: payload.name,
          mimeType: 'image/*',
          kind: 'image',
          source: 'library',
          assetId: payload.assetId,
        }]);
        return;
      }
      if (payload.mediaType === 'VIDEO') {
        setPickedReferenceMedia(
          [...new Set([...pickedReferenceVideos, payload.url])],
          pickedReferenceAudios,
        );
        addPendingAttachments([{
          url: payload.url,
          name: payload.name,
          mimeType: 'video/*',
          kind: 'video',
          source: 'library',
          assetId: payload.assetId,
        }]);
        return;
      }
      setPickedReferenceMedia(
        pickedReferenceVideos,
        [...new Set([...pickedReferenceAudios, payload.url])],
      );
      addPendingAttachments([{
        url: payload.url,
        name: payload.name,
        mimeType: 'audio/*',
        kind: 'audio',
        source: 'library',
        assetId: payload.assetId,
      }]);
    } catch (error) {
      addLocalUserMessage(error instanceof Error ? `项目库素材添加失败：${error.message}` : '项目库素材添加失败');
    }
  };

  const refreshBoundGroup = async (groupId: string) => {
    if (readOnly) return;
    const conversationId = useAgentStore.getState().activeConversationId;
    if (!conversationId) return;
    await agentApi.attachConversationAssetGroup(conversationId, groupId);
    await reloadBoundGroups(conversationId);
  };
  const toggleAsset = (id: string) => {
    setAssetSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const hasAttachmentRow = !!(
    canvasEditContext?.mediaRefs.length || refImageUrls.length > 0 || pickedAssetIds.length > 0
    || pickedReferenceVideos.length > 0 || pickedReferenceAudios.length > 0
    || fileAttachments.length > 0 || pendingGroup || boundGroups.length > 0
  );
  /** 输入框上方的附加行：收缩态整块收起，所以只有真的渲染了内容时才占位 */
  const hasComposerExtras = !!(
    compressing || compressNotice
    || (!readOnly && hasPendingAnnotations) || (!readOnly && hasSideChatSelections)
    || hasAttachmentRow || pendingPicUrls.length > 0 || canvasBinding || canvasExitPending || readOnly
  );
  const collapsed = isComposerCollapsed(compact, focused || engaged)
    && !menuOpen && !removeModalOpen && !sceneModalOpen && !projectLibraryOpen && !canvasMediaOpen
    && !slashMenuVisible && !statusOpen;

  useEffect(() => {
    onCollapsedChange?.(collapsed);
  }, [collapsed, onCollapsedChange]);

  return (
    <>
    <div
      ref={cardRef}
      className={`chat-composer-card${collapsed ? ' is-collapsed' : ''}`}
      onDragEnter={handleDragEnter}
      onDragOver={handleDragOver}
      onDragLeave={handleDragLeave}
      onDrop={handleDrop}
      onFocusCapture={() => setEngaged(true)}
      onBlurCapture={() => setEngaged(false)}
      style={{
      width: '100%', maxWidth: collapsed ? DS.maxContentCompact : DS.maxContent, margin: '0 auto',
      border: `1px solid ${dropActive ? DS.brand : focused ? 'rgba(65,118,230,0.45)' : DS.border}`,
      borderRadius: collapsed ? 18 : 22,
      background: dropActive
        ? 'rgba(65,118,230,0.035)'
        : collapsed ? 'rgba(255, 255, 255, 0.62)' : 'white',
      backdropFilter: collapsed ? 'blur(10px)' : undefined,
      WebkitBackdropFilter: collapsed ? 'blur(10px)' : undefined,
      boxShadow: dropActive
        ? '0 0 0 3px rgba(65,118,230,0.14), 0 0 24px rgba(65,118,230,0.20)'
        : focused ? '0 0 0 3px rgba(65,118,230,0.10), 0 0 20px rgba(65,118,230,0.18)'
          : collapsed ? '0 10px 26px -14px rgba(20, 20, 19, 0.35)' : '0 2px 12px rgba(0, 0, 0, 0.06)',
      transition: 'border-color 0.2s, box-shadow 0.2s, background-color 240ms ease, padding 240ms cubic-bezier(0.22, 1, 0.36, 1), border-radius 240ms ease, max-width 260ms cubic-bezier(0.22, 1, 0.36, 1)',
      padding: collapsed ? '4px 6px 4px 12px' : '10px 14px 12px',
      display: 'flex',
      // 收缩态：输入框与发送按钮并排一行
      flexDirection: collapsed ? 'row' : 'column',
      alignItems: collapsed ? 'center' : undefined,
      gap: collapsed ? 8 : 10,
      boxSizing: 'border-box', position: 'relative',
    }}>
      {/* 两个向上弹出的浮层放在附加行之外：附加行收缩时要裁剪，浮层不能被裁 */}
      {slashMenuVisible && (
        <ChatSlashMenu
          items={slashItems}
          query={slashQuery ?? ''}
          onClose={() => setText('')}
          anchorRef={cardRef}
        />
      )}
      {statusOpen && (
        <ConversationStatusCard
          conversationId={activeConversationId ?? null}
          usedTokens={contextUsageTokens}
          limitTokens={contextLimitTokens}
          hasContextData={hasContextData}
          onClose={() => setStatusOpen(false)}
        />
      )}
      {hasComposerExtras && (
      <div className={`chat-composer-extra${collapsed ? ' is-collapsed' : ''}`}>
      <div className="chat-composer-extra__inner">
      {compressing && (
        <div style={{ fontSize: 12, color: DS.textSecondary }}>正在压缩上下文…</div>
      )}
      {compressNotice && (
        <div style={{
          display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8,
          padding: '8px 12px', borderRadius: 12, background: DS.hover,
          color: DS.textSecondary, fontSize: 13,
        }}>
          <span>{compressNotice}</span>
          <button
            type="button"
            onClick={() => setCompressNotice('')}
            aria-label="关闭提示"
            style={{ border: 'none', background: 'none', color: DS.textSecondary, cursor: 'pointer' }}
          >✕</button>
        </div>
      )}
      {!readOnly && pendingAnnotationGroups.length > 0 && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
          {pendingAnnotationGroups.map((group) => (
            <AnnotationChip
              key={group[0].messageId}
              annotations={group}
              onEdit={(annotation) => useChatAnnotationStore.getState().openEditor(annotation.id)}
              onDelete={(id) => useChatAnnotationStore.getState().removeAnnotation(id)}
              onClear={() => useChatAnnotationStore.getState().removeForMessage(group[0].messageId)}
            />
          ))}
        </div>
      )}
      {!readOnly && hasSideChatSelections && <SideChatContextChip />}
      {(canvasEditContext?.mediaRefs.length || refImageUrls.length > 0 || pickedAssetIds.length > 0
        || pickedReferenceVideos.length > 0 || pickedReferenceAudios.length > 0
        || fileAttachments.length > 0 || pendingGroup || boundGroups.length > 0) && (
        <div style={{
          display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap',
          padding: '8px 12px', borderRadius: 12, background: DS.hover,
          border: `1px solid ${DS.border}`,
        }}>
          {refImageUrls.length > 0 && (
            <div className="chat-reference-preview" role="group" aria-label="参考图预览">
              {visibleReferencePreviews.map((item) => (
                <div className="chat-reference-preview__item" key={item.url}>
                  <button
                    type="button"
                    className="chat-reference-preview__open"
                    onClick={() => setPreviewMedia({ url: item.url, type: 'image' })}
                    aria-label={`预览参考图 ${item.index + 1}：${item.name}`}
                    title={item.name}
                  >
                    <img src={assetUrl(item.url)} alt="" draggable={false} />
                  </button>
                  <button
                    type="button"
                    className="chat-reference-preview__remove"
                    onClick={() => removeRefImageUrl(item.index)}
                    aria-label={`移除参考图 ${item.index + 1}`}
                    title="移除参考图"
                  >
                    <X size={10} strokeWidth={2.5} />
                  </button>
                </div>
              ))}
              {remainingReferenceCount > 0 && (
                <Tooltip label={`还有 ${remainingReferenceCount} 张参考图`}>
                <button
                  type="button"
                  className="chat-reference-preview__overflow"
                  onClick={() => setRemoveModalOpen(true)}
                  aria-label={`查看其余 ${remainingReferenceCount} 张参考图`}
                >
                  <span>+{remainingReferenceCount}</span>
                </button>
                </Tooltip>
              )}
            </div>
          )}
          {pickedAssetIds.length > 0 && (
            <span style={{ display: 'inline-flex', alignItems: 'center', gap: 2 }}>
              <button onClick={handleAssetOpen} style={{
                display: 'inline-flex', alignItems: 'center', gap: 5, border: 'none',
                background: 'white', color: DS.textSecondary, fontSize: 13, fontWeight: 500,
                padding: '5px 10px', borderRadius: 8, cursor: 'pointer',
              }}>
                <Package size={14} strokeWidth={2} style={{ color: DS.brand }} />
                资产 {pickedAssetIds.length} 个
              </button>
              <Tooltip label="移除已选资产">
                <button
                  onClick={() => clearPickedAssetIds()}
                  aria-label="移除已选资产"
                  style={{
                    border: 'none', background: 'white', color: DS.textSecondary,
                    cursor: 'pointer', fontSize: 15, lineHeight: 1, padding: '5px 6px', borderRadius: 8,
                  }}
                >×</button>
              </Tooltip>
            </span>
          )}
          {pendingGroup && (
            <span style={{
              display: 'inline-flex', alignItems: 'center', gap: 5,
              padding: '5px 8px', borderRadius: 8, background: 'white',
              color: DS.brand, fontSize: 12,
            }}>
              <Package size={14} strokeWidth={2} />
              资产组 · {pendingGroup.items.length} 项
              <button
                onClick={() => setPendingGroup(null)}
                style={{ border: 'none', background: 'transparent', color: DS.textSecondary, cursor: 'pointer' }}
              >×</button>
            </span>
          )}
          {boundGroups.map((binding) => (
            <span key={binding.groupId} style={{
              display: 'inline-flex', alignItems: 'center', gap: 5,
              padding: '5px 8px', borderRadius: 8, background: 'white',
              color: binding.stale ? 'var(--color-error)' : DS.brand, fontSize: 12,
              border: `1px solid ${binding.stale ? 'rgba(217,45,32,0.35)' : DS.border}`,
            }}>
              <Package size={14} strokeWidth={2} />
              资产组 · {binding.media.length} 项{binding.stale ? ' · 已更新' : ''}
              {binding.stale && (
                <button
                  onClick={() => void refreshBoundGroup(binding.groupId)}
                  style={{ border: 'none', background: 'transparent', color: 'var(--color-primary)', cursor: 'pointer', fontSize: 11 }}
                >刷新</button>
              )}
              <button
                onClick={() => void handleAssetGroupConfirm(
                  boundGroups.filter((item) => item.groupId !== binding.groupId).map((item) => item.groupId))}
                style={{ border: 'none', background: 'transparent', color: DS.textSecondary, cursor: 'pointer' }}
              >×</button>
            </span>
          ))}
          {(pickedReferenceVideos.length > 0 || pickedReferenceAudios.length > 0) && (
            <span style={{
              display: 'inline-flex', alignItems: 'center', gap: 5,
              padding: '5px 8px', borderRadius: 8, background: 'white',
              color: DS.textSecondary, fontSize: 12,
            }}>
              参考视频 {pickedReferenceVideos.length} · 音频 {pickedReferenceAudios.length}
              <button
                onClick={clearPickedReferenceMedia}
                style={{ border: 'none', background: 'transparent', color: DS.textSecondary, cursor: 'pointer' }}
              >×</button>
            </span>
          )}
          {fileAttachments.map((attachment, index) => {
            const label = attachment.name || `文档 ${index + 1}`;
            return (
              <span key={`${attachment.url}-${index}`} style={{
                display: 'inline-flex', alignItems: 'center', maxWidth: 238,
                padding: '4px 5px 4px 8px', borderRadius: 8, background: 'white',
                border: `1px solid ${DS.border}`, color: DS.textSecondary, fontSize: 12,
              }}>
                <button
                  type="button"
                  onClick={() => useDocumentPreviewStore.getState().openDocument(attachment)}
                  title={label}
                  aria-label={`打开文档 ${label}`}
                  style={{
                    minWidth: 0, display: 'inline-flex', alignItems: 'center', gap: 6,
                    padding: 0, border: 'none', background: 'transparent',
                    color: DS.textSecondary, cursor: 'pointer', font: 'inherit',
                  }}
                >
                  <FileText size={14} strokeWidth={2} style={{ color: DS.brand, flexShrink: 0 }} />
                  <span style={{
                    minWidth: 0, maxWidth: 170, overflow: 'hidden',
                    textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                  }}>{label}</span>
                </button>
                <Tooltip className="tooltip-fixed" label="移除文件">
                  <button
                    type="button"
                    onClick={() => removePendingAttachment(attachment.url)}
                    aria-label={`移除文件 ${label}`}
                    style={{
                      width: 22, height: 22, display: 'grid', placeItems: 'center',
                      border: 'none', borderRadius: 6, background: 'transparent',
                      color: DS.textCaption, cursor: 'pointer', flexShrink: 0,
                    }}
                  ><X size={12} strokeWidth={2} /></button>
                </Tooltip>
              </span>
            );
          })}
          {(canvasEditContext?.mediaRefs.length ?? 0) > 0 && (
            <button onClick={() => setCanvasMediaOpen(true)} style={{
              display: 'inline-flex', alignItems: 'center', gap: 5,
              padding: '5px 10px', borderRadius: 8, background: 'rgba(65,118,230,0.08)',
              color: DS.brand, fontSize: 12, fontWeight: 600, border: 'none', cursor: 'pointer',
            }}>
              <Paperclip size={13} strokeWidth={2} />
              画布媒体 {canvasEditContext?.mediaRefs.length} 项
            </button>
          )}
          <div style={{ marginLeft: 'auto', display: 'flex', gap: 4 }}>
            <button
              onClick={readOnly ? undefined : () => fileRef.current?.click()}
              disabled={readOnly}
              style={{
                padding: '5px 10px', borderRadius: 8, border: `1px solid ${DS.border}`,
                background: 'white', color: DS.textSecondary, fontSize: 12, cursor: readOnly ? 'not-allowed' : 'pointer',
                opacity: readOnly ? 0.45 : 1,
                display: 'flex', alignItems: 'center', gap: 4,
              }}
            ><Paperclip size={12} strokeWidth={2} /> 加文件</button>
            <button
              onClick={readOnly ? undefined : () => setProjectLibraryOpen(true)}
              disabled={readOnly}
              style={{
                padding: '5px 10px', borderRadius: 8, border: `1px solid ${DS.border}`,
                background: 'white', color: DS.textSecondary, fontSize: 12, cursor: readOnly ? 'not-allowed' : 'pointer',
                opacity: readOnly ? 0.45 : 1,
                display: 'flex', alignItems: 'center', gap: 4,
              }}
            ><Package size={12} strokeWidth={2} /> 加资产</button>
          </div>
        </div>
      )}
      {pendingPicUrls.length > 0 && (
        <div style={{
          display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          padding: '8px 12px', borderRadius: 12, background: DS.hover, fontSize: 14, color: DS.textSecondary,
        }}>
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
            <Paperclip size={13} strokeWidth={1.8} /> 已选当前图片作为参考，请输入你想完善的地方
          </span>
          <button onClick={() => cancelRefine()} style={{ border: 'none', background: 'none', color: DS.textSecondary, cursor: 'pointer', fontSize: 14 }}>✕ 取消</button>
        </div>
      )}
      {(canvasBinding || canvasExitPending) && (
        <div style={{
          display: 'flex', alignItems: 'center', gap: 8, padding: '8px 12px',
          borderRadius: 12, background: 'rgba(65,118,230,0.07)',
          color: DS.brand, fontSize: 13,
        }}>
          <span style={{
            width: 7, height: 7, borderRadius: '50%', background: DS.brand,
            flexShrink: 0,
          }} />
          {canvasExitPending
            ? '本轮结束后将离开画布编辑模式'
            : (canvasBindingStatus || '正在准备画布…')}
        </div>
      )}
      {readOnly && (
        <div style={{
          padding: '8px 12px', borderRadius: 12, background: 'rgba(0,0,0,0.04)',
          color: DS.textSecondary, fontSize: 13,
        }}>
          {readOnlyHint}
        </div>
      )}
      </div>
      </div>
      )}
      <textarea
        ref={inputRef}
        value={text}
        onChange={(e) => setText(e.target.value)}
        onFocus={() => setFocused(true)}
        onBlur={() => setFocused(false)}
        onKeyDown={(e) => {
          // 斜杠命令模式下：面板自己消费 ↑↓/Enter/Esc，这里只阻止 Enter 换行
          if (slashMenuVisible) {
            if (e.key === 'Enter' && !e.shiftKey) e.preventDefault();
            return;
          }
          // I5：中文输入法组合期（选词中）按下 Enter 不发送
          if (e.nativeEvent.isComposing) return;
          if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); handleSend(); }
        }}
        placeholder={readOnly ? '该会话仅可查看历史记录' : dropActive ? (dragKind === 'scene' ? '松开鼠标提交分镜审核' : dragKind === 'library' ? '松开鼠标添加项目库素材' : '松开鼠标上传素材') : canvasBinding ? '正在准备画布…' : canvasExitPending ? '本轮结束后离开画布编辑模式…' : waitingHumanInput || waitingVideoPlan ? '请先完成对话中的确认' : hasSideChatSelections ? '针对选中的文本提问…' : streaming ? '可输入新指令打断当前生成…' : pendingPicUrls.length > 0 ? '例如：把色调调暖一点、换成日系风格…' : '发消息或创建任务… / 使用技能 @ 添加资料'}
        disabled={readOnly || (busy && !canInterruptSend)}
        rows={collapsed ? 1 : 2}
        style={{
          border: 'none', outline: 'none', resize: 'none', background: 'transparent',
          fontSize: 16, lineHeight: 1.6, color: DS.ink, fontFamily: 'inherit',
          boxSizing: 'border-box', overflowY: 'hidden', scrollbarWidth: 'thin',
          ...(collapsed ? { flex: '1 1 auto', minWidth: 0, minHeight: 24 } : {}),
        }}
      />
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          ...(collapsed ? { flex: '0 0 auto' } : {}),
        }}
      >
        <div className={`chat-composer-extra chat-composer-extra--inline${collapsed ? ' is-collapsed' : ''}`}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, minWidth: 0 }}>
          {/* 浮窗（智能体被挤成一个输入框）时，工具栏里保留「查看当前对话」入口 */}
          {onViewConversation && (
            <Tooltip label="查看当前对话">
              <button
                type="button"
                onClick={onViewConversation}
                aria-label="查看当前对话"
                className="chat-plus-trigger"
                onMouseEnter={(e) => { e.currentTarget.style.background = DS.hover; e.currentTarget.style.color = DS.brand; }}
                onMouseLeave={(e) => { e.currentTarget.style.background = 'transparent'; e.currentTarget.style.color = DS.textSecondary; }}
                style={{
                  width: 36, height: 36, borderRadius: 10, border: 'none', background: 'transparent',
                  cursor: 'pointer', color: DS.textSecondary, flexShrink: 0,
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                }}
              >
                <MessagesSquare size={17} strokeWidth={1.8} />
              </button>
            </Tooltip>
          )}
          <ApprovalModeSelector
            mode={approvalMode}
            disabled={readOnly || streaming || !!waitingHumanInput || !!waitingVideoPlan
              || !!canvasBinding || canvasExitPending}
            onChange={(mode, options) => { void setApprovalMode(mode, options); }}
          />
          {/* 生成偏好：自动 / 自定义 + 忽略对话中的生成方案（本地记住，随每轮请求下发） */}
          <ChatGenerationPreferences disabled={readOnly} />
          <div style={{ position: 'relative' }}>
            <Tooltip label="上传素材">
            <button
              ref={menuBtnRef}
              onClick={attachLocked ? undefined : openMenu}
              disabled={attachLocked}
              aria-label="上传"
              className={`chat-plus-trigger${menuOpen ? ' is-open' : ''}`}
              onMouseEnter={(e) => { e.currentTarget.style.background = DS.hover; e.currentTarget.style.color = DS.brand; }}
              onMouseLeave={(e) => { e.currentTarget.style.background = 'transparent'; e.currentTarget.style.color = DS.textSecondary; }}
              style={{
                width: 36, height: 36, borderRadius: 10, border: 'none', background: 'transparent',
                fontSize: 17, cursor: attachLocked ? 'not-allowed' : 'pointer', color: DS.textSecondary, flexShrink: 0,
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                opacity: attachLocked ? 0.45 : 1,
              }}
            >{/** DeepSeek 风格上传图标：圆圈+加号 */}<PlusCircle size={20} strokeWidth={1.5} /></button>
            </Tooltip>
            <input ref={fileRef} type="file" multiple hidden disabled={attachLocked} onChange={handleFile} />
            {/* 下拉菜单：portal 到 body */}
            {menuPresence.mounted && menuPos && createPortal(
              <div ref={menuRef} className={`chat-plus-menu${menuPresence.visible ? ' is-open' : ''}${menuPresence.closing ? ' is-closing' : ''}`} style={{
                position: 'fixed', top: menuPos.top, left: menuPos.left,
                background: 'white', border: `1px solid ${DS.border}`,
                borderRadius: 10, boxShadow: '0 4px 16px rgba(0,0,0,0.12)',
                padding: 4, zIndex: 1000, minWidth: 140,
              }}>
                <UploadMaterialMenu
                  onClose={() => setMenuOpen(false)}
                  onLocalFile={() => fileRef.current?.click()}
                  onProjectLibrary={() => setProjectLibraryOpen(true)}
                  onScene={() => setSceneModalOpen(true)}
                />
              </div>,
              document.body,
            )}
          </div>
        </div>
        </div>
        {/* 右侧：麦克风 + 发送，紧贴排列 */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 0 }}>
          <span className={`chat-composer-extra chat-composer-extra--inline${collapsed ? ' is-collapsed' : ''}`}>
          <MicButton
            onToggle={handleMicToggle}
            onPcm={handlePcm}
            // 录音中不禁用（识别中也能停止）；流式回复中非录音态禁用开始
            disabled={readOnly || (busy && !recording)}
          />
          </span>
          {streaming && !agentVideoTask && !canSend ? (
            <button
              onClick={stopGeneration}
              style={{
                height: 38, padding: '0 16px', borderRadius: 12, border: 'none',
                background: 'rgba(217, 45, 32, 0.9)', color: 'white', fontSize: 14, fontWeight: 500,
                cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6,
                transition: 'background 0.15s',
              }}
              onMouseEnter={(e) => { (e.target as HTMLElement).style.background = 'rgb(217, 45, 32)'; }}
              onMouseLeave={(e) => { (e.target as HTMLElement).style.background = 'rgba(217, 45, 32, 0.9)'; }}
            ><span style={{ width: 9, height: 9, borderRadius: 2, background: 'white', display: 'inline-block' }} />停止生成</button>
          ) : (
            <button
              onClick={handleSend}
              disabled={!canSend}
              style={{
                height: 38, padding: '0 22px', borderRadius: 12, border: 'none',
                background: DS.brand, color: 'white', fontSize: 15, fontWeight: 500,
                cursor: canSend ? 'pointer' : 'not-allowed',
                opacity: canSend ? 1 : 0.45,
                transition: 'background 0.15s',
              }}
              onMouseEnter={(e) => { if (canSend) (e.target as HTMLElement).style.background = DS.brandHover; }}
              onMouseLeave={(e) => { (e.target as HTMLElement).style.background = DS.brand; }}
            >{canInterruptSend ? '停止并发送' : '发送'}</button>
          )}
        </div>
      </div>

      {/* 分镜选择弹窗 */}
      <SceneSelectorModal
        open={sceneModalOpen}
        onClose={() => setSceneModalOpen(false)}
        onConfirm={handleSceneConfirm}
      />

      <ProjectLibraryPickerModal
        open={projectLibraryOpen}
        projectId={projectId}
        type="image"
        allowedTypes={['image', 'video', 'audio', 'document']}
        purpose="image"
        assetGroups={{
          selectedIds: boundGroups.map((binding) => binding.groupId),
          onConfirm: handleAssetGroupConfirm,
        }}
        onClose={() => setProjectLibraryOpen(false)}
        onConfirm={async (references) => {
          addPendingAttachments(references.map((reference) => ({
            url: reference.url,
            name: reference.fileName || `${reference.type} 素材`,
            mimeType: reference.mimeType || `${reference.type}/*`,
            sizeBytes: reference.sizeBytes,
            kind: reference.type === 'document' ? 'file' : reference.type,
            source: 'library' as const,
            assetId: reference.sourceType === 'library_asset' ? reference.sourceId : undefined,
            mediaId: reference.mediaId,
          })));
        }}
      />

      {/* 参考图列表弹窗：指定移除单张（2026-08-28 用户反馈：不再一键清空，改为逐张指定移除） */}
      {removeModalOpen && createPortal(
        <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.35)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1200 }}
          onClick={() => setRemoveModalOpen(false)}>
          <div onClick={(e) => e.stopPropagation()} style={{
            width: 480, maxWidth: '92vw', background: 'white', borderRadius: 16, padding: 18,
            boxShadow: '0 8px 32px rgba(0,0,0,0.18)', display: 'flex', flexDirection: 'column', gap: 14,
          }}>
            <div style={{ fontSize: 15, fontWeight: 600 }}>指定移除参考图（共 {refImageUrls.length} 张）</div>
            <div style={{ maxHeight: 320, overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: 10 }}>
              {refImageUrls.map((u, i) => (
                <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '6px 8px', borderRadius: 12, background: DS.hover }}>
                  <button
                    type="button"
                    onClick={() => setPreviewMedia({ url: u, type: 'image' })}
                    aria-label={`放大预览第 ${i + 1} 张参考图`}
                    style={{ padding: 0, border: 'none', background: 'transparent', cursor: 'zoom-in', flexShrink: 0 }}
                  >
                    <img src={assetUrl(u)} alt={`参考图 ${i + 1}`} style={{ width: 48, height: 48, objectFit: 'cover', borderRadius: 10, display: 'block' }} />
                  </button>
                  <span style={{ fontSize: 12, color: DS.textCaption, flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {pendingAttachments.find((attachment) => attachment.url === u)?.name || `图 ${i + 1}`}
                  </span>
                  <button
                    onClick={() => removeRefImageUrl(i)}
                    style={{ border: 'none', background: 'none', color: '#d9534f', cursor: 'pointer', fontSize: 19, lineHeight: 1 }}
                    aria-label={`移除第 ${i + 1} 张`}
                  >×</button>
                </div>
              ))}
            </div>
            <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
              <button
                onClick={() => setRemoveModalOpen(false)}
                style={{ border: 'none', background: DS.hover, color: DS.ink, cursor: 'pointer', fontSize: 14, padding: '7px 18px', borderRadius: 10 }}
              >完成</button>
            </div>
          </div>
        </div>,
        document.body
      )}

      {canvasMediaOpen && canvasEditContext && createPortal(
        <div
          style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.35)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1320 }}
          onClick={() => setCanvasMediaOpen(false)}
        >
          <div
            onClick={(event) => event.stopPropagation()}
            style={{
              width: 620, maxWidth: '92vw', maxHeight: '82vh', background: 'white', borderRadius: 16,
              padding: 18, boxShadow: '0 10px 36px rgba(0,0,0,0.2)', display: 'flex', flexDirection: 'column', gap: 14,
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
              <span style={{ fontSize: 16, fontWeight: 600, color: DS.ink }}>画布媒体 · {canvasEditContext.mediaRefs.length} 项</span>
              <button
                onClick={() => setCanvasMediaOpen(false)}
                aria-label="关闭"
                style={{ border: 'none', background: 'transparent', color: DS.textSecondary, cursor: 'pointer', padding: 4, display: 'grid', placeItems: 'center' }}
              ><X size={17} strokeWidth={2} /></button>
            </div>
            <div style={{ overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: 10, minHeight: 120 }}>
              {canvasEditContext.mediaRefs.length === 0 && (
                <div style={{ padding: '36px 12px', textAlign: 'center', color: DS.textCaption, fontSize: 13 }}>
                  暂无画布媒体
                </div>
              )}
              {canvasEditContext.mediaRefs.map((ref, index) => (
                <div
                  key={`${ref.kind}:${ref.assetId ?? ref.url ?? index}`}
                  style={{
                    display: 'flex', alignItems: 'center', gap: 12, padding: 10, borderRadius: 12,
                    border: `1px solid ${DS.border}`, background: DS.hover,
                  }}
                >
                  {ref.kind === 'video' ? (
                    <video src={ref.url} muted playsInline style={{ width: 64, height: 64, borderRadius: 10, objectFit: 'cover', background: '#111', flexShrink: 0 }} />
                  ) : ref.kind === 'image' ? (
                    <img src={ref.url} alt={ref.name ?? `画布媒体 ${index + 1}`} style={{ width: 64, height: 64, borderRadius: 10, objectFit: 'cover', flexShrink: 0 }} />
                  ) : (
                    <span style={{ width: 64, height: 64, borderRadius: 10, flexShrink: 0, background: 'white', display: 'grid', placeItems: 'center', color: DS.textSecondary }}>
                      <Paperclip size={20} strokeWidth={1.8} />
                    </span>
                  )}
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ fontSize: 13, fontWeight: 600, color: DS.ink, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                      {ref.name ?? `${ref.kind} ${index + 1}`}
                    </div>
                    <div style={{ marginTop: 4, fontSize: 11, color: DS.textCaption }}>
                      {ref.kind === 'image' ? '图片' : ref.kind === 'video' ? '视频' : ref.kind === 'audio' ? '音频' : '文件'}
                    </div>
                  </div>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                    <button
                      onClick={() => moveCanvasMediaRef(ref.url ?? '', -1)}
                      disabled={index === 0}
                      aria-label="上移"
                      style={{ width: 30, height: 30, border: 'none', borderRadius: 8, background: 'white', color: DS.textSecondary, cursor: index === 0 ? 'not-allowed' : 'pointer', opacity: index === 0 ? 0.35 : 1 }}
                    ><ChevronUp size={15} /></button>
                    <button
                      onClick={() => moveCanvasMediaRef(ref.url ?? '', 1)}
                      disabled={index === canvasEditContext.mediaRefs.length - 1}
                      aria-label="下移"
                      style={{ width: 30, height: 30, border: 'none', borderRadius: 8, background: 'white', color: DS.textSecondary, cursor: index === canvasEditContext.mediaRefs.length - 1 ? 'not-allowed' : 'pointer', opacity: index === canvasEditContext.mediaRefs.length - 1 ? 0.35 : 1 }}
                    ><ChevronDown size={15} /></button>
                    <button
                      onClick={() => removeCanvasMediaRef(ref.url)}
                      aria-label="移除"
                      style={{ width: 30, height: 30, border: 'none', borderRadius: 8, background: 'rgba(217,45,32,0.08)', color: 'rgb(190,45,35)', cursor: 'pointer' }}
                    ><Trash2 size={15} /></button>
                  </div>
                </div>
              ))}
            </div>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', borderTop: `1px solid ${DS.border}`, paddingTop: 12 }}>
              <button
                onClick={() => fileRef.current?.click()}
                style={{
                  display: 'inline-flex', alignItems: 'center', gap: 6, padding: '8px 14px', borderRadius: 10,
                  border: 'none', background: DS.brand, color: 'white', fontSize: 13, fontWeight: 600, cursor: 'pointer',
                }}
              ><Plus size={15} strokeWidth={2.2} /> 重新加入图片或视频</button>
              <button
                onClick={() => setCanvasMediaOpen(false)}
                style={{ padding: '8px 16px', borderRadius: 10, border: `1px solid ${DS.border}`, background: 'white', color: DS.textSecondary, fontSize: 13, cursor: 'pointer' }}
              >完成</button>
            </div>
          </div>
        </div>,
        document.body,
      )}

      {/* 上传素材前填写用途/类型 */}
      {uploadDrafts.length > 0 && createPortal(
        <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.35)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1300 }}
          onClick={closeUploadDrafts}>
          <div onClick={(e) => e.stopPropagation()} style={{
            width: 520, maxWidth: '92vw', background: 'white', borderRadius: 16, padding: 18,
            boxShadow: '0 8px 32px rgba(0,0,0,0.18)', display: 'flex', flexDirection: 'column', gap: 12,
          }}>
            <div style={{ fontSize: 15, fontWeight: 600 }}>上传附件 · 写清每个文件是干什么的</div>
            <div style={{ fontSize: 12, color: DS.textCaption, lineHeight: 1.5 }}>
              图片会作为参考图参与识别与生成，其他文件会作为对话附件保留。资产请在「加资产」里选择。
            </div>
            <div style={{ maxHeight: 340, overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: 10 }}>
              {uploadDrafts.map((d, i) => (
                <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '8px 10px', borderRadius: 12, background: DS.hover, border: `1px solid ${DS.border}` }}>
                  {d.file.type.startsWith('video/') ? (
                    <video src={d.preview} muted style={{ width: 54, height: 54, objectFit: 'cover', borderRadius: 10, flexShrink: 0 }} />
                  ) : d.file.type.startsWith('image/') ? (
                    <img src={d.preview} alt={`素材${i + 1}`} style={{ width: 54, height: 54, objectFit: 'cover', borderRadius: 10, flexShrink: 0 }} />
                  ) : (
                    <span style={{ width: 54, height: 54, borderRadius: 10, flexShrink: 0, background: DS.hover, color: DS.textSecondary, display: 'grid', placeItems: 'center' }}>
                      <Paperclip size={18} strokeWidth={1.8} />
                    </span>
                  )}
                  <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', gap: 6 }}>
                    <input
                      value={d.purpose}
                      onChange={(e) => setUploadDrafts((prev) => prev.map((x, idx) => idx === i ? { ...x, purpose: e.target.value } : x))}
                      placeholder="这个文件是干什么的？（可留空，图片会自动识别）"
                      style={{
                        width: '100%', border: `1px solid ${DS.border}`, borderRadius: 8, padding: '7px 10px',
                        fontSize: 13, color: DS.ink, outline: 'none', background: 'white', boxSizing: 'border-box',
                      }}
                    />
                  </div>
                </div>
              ))}
            </div>
            {uploadError && <div style={{ color: 'rgb(217, 45, 32)', fontSize: 13 }}>{uploadError}</div>}
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
              <button onClick={closeUploadDrafts} style={{
                padding: '8px 16px', borderRadius: 10, border: `1px solid ${DS.border}`, background: 'white',
                color: DS.textSecondary, fontSize: 14, cursor: 'pointer',
              }}>取消</button>
              <button onClick={handleUploadConfirm} style={{
                padding: '8px 16px', borderRadius: 10, border: 'none', background: DS.brand, color: 'white',
                fontSize: 14, cursor: 'pointer',
              }}>上传 {uploadDrafts.length} 个文件</button>
            </div>
          </div>
        </div>,
        document.body
      )}

      {/* 「上传资产」从资产库选择资产：只记录资产 id，不把资产图片混入参考图列表 */}
      {assetOpen && createPortal(
        <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.35)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1100 }}
          onClick={() => setAssetOpen(false)}>
          <div onClick={(e) => e.stopPropagation()} style={{
            width: 420, maxWidth: '90vw', background: 'white', borderRadius: 16, padding: 18,
            boxShadow: '0 8px 32px rgba(0,0,0,0.18)', display: 'flex', flexDirection: 'column', gap: 12,
          }}>
            <div style={{ fontSize: 15, fontWeight: 600 }}>选择资产（可多选，再次点击取消）</div>
            <div style={{ fontSize: 12, color: DS.textCaption }}>资产图会在生成阶段按资产读取，不会加入“参考图”列表。</div>
            {assetLoading ? <div style={{ color: DS.textSecondary, fontSize: 13, padding: '12px 0' }}>加载中…</div>
              : assetList.length === 0 ? <div style={{ color: DS.textSecondary, fontSize: 13, padding: '12px 0' }}>当前项目AI资产库为空，可先在「AI资产库」创建资产</div>
              : <div style={{ maxHeight: 320, overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: 8 }}>
                  {assetList.map((a) => (
                    <button key={a.id} onClick={() => toggleAsset(a.id)} style={{
                      display: 'flex', alignItems: 'center', gap: 10, width: '100%', padding: 8, borderRadius: 12,
                      border: `1px solid ${assetSelected.has(a.id) ? DS.brand : DS.border}`,
                      background: assetSelected.has(a.id) ? DS.hover : 'white', cursor: 'pointer', textAlign: 'left',
                    }}>
                      {a.images?.[0]
                        ? <img src={assetUrl(a.images[0].url)} style={{ width: 44, height: 44, objectFit: 'cover', borderRadius: 8, flexShrink: 0 }} />
                        : <div style={{ width: 44, height: 44, borderRadius: 8, background: DS.hover, display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 11, color: DS.textCaption, flexShrink: 0 }}>无图</div>}
                      <div style={{ flex: 1, minWidth: 0 }}>
                        <div style={{ fontSize: 13, fontWeight: 500, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{a.name}</div>
                        <div style={{ fontSize: 11, color: DS.textCaption }}>{ASSET_TYPE_LABEL[a.type]}{a.projectId ? '' : '·全局'} · {a.images?.length ?? 0} 图</div>
                      </div>
                      <span style={{ fontSize: 16, color: assetSelected.has(a.id) ? DS.brand : 'transparent' }}>✓</span>
                    </button>
                  ))}
                </div>}
            {assetError && <div style={{ color: 'rgb(217,45,32)', fontSize: 13 }}>{assetError}</div>}
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
              <button onClick={() => setAssetOpen(false)} style={{
                padding: '8px 16px', borderRadius: 10, border: `1px solid ${DS.border}`, background: 'white',
                color: DS.textSecondary, fontSize: 14, cursor: 'pointer',
              }}>取消</button>
              <button onClick={handleAssetConfirm} style={{
                padding: '8px 16px', borderRadius: 10, border: 'none', background: DS.brand, color: 'white',
                fontSize: 14, cursor: 'pointer', opacity: 1,
              }}>确认选择{assetSelected.size ? `（${assetSelected.size}）` : ''}</button>
            </div>
          </div>
        </div>,
        document.body
      )}
      <ImagePreviewModal
        url={previewMedia?.url ?? null}
        type={previewMedia?.type}
        onClose={() => setPreviewMedia(null)}
      />
    </div>
    {/* 底部状态栏（位于输入框卡片下方）：收缩态整块收起，输入卡下沉贴到原计数器位置 */}
    {!docked && (
      <div
        className={`chat-status-bar-slot${collapsed ? ' is-collapsed' : ''}`}
        inert={collapsed}
      >
        <div className="chat-status-bar-slot__inner">
          <ChatStatusBar compact={compact} disabled={readOnly} collapsed={collapsed} />
        </div>
      </div>
    )}
    </>
  );
}
