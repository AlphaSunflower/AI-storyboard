import { useLayoutEffect, useRef, useState } from 'react';
import gsap from 'gsap';
import { useAgentStore } from '../../stores/agentStore';
import { DS } from './ChatComposer';
import type { AgentConversation } from '../../api/agent';
import SpecularButton from '../SpecularButton';
import { MoreMenu } from '../common/MoreMenu';
import MoonLogo from './MoonLogo';

/** DeepSeek 风格时间分组：今天 / 昨天 / 7天内 / yyyy-MM（updatedAt 倒序前提下连续归组） */
function groupByTime(items: AgentConversation[]) {
  const now = new Date();
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  const yesterday = today - 86400000;
  const weekAgo = today - 7 * 86400000;
  const groups: { label: string; items: AgentConversation[] }[] = [];
  let cur = '';
  for (const c of items) {
    const ts = new Date(c.updatedAt).getTime();
    const label = ts >= today ? '今天' : ts >= yesterday ? '昨天' : ts >= weekAgo ? '7天内' : c.updatedAt.slice(0, 7);
    if (label !== cur) { cur = label; groups.push({ label, items: [] }); }
    groups[groups.length - 1].items.push(c);
  }
  return groups;
}

/** 勾选框（选中态 #cc785c 填充 + 勾，未选中空框） */
function CheckIcon({ checked }: { checked: boolean }) {
  return (
    <span
      style={{
        width: 18, height: 18, flexShrink: 0, borderRadius: 4,
        border: `2px solid ${checked ? '#cc785c' : 'var(--color-hairline)'}`,
        background: checked ? '#cc785c' : 'transparent',
        display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
        transition: 'background 0.15s, border-color 0.15s',
        boxSizing: 'border-box',
      }}
    >
      {checked && (
        <svg width="12" height="12" viewBox="0 0 12 12" fill="none" stroke="white" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
          <path d="M2 6l3 3 5-5" />
        </svg>
      )}
    </span>
  );
}

