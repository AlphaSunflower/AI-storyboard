import { MessageSquareText, X } from 'lucide-react';
import { useSideChatSelectionStore } from '../../stores/sideChatSelectionStore';
import { currentSideChatId, postSideChatSelectionUpdate } from '../../lib/sideChatSelectionSync';
import './SideChatContextChip.css';

export function SideChatContextChip() {
  const selections = useSideChatSelectionStore((state) => state.selections);
  const removeSelection = useSideChatSelectionStore((state) => state.removeSelection);
  if (selections.length === 0) return null;

  const remove = (id: string) => {
    removeSelection(id);
    const sideChatId = currentSideChatId();
    if (sideChatId) {
      postSideChatSelectionUpdate(
        sideChatId,
        useSideChatSelectionStore.getState().selections,
      );
    }
  };

  return (
    <div className="side-chat-context" tabIndex={0}>
      <span className="side-chat-context__chip">
        <MessageSquareText size={14} strokeWidth={1.9} />
        {selections.length} 个已选文本片段
      </span>
      <div className="side-chat-context__popover" role="region" aria-label="已选文本片段">
        <div className="side-chat-context__head">
          <strong>问答上下文</strong>
          <span>发送时会一并提供给智能体</span>
        </div>
        <div className="side-chat-context__list">
          {selections.map((selection, index) => (
            <article key={selection.id} className="side-chat-context__item">
              <span>{index + 1}</span>
              <p>{selection.text}</p>
              <button
                type="button"
                onClick={() => remove(selection.id)}
                title="移除该文本片段"
                aria-label={`移除文本片段 ${index + 1}`}
              >
                <X size={13} strokeWidth={2} />
              </button>
            </article>
          ))}
        </div>
      </div>
    </div>
  );
}
