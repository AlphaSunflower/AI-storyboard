import { useEffect, useState } from 'react';
import { assetUrl } from '../../config';
import { useProjectStore } from '../../stores/projectStore';

/**
 * 完善图片弹窗（灯箱）：显示当前生成图大图 + 完善诉求输入，
 * 提交后以当前图为源图走图改图（mode=edit, generatedImageUrl=当前图）。
 * 遮罩点击 / ESC / ✕ 关闭；生成中按钮禁用。
 */
export function RefineImageModal({
  sceneId,
  imageUrl,
  onClose,
}: {
  sceneId: string;
  imageUrl: string;
  onClose: () => void;
}) {
  const generateImage = useProjectStore((s) => s.generateImage);
  const generatingImage = useProjectStore((s) => !!s.generatingImage[sceneId]);
  const [refinePrompt, setRefinePrompt] = useState('');
  const [error, setError] = useState('');

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    const prevOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      window.removeEventListener('keydown', onKey);
      document.body.style.overflow = prevOverflow;
    };
  }, [onClose]);

  const handleRefine = async () => {
    if (!refinePrompt.trim()) {
      setError('请填写完善诉求，例如「让光线更柔和，人物更清晰」');
      return;
    }
    setError('');
    try {
      // 图改图：当前图为源图，诉求文本作为 prompt（model 传空 → 走默认编辑模型）
      await generateImage(sceneId, refinePrompt.trim(), undefined, undefined, 'edit', imageUrl);
      onClose(); // 成功后关闭弹窗，预览面板刷新展示新图
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
        || (err instanceof Error ? err.message : '完善失败，请重试');
      setError(msg);
    }
  };

  return (
    <div
      onClick={onClose}
      style={{
        position: 'fixed', inset: 0, zIndex: 1000,
        background: 'rgba(20,20,19,0.78)', backdropFilter: 'blur(2px)',
        display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 24,
      }}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        style={{
          background: 'var(--color-canvas)', borderRadius: 16,
          padding: 20, width: 'min(640px, 92vw)',
          boxShadow: '0 12px 48px rgba(0,0,0,0.45)',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 12 }}>
          <span style={{ fontSize: 15, fontWeight: 600, color: 'var(--color-ink)' }}>完善图片</span>
          <button
            onClick={onClose}
            aria-label="关闭"
            style={{
              width: 32, height: 32, borderRadius: '50%', border: 'none',
              background: 'var(--color-surface-card)', color: 'var(--color-muted)',
              fontSize: 15, cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center',
            }}
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
          </button>
        </div>

        {/* 源图大图（点击图片放大查看，勿裁切） */}
        <img
          src={assetUrl(imageUrl)}
          alt="当前生成图"
          style={{
            width: '100%', maxHeight: '42vh', objectFit: 'contain',
            borderRadius: 12, border: '1px solid var(--color-hairline)',
            background: 'var(--color-surface-soft)', marginBottom: 12,
          }}
        />

        <textarea
          value={refinePrompt}
          onChange={(e) => setRefinePrompt(e.target.value)}
          placeholder="描述你想如何完善这张图，例如：让光线更柔和，人物表情更自然，背景虚化…"
          rows={3}
          style={{
            width: '100%', boxSizing: 'border-box', padding: '10px 12px',
            borderRadius: 8, border: '1px solid var(--color-hairline)',
            background: 'white', color: 'var(--color-ink)', fontSize: 13,
            outline: 'none', resize: 'vertical', fontFamily: 'inherit', marginBottom: 10,
          }}
        />
        {error && (
          <div style={{ fontSize: 12, color: 'var(--color-warning, #c0392b)', marginBottom: 10, lineHeight: 1.5 }}>
            <svg style={{ verticalAlign: 'middle', marginRight: 4 }} width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>{' '}{error}
          </div>
        )}
        <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
          <button
            onClick={onClose}
            style={{
              padding: '8px 18px', fontSize: 13, borderRadius: 8,
              border: '1px solid var(--color-hairline)', background: 'var(--color-surface-card)',
              color: 'var(--color-muted)', cursor: 'pointer',
            }}
          >
            取消
          </button>
          <button
            disabled={generatingImage}
            onClick={handleRefine}
            style={{
              padding: '8px 20px', fontSize: 13, borderRadius: 8, border: 'none',
              background: 'var(--color-primary)', color: '#fff', cursor: 'pointer',
              opacity: generatingImage ? 0.6 : 1,
            }}
          >
            {generatingImage ? '生成中...' : '生成完善图'}
          </button>
        </div>
      </div>
    </div>
  );
}
