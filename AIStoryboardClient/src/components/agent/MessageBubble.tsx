import { useState, useRef } from 'react';
import gsap from 'gsap';
import { useGSAP } from '@gsap/react';
import { assetUrl } from '../../config';
import { ImagePreviewModal } from './ImagePreviewModal';

/**
 * 图片加载失败时降级为"（图片已过期）"占位文本：
 * 防御历史消息里残留的 Dify 签名 URL（/files/tools/ 带 timestamp+sign，过期后 403）
 * 渲染成裂图 + alt 文件名的难看效果。
 * onClick 可选：点击图片放大预览（灯箱）。
 */
function ImgWithFallback({
  src, alt, style, onClick,
}: {
  src: string; alt?: string; style?: React.CSSProperties; onClick?: () => void;
}) {
  const [failed, setFailed] = useState(false);
  if (failed) {
    return (
      <span style={{ color: 'var(--color-muted-soft)', fontSize: 12, fontStyle: 'italic', margin: '4px 0' }}>
        （图片已过期或无法加载）
      </span>
    );
  }
  return (
    <img
      src={src}
      alt={alt ?? ''}
      style={{ ...style, cursor: onClick ? 'zoom-in' : undefined }}
      onClick={onClick}
      onError={() => setFailed(true)}
    />
  );
}

/** 轻量渲染：**加粗**、换行、![]() 图片、[]() 链接、视频 URL 直接识别；onImgClick 为图片点击预览回调 */
function renderContent(content: string, onImgClick: (url: string) => void) {
  const lines = content.split('\n');
  return lines.map((line, i) => {
    // 图片 ![alt](url)
    const imgMatch = line.match(/!\[([^\]]*)\]\(([^)]+)\)/);
    if (imgMatch) {
      return (
        <ImgWithFallback
          key={i}
          src={assetUrl(imgMatch[2])}
          alt={imgMatch[1]}
          onClick={() => onImgClick(imgMatch[2])}
          style={{ maxWidth: '100%', maxHeight: 220, borderRadius: 8, margin: '4px 0', display: 'block' }}
        />
      );
    }
    // 视频 URL（http(s) 绝对地址或后端相对路径 /api/files/videos/ 开头的 .mp4/.webm 直接渲染；
    // assetUrl() 对 http 原样返回、对相对路径拼 BACKEND 前缀）
    const videoMatch = line.match(/(?:https?:\/\/|\/api\/files\/videos\/)\S+\.(mp4|webm)(\?\S*)?/i);
    if (videoMatch) {
      return (
        <video
          key={i}
          src={assetUrl(videoMatch[0])}
          controls
          style={{ maxWidth: '100%', maxHeight: 240, borderRadius: 8, margin: '4px 0', display: 'block' }}
        />
      );
    }
    // 裸图片 URL（整行为 .png/.jpg/.jpeg/.gif/.webp 链接或 data: URI）直接渲染——
    // Dify 工作流生图节点常直接输出 URL 文本而非 markdown 图片语法
    const rawImg = line.trim().match(/^(https?:\/\/\S+\.(png|jpe?g|gif|webp)(\?\S*)?|\/\S+\.(png|jpe?g|gif|webp)(\?\S*)?|data:image\/[a-z+]+;base64,\S+)$/i);
    if (rawImg) {
      const raw = rawImg[0];
      return (
        <ImgWithFallback
          key={i}
          src={raw.startsWith('data:') || raw.startsWith('http') ? raw : assetUrl(raw)}
          alt=""
          onClick={() => onImgClick(raw)}
          style={{ maxWidth: '100%', maxHeight: 220, borderRadius: 8, margin: '4px 0', display: 'block' }}
        />
      );
    }
    // 加粗
    const parts = line.split(/(\*\*[^*]+\*\*)/g).map((part, j) =>
      part.startsWith('**') && part.endsWith('**') ? (
        <strong key={j}>{part.slice(2, -2)}</strong>
      ) : (
        <span key={j}>{part}</span>
      ),
    );
    return <div key={i} style={{ marginBottom: 2 }}>{parts}</div>;
  });
}

/** 消息时间格式化：今天 → HH:MM；跨天 → MM-DD HH:MM */
function formatTime(iso?: string): string {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  const today = new Date();
  const sameDay = d.getFullYear() === today.getFullYear() && d.getMonth() === today.getMonth() && d.getDate() === today.getDate();
  if (sameDay) return `${hh}:${mm}`;
  return `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${hh}:${mm}`;
}

