import { useState, useRef, useLayoutEffect, type ReactNode } from 'react';
import gsap from 'gsap';
import { useGSAP } from '@gsap/react';
import type { SceneResponse } from '../../api/projects';
import { sceneApi } from '../../api/scenes';
import { useProjectStore } from '../../stores/projectStore';
import { MoreMenu } from '../common/MoreMenu';
import { ContextMenu } from '../common/ContextMenu';

function Tag({ children }: { children: string }) {
  return (
    <span
      style={{
        fontSize: 10,
        padding: '1px 6px',
        borderRadius: 'var(--rounded-sm)',
        background: 'var(--color-surface-card)',
        color: 'var(--color-muted)',
        lineHeight: 1.6,
      }}
    >
      {children}
    </span>
  );
}

/** 状态徽标文案与配色（列表只读展示，生成/完善操作已移到预览面板） */
const ImageIcon = <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="8.5" cy="8.5" r="1.5"/><path d="M21 15l-5-5L5 21"/></svg>;
const VideoIcon = <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><rect x="2" y="2" width="20" height="20" rx="2.18"/><line x1="7" y1="2" x2="7" y2="22"/><line x1="17" y1="2" x2="17" y2="22"/><line x1="2" y1="12" x2="22" y2="12"/><line x1="2" y1="7" x2="7" y2="7"/><line x1="2" y1="17" x2="7" y2="17"/><line x1="17" y1="17" x2="22" y2="17"/><line x1="17" y1="7" x2="22" y2="7"/></svg>;

function statusBadge(status: string | undefined, kind: 'image' | 'video'): { text: ReactNode; color: string } {
  switch (status) {
    case 'generating':
      return { text: <>{kind === 'image' ? ImageIcon : VideoIcon}{' '}{kind === 'image' ? '图片生成中' : '视频生成中'}</>, color: '#d97706' };
    case 'completed':
      return { text: <>{kind === 'image' ? ImageIcon : VideoIcon}{' '}{kind === 'image' ? '图片已生成' : '视频已生成'}</>, color: '#059669' };
    case 'failed':
      return { text: <>{kind === 'image' ? ImageIcon : VideoIcon}{' '}{kind === 'image' ? '图片失败' : '视频失败'}</>, color: '#e53935' };
    default:
      return { text: <>{kind === 'image' ? ImageIcon : VideoIcon}{' '}{kind === 'image' ? '图片未生成' : '视频未生成'}</>, color: 'var(--color-muted-soft)' };
  }
}

/** E11: 进度数字滚动——从旧值平滑滚到新值（gsap 数字补间 + 卸载自动清理） */
function AnimatedProgress({ value }: { value: number }) {
  const ref = useRef<HTMLSpanElement>(null);
  const displayRef = useRef(value);

  useGSAP(() => {
    const el = ref.current;
    if (!el) return;
    const from = displayRef.current;
    const to = value;
    displayRef.current = to;
    const obj = { v: from };
    gsap.to(obj, {
      v: to,
      duration: 0.4,
      ease: 'power1.out',
      onUpdate: () => {
        if (el) el.textContent = `${Math.round(obj.v)}%`;
      },
    });
  }, { dependencies: [value] });

  return <span ref={ref}>{Math.round(displayRef.current)}%</span>;
}

