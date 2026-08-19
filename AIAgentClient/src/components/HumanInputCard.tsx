import { useState } from 'react';
import { useAgentStore, type HumanInputInfo } from '../stores/agentStore';

/**
 * HITL 确认卡片：选项垂直堆叠（flex flex-col），一行一个按钮。
 * HITL 后新建气泡（pendingAssistantId = null）由 store submitHumanInput 处理。
 */
export function HumanInputCard({ info }: { info: HumanInputInfo }) {
  const submitHumanInput = useAgentStore((s) => s.submitHumanInput);
  const streaming = useAgentStore((s) => s.streaming);
  const expired = info.expirationTime > 0 && Date.now() / 1000 > info.expirationTime;

  const [customOpen, setCustomOpen] = useState(false);
  const [customText, setCustomText] = useState('');

  const handleActionClick = (a: { id: string; title: string }) => {
    if (a.id === 'custom') {
      setCustomOpen(true);
    } else {
      submitHumanInput(a.id);
    }
  };

  return (
    <div className="flex justify-start mb-2.5">
      <div
        className="text-left"
        style={{
          maxWidth: '82%',
          padding: 12,
          borderRadius: 12,
          background: 'white',
          border: '1px solid var(--color-hairline)',
          boxShadow: '0 2px 8px rgba(20,20,19,0.06)',
        }}
      >
        <div className="text-xs mb-1.5 tracking-wide" style={{ color: 'var(--color-muted)' }}>
          需要您确认
        </div>
        <div className="text-sm leading-relaxed mb-2.5 whitespace-pre-wrap" style={{ color: 'var(--color-ink)' }}>
          {info.formContent || '请确认是否继续？'}
        </div>

        {expired ? (
          <div className="text-xs" style={{ color: '#e67e22' }}>确认已过期，请重新发起对话</div>
        ) : customOpen ? (
          <div>
            <input
              autoFocus
              value={customText}
              onChange={(e) => setCustomText(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && customText.trim() && !streaming) {
                  submitHumanInput('custom', customText.trim());
                }
              }}
              placeholder="输入你的想法…"
              className="w-full px-2.5 py-1.5 text-sm outline-none mb-2"
              style={{ border: '1px solid var(--color-hairline)', borderRadius: 'var(--rounded-md)' }}
            />
            <div className="flex gap-2">
              <button
                disabled={streaming || !customText.trim()}
                onClick={() => submitHumanInput('custom', customText.trim())}
                className="px-4 py-1.5 text-sm rounded-md"
                style={{
                  background: 'var(--color-primary)',
                  color: 'white',
                  opacity: streaming || !customText.trim() ? 0.6 : 1,
                  cursor: streaming || !customText.trim() ? 'not-allowed' : 'pointer',
                }}
              >
                确认输入
              </button>
              <button
                disabled={streaming}
                onClick={() => { setCustomOpen(false); setCustomText(''); }}
                className="px-4 py-1.5 text-sm rounded-md"
                style={{
                  border: '1px solid var(--color-hairline)',
                  background: 'transparent',
                  color: 'var(--color-muted)',
                  cursor: streaming ? 'not-allowed' : 'pointer',
                }}
              >
                取消
              </button>
            </div>
          </div>
        ) : (
          /* HITL 选项垂直堆叠：flex flex-col，一行一个 */
          <div className="flex flex-col gap-2">
            {info.actions.map((a) => (
              <button
                key={a.id}
                disabled={streaming}
                onClick={() => handleActionClick(a)}
                className="w-full px-4 py-2 text-sm rounded-md text-left"
                style={{
                  background: 'var(--color-primary)',
                  color: 'white',
                  cursor: streaming ? 'not-allowed' : 'pointer',
                  opacity: streaming ? 0.6 : 1,
                }}
              >
                {a.title}
              </button>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
