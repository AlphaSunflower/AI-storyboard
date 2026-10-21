import { useEffect, useRef, useState } from 'react';
import {
  ArrowDown, ArrowUp, Check, ChevronDown, ChevronUp, ChevronsUp,
  ListOrdered, Pencil, Send, X,
} from 'lucide-react';
import {
  EMPTY_QUEUE,
  useSendQueueStore,
  type QueuedSendItem,
} from '../../stores/sendQueueStore';

function formatTime(timestamp: number): string {
  const date = new Date(timestamp);
  if (Number.isNaN(date.getTime())) return '';
  return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
}

function itemMeta(item: QueuedSendItem): string[] {
  const meta: string[] = [];
  const attachments = item.opts?.attachments?.length ?? 0;
  const images = item.opts?.picUrls?.length ?? 0;
  const references = item.opts?.references?.length ?? 0;
  if (attachments > 0) meta.push(`${attachments} 个附件`);
  if (images > 0) meta.push(`${images} 张参考图`);
  if (references > 0) meta.push(`${references} 个引用`);
  return meta;
}

/**
 * 发送队列面板：繁忙时按「排队发送」的消息在这里可见、可排序、可删除。
 *
 * <p>除了排序，排队中的消息还支持两种即时操作：</p>
 * <ul>
 *   <li><b>插队发送</b>：不等当前回合结束，立即把这条发出去。当前正在生成时由
 *       `ChatComposer` 先停止本回合再发送（会弹确认），队列里其余消息保持原顺序。</li>
 *   <li><b>重新编辑</b>：就地改正文，附件 / 参考图 / `@` 引用快照原样保留；
 *       编辑期间暂缓自动出队，避免把未保存的旧正文发走。</li>
 * </ul>
 */
