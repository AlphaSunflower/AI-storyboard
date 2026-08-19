import { useState, useEffect } from 'react';
import { useAgentStore } from '../../stores/agentStore';

export function ProjectModal() {
  const { projectId, setProjectId, loadConversations } = useAgentStore();
  const [inputValue, setInputValue] = useState(projectId ?? '');

  useEffect(() => {
    setInputValue(projectId ?? '');
  }, [projectId]);

  const handleSave = () => {
    const trimmed = inputValue.trim();
    if (!trimmed) return;
    setProjectId(trimmed);
    // 加载该项目的对话列表
    setTimeout(() => loadConversations(), 100);
  };

  return (
    <div className="text-sm" style={{ color: 'var(--color-body)' }}>
      <p className="mb-3">设置当前项目 ID，用于加载该项目下的对话和资产。</p>

      <label className="block text-xs mb-1" style={{ color: 'var(--color-muted)' }}>项目 ID</label>
      <input
        type="text"
        value={inputValue}
        onChange={(e) => setInputValue(e.target.value)}
        onKeyDown={(e) => { if (e.key === 'Enter') handleSave(); }}
        placeholder="输入项目 ID..."
        className="w-full px-3 py-2 text-sm mb-3 outline-none"
        style={{
          border: '1px solid var(--color-hairline)',
          borderRadius: 'var(--rounded-md)',
          color: 'var(--color-ink)',
        }}
      />

      <button
        onClick={handleSave}
        disabled={!inputValue.trim()}
        className="px-4 py-2 text-sm font-medium rounded-md"
        style={{
          background: 'var(--color-primary)',
          color: 'var(--color-on-primary)',
          opacity: inputValue.trim() ? 1 : 0.5,
          cursor: inputValue.trim() ? 'pointer' : 'not-allowed',
        }}
      >
        确认
      </button>

      {projectId && (
        <p className="mt-3 text-xs" style={{ color: 'var(--color-muted)' }}>
          当前项目: <code className="px-1 py-0.5 rounded" style={{ background: 'var(--color-surface-soft)' }}>{projectId}</code>
        </p>
      )}
    </div>
  );
}