export function SceneCard({
  scene,
  isSelected,
  onSelect,
}: {
  scene: SceneResponse;
  isSelected: boolean;
  onSelect: () => void;
}) {
  const deleteScene = useProjectStore((s) => s.deleteScene);
  const videoProgress = useProjectStore((s) => s.videoProgress[scene.id]) || 0;
  const updateSceneInStore = useProjectStore((s) => s.updateSceneInStore);

  // 卡片根节点 ref（删除收起动画用）
  const cardRef = useRef<HTMLDivElement>(null);
  // 提示词折叠区 ref（A4 展开/收起高度动画用）
  const promptPanelRef = useRef<HTMLDivElement>(null);

  const [expanded, setExpanded] = useState(false);
  const [imagePrompt, setImagePrompt] = useState(scene.imagePrompt || '');
  const [videoPrompt, setVideoPrompt] = useState(scene.videoPrompt || '');
  const [scriptContent, setScriptContent] = useState(scene.scriptContent || '');
  const [isRenaming, setIsRenaming] = useState(false);
  const [sceneLabel, setSceneLabel] = useState(`分镜 ${scene.sceneNumber}`);
  const unreadScenes = useProjectStore((s) => s.unreadScenes);
  const isUnread = unreadScenes.has(scene.id);
  // A2: 未读红点 ref（生成完成弹入动画用）+ 前一状态记录（只在 false→true 时播）
  const unreadDotRef = useRef<HTMLSpanElement>(null);
  const prevUnreadRef = useRef(isUnread);

  // A2: 生成完成通知动画——卡片边框 primary 光晕脉冲 + 红点弹入
  useGSAP(() => {
    const prev = prevUnreadRef.current;
    prevUnreadRef.current = isUnread;
    if (!isUnread || prev === isUnread || !cardRef.current) return; // 仅 false→true 且卡片在 DOM
    gsap.fromTo(
      cardRef.current,
      { boxShadow: '0 0 0 0 rgba(204, 120, 92, 0.55)' },
      {
        boxShadow: '0 0 0 9px rgba(204, 120, 92, 0)',
        duration: 0.9,
        ease: 'power2.out',
        onComplete: () => {
          // 动画结束后恢复 React 状态对应的 boxShadow，避免残留光晕
          gsap.set(cardRef.current, {
            boxShadow: isSelected ? '0 2px 10px rgba(204, 120, 92, 0.18)' : 'none',
          });
        },
      }
    );
    if (unreadDotRef.current) {
      gsap.fromTo(
        unreadDotRef.current,
        { scale: 0 },
        { scale: 1, duration: 0.4, ease: 'back.out(2.5)' }
      );
    }
  }, { dependencies: [isUnread], scope: cardRef });

  // 提示词修改即存（无本地保存按钮——失焦保存语义）
  const handlePromptBlur = async (field: 'scriptContent' | 'imagePrompt' | 'videoPrompt') => {
    const value = (field === 'scriptContent' ? scriptContent : field === 'imagePrompt' ? imagePrompt : videoPrompt).trim();
    const original = (field === 'scriptContent' ? scene.scriptContent : field === 'imagePrompt' ? scene.imagePrompt : scene.videoPrompt) || '';
    if (value === original) return;
    try {
      await sceneApi.update(scene.id, { [field]: value });
      updateSceneInStore(scene.id, { [field]: value });
    } catch {
      // 保存失败保持原值（下次编辑重新触发）
    }
  };

  const handleDelete = async () => {
    const el = cardRef.current;
    if (el) {
      // 先播收起动画（高度折叠 + 淡出），动画结束后再真正删除
      gsap.to(el, {
        height: 0,
        opacity: 0,
        scale: 0.96,
        marginBottom: 0,
        paddingTop: 0,
        paddingBottom: 0,
        duration: 0.28,
        ease: 'power2.in',
        onComplete: () => {
          deleteScene(scene.id);
        },
      });
    } else {
      await deleteScene(scene.id);
    }
  };

  const handleToggleExpand = (e: React.MouseEvent) => {
    e.stopPropagation();
    setExpanded(!expanded);
  };

  // A3: 选中弹性脉冲（仅选中瞬间播放一次轻微放大回弹）
  useGSAP(() => {
    if (!isSelected || !cardRef.current) return;
    gsap.fromTo(
      cardRef.current,
      { scale: 0.98 },
      {
        scale: 1,
        duration: 0.3,
        ease: 'back.out(2.5)',
        onComplete: () => {
          // 关键：清除残留 transform，否则卡片作为 containing block 会让内部 fixed 弹窗错位被遮挡
          gsap.set(cardRef.current, { clearProps: 'transform' });
        },
      }
    );
  }, { dependencies: [isSelected], scope: cardRef });

  // A4: 提示词折叠区展开/收起高度动画
  useLayoutEffect(() => {
    const panel = promptPanelRef.current;
    if (!panel) return;
    const ctx = gsap.context(() => {
      if (expanded) {
        gsap.set(panel, { height: 'auto', opacity: 1, visibility: 'visible' });
        const target = panel.offsetHeight;
        gsap.fromTo(
          panel,
          { height: 0, opacity: 0 },
          {
            height: target,
            opacity: 1,
            duration: 0.3,
            ease: 'power2.out',
            onComplete: () => {
              gsap.set(panel, { height: 'auto' });
            },
          }
        );
      } else {
        gsap.to(panel, {
          height: 0,
          opacity: 0,
          duration: 0.25,
          ease: 'power2.in',
        });
      }
    }, panel);
    return () => ctx.revert();
  }, [expanded]);

  const handleSaveRename = async () => {
    const trimmed = sceneLabel.trim();
    if (trimmed) {
      await sceneApi.update(scene.id, { soundDesign: trimmed });
      updateSceneInStore(scene.id, { soundDesign: trimmed });
    }
    setIsRenaming(false);
  };

  const handleStartRename = () => {
    const customName = scene.soundDesign && !scene.soundDesign.startsWith('{') && !scene.soundDesign.startsWith('分镜') ? scene.soundDesign : `分镜 ${scene.sceneNumber}`;
    setSceneLabel(customName);
    setIsRenaming(true);
  };

  const imageBadge = statusBadge(scene.imageStatus, 'image');
  const videoBadge = statusBadge(scene.videoStatus, 'video');

  return (
    <ContextMenu items={[
      { label: '✏️ 重命名', onClick: handleStartRename },
      { label: '🗑️ 删除分镜', danger: true, onClick: handleDelete },
    ]}>
    <div
      ref={cardRef}
      onClick={onSelect}
      style={{
        padding: 12,
        borderRadius: 'var(--rounded-md)',
        border: isSelected ? '2px solid var(--color-primary)' : '1px solid var(--color-hairline)',
        borderLeft: `3px solid ${isSelected ? 'var(--color-primary)' : 'var(--color-hairline)'}`,
        background: isSelected ? 'var(--color-surface-card)' : 'white',
        boxShadow: isSelected ? '0 2px 10px rgba(204, 120, 92, 0.18)' : 'none',
        cursor: 'pointer',
        marginBottom: 8,
        transition: 'border-color 0.2s ease, background 0.2s ease, box-shadow 0.2s ease',
        transformOrigin: 'center',
      }}
    >
      {/* Header row: scene number + delete button */}
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          marginBottom: 4,
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          {isRenaming ? (
            <input
              value={sceneLabel}
              onChange={(e) => setSceneLabel(e.target.value)}
              onBlur={handleSaveRename}
              onKeyDown={(e) => {
                if (e.key === 'Enter') {
                  e.preventDefault();
                  handleSaveRename();
                } else if (e.key === 'Escape') {
                  setIsRenaming(false);
                }
              }}
              onClick={(e) => e.stopPropagation()}
              autoFocus
              style={{
                fontSize: 13,
                fontWeight: 600,
                padding: '2px 6px',
                borderRadius: 'var(--rounded-sm)',
                border: '1px solid var(--color-primary)',
                outline: 'none',
                fontFamily: 'inherit',
                width: 160,
              }}
            />
          ) : (
            <>
              <div
                style={{
                  fontWeight: 600,
                  fontSize: 13,
                  color: 'var(--color-ink)',
                  display: 'flex',
                  alignItems: 'center',
                  gap: 6,
                }}
              >
                {scene.soundDesign && !scene.soundDesign.startsWith('{') && !scene.soundDesign.startsWith('分镜') ? scene.soundDesign : `分镜 ${scene.sceneNumber}`}
                {isUnread && (
                  <span
                    ref={unreadDotRef}
                    style={{
                      width: 8,
                      height: 8,
                      borderRadius: '50%',
                      background: '#e53935',
                      flexShrink: 0,
                    }}
                    title="有新生成结果"
                  />
                )}
              </div>
            </>
          )}
        </div>
        <MoreMenu
          items={[
            { label: '重命名', onClick: handleStartRename },
            { label: '删除分镜', danger: true, onClick: handleDelete },
          ]}
        />
      </div>

      <div
        style={{
          fontSize: 12,
          color: 'var(--color-muted)',
          lineHeight: 1.4,
          marginBottom: 6,
        }}
      >
        {scene.scriptContent?.slice(0, 80) || '空分镜'}
      </div>

      {/* Tags */}
      <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap', marginBottom: 6 }}>
        {scene.cameraMovement && <Tag>{scene.cameraMovement}</Tag>}
        {scene.shotType && <Tag>{scene.shotType}</Tag>}
        {scene.soundDesign && !scene.soundDesign.startsWith('{') && <Tag>{scene.soundDesign}</Tag>}
      </div>

      {/* 状态徽标（只读查看；生成/完善操作在预览面板） */}
      <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginBottom: 6 }}>
        <span
          style={{
            fontSize: 10,
            padding: '1px 6px',
            borderRadius: 'var(--rounded-sm)',
            background: 'var(--color-surface-soft)',
            color: imageBadge.color,
          }}
        >
          {imageBadge.text}
        </span>
        <span
          style={{
            fontSize: 10,
            padding: '1px 6px',
            borderRadius: 'var(--rounded-sm)',
            background: 'var(--color-surface-soft)',
            color: videoBadge.color,
          }}
        >
          {videoBadge.text}
        </span>
      </div>

      {/* Video progress bar */}
      {scene.videoStatus === 'generating' && (
        <div style={{ marginBottom: 8 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 3 }}>
            <span style={{ fontSize: 10, color: 'var(--color-muted)' }}>视频生成中</span>
            <span style={{ fontSize: 10, color: 'var(--color-muted)' }}>
              <AnimatedProgress value={videoProgress} />
            </span>
          </div>
          <div style={{ height: 4, borderRadius: 2, background: 'var(--color-surface-soft)', overflow: 'hidden' }}>
            <div style={{ height: '100%', width: `${videoProgress}%`, borderRadius: 2, background: 'var(--color-primary)', transition: 'width 0.3s ease' }} />
          </div>
        </div>
      )}

      {/* Expand/collapse toggle */}
      <button
        onClick={handleToggleExpand}
        style={{
          background: 'none',
          border: 'none',
          cursor: 'pointer',
          fontSize: 11,
          color: 'var(--color-primary)',
          padding: 0,
          marginBottom: expanded ? 8 : 0,
        }}
      >
        {expanded ? '▲ 收起编辑' : '▼ 编辑描述/提示词'}
      </button>

      {/* Prompt editor (collapsible) — 常驻 DOM，高度由 gsap 动画控制 */}
      <div
        ref={promptPanelRef}
        style={{
          overflow: 'hidden',
          height: 0,
          visibility: 'hidden',
          marginBottom: 0,
        }}
      >
        <div style={{ marginBottom: 8 }}>
          <label
            style={{ fontSize: 10, color: 'var(--color-muted)', display: 'block', marginBottom: 2 }}
          >
            注释(跟生图/生视频无关)
          </label>
          <textarea
            value={scriptContent}
            onChange={(e) => setScriptContent(e.target.value)}
            onBlur={() => handlePromptBlur('scriptContent')}
            onClick={(e) => e.stopPropagation()}
            placeholder="输入分镜描述..."
            rows={3}
            style={{
              width: '100%',
              fontSize: 11,
              padding: '6px 8px',
              borderRadius: 'var(--rounded-sm)',
              border: '1px solid var(--color-hairline)',
              resize: 'vertical',
              marginBottom: 8,
              boxSizing: 'border-box',
              fontFamily: 'inherit',
            }}
          />
          <label
            style={{ fontSize: 10, color: 'var(--color-muted)', display: 'block', marginBottom: 2 }}
          >
            生图提示词
          </label>
          <textarea
            value={imagePrompt}
            onChange={(e) => setImagePrompt(e.target.value)}
            onBlur={() => handlePromptBlur('imagePrompt')}
            onClick={(e) => e.stopPropagation()}
            placeholder="输入生图提示词..."
            rows={3}
            style={{
              width: '100%',
              fontSize: 11,
              padding: '6px 8px',
              borderRadius: 'var(--rounded-sm)',
              border: '1px solid var(--color-hairline)',
              resize: 'vertical',
              marginBottom: 8,
              boxSizing: 'border-box',
              fontFamily: 'inherit',
            }}
          />
          <label
            style={{ fontSize: 10, color: 'var(--color-muted)', display: 'block', marginBottom: 2 }}
          >
            生视频提示词
          </label>
          <textarea
            value={videoPrompt}
            onChange={(e) => setVideoPrompt(e.target.value)}
            onBlur={() => handlePromptBlur('videoPrompt')}
            onClick={(e) => e.stopPropagation()}
            placeholder="输入生视频提示词..."
            rows={3}
            style={{
              width: '100%',
              fontSize: 11,
              padding: '6px 8px',
              borderRadius: 'var(--rounded-sm)',
              border: '1px solid var(--color-hairline)',
              resize: 'vertical',
              marginBottom: 8,
              boxSizing: 'border-box',
              fontFamily: 'inherit',
            }}
          />
        </div>
      </div>
    </div>
    </ContextMenu>
  );
}
