import { useState } from 'react';
import { ArrowDown, ArrowUp, ChevronDown, ChevronUp, ChevronsUp, ListOrdered, X } from 'lucide-react';
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
 * 队列只存未来要发的消息，不进入当前回合；当前回合结束后由 ChatComposer 逐条取出。
 */
export function SendQueuePanel({ conversationId }: { conversationId: string | null }) {
  const items = useSendQueueStore((state) => (
    conversationId ? state.queues[conversationId] ?? EMPTY_QUEUE : EMPTY_QUEUE
  ));
  const remove = useSendQueueStore((state) => state.remove);
  const clear = useSendQueueStore((state) => state.clear);
  const move = useSendQueueStore((state) => state.move);
  const promote = useSendQueueStore((state) => state.promote);
  const [expanded, setExpanded] = useState(false);

  if (!conversationId || items.length === 0) return null;

  const visible = expanded ? items : items.slice(0, 2);
  const hiddenCount = items.length - visible.length;

  return (
    <section className="chat-send-queue" aria-label="发送队列">
      <header className="chat-send-queue__head">
        <span className="chat-send-queue__icon" aria-hidden="true">
          <ListOrdered size={14} strokeWidth={2} />
        </span>
        <span className="chat-send-queue__title">发送队列</span>
        <span className="chat-send-queue__count">{items.length}</span>
        <span className="chat-send-queue__hint">当前回合结束后按顺序发送</span>
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
          return (
            <li className="chat-send-queue__item" key={item.id}>
              <span className="chat-send-queue__index" aria-hidden="true">{index + 1}</span>
              <span className="chat-send-queue__content" title={item.content}>
                {item.content}
              </span>
              <span className="chat-send-queue__meta">
                {meta.length > 0 && <span>{meta.join(' · ')}</span>}
                <time>{formatTime(item.createdAt)}</time>
              </span>
              <span className="chat-send-queue__actions">
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
