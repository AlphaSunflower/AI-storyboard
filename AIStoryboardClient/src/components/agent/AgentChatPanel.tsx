import { useCallback, useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { MicButton } from './MicButton';
import { sttStream } from '../../api/agent';
import { useAgentStore } from '../../stores/agentStore';
import { MessageBubble } from './MessageBubble';
import { HumanInputCard } from './HumanInputCard';
import { ConfirmResultCard } from './ConfirmResultCard';
import { VideoPlanCard } from './VideoPlanCard';
import { AgentAssetsModal } from './AgentAssetsPanel';
import { SceneSelectorModal } from './SceneSelectorModal';
import TextType from '../TextType';
import SpecularButton from '../SpecularButton';
import type { SceneResponse } from '../../api/projects';

export function AgentChatPanel() {
  const { messages, streaming, waitingHumanInput, waitingVideoPlan, streamError, refImageUrl, setRefImageUrl, uploadRefImage, sendMessage, confirmResult, pendingPicUrl, cancelRefine, assets, loadAssets, conversations, activeConversationId, workflowHint } = useAgentStore();
  const [text, setText] = useState('');
  // 录音中（禁用发送/流转等）
  const [recording, setRecording] = useState(false);
  // 流式语音识别状态：活跃会话句柄 + 已确认文本（增量追加，不清空）
  const sttRef = useRef<{ push: (p: Int16Array) => void; close: () => void; cancel: () => void } | null>(null);
  const confirmedRef = useRef(''); // 已确认片段累积（vosk 每条 text 是增量，片段间逗号分隔）
  // 镜像 text 供回调读取（避免 useCallback 闭包陈旧）
  const textRef = useRef('');
  textRef.current = text;
  // 产出素材弹窗（文件夹图标入口，素材不再常驻底部）
  const [assetsOpen, setAssetsOpen] = useState(false);
  // "+" 菜单状态
  const [menuOpen, setMenuOpen] = useState(false);
  const [sceneModalOpen, setSceneModalOpen] = useState(false);
  const [menuPos, setMenuPos] = useState<{ top: number; left: number } | null>(null);
  const menuBtnRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  // M9：是否处于近底位置（距底部 <80px）；用户上翻查看历史时暂停自动滚底跟随
  const nearBottomRef = useRef(true);
  const fileRef = useRef<HTMLInputElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);

  // 新消息自动滚底（M9：仅当用户处于近底位置时跟随）
  useEffect(() => {
    const el = scrollRef.current;
    if (el && nearBottomRef.current) el.scrollTop = el.scrollHeight;
  }, [messages, streaming, waitingHumanInput, waitingVideoPlan]);

  // 切换会话清空草稿
  useEffect(() => { setText(''); }, [activeConversationId]);

  // 点击"继续完善"后：聚焦输入框，引导用户输入完善需求（不自动发送）
  useEffect(() => {
    if (pendingPicUrl) {
      inputRef.current?.focus();
    }
  }, [pendingPicUrl]);

  // 点击外部关闭"+"菜单
  useEffect(() => {
    if (!menuOpen) return;
    const close = (e: MouseEvent) => {
      if (!menuBtnRef.current?.contains(e.target as Node) && !menuRef.current?.contains(e.target as Node)) setMenuOpen(false);
    };
    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, [menuOpen]);

  const handleSend = () => {
    const content = text.trim();
    if (!content || streaming || waitingHumanInput || waitingVideoPlan) return;
    setText('');
    sendMessage(content);
  };

  const openMenu = () => {
    const btn = menuBtnRef.current;
    if (btn) {
      const r = btn.getBoundingClientRect();
      setMenuPos({ top: r.top - 88, left: r.left });
    }
    setMenuOpen(!menuOpen);
  };

  const handleSceneConfirm = (selected: SceneResponse[]) => {
    const summary = selected.map((s) => {
      const desc = s.scriptContent?.slice(0, 60) || '无描述';
      const img = s.imageUrl ? '[已有图]' : '[未生图]';
      const vid = s.videoUrl ? '[已有视频]' : '[未生视频]';
      return `分镜${s.sceneNumber}（${img}${vid}）：${desc}`;
    }).join('\n');
    sendMessage(`请分析以下分镜并给出优化和生成建议：\n${summary}`);
    setSceneModalOpen(false);
  };

  // 当前会话标题：对话窗口顶部展示；无会话时占位
  const currentTitle = conversations.find((c) => c.id === activeConversationId)?.title ?? '未选择对话';

  const handleFile = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    try {
      await uploadRefImage(file);
    } catch {
      setStreamErrorLocal('图片上传失败');
    }
    // M8：重置 input value，允许连续选择同一文件再次上传
    e.target.value = '';
  };

  const setStreamErrorLocal = (msg: string) => {
    // 轻量提示：直接复用 streamError 展示
    void msg;
    alert(msg);
  };

  // 麦克风录制完成 → 上传识别 → 回填输入框
  // partial（预览全文）→ 显示「已确认 + 新片段」临时预览（新片段会随识别修正变化）
  const handleSttPartial = useCallback((t: string) => {
    const clean = t.replace(/\s+/g, '');
    if (!clean) return;
    const base = confirmedRef.current;
    if (!base) { setText(clean); return; }
    // partial 全文含历史：去掉已确认前缀，剩余作为新片段预览（逗号分隔）
    const rest = clean.startsWith(base) ? clean.slice(base.length) : clean;
    setText(rest ? `${base}，${rest}` : base);
  }, []);

  // vosk 确认片段（实测为增量，每段确认触发）→ 追加到已确认文本，片段间逗号分隔；停止后保留不清空
  const handleSttFinal = useCallback((t: string) => {
    const clean = t.replace(/\s+/g, '');
    if (!clean) return;
    const base = confirmedRef.current;
    if (base.includes(clean)) return; // 防重复（partial 预览已含）
    const next = base ? `${base}，${clean}` : clean;
    confirmedRef.current = next;
    setText(next);
  }, []);

  // 麦克风开关：开始录音 → 开流式识别会话（继续追加输入框已有内容）；停止录音 → 关流（不清空已识别文本）
  const handleMicToggle = useCallback((active: boolean) => {
    setRecording(active);
    if (active) {
      sttRef.current?.cancel();
      confirmedRef.current = textRef.current; // 输入框已有内容作为累积 base（继续追加）
      sttRef.current = sttStream(handleSttPartial, handleSttFinal);
    } else {
      sttRef.current?.close();
    }
  }, [handleSttPartial, handleSttFinal]);

  // 实时 PCM 块 → 推给后端流式识别
  const handlePcm = useCallback((pcm: Int16Array) => {
    sttRef.current?.push(pcm);
  }, []);

  // 组件卸载：中断活跃识别
  useEffect(() => () => { sttRef.current?.cancel(); }, []);

  return (
    <div style={{ flex: 1, display: 'flex', flexDirection: 'column', minHeight: 0 }}>
      {/* 头部 */}
      <div style={{ padding: '16px 20px', borderBottom: '1px solid var(--color-hairline)', background: 'white', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        {/* 当前会话标题（☾ Moon 智能体标题已迁移至会话栏顶部） */}
        <span
          title={currentTitle}
          style={{
            flex: 1, minWidth: 0, fontSize: 18, fontWeight: 600, color: 'var(--color-ink)',
            overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
          }}
        >
          {currentTitle}
        </span>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          {/* 文件夹图标：查看当前对话的产出素材（弹窗） */}
          <button
            onClick={() => { setAssetsOpen(true); void loadAssets(); }}
            disabled={!activeConversationId}
            title="查看当前对话的产出素材"
            style={{
              border: 'none', background: 'none', color: 'var(--color-muted)',
              fontSize: 14, cursor: activeConversationId ? 'pointer' : 'not-allowed',
              padding: '4px 8px', borderRadius: 6, opacity: activeConversationId ? 1 : 0.4,
            }}
          >
            📁 产出素材{assets && assets.total > 0 ? ` (${assets.total})` : ''}
          </button>
        </div>
      </div>

      {/* 消息流 */}
      <div
        ref={scrollRef}
        onScroll={(e) => {
          // M9：滚动时更新近底状态（距底部 <80px 视为近底）
          const el = e.target as HTMLElement;
          nearBottomRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
        }}
        style={{ flex: 1, minHeight: 0, overflowY: 'auto', padding: 20, background: 'var(--color-canvas)' }}
      >
        {messages.length === 0 && !streaming && (
          <TextType
            as="p"
            text="与 Moon 智能体对话，设计分镜、图片与视频方案"
            typingSpeed={55}
            initialDelay={200}
            pauseDuration={4000}
            loop={false}
            showCursor
            cursorCharacter="|"
            style={{ textAlign: 'center', color: 'var(--color-muted-soft)', fontSize: 15, marginTop: 64 }}
          />
        )}
        {messages.map((m) => (
          <MessageBubble
            key={m.id}
            role={m.role}
            content={m.content}
            createdAt={m.createdAt}
            streaming={streaming && m.role === 'assistant' && m.id === messages[messages.length - 1]?.id}
          />
        ))}
        {streaming && !waitingHumanInput && (
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, color: 'var(--color-muted)', fontSize: 14, marginLeft: 6 }}>
            <span>{workflowHint || '正在生成'}</span>
            {/* C 组：思考中三点依次跳动 */}
            <span style={{ display: 'inline-flex', gap: 2 }}>
              {[0, 1, 2].map((i) => (
                <span
                  key={i}
                  style={{
                    width: 3, height: 3, borderRadius: '50%',
                    background: 'var(--color-muted)',
                    animation: 'thinkingDot 1.2s ease-in-out infinite',
                    animationDelay: `${i * 0.18}s`,
                  }}
                />
              ))}
            </span>
            <style>{`
              @keyframes thinkingDot {
                0%, 60%, 100% { transform: translateY(0); opacity: 0.4; }
                30% { transform: translateY(-4px); opacity: 1; }
              }
            `}</style>
          </div>
        )}
        {waitingHumanInput && <HumanInputCard info={waitingHumanInput} />}
        {waitingVideoPlan && <VideoPlanCard info={waitingVideoPlan} />}
        {confirmResult && <ConfirmResultCard />}
        {streamError && (
          <div style={{ color: 'var(--color-error)', fontSize: 14, margin: '10px 6px' }}>
            ⚠ {streamError}
          </div>
        )}
      </div>

      {/* 输入区 */}
      <div style={{ padding: 16, borderTop: '1px solid var(--color-hairline)', background: 'white' }}>
        {refImageUrl && (
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10 }}>
            <img src={refImageUrl} style={{ width: 56, height: 56, objectFit: 'cover', borderRadius: 10 }} />
            <span style={{ fontSize: 14, color: 'var(--color-muted)' }}>参考图已附</span>
            <button onClick={() => setRefImageUrl(null)} style={{ border: 'none', background: 'none', color: 'var(--color-error)', cursor: 'pointer', fontSize: 14 }}>移除</button>
          </div>
        )}
        {/* 继续完善提示条：点击"继续完善"后暂存参考图，等待用户输入完善需求 */}
        {pendingPicUrl && (
          <div
            style={{
              display: 'flex', alignItems: 'center', justifyContent: 'space-between',
              padding: '10px 14px', marginBottom: 10, borderRadius: 'var(--rounded-md)',
              background: 'var(--color-primary-soft, #fdf1ec)', border: '1px solid var(--color-hairline)',
              fontSize: 14, color: 'var(--color-muted)',
            }}
          >
            <span>📎 已选当前图片作为参考，请输入你想完善的地方</span>
            <button
              onClick={() => cancelRefine()}
              style={{ border: 'none', background: 'none', color: 'var(--color-muted)', cursor: 'pointer', fontSize: 14, marginLeft: 10 }}
            >
              ✕ 取消
            </button>
          </div>
        )}
        <div style={{ display: 'flex', gap: 10, alignItems: 'flex-end' }}>
          <div style={{ position: 'relative' }}>
            <button
              ref={menuBtnRef}
              onClick={openMenu}
              title="上传"
              style={{ width: 42, height: 42, border: '1px solid var(--color-hairline)', borderRadius: 'var(--rounded-md)', background: 'var(--color-canvas)', cursor: 'pointer', fontSize: 18, flexShrink: 0, display: 'flex', alignItems: 'center', justifyContent: 'center' }}
            >{/** DeepSeek 风格上传图标：圆圈+加号 */}<svg  width="20" height="20" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"><circle cx="10" cy="10" r="8.5" /><line x1="10" y1="6" x2="10" y2="14" /><line x1="6" y1="10" x2="14" y2="10" /></svg></button>
            <input ref={fileRef} type="file" accept="image/*" hidden onChange={handleFile} />
            {/* 下拉菜单：portal 到 body */}
            {menuOpen && menuPos && createPortal(
              <div ref={menuRef} style={{
                position: 'fixed', top: menuPos.top, left: menuPos.left,
                background: 'white', border: '1px solid var(--color-hairline)',
                borderRadius: 10, boxShadow: '0 4px 16px rgba(0,0,0,0.12)',
                padding: 4, zIndex: 1000, minWidth: 140,
              }}>
                <button
                  onClick={() => { setMenuOpen(false); setSceneModalOpen(true); }}
                  style={{
                    display: 'flex', alignItems: 'center', gap: 8, width: '100%', padding: '8px 14px', border: 'none',
                    background: 'transparent', textAlign: 'left', fontSize: 14, cursor: 'pointer',
                    color: 'var(--color-ink)', borderRadius: 8,
                  }}
                  onMouseEnter={(e) => { (e.target as HTMLElement).style.background = 'rgba(38,49,72,0.06)'; }}
                  onMouseLeave={(e) => { (e.target as HTMLElement).style.background = 'transparent'; }}
                >
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><rect x="2" y="2" width="20" height="20" rx="2"/><path d="M10 12h4M12 10v4"/></svg>
                  上传分镜
                </button>
                <button
                  onClick={() => { setMenuOpen(false); fileRef.current?.click(); }}
                  style={{
                    display: 'flex', alignItems: 'center', gap: 8, width: '100%', padding: '8px 14px', border: 'none',
                    background: 'transparent', textAlign: 'left', fontSize: 14, cursor: 'pointer',
                    color: 'var(--color-ink)', borderRadius: 8,
                  }}
                  onMouseEnter={(e) => { (e.target as HTMLElement).style.background = 'rgba(38,49,72,0.06)'; }}
                  onMouseLeave={(e) => { (e.target as HTMLElement).style.background = 'transparent'; }}
                >
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="8.5" cy="8.5" r="1.5"/><path d="m21 15-5-5L5 21"/></svg>
                  上传图片
                </button>
              </div>,
              document.body,
            )}
          </div>
          <textarea
            ref={inputRef}
            value={text}
            onChange={(e) => setText(e.target.value)}
            onKeyDown={(e) => {
              // I5：中文输入法组合期（选词中）按下 Enter 不发送
              if (e.nativeEvent.isComposing) return;
              if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); handleSend(); }
            }}
            placeholder={waitingHumanInput || waitingVideoPlan ? '请先完成上方确认' : streaming ? '智能体正在回复…' : pendingPicUrl ? '例如：把色调调暖一点、换成日系风格…' : '描述你的需求…'}
            disabled={streaming || !!waitingHumanInput || !!waitingVideoPlan}
            rows={2}
            style={{
              flex: 1, padding: '12px 14px', border: '1px solid var(--color-hairline)',
              borderRadius: 'var(--rounded-md)', fontSize: 15, lineHeight: 1.6, color: 'var(--color-ink)',
              resize: 'none', outline: 'none', background: 'var(--color-canvas)',
            }}
          />
          {/* 右侧：麦克风 + 发送，紧贴排列 */}
          <div style={{ display: "flex", alignItems: "center", gap: 0 }}>
            <MicButton
              onToggle={handleMicToggle}
              onPcm={handlePcm}
              // 录音中不禁用（识别中也能停止）；流式回复中非录音态禁用开始
              disabled={(streaming || !!waitingHumanInput || !!waitingVideoPlan) && !recording}
            />
            <SpecularButton
              size="sm"
              radius={8}
              tint="#cc785c"
              tintOpacity={1}
              textColor="#ffffff"
              lineColor="#ffffff"
              baseColor="#ffffff"
              intensity={1}
              thickness={1.2}
              disabled={recording || streaming || !!waitingHumanInput || !!waitingVideoPlan || !text.trim()}
              onClick={handleSend}
            >
              发送
            </SpecularButton>
          </div>
        </div>
      </div>

      {/* 产出素材弹窗（文件夹图标入口） */}
      <AgentAssetsModal open={assetsOpen} onClose={() => setAssetsOpen(false)} />
      {/* 分镜选择弹窗 */}
      <SceneSelectorModal
        open={sceneModalOpen}
        onClose={() => setSceneModalOpen(false)}
        onConfirm={handleSceneConfirm}
      />
    </div>
  );
}
