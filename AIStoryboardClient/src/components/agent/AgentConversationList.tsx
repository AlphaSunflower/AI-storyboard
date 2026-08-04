import { useRef, useState } from 'react';
import { useAgentStore } from '../../stores/agentStore';

export function AgentConversationList() {
  const {
    conversations, activeConversationId, selectConversation,
    createConversation, renameConversation, setConversationStatus, deleteConversation,
  } = useAgentStore();
  const [showArchived, setShowArchived] = useState(false);
  const [renamingId, setRenamingId] = useState<string | null>(null);
  const [renameText, setRenameText] = useState('');
  const [confirmDeleteId, setConfirmDeleteId] = useState<string | null>(null);
  // renamingId 的同步镜像 ref：闭包捕获的 renamingId 是渲染时刻的旧值，
  // 竞态守卫需要读取"当前"编辑态（Enter 提交后输入框卸载触发的 blur 仍持有旧闭包）
  const renamingIdRef = useRef<string | null>(null);

  const visible = conversations.filter((c) =>
    showArchived ? c.status === 'archived' : c.status !== 'archived');

  // 进入重命名编辑态（同步维护 ref）
  const startRename = (id: string, title: string) => {
    renamingIdRef.current = id;
    setRenamingId(id);
    setRenameText(title);
  };

  // 退出重命名编辑态（同步维护 ref）
  const cancelRename = () => {
    renamingIdRef.current = null;
    setRenamingId(null);
  };

  const handleRename = async (id: string) => {
    // 竞态守卫：Enter 提交/ Esc 取消后输入框卸载触发的 blur 会再次进入，此时编辑态已退出，直接忽略
    if (renamingIdRef.current !== id) return;
    const title = renameText.trim();
    // 先退出编辑态（卸载输入框）再发起请求，后续 blur 被守卫拦截，避免重复提交
    cancelRename();
    if (title) await renameConversation(id, title);
  };

  return (
    <div
      style={{
        position: 'relative',
        width: 138,
        minWidth: 138,
        borderRight: '1px solid var(--color-hairline)',
        background: 'var(--color-surface-soft)',
        display: 'flex',
        flexDirection: 'column',
        overflowY: 'auto',
      }}
    >
      {/* 新建 */}
      <button
        onClick={() => createConversation()}
        style={{
          margin: 10, padding: '8px 0', border: '1px dashed var(--color-hairline)',
          borderRadius: 'var(--rounded-md)', background: 'white',
          color: 'var(--color-primary)', fontSize: 12, fontWeight: 500, cursor: 'pointer',
        }}
      >
        + 新建对话
      </button>

      {/* 归档筛选切换 */}
      <button
        onClick={() => setShowArchived(!showArchived)}
        style={{
          margin: '0 10px 6px', padding: '4px 0', border: 'none', background: 'none',
          color: 'var(--color-muted)', fontSize: 11, cursor: 'pointer', textAlign: 'left',
        }}
      >
        {showArchived ? '◀ 返回进行中' : '🗂 已归档'}
      </button>

      {/* 会话列表 */}
      {visible.map((c) => (
        <div
          key={c.id}
          onClick={() => selectConversation(c.id)}
          style={{
            padding: '8px 10px', cursor: 'pointer',
            background: c.id === activeConversationId ? 'var(--color-surface-card)' : 'transparent',
            borderBottom: '1px solid var(--color-hairline-soft)',
          }}
        >
          {renamingId === c.id ? (
            <input
              autoFocus
              value={renameText}
              onChange={(e) => setRenameText(e.target.value)}
              onBlur={() => handleRename(c.id)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') handleRename(c.id);
                if (e.key === 'Escape') {
                  // 阻止 Esc 冒泡到 window/document 的 keydown 监听（AgentDrawer 会据此关闭抽屉）。
                  // React 19 合成事件的 stopPropagation 会同步调用原生事件 stopPropagation，
                  // 且事件委托挂载在 #root 容器（位于 document 之前冒泡），可可靠拦截原生监听器。
                  e.stopPropagation();
                  cancelRename();
                }
              }}
              onClick={(e) => e.stopPropagation()}
              style={{ width: '100%', fontSize: 12, padding: '2px 4px', border: '1px solid var(--color-primary)', borderRadius: 4 }}
            />
          ) : (
            <>
              <div style={{ fontSize: 12, fontWeight: c.id === activeConversationId ? 600 : 400, color: 'var(--color-ink)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {c.title}
              </div>
              <div style={{ display: 'flex', gap: 6, marginTop: 4 }}>
                <span
                  onClick={(e) => { e.stopPropagation(); startRename(c.id, c.title); }}
                  title="重命名" style={{ fontSize: 11, cursor: 'pointer', color: 'var(--color-muted)' }}
                >✏️</span>
                <span
                  onClick={(e) => { e.stopPropagation(); setConversationStatus(c.id, c.status === 'archived' ? 'active' : 'archived'); }}
                  title={c.status === 'archived' ? '恢复' : '归档'}
                  style={{ fontSize: 11, cursor: 'pointer', color: 'var(--color-muted)' }}
                >🗂</span>
                <span
                  onClick={(e) => { e.stopPropagation(); setConfirmDeleteId(c.id); }}
                  title="删除" style={{ fontSize: 11, cursor: 'pointer', color: 'var(--color-error)' }}
                >🗑️</span>
              </div>
            </>
          )}
        </div>
      ))}

      {visible.length === 0 && (
        <p style={{ padding: 12, fontSize: 11, color: 'var(--color-muted-soft)', textAlign: 'center' }}>
          {showArchived ? '暂无已归档对话' : '暂无对话'}
        </p>
      )}

      {/* 删除二次确认 */}
      {confirmDeleteId && (
        <div style={{ position: 'absolute', bottom: 12, left: 10, right: 10, background: 'white', border: '1px solid var(--color-hairline)', borderRadius: 'var(--rounded-md)', padding: 10, boxShadow: '0 4px 12px rgba(20,20,19,0.12)', zIndex: 5 }}>
          <p style={{ margin: '0 0 8px', fontSize: 12, color: 'var(--color-ink)' }}>删除该对话？</p>
          <div style={{ display: 'flex', gap: 6 }}>
            <button onClick={() => setConfirmDeleteId(null)} style={{ flex: 1, padding: '4px 0', fontSize: 11, border: '1px solid var(--color-hairline)', borderRadius: 6, background: 'white', cursor: 'pointer' }}>取消</button>
            <button onClick={() => { deleteConversation(confirmDeleteId); setConfirmDeleteId(null); }} style={{ flex: 1, padding: '4px 0', fontSize: 11, border: 'none', borderRadius: 6, background: 'var(--color-error)', color: 'white', cursor: 'pointer' }}>删除</button>
          </div>
        </div>
      )}
    </div>
  );
}
