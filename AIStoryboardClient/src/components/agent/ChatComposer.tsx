import { useRef, useState, useEffect } from 'react';
import { createPortal } from 'react-dom';
import { useAgentStore } from '../../stores/agentStore';
import { SceneSelectorModal } from './SceneSelectorModal';
import { MicButton } from './MicButton';
import { agentApi } from '../../api/agent';
import type { SceneResponse } from '../../api/projects';

/** /chat 页设计 token（蓝色品牌 + 中性灰底，统一全局） */
export const DS = {
  brand: 'rgb(65, 118, 230)',
  brandHover: 'rgb(86, 134, 254)',
  bubble: 'rgb(235, 240, 250)',
  ink: 'rgb(15, 17, 21)',
  textSecondary: 'rgb(96, 100, 108)',
  textCaption: 'rgb(152, 158, 168)',
  border: 'rgba(0, 0, 0, 0.08)',
  hover: 'rgba(65, 118, 230, 0.06)',
  sidebarBg: '#f7f8fa',
  maxContent: 780,
};

/**
 * DeepSeek 风格输入卡（780px 悬浮胶囊）：/chat 页 hero 居中与底部输入共用。
 * 逻辑复用 agentStore（参考图/pendingPicUrl/发送/流式禁用）。
 */
export function ChatComposer() {
  const {
    streaming, waitingHumanInput, waitingVideoPlan,
    refImageUrl, setRefImageUrl, uploadRefImage, sendMessage,
    pendingPicUrl, cancelRefine,
  } = useAgentStore();
  const [text, setText] = useState('');
  const [sttBusy, setSttBusy] = useState(false);
  const fileRef = useRef<HTMLInputElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const menuBtnRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const activeConversationId = useAgentStore((s) => s.activeConversationId);

  // "+" 菜单状态
  const [menuOpen, setMenuOpen] = useState(false);
  const [sceneModalOpen, setSceneModalOpen] = useState(false);
  const [menuPos, setMenuPos] = useState<{ top: number; left: number } | null>(null);

  // 切换会话清空草稿（与抽屉 AgentChatPanel 一致）
  useEffect(() => { setText(''); }, [activeConversationId]);

  // 点击"继续完善"后聚焦输入框
  useEffect(() => {
    if (pendingPicUrl) inputRef.current?.focus();
  }, [pendingPicUrl]);

  // 点击外部关闭菜单
  useEffect(() => {
    if (!menuOpen) return;
    const close = (e: MouseEvent) => {
      if (!menuBtnRef.current?.contains(e.target as Node) && !menuRef.current?.contains(e.target as Node)) setMenuOpen(false);
    };
    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, [menuOpen]);

  const busy = streaming || !!waitingHumanInput || !!waitingVideoPlan;

  const handleSend = () => {
    const content = text.trim();
    if (!content || busy) return;
    setText('');
    sendMessage(content);
  };

  const handleFile = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    try {
      await uploadRefImage(file);
    } catch {
      alert('图片上传失败');
    }
    e.target.value = '';
  };

  // 语音识别：录音结束 → stt → 直接作为消息发送（即说即发；vosk 中文词间空格已去除）
  const handleRecorded = async (wav: Blob) => {
    setSttBusy(true);
    try {
      const res = await agentApi.stt(wav);
      const recognized = (res.data?.data?.text ?? '').replace(/\s+/g, '');
      if (recognized.trim()) {
        setText('');
        sendMessage(recognized);
      } else {
        alert('未识别到语音内容，请重试');
      }
    } catch {
      alert('语音识别失败，请稍后重试');
    } finally {
      setSttBusy(false);
    }
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

  return (
    <div style={{
      width: '100%', maxWidth: DS.maxContent, margin: '0 auto',
      border: `1px solid ${DS.border}`, borderRadius: 22,
      background: 'white', boxShadow: '0 2px 12px rgba(0, 0, 0, 0.06)',
      padding: '10px 14px 12px', display: 'flex', flexDirection: 'column', gap: 10,
      boxSizing: 'border-box',
    }}>
      {refImageUrl && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <img src={refImageUrl} style={{ width: 48, height: 48, objectFit: 'cover', borderRadius: 10 }} />
          <span style={{ fontSize: 14, color: DS.textSecondary }}>参考图已附</span>
          <button onClick={() => setRefImageUrl(null)} style={{ border: 'none', background: 'none', color: DS.textSecondary, cursor: 'pointer', fontSize: 14 }}>移除</button>
        </div>
      )}
      {pendingPicUrl && (
        <div style={{
          display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          padding: '8px 12px', borderRadius: 12, background: DS.hover, fontSize: 14, color: DS.textSecondary,
        }}>
          <span>📎 已选当前图片作为参考，请输入你想完善的地方</span>
          <button onClick={() => cancelRefine()} style={{ border: 'none', background: 'none', color: DS.textSecondary, cursor: 'pointer', fontSize: 14 }}>✕ 取消</button>
        </div>
      )}
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
        disabled={busy}
        rows={2}
        style={{
          border: 'none', outline: 'none', resize: 'none', background: 'transparent',
          fontSize: 16, lineHeight: 1.6, color: DS.ink, fontFamily: 'inherit',
        }}
      />
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <div style={{ position: 'relative' }}>
          <button
            ref={menuBtnRef}
            onClick={openMenu}
            title="上传"
            style={{
              width: 36, height: 36, borderRadius: 10, border: 'none', background: 'transparent',
              fontSize: 17, cursor: 'pointer', color: DS.textSecondary, flexShrink: 0,
              display: 'flex', alignItems: 'center', justifyContent: 'center',
            }}
          >{/** DeepSeek 风格上传图标：圆圈+加号 */}<svg width="20" height="20" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"><circle cx="10" cy="10" r="8.5" /><line x1="10" y1="6" x2="10" y2="14" /><line x1="6" y1="10" x2="14" y2="10" /></svg></button>
          <input ref={fileRef} type="file" accept="image/*" hidden onChange={handleFile} />
          {/* 下拉菜单：portal 到 body */}
          {menuOpen && menuPos && createPortal(
            <div ref={menuRef} style={{
              position: 'fixed', top: menuPos.top, left: menuPos.left,
              background: 'white', border: `1px solid ${DS.border}`,
              borderRadius: 10, boxShadow: '0 4px 16px rgba(0,0,0,0.12)',
              padding: 4, zIndex: 1000, minWidth: 140,
            }}>
              <button
                onClick={() => { setMenuOpen(false); setSceneModalOpen(true); }}
                style={{
                  display: 'flex', alignItems: 'center', gap: 8, width: '100%', padding: '8px 14px', border: 'none',
                  background: 'transparent', textAlign: 'left', fontSize: 14, cursor: 'pointer',
                  color: DS.ink, borderRadius: 8,
                }}
                onMouseEnter={(e) => { (e.target as HTMLElement).style.background = DS.hover; }}
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
                  color: DS.ink, borderRadius: 8,
                }}
                onMouseEnter={(e) => { (e.target as HTMLElement).style.background = DS.hover; }}
                onMouseLeave={(e) => { (e.target as HTMLElement).style.background = 'transparent'; }}
              >
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="8.5" cy="8.5" r="1.5"/><path d="m21 15-5-5L5 21"/></svg>
                上传图片
              </button>
            </div>,
            document.body,
          )}
        </div>
        {/* 右侧：麦克风 + 发送，紧贴排列 */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 0 }}>
          <MicButton onRecorded={handleRecorded} disabled={sttBusy || busy} />
          <button
            onClick={handleSend}
            disabled={busy || !text.trim()}
            style={{
              height: 38, padding: '0 22px', borderRadius: 12, border: 'none',
              background: DS.brand, color: 'white', fontSize: 15, fontWeight: 500,
              cursor: busy || !text.trim() ? 'not-allowed' : 'pointer', opacity: busy || !text.trim() ? 0.45 : 1,
              transition: 'background 0.15s',
            }}
            onMouseEnter={(e) => { if (!busy && text.trim()) (e.target as HTMLElement).style.background = DS.brandHover; }}
            onMouseLeave={(e) => { (e.target as HTMLElement).style.background = DS.brand; }}
          >发送</button>
        </div>
      </div>

      {/* 分镜选择弹窗 */}
      <SceneSelectorModal
        open={sceneModalOpen}
        onClose={() => setSceneModalOpen(false)}
        onConfirm={handleSceneConfirm}
      />
    </div>
  );
}