export function SendQueuePanel({
  conversationId,
  onSendNow,
}: {
  conversationId: string | null;
  /** 插队发送：打断/发送逻辑在 ChatComposer（需要知道 busy 状态），这里只负责触发。 */
  onSendNow?: (item: QueuedSendItem) => void;
}) {
  const items = useSendQueueStore((state) => (
    conversationId ? state.queues[conversationId] ?? EMPTY_QUEUE : EMPTY_QUEUE
  ));
  const remove = useSendQueueStore((state) => state.remove);
  const clear = useSendQueueStore((state) => state.clear);
  const move = useSendQueueStore((state) => state.move);
  const promote = useSendQueueStore((state) => state.promote);
  const update = useSendQueueStore((state) => state.update);
  const holdQueue = useSendQueueStore((state) => state.holdQueue);
  const releaseQueue = useSendQueueStore((state) => state.releaseQueue);
  const [expanded, setExpanded] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [draft, setDraft] = useState('');
  /** 当前握着暂缓的是哪个会话（切换会话时也要能释放旧会话的 hold）。 */
  const heldConversationRef = useRef<string | null>(null);

  const releaseHold = () => {
    const heldConversation = heldConversationRef.current;
    if (!heldConversation) return;
    heldConversationRef.current = null;
    releaseQueue(heldConversation);
  };

  const startEdit = (item: QueuedSendItem) => {
    if (!conversationId) return;
    setEditingId(item.id);
    setDraft(item.content);
    if (!heldConversationRef.current) {
      heldConversationRef.current = conversationId;
      holdQueue(conversationId);
    }
  };

  const stopEdit = () => {
    setEditingId(null);
    setDraft('');
    releaseHold();
  };

  // 切换会话 / 组件卸载：别把暂缓状态留在队列上。
  useEffect(() => {
    setEditingId(null);
    setDraft('');
    releaseHold();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [conversationId]);

  useEffect(() => () => releaseHold(), []); // eslint-disable-line react-hooks/exhaustive-deps

  // 编辑中的消息被自动发送/删除后，编辑态要收掉，暂缓也必须释放。
  useEffect(() => {
    if (!editingId) return;
    if (items.some((item) => item.id === editingId)) return;
    setEditingId(null);
    setDraft('');
    releaseHold();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [items, editingId]);

  if (!conversationId || items.length === 0) return null;

  const visible = expanded ? items : items.slice(0, 2);
  const hiddenCount = items.length - visible.length;

  const saveEdit = (item: QueuedSendItem) => {
    const content = draft.trim();
    if (!content) return;
    update(conversationId, item.id, content);
    stopEdit();
  };

  return (
    <section className="chat-send-queue" aria-label="发送队列">
      <header className="chat-send-queue__head">
        <span className="chat-send-queue__icon" aria-hidden="true">
          <ListOrdered size={14} strokeWidth={2} />
        </span>
        <span className="chat-send-queue__title">发送队列</span>
        <span className="chat-send-queue__count">{items.length}</span>
        <span className="chat-send-queue__hint">当前回合结束后按顺序发送，也可直接插队发送</span>
        <button
          type="button"
          className="chat-send-queue__clear"
          onClick={() => {
            if (window.confirm(`清空 ${items.length} 条排队消息？`)) clear(conversationId);
          }}
        >
          清空
        </button>
      </header>

      <ol className="chat-send-queue__list">
        {visible.map((item, index) => {
          const meta = itemMeta(item);
          const editing = editingId === item.id;
          return (
            <li
              className={`chat-send-queue__item${editing ? ' is-editing' : ''}`}
              key={item.id}
            >
              <span className="chat-send-queue__index" aria-hidden="true">{index + 1}</span>
              {editing ? (
                <textarea
                  className="chat-send-queue__edit"
                  aria-label={`编辑队列消息 ${index + 1} 内容`}
                  value={draft}
                  rows={Math.min(4, Math.max(1, draft.split('\n').length))}
                  autoFocus
                  onChange={(event) => setDraft(event.target.value)}
                  onKeyDown={(event) => {
                    if (event.key === 'Escape') {
                      event.preventDefault();
                      stopEdit();
                      return;
                    }
                    // 输入法组字中的回车不能当成保存
                    if (event.key === 'Enter' && !event.shiftKey
                      && !(event.nativeEvent as KeyboardEvent).isComposing) {
                      event.preventDefault();
                      saveEdit(item);
                    }
                  }}
                />
              ) : (
                <span className="chat-send-queue__content" title={item.content}>
                  {item.content}
                </span>
              )}
              {!editing && (
                <span className="chat-send-queue__meta">
                  {meta.length > 0 && <span>{meta.join(' · ')}</span>}
                  <time>{formatTime(item.createdAt)}</time>
                </span>
              )}
              <span className="chat-send-queue__actions">
                {editing ? (
                  <>
                    <button
                      type="button"
                      className="is-primary"
                      aria-label={`保存队列消息 ${index + 1}`}
                      title="保存（Enter）"
                      disabled={!draft.trim()}
                      onClick={() => saveEdit(item)}
                    >
                      <Check size={13} strokeWidth={2.6} />
                    </button>
                    <button
                      type="button"
                      aria-label={`取消编辑队列消息 ${index + 1}`}
                      title="取消（Esc）"
                      onClick={stopEdit}
                    >
                      <X size={13} strokeWidth={2.4} />
                    </button>
                  </>
                ) : (
                  <>
                    {onSendNow && (
                      <button
                        type="button"
                        className="is-primary"
                        aria-label={`插队发送队列消息 ${index + 1}`}
                        title="插队发送（当前正在生成时会先停止当前回复）"
                        onClick={() => onSendNow(item)}
                      >
                        <Send size={13} strokeWidth={2.2} />
                      </button>
                    )}
                    <button
                      type="button"
                      aria-label={`编辑队列消息 ${index + 1}`}
                      title="重新编辑"
                      onClick={() => startEdit(item)}
                    >
                      <Pencil size={13} strokeWidth={2.2} />
                    </button>
                    <button
                      type="button"
                      aria-label={`置顶队列消息 ${index + 1}`}
                      title="置顶"
                      disabled={index === 0}
                      onClick={() => promote(conversationId, item.id)}
                    >
                      <ChevronsUp size={13} strokeWidth={2.4} />
                    </button>
                    <button
                      type="button"
                      aria-label={`上移队列消息 ${index + 1}`}
                      title="上移"
                      disabled={index === 0}
                      onClick={() => move(conversationId, item.id, -1)}
                    >
                      <ArrowUp size={13} strokeWidth={2.2} />
                    </button>
                    <button
                      type="button"
                      aria-label={`下移队列消息 ${index + 1}`}
                      title="下移"
                      disabled={index === visible.length - 1 && hiddenCount === 0}
                      onClick={() => move(conversationId, item.id, 1)}
                    >
                      <ArrowDown size={13} strokeWidth={2.2} />
                    </button>
                    <button
                      type="button"
                      className="is-danger"
                      aria-label={`删除队列消息 ${index + 1}`}
                      title="删除"
                      onClick={() => remove(conversationId, item.id)}
                    >
                      <X size={13} strokeWidth={2.4} />
                    </button>
                  </>
                )}
              </span>
            </li>
          );
        })}
      </ol>

      {(hiddenCount > 0 || expanded) && (
        <button
          type="button"
          className="chat-send-queue__toggle"
          onClick={() => setExpanded((value) => !value)}
        >
          {expanded ? (
            <>收起队列 <ChevronUp size={13} /></>
          ) : (
            <>还有 {hiddenCount} 条 <ChevronDown size={13} /></>
          )}
        </button>
      )}
    </section>
  );
}