export function AgentConversationList({ width, toolbar, onSelectOverride, hideHeader }: { width?: number; toolbar?: React.ReactNode; onSelectOverride?: (id: string) => void; hideHeader?: boolean }) {
  const {
    conversations, activeConversationId, selectConversation,
    createConversation, renameConversation, setConversationStatus, deleteConversation,
    waitingHumanInput, waitingVideoPlan,
  } = useAgentStore();
  const [showArchived, setShowArchived] = useState(false);
  const [renamingId, setRenamingId] = useState<string | null>(null);
  const [renameText, setRenameText] = useState('');
  const [confirmDeleteId, setConfirmDeleteId] = useState<string | null>(null);
  const [multiSelect, setMultiSelect] = useState(false);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const renamingIdRef = useRef<string | null>(null);
  const barRef = useRef<HTMLDivElement>(null);

  const visible = conversations.filter((c) =>
    showArchived ? c.status === 'archived' : c.status !== 'archived');
  const groups = groupByTime(visible);
  const selectedCount = selectedIds.size;

  // 底部删除栏入场动画（滑入 + 淡出）
  useLayoutEffect(() => {
    if (barRef.current && multiSelect) {
      const ctx = gsap.context(() => {
        gsap.from(barRef.current, { y: 24, opacity: 0, duration: 0.28, ease: 'power2.out' });
      }, barRef);
      return () => ctx.revert();
    }
  }, [multiSelect]);

  // 切换选中态
  const toggleSelect = (id: string) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  };

  // 批量删除
  const batchDelete = async () => {
    const ids = [...selectedIds];
    setSelectedIds(new Set());
    // ponytail: 串行删除（API 无批量端点），性能天花板低，升级时加后端批量接口
    for (const id of ids) {
      await deleteConversation(id);
    }
    setMultiSelect(false);
  };

  const startRename = (id: string, title: string) => {
    renamingIdRef.current = id;
    setRenamingId(id);
    setRenameText(title);
  };

  const cancelRename = () => {
    renamingIdRef.current = null;
    setRenamingId(null);
  };

  const handleRename = async (id: string) => {
    if (renamingIdRef.current !== id) return;
    const title = renameText.trim();
    cancelRename();
    if (title) await renameConversation(id, title);
  };

  return (
    <div
      style={{
        position: 'relative',
        width: width ?? '100%',
        minWidth: width ? 150 : undefined,
        borderRight: width ? `1px solid ${DS.border}` : undefined,
        background: DS.sidebarBg,
        display: 'flex',
        flexDirection: 'column',
        overflowY: 'auto',
        overflowX: 'hidden',
      }}
    >
      {/* Moon Logo 标题（手机端 overlay 由父组件渲染，此处跳过） */}
      {!hideHeader && (
        <div style={{ padding: '18px 16px 12px', borderBottom: `1px solid ${DS.border}` }}>
          <MoonLogo size={22} showText />
        </div>
      )}
      {/* toolbar 插槽 */}
      {toolbar}
      {/* 新建 */}
      <div style={{ padding: 14 }}>
        <SpecularButton
          size="md"
          radius={10}
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

      {/* 操作区：归档筛选 + 多选 */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', margin: '0 14px 8px' }}>
        <button
          onClick={() => setShowArchived(!showArchived)}
          style={{ padding: '6px 0', border: 'none', background: 'none', color: DS.textCaption, fontSize: 13, cursor: 'pointer' }}
        >
          {showArchived ? '◀ 返回进行中' : '🗂 已归档'}
        </button>
        {visible.length > 0 && (
          <button
            onClick={() => { setMultiSelect(!multiSelect); setSelectedIds(new Set()); }}
            style={{
              padding: '4px 0', border: 'none', background: 'none',
              color: multiSelect ? '#cc785c' : DS.textCaption,
              fontSize: 13, cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 4,
            }}
            title={multiSelect ? '取消多选' : '多选删除'}
          >
            {/* 多选图标：两个叠加方块 */}
            <svg width="15" height="15" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round">
              <rect x="1" y="4" width="10" height="10" rx="2" />
              <path d="M5 4V2a1 1 0 011-1h9a1 1 0 011 1v9a1 1 0 01-1 1h-2" />
            </svg>
            {multiSelect ? '取消' : '多选'}
          </button>
        )}
      </div>

      {/* 按时间分组的会话列表 */}
      {groups.map((g) => (
        <div key={g.label}>
          <div style={{ padding: '10px 14px 4px', fontSize: 12, fontWeight: 600, color: DS.textCaption, letterSpacing: '0.02em', textAlign: 'left' }}>
            {g.label}
          </div>
          {g.items.map((c) => {
            const isSelected = selectedIds.has(c.id);
            return (
              <div
                key={c.id}
                onClick={() => {
                  if (waitingHumanInput || waitingVideoPlan) return;
                  if (multiSelect) { toggleSelect(c.id); return; }
                  if (onSelectOverride) { onSelectOverride(c.id); return; }
                  selectConversation(c.id);
                }}
                title={(waitingHumanInput || waitingVideoPlan) && c.id !== activeConversationId ? '请先完成当前确认' : undefined}
                style={{
                  padding: '12px 14px',
                  cursor: 'pointer',
                  display: 'flex',
                  alignItems: 'center',
                  gap: 8,
                  background: c.id === activeConversationId ? DS.hover : isSelected ? 'rgba(204,120,92,0.06)' : 'transparent',
                  borderBottom: `1px solid ${DS.border}`,
                  opacity: (waitingHumanInput || waitingVideoPlan) && c.id !== activeConversationId ? 0.5 : 1,
                }}
              >
                {/* 勾选框（仅多选模式显示，scale 弹入动画） */}
                {multiSelect && (
                  <CheckIcon checked={isSelected} />
                )}

                {renamingId === c.id ? (
                  <input
                    autoFocus
                    value={renameText}
                    onChange={(e) => setRenameText(e.target.value)}
                    onBlur={() => handleRename(c.id)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') handleRename(c.id);
                      if (e.key === 'Escape') {
                        e.stopPropagation();
                        cancelRename();
                      }
                    }}
                    onClick={(e) => e.stopPropagation()}
                    style={{ flex: 1, fontSize: 14, padding: '4px 6px', border: '1px solid var(--color-primary)', borderRadius: 6 }}
                  />
                ) : (
                  <>
                    <div style={{
                      flex: 1, minWidth: 0, fontSize: 15,
                      fontWeight: c.id === activeConversationId ? 600 : 400,
                      color: 'var(--color-ink)',
                      overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                    }}>
                      {c.title}
                    </div>
                    {!multiSelect && (
                      <MoreMenu
                        items={[
                          { label: '重命名', onClick: () => startRename(c.id, c.title) },
                          { label: c.status === 'archived' ? '恢复' : '归档', onClick: () => setConversationStatus(c.id, c.status === 'archived' ? 'active' : 'archived') },
                          { label: '删除', danger: true, onClick: () => setConfirmDeleteId(c.id) },
                        ]}
                      />
                    )}
                  </>
                )}
              </div>
            );
          })}
        </div>
      ))}

      {visible.length === 0 && (
        <p style={{ padding: 16, fontSize: 13, color: DS.textCaption, textAlign: 'center' }}>
          {showArchived ? '暂无已归档对话' : '暂无对话'}
        </p>
      )}

      {/* 多选模式：底部删除栏（position: sticky 底部，gsap 滑入动画） */}
      {multiSelect && (
        <div
          ref={barRef}
          style={{
            position: 'sticky',
            bottom: 0,
            padding: '12px 14px',
            background: 'white',
            borderTop: `1px solid ${DS.border}`,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: 10,
            zIndex: 10,
          }}
        >
          <span style={{ fontSize: 13, color: DS.textCaption }}>
            已选 {selectedCount} 项
          </span>
          <button
            disabled={selectedCount === 0}
            onClick={batchDelete}
            style={{
              padding: '6px 16px',
              height: 32,
              border: 'none',
              borderRadius: 'var(--rounded-md)',
              background: selectedCount > 0 ? 'var(--color-error)' : 'var(--color-hairline)',
              color: selectedCount > 0 ? 'white' : 'var(--color-muted)',
              fontSize: 13,
              cursor: selectedCount > 0 ? 'pointer' : 'not-allowed',
              opacity: selectedCount > 0 ? 1 : 0.55,
            }}
          >
            删除
          </button>
        </div>
      )}

      {/* 单条删除二次确认 */}
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
