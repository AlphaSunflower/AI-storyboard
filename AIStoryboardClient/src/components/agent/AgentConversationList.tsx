import { useRef, useState } from 'react';
import { useAgentStore } from '../../stores/agentStore';
import SpecularButton from '../SpecularButton';

export function AgentConversationList({ width, toolbar }: { width?: number; toolbar?: React.ReactNode }) {
  const {
    conversations, activeConversationId, selectConversation,
    createConversation, renameConversation, setConversationStatus, deleteConversation,
    waitingHumanInput, waitingVideoPlan,
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
        width: width ?? 180,
        minWidth: 150,
        borderRight: '1px solid var(--color-hairline)',
        background: 'var(--color-surface-soft)',
        display: 'flex',
        flexDirection: 'column',
        overflowY: 'auto',
        overflowX: 'hidden',
      }}
    >
      {/* ☾ Moon 智能体标题（迁移自对话窗口头部） */}
      <div
        style={{
          padding: '18px 16px 12px',
          borderBottom: '1px solid var(--color-hairline)',
          fontSize: 18,
          fontWeight: 600,
          color: 'var(--color-ink)',
          whiteSpace: 'nowrap',
        }}
      >
        ☾ Moon 智能体
      </div>
      {/* toolbar 插槽：/chat 页在此注入 项目选择+资源库（位于新建对话上方）；抽屉不传，零影响 */}
      {toolbar}
      {/* 新建 */}
      <div style={{ padding: 14 }}>
        <SpecularButton
          size="sm"
          radius={8}
          tintOpacity={0}
          textColor="#cc785c"
          lineColor="#cc785c"
          baseColor="#cc785c"
          intensity={0.9}
          thickness={1.2}
          className="specular-button--block"
          onClick={() => createConversation()}
        >
          + 新建对话
        </SpecularButton>
      </div>

      {/* 归档筛选切换 */}
      <button
        onClick={() => setShowArchived(!showArchived)}
        style={{
          margin: '0 14px 8px', padding: '6px 0', border: 'none', background: 'none',
          color: 'var(--color-muted)', fontSize: 13, cursor: 'pointer', textAlign: 'left',
        }}
      >
        {showArchived ? '◀ 返回进行中' : '🗂 已归档'}
      </button>

      {/* 会话列表 */}
      {visible.map((c) => (
        <div
          key={c.id}
          onClick={() => { if (waitingHumanInput || waitingVideoPlan) return; selectConversation(c.id); }}
          title={(waitingHumanInput || waitingVideoPlan) && c.id !== activeConversationId ? '请先完成当前确认' : undefined}
          style={{
            padding: '12px 14px', cursor: 'pointer',
            background: c.id === activeConversationId ? 'var(--color-surface-card)' : 'transparent',
            borderBottom: '1px solid var(--color-hairline-soft)',
            // I3：HITL 等待期/图生视频方案确认期未激活会话项置灰，提示先完成当前确认
            opacity: (waitingHumanInput || waitingVideoPlan) && c.id !== activeConversationId ? 0.5 : 1,
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
              style={{ width: '100%', fontSize: 14, padding: '4px 6px', border: '1px solid var(--color-primary)', borderRadius: 6 }}
            />
          ) : (
            <>
              <div style={{ fontSize: 15, fontWeight: c.id === activeConversationId ? 600 : 400, color: 'var(--color-ink)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {c.title}
              </div>
              <div style={{ display: 'flex', gap: 8, marginTop: 6 }}>
                <span
                  onClick={(e) => { e.stopPropagation(); startRename(c.id, c.title); }}
                  title="重命名" style={{ fontSize: 13, cursor: 'pointer', color: 'var(--color-muted)' }}
                >✏️</span>
                <span
                  onClick={(e) => { e.stopPropagation(); setConversationStatus(c.id, c.status === 'archived' ? 'active' : 'archived'); }}
                  title={c.status === 'archived' ? '恢复' : '归档'}
                  style={{ fontSize: 13, cursor: 'pointer', color: 'var(--color-muted)' }}
                >🗂</span>
                <span
                  onClick={(e) => { e.stopPropagation(); setConfirmDeleteId(c.id); }}
                  title="删除" style={{ fontSize: 13, cursor: 'pointer', color: 'var(--color-error)' }}
                >🗑️</span>
              </div>
            </>
          )}
        </div>
      ))}

      {visible.length === 0 && (
        <p style={{ padding: 16, fontSize: 13, color: 'var(--color-muted-soft)', textAlign: 'center' }}>
          {showArchived ? '暂无已归档对话' : '暂无对话'}
        </p>
      )}

      {/* 删除二次确认 —— 居中模态（与 AppHeader 弹窗风格一致） */}
      {confirmDeleteId && (
        <div
          onClick={() => setConfirmDeleteId(null)}
          style={{
            position: 'fixed',
            inset: 0,
            background: 'rgba(20, 20, 19, 0.35)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            zIndex: 200,
          }}
        >
          <div
            onClick={(e) => e.stopPropagation()}
            style={{
              background: 'white',
              borderRadius: 'var(--rounded-md)',
              boxShadow: '0 8px 32px rgba(20, 20, 19, 0.18)',
              padding: 24,
              minWidth: 320,
              maxWidth: 440,
            }}
          >
            <h3 style={{ margin: '0 0 12px', font: 'var(--text-body)', color: 'var(--color-ink)' }}>
              删除对话
            </h3>
            <p style={{ margin: '0 0 16px', font: 'var(--text-body-sm)', color: 'var(--color-muted)' }}>
              确定要删除对话「{conversations.find((c) => c.id === confirmDeleteId)?.title ?? ''}」吗？此操作无法撤销。
            </p>
            <div style={{ textAlign: 'right' }}>
              <button
                onClick={() => setConfirmDeleteId(null)}
                style={{
                  padding: '6px 18px',
                  height: 32,
                  border: '1px solid var(--color-hairline)',
                  borderRadius: 'var(--rounded-md)',
                  background: 'white',
                  color: 'var(--color-muted)',
                  font: 'var(--text-caption)',
                  cursor: 'pointer',
                  marginRight: 8,
                }}
              >
                取消
              </button>
              <button
                onClick={() => { deleteConversation(confirmDeleteId); setConfirmDeleteId(null); }}
                style={{
                  padding: '6px 18px',
                  height: 32,
                  border: 'none',
                  borderRadius: 'var(--rounded-md)',
                  background: 'var(--color-error)',
                  color: 'white',
                  font: 'var(--text-caption)',
                  cursor: 'pointer',
                }}
              >
                删除
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