export function MessageBubble({ role, content, streaming, variant = 'default', createdAt }: {
  role: 'user' | 'assistant'; content: string; streaming?: boolean;
  variant?: 'default' | 'deepseek'; createdAt?: string;
}) {
  const isUser = role === 'user';
  const ds = variant === 'deepseek';
  // 图片点击放大预览（灯箱）
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const bubbleRef = useRef<HTMLDivElement>(null);

  const handleCopy = () => {
    navigator.clipboard.writeText(content).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    }).catch(() => { /* 剪贴板不可用时静默 */ });
  };

  // 新消息入场：从下往上轻微浮入（仅挂载时播放一次）
  useGSAP(() => {
    if (!bubbleRef.current) return;
    gsap.fromTo(
      bubbleRef.current,
      { y: 12, opacity: 0, scale: 0.98 },
      {
        y: 0, opacity: 1, scale: 1, duration: 0.3, ease: 'power2.out',
        onComplete: () => {
          // 清除残留 transform：气泡作为 containing block 会让内部 fixed 灯箱（ImagePreviewModal）错位
          gsap.set(bubbleRef.current, { clearProps: 'transform' });
        },
      }
    );
  }, { scope: bubbleRef });

  return (
    <>
    <div ref={bubbleRef} style={{ display: 'flex', flexDirection: 'column', alignItems: isUser ? 'flex-end' : 'flex-start', marginBottom: ds ? 16 : 18 }}>
      <div
        style={ds
          ? // DeepSeek 风格：用户=浅蓝 22px 圆角气泡(≤525px)；助手=无气泡纯文本
            {
              maxWidth: isUser ? 'min(525px, 82%)' : '100%',
              padding: isUser ? '10px 16px' : 0,
              borderRadius: isUser ? 22 : 0,
              background: isUser ? 'rgb(237, 243, 254)' : 'transparent',
              color: 'rgb(15, 17, 21)',
              fontSize: 16,
              lineHeight: 1.6,
              whiteSpace: 'pre-wrap',
              wordBreak: 'break-word',
              textAlign: 'left',
            }
          : {
              maxWidth: '82%',
              padding: '12px 18px',
              borderRadius: isUser ? '14px 14px 4px 14px' : '14px 14px 14px 4px',
              background: isUser ? 'var(--color-primary)' : 'var(--color-surface-card)',
              color: isUser ? 'white' : 'var(--color-body)',
              // 抽屉空间有限,正文 15px(全局 16px 偏大)
              fontSize: 15,
              lineHeight: 1.7,
              whiteSpace: 'pre-wrap',
              wordBreak: 'break-word',
              // 全局 #root 有 text-align: center（模板遗留），必须显式左对齐，否则气泡内文字继承居中
              textAlign: 'left',
            }}
      >
        {content ? renderContent(content, setPreviewUrl) : <span style={{ opacity: 0.6 }}>…</span>}
        {/* C 组：流式回复中的打字机光标 */}
        {streaming && content && (
          <span
            style={{
              display: 'inline-block',
              width: 2, height: 16,
              marginLeft: 2,
              verticalAlign: 'text-bottom',
              background: isUser ? (ds ? 'rgb(65, 118, 230)' : 'white') : (ds ? 'rgb(65, 118, 230)' : 'var(--color-primary)'),
              animation: 'typeCursor 1s steps(1) infinite',
            }}
          />
        )}
        <style>{`@keyframes typeCursor { 0%, 100% { opacity: 1; } 50% { opacity: 0; } }`}</style>
      </div>
      {/* 消息 meta 行：发送时间常显 + 复制（行 hover 显示） */}
      {!streaming && (
        <div
          style={{
            display: 'flex', alignItems: 'center', gap: 8, marginTop: 4,
            padding: ds ? '0 2px' : '0 4px',
          }}
          className="msg-meta"
        >
          <span style={{ fontSize: ds ? 12 : 11, color: ds ? 'rgb(162, 164, 166)' : 'var(--color-muted-soft)' }}>
            {formatTime(createdAt)}
          </span>
          <button
            onClick={handleCopy}
            title="复制内容"
            className="msg-copy"
            style={{
              border: 'none', background: 'transparent', cursor: 'pointer',
              fontSize: ds ? 12 : 11, padding: '1px 4px', borderRadius: 6,
              color: ds ? 'rgb(162, 164, 166)' : 'var(--color-muted)',
              opacity: 0, transition: 'opacity 0.15s',
            }}
          >{copied ? '✓ 已复制' : '⧉ 复制'}</button>
        </div>
      )}
      <style>{`
        .msg-meta:hover .msg-copy { opacity: 1; }
      `}</style>
    </div>
    <ImagePreviewModal url={previewUrl} onClose={() => setPreviewUrl(null)} />
    </>
  );
}
