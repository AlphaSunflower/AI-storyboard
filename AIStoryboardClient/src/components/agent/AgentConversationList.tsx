import { useLayoutEffect, useRef, useState } from 'react';
import gsap from 'gsap';
import { useAgentStore } from '../../stores/agentStore';
import { notify } from '../../stores/toastStore';
import { DS } from './ChatComposer';
import type { AgentConversation } from '../../api/agent';
import SpecularButton from '../SpecularButton';
import { Archive, ChevronLeft, Pencil, Plus, Trash2 } from 'lucide-react';
import { MoreMenu } from '../common/MoreMenu';
import { ContextMenu } from '../common/ContextMenu';
import { Tooltip, TooltipGroup } from '../common/Tooltip';
import MoonLogo from './MoonLogo';
import { CONVERSATION_DRAG_MIME } from '../../lib/mentionReferences';

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

/** 勾选框（选中态 var(--color-primary) 填充 + 勾，未选中空框） */
function CheckIcon({ checked }: { checked: boolean }) {
  return (
    <span
      style={{
        width: 18, height: 18, flexShrink: 0, borderRadius: 4,
        border: `2px solid ${checked ? 'var(--color-primary)' : 'var(--color-hairline)'}`,
        background: checked ? 'var(--color-primary)' : 'transparent',
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

export function AgentConversationList({ width, toolbar, onSelectOverride, hideHeader, headerRight }: { width?: number; toolbar?: React.ReactNode; onSelectOverride?: (id: string) => void; hideHeader?: boolean; headerRight?: React.ReactNode }) {
  const {
    conversations, activeConversationId, selectConversation,
    newConversation, renameConversation, setConversationStatus, deleteConversation,
    taskUnread, generating, replyUnread,
  } = useAgentStore();
  const [showArchived, setShowArchived] = useState(false);
  const [renamingId, setRenamingId] = useState<string | null>(null);
  const [renameText, setRenameText] = useState('');
  const [confirmDeleteId, setConfirmDeleteId] = useState<string | null>(null);
  const [multiSelect, setMultiSelect] = useState(false);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [hoverId, setHoverId] = useState<string | null>(null);
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
    let deleted = 0;
    for (const id of ids) {
      await deleteConversation(id, { silent: true });
      deleted += 1;
    }
    if (deleted > 0) notify({ title: `已删除 ${deleted} 个对话` });
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
        overflow: 'hidden',
      }}
    >
      <style>{`
        @keyframes conversationGeneratingDot {
          0%, 100% { opacity: 1; transform: scale(1); }
          50% { opacity: 0.35; transform: scale(0.7); }
        }
        .conversation-generating__dot {
          width: 6px; height: 6px; border-radius: 50%;
          background: currentColor;
          animation: conversationGeneratingDot 1.1s ease-in-out infinite;
        }
        @media (prefers-reduced-motion: reduce) {
          .conversation-generating__dot { animation: none; }
        }
      `}</style>
      {/* Moon Logo 标题（手机端 overlay 由父组件渲染，此处跳过） */}
      {!hideHeader && (
        <TooltipGroup>
        <div style={{ padding: '14px 14px 10px', borderBottom: `1px solid ${DS.border}`, display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}>
          <Tooltip side="bottom" label="新建对话">
          <button
            onClick={() => newConversation()}
            aria-label="新建对话"
            style={{
              display: 'inline-flex', alignItems: 'center', border: 'none', background: 'transparent',
              padding: '3px 6px', marginLeft: -6, borderRadius: 8, cursor: 'pointer', transition: 'background 0.15s',
            }}
            onMouseEnter={(e) => { e.currentTarget.style.background = DS.hover; }}
            onMouseLeave={(e) => { e.currentTarget.style.background = 'transparent'; }}
          >
            <MoonLogo size={24} showText animated />
          </button>
          </Tooltip>
          {headerRight}
        </div>
        </TooltipGroup>
      )}
      {/* toolbar 插槽 */}
      {toolbar}
      {/* 新建 */}
      <div style={{ padding: '12px 12px 4px' }}>
        <SpecularButton
          size="md"
          radius={8}
          tintOpacity={0}
          textColor="var(--color-primary)"
          lineColor="var(--color-primary)"
          baseColor="var(--color-primary)"
          intensity={0.5}
          thickness={1.1}
          className="specular-button--block"
          onClick={() => newConversation()}
        >
          <span style={{ display: 'inline-flex', alignItems: 'center', justifyContent: 'center', gap: 6 }}>
            <Plus size={14} strokeWidth={2} />
            新建对话
          </span>
        </SpecularButton>
      </div>

      {/* 操作区：归档筛选 + 多选 */}
      <TooltipGroup>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', margin: '0 14px 8px' }}>
        <Tooltip side="bottom" label={showArchived ? '返回进行中的对话' : '查看已归档对话'}>
        <button
          className="chat-archive-toggle"
          onClick={() => setShowArchived(!showArchived)}
          aria-label={showArchived ? '返回进行中的对话' : '查看已归档对话'}
          style={{ padding: '6px 8px', marginLeft: -8, border: 'none', background: 'none', color: DS.textCaption, fontSize: 12.5, cursor: 'pointer', display: 'inline-flex', alignItems: 'center', gap: 4, borderRadius: 8, transition: 'background 0.15s, color 0.15s' }}
          onMouseEnter={(e) => { e.currentTarget.style.background = DS.hover; e.currentTarget.style.color = DS.ink; }}
          onMouseLeave={(e) => { e.currentTarget.style.background = 'transparent'; e.currentTarget.style.color = DS.textCaption; }}
        >
          {showArchived ? (
            <span key="active" className="chat-archive-label">
              <ChevronLeft size={13} strokeWidth={1.8} />
              返回进行中
            </span>
          ) : (
            <span key="archived" className="chat-archive-label">
              <Archive size={13} strokeWidth={1.8} />
              已归档
            </span>
          )}
        </button>
        </Tooltip>
        {visible.length > 0 && (
          <Tooltip side="bottom" label={multiSelect ? '取消多选' : '多选删除'}>
          <button
            onClick={() => { setMultiSelect(!multiSelect); setSelectedIds(new Set()); }}
            aria-label={multiSelect ? '取消多选' : '多选删除'}
            style={{
              padding: '6px 8px', marginRight: -8, border: 'none', background: 'none',
              color: multiSelect ? 'var(--color-primary)' : DS.textCaption,
              fontSize: 12.5, cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 4, borderRadius: 8, transition: 'background 0.15s, color 0.15s',
            }}
            onMouseEnter={(e) => { e.currentTarget.style.background = DS.hover; }}
            onMouseLeave={(e) => { e.currentTarget.style.background = 'transparent'; }}
          >
            {/* 多选图标：两个叠加方块 */}
            <svg width="15" height="15" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round">
              <rect x="1" y="4" width="10" height="10" rx="2" />
              <path d="M5 4V2a1 1 0 011-1h9a1 1 0 011 1v9a1 1 0 01-1 1h-2" />
            </svg>
            {multiSelect ? '取消' : '多选'}
          </button>
          </Tooltip>
        )}
      </div>
      </TooltipGroup>

      {/* 会话列表（仅此区域滚动，头部/工具栏/操作区固定） */}
      <div style={{ flex: 1, minHeight: 0, overflowY: 'auto', overflowX: 'hidden' }}>
      <div key={showArchived ? 'archived' : 'active'} className="conversation-list-stage">
      {/* 按时间分组的会话列表 */}
      {groups.map((g) => (
        <div key={g.label}>
          <div style={{ padding: '12px 18px 4px', fontSize: 11, fontWeight: 600, color: DS.textCaption, letterSpacing: '0.04em', textAlign: 'left' }}>
            {g.label}
          </div>
          {g.items.map((c) => {
            const isSelected = selectedIds.has(c.id);
            return (
              <ContextMenu
                key={c.id}
                items={[
                  { label: '重命名', icon: <Pencil size={14} strokeWidth={1.8} />, onClick: () => startRename(c.id, c.title) },
                  { label: c.status === 'archived' ? '恢复' : '归档', icon: <Archive size={14} strokeWidth={1.8} />, onClick: () => setConversationStatus(c.id, c.status === 'archived' ? 'active' : 'archived') },
                  { label: '删除', icon: <Trash2 size={14} strokeWidth={1.8} />, danger: true, onClick: () => setConfirmDeleteId(c.id) },
                ]}
              >
                <div
                  className={`conversation-item${c.id === activeConversationId ? ' is-active' : ''}`}
                  draggable={!multiSelect && c.id !== activeConversationId}
                  onDragStart={(e) => {
                    e.dataTransfer.effectAllowed = 'copy';
                    e.dataTransfer.setData(CONVERSATION_DRAG_MIME, JSON.stringify({
                      id: c.id,
                      title: c.title,
                      messageCount: c.messageCount,
                      summaryStatus: c.summaryStatus,
                    }));
                    e.dataTransfer.setData('text/plain', `@对话《${c.title || '未命名'}》`);
                  }}
                  onClick={() => {
                    if (multiSelect) { toggleSelect(c.id); return; }
                    if (onSelectOverride) { onSelectOverride(c.id); return; }
                    selectConversation(c.id);
                  }}
                  onMouseEnter={(e) => { setHoverId(c.id); if (c.id !== activeConversationId && !isSelected) e.currentTarget.style.background = 'var(--color-surface-subtle)'; }}
                  onMouseLeave={(e) => { setHoverId(null); e.currentTarget.style.background = c.id === activeConversationId ? DS.hover : isSelected ? 'color-mix(in srgb, var(--color-primary) 7%, transparent)' : 'transparent'; }}
                  style={{
                    margin: '1px 8px',
                    padding: '9px 10px',
                    borderRadius: 8,
                    cursor: 'pointer',
                    display: 'flex',
                    alignItems: 'center',
                    gap: 8,
                    background: c.id === activeConversationId ? DS.hover : isSelected ? 'color-mix(in srgb, var(--color-primary) 7%, transparent)' : 'transparent',
                    opacity: 1,
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
                        flex: 1, minWidth: 0, fontSize: 14,
                        fontWeight: c.id === activeConversationId ? 600 : 400,
                        color: c.id === activeConversationId ? DS.brand : 'var(--color-ink)',
                        overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                      }}>
                        {c.title}
                      </div>
                      {/* 正在生成：生成中胶囊（切走别的会话也能看到这个会话在跑） */}
                      {generating[c.id] && !generating[c.id].finished ? (
                        <Tooltip className="tooltip-fixed" label="正在生成回答">
                        <span
                          className="conversation-generating"
                          aria-label="正在生成"
                          style={{
                            display: 'inline-flex', alignItems: 'center', gap: 4, flexShrink: 0,
                            padding: '1px 7px', borderRadius: 999,
                            background: 'color-mix(in srgb, var(--color-primary) 10%, transparent)', color: DS.brand,
                            fontSize: 11, fontWeight: 600, whiteSpace: 'nowrap',
                          }}
                        >
                          <span className="conversation-generating__dot" />
                          生成中
                        </span>
                        </Tooltip>
                      ) : replyUnread[c.id] ? (
                        <Tooltip className="tooltip-fixed" label="有新回复">
                        <span
                          aria-label="有新回复"
                          style={{
                            display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                            minWidth: 18, height: 18, padding: '0 5px', borderRadius: 9,
                            background: DS.brand, color: 'white', fontSize: 10, fontWeight: 700,
                            flexShrink: 0,
                          }}
                        >
                          新
                        </span>
                        </Tooltip>
                      ) : null}
                      {!!taskUnread[c.id] && (
                        <Tooltip className="tooltip-fixed" label="后台任务已完成">
                        <span
                          aria-label="后台任务已完成"
                          style={{
                            display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                            minWidth: 18, height: 18, padding: '0 5px', borderRadius: 9,
                            background: '#22a06b', color: 'white', fontSize: 10, fontWeight: 700,
                          }}
                        >
                          {taskUnread[c.id]}
                        </span>
                        </Tooltip>
                      )}
                      {!multiSelect && (
                        <span style={{
                          display: 'inline-flex', flexShrink: 0,
                          opacity: hoverId === c.id || c.id === activeConversationId ? 1 : 0,
                          pointerEvents: hoverId === c.id || c.id === activeConversationId ? 'auto' : 'none',
                          transition: 'opacity 0.15s',
                        }}>
                          <MoreMenu
                            items={[
                              { label: '重命名', onClick: () => startRename(c.id, c.title) },
                              { label: c.status === 'archived' ? '恢复' : '归档', onClick: () => setConversationStatus(c.id, c.status === 'archived' ? 'active' : 'archived') },
                              { label: '删除', danger: true, onClick: () => setConfirmDeleteId(c.id) },
                            ]}
                          />
                        </span>
                      )}
                    </>
                  )}
                </div>
              </ContextMenu>
            );
          })}
        </div>
      ))}

      {visible.length === 0 && (
        <p style={{ padding: 16, fontSize: 13, color: DS.textCaption, textAlign: 'center' }}>
          {showArchived ? '暂无已归档对话' : '暂无对话'}
        </p>
      )}
      </div>
      </div>

      {/* 多选模式：底部删除栏（固定于列表下方，gsap 滑入动画） */}
      {multiSelect && (
        <div
          ref={barRef}
          style={{
            flexShrink: 0,
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
