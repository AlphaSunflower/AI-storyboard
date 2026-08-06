import { useState } from 'react';
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

export function MessageBubble({ role, content }: { role: 'user' | 'assistant'; content: string }) {
  const isUser = role === 'user';
  // 图片点击放大预览（灯箱）
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  return (
    <>
    <div style={{ display: 'flex', justifyContent: isUser ? 'flex-end' : 'flex-start', marginBottom: 10 }}>
      <div
        style={{
          maxWidth: '82%',
          padding: '8px 12px',
          borderRadius: isUser ? '10px 10px 2px 10px' : '10px 10px 10px 2px',
          background: isUser ? 'var(--color-primary)' : 'var(--color-surface-card)',
          color: isUser ? 'white' : 'var(--color-body)',
          fontSize: 13,
          lineHeight: 1.6,
          whiteSpace: 'pre-wrap',
          wordBreak: 'break-word',
          // 全局 #root 有 text-align: center（模板遗留），必须显式左对齐，否则气泡内文字继承居中
          textAlign: 'left',
        }}
      >
        {content ? renderContent(content, setPreviewUrl) : <span style={{ opacity: 0.6 }}>…</span>}
      </div>
    </div>
    <ImagePreviewModal url={previewUrl} onClose={() => setPreviewUrl(null)} />
    </>
  );
}
