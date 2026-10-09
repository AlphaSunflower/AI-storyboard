import { create } from 'zustand';

export interface ChatAnnotation {
  id: string;
  conversationId: string;
  messageId: string;
  quote: string;
  comment: string;
  createdAt: number;
  /** 同一消息里出现多段相同文字时，用于标记是第几处（0-based）。 */
  occurrence?: number;
}

export interface NewChatAnnotation {
  conversationId: string;
  messageId: string;
  quote: string;
  comment?: string;
  occurrence?: number;
}

interface ChatAnnotationState {
  annotations: ChatAnnotation[];
  /** 正在编辑的注释 id（跨组件共享：输入框上的胶囊也能触发对应消息里的编辑框）。 */
  editingId: string | null;
  addAnnotation: (input: NewChatAnnotation) => ChatAnnotation | null;
  updateComment: (id: string, comment: string) => void;
  removeAnnotation: (id: string) => void;
  removeForMessage: (messageId: string) => void;
  /** 取出并移除某会话的全部待发注释（发送时一次性消费）。 */
  consumeConversation: (conversationId: string) => ChatAnnotation[];
  openEditor: (id: string) => void;
  closeEditor: () => void;
  reset: () => void;
}

let annotationSeq = 0;

function nextAnnotationId(): string {
  annotationSeq += 1;
  return `annotation-${Date.now()}-${annotationSeq}`;
}

/**
 * /chat 消息内注释态（选中文字 → 评论）。
 * 按需求只存在于当前页面内存：刷新 / 重连即清空，不落 localStorage，也不走后端。
 */
export const useChatAnnotationStore = create<ChatAnnotationState>((set, get) => ({
  annotations: [],
  editingId: null,
  addAnnotation: (input) => {
    const quote = input.quote.trim();
    if (!quote) return null;
    const annotation: ChatAnnotation = {
      id: nextAnnotationId(),
      conversationId: input.conversationId,
      messageId: input.messageId,
      quote,
      comment: (input.comment ?? '').trim(),
      createdAt: Date.now(),
      occurrence: input.occurrence,
    };
    set((state) => ({ annotations: [...state.annotations, annotation] }));
    return annotation;
  },
  updateComment: (id, comment) => set((state) => ({
    annotations: state.annotations.map((annotation) =>
      annotation.id === id ? { ...annotation, comment: comment.trim() } : annotation),
  })),
  removeAnnotation: (id) => set((state) => ({
    annotations: state.annotations.filter((annotation) => annotation.id !== id),
    editingId: state.editingId === id ? null : state.editingId,
  })),
  removeForMessage: (messageId) => set((state) => ({
    annotations: state.annotations.filter((annotation) => annotation.messageId !== messageId),
    editingId: state.annotations.some((annotation) => annotation.id === state.editingId && annotation.messageId === messageId)
      ? null
      : state.editingId,
  })),
  consumeConversation: (conversationId) => {
    const all = get().annotations;
    const consumed = all.filter((annotation) => annotation.conversationId === conversationId);
    if (consumed.length > 0) {
      const editingConsumed = consumed.some((annotation) => annotation.id === get().editingId);
      set({
        annotations: all.filter((annotation) => annotation.conversationId !== conversationId),
        editingId: editingConsumed ? null : get().editingId,
      });
    }
    return consumed;
  },
  openEditor: (id) => set({ editingId: id }),
  closeEditor: () => set({ editingId: null }),
  reset: () => set({ annotations: [], editingId: null }),
}));

export function selectAnnotationsForMessage(
  annotations: ChatAnnotation[],
  messageId?: string,
): ChatAnnotation[] {
  if (!messageId) return [];
  return annotations.filter((annotation) => annotation.messageId === messageId);
}
