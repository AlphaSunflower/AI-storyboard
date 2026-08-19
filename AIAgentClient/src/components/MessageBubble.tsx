import { useState, useRef, isValidElement } from 'react';
import gsap from 'gsap';
import { useGSAP } from '@gsap/react';
import ReactMarkdown, { type Components } from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { Check, Copy, Moon } from 'lucide-react';
import { assetUrl } from '../config';
import { ImagePreviewModal } from './ImagePreviewModal';
import type { AgentMessage } from '../api/agent';

/**
 * 图片加载失败时降级为「(图片已过期)」占位文本:
 * 防御历史消息里残留的签名 URL(带 timestamp+sign,过期后 403)
 * 渲染成裂图 + alt 文件名的难看效果。
 * onClick 可选:点击图片放大预览(灯箱)。
 */
function ImgWithFallback({
  src, alt, style, onClick,
}: {
  src: string; alt?: string; style?: React.CSSProperties; onClick?: () => void;
}) {
  const [failed, setFailed] = useState(false);
  if (failed) {
    return (
      <span style={{ color: 'var(--color-muted-soft)', fontSize: 14, fontStyle: 'italic', margin: '4px 0' }}>
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

/** 复制按钮:点击复制,成功短暂显示 ✓ 已复制 */
function CopyButton({ text, label = '' }: { text: string; label?: string }) {
  const [copied, setCopied] = useState(false);
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch { /* 剪贴板不可用(非安全上下文)时静默 */ }
  };
  return (
    <button onClick={copy} title="复制">
      {copied ? <Check size={13} style={{ color: 'var(--color-primary)' }} /> : <Copy size={13} />}
      {label}
    </button>
  );
}

/** 消息时间戳:createdAt ISO → HH:mm,非法值留空 */
function formatTime(iso?: string) {
  if (!iso) return '';
  try {
    return new Date(iso).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
  } catch {
    return '';
  }
}

/** react-markdown 组件映射:图片灯箱/失败降级、视频与裸图 URL 行内渲染、链接新窗口、代码块深色+复制 */
function buildMdComponents(onImgClick: (url: string) => void): Components {
  return {
    a: ({ href, children }) => (
      <a href={href} target="_blank" rel="noreferrer" style={{ color: 'var(--color-primary)', textDecoration: 'underline' }}>
        {children}
      </a>
    ),
    img: ({ src, alt }) => (
      <ImgWithFallback
        src={assetUrl(src ?? '')}
        alt={alt ?? ''}
        onClick={() => onImgClick(src ?? '')}
        style={{ maxWidth: '100%', maxHeight: 220, borderRadius: 10, margin: '6px 0', display: 'block' }}
      />
    ),
    // 行内代码:浅底小圆角;块级代码(带 language- class)由 pre 统一包深色容器
    code: ({ className, children }) => {
      if (className?.includes('language-')) {
        return <code className={className}>{children}</code>;
      }
      return (
        <code style={{ background: 'var(--color-surface-card)', padding: '2px 6px', borderRadius: 6, fontSize: '0.92em', color: 'var(--color-ink)' }}>
          {children}
        </code>
      );
    },
    // 块级代码:深墨色容器 + 语言标签 + 复制按钮
    pre: ({ children }) => {
      const child = isValidElement<{ className?: string; children?: React.ReactNode }>(children) ? children : null;
      const lang = (child?.props.className?.match(/language-(\S+)/) ?? [])[1] ?? '';
      const codeText = child ? String(child.props.children ?? '').replace(/\n$/, '') : '';
      return (
        <div className="md-code-block">
          <div className="md-code-bar">
            <span className="md-code-lang">{lang || 'code'}</span>
            <CopyButton text={codeText} label="复制" />
          </div>
          <pre>{children}</pre>
        </div>
      );
    },
    // 整行裸视频 URL / 裸图 URL:直接渲染媒体(不走链接)
    p: ({ children }) => {
      if (typeof children === 'string') {
        const t = children.trim();
        const videoMatch = t.match(/(?:https?:\/\/|\/api\/files\/videos\/)\S+\.(mp4|webm)(\?\S*)?/i);
        if (videoMatch) {
          return (
            <video src={assetUrl(videoMatch[0])} controls
              style={{ maxWidth: '100%', maxHeight: 240, borderRadius: 10, margin: '6px 0', display: 'block' }} />
          );
        }
        const imgMatch = t.match(/^(?:https?:\/\/\S+\.(png|jpe?g|gif|webp)(\?\S*)?|\/\S+\.(png|jpe?g|gif|webp)(\?\S*)?|data:image\/[a-z+]+;base64,\S+)$/i);
        if (imgMatch) {
          const raw = imgMatch[0];
          return (
            <ImgWithFallback
              src={raw.startsWith('data:') || raw.startsWith('http') ? raw : assetUrl(raw)}
              alt=""
              onClick={() => onImgClick(raw)}
              style={{ maxWidth: '100%', maxHeight: 220, borderRadius: 10, margin: '6px 0', display: 'block' }}
            />
          );
        }
      }
      return <p>{children}</p>;
    },
  };
}

export function MessageBubble({ message, streaming }: { message: AgentMessage; streaming?: boolean }) {
  const { role, content, createdAt } = message;
  const isUser = role === 'user';
  const ref = useRef<HTMLDivElement>(null);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);

  useGSAP(() => {
    if (!ref.current) return;
    gsap.fromTo(ref.current, { y: 6, opacity: 0 }, {
      y: 0, opacity: 1, duration: 0.2, ease: 'power2.out',
      onComplete: () => gsap.set(ref.current!, { clearProps: 'transform' }),
    });
  }, { scope: ref });

  // 消息操作行:时间戳 + 复制(hover 浮现,由 .chat-actions 控制)
  const actions = (
    <div className="chat-actions">
      <time>{formatTime(createdAt)}</time>
      <CopyButton text={content ?? ''} label="复制" />
    </div>
  );

  if (isUser) {
    return (
      <div ref={ref} className="chat-user group">
        <div className="flex flex-col items-end gap-1">
          <div className="chat-user-bubble">
            {content || <span style={{ opacity: 0.5 }}>…</span>}
          </div>
          {actions}
        </div>
      </div>
    );
  }

  return (
    <div ref={ref} className="chat-assistant group">
      {/* 企业级身份行:月亮头像 + 名字 + 时间(同排,时间随操作行 hover 浮现) */}
      <div className="chat-assistant-head">
        <span className="chat-assistant-avatar"><Moon size={15} strokeWidth={2} /></span>
        <span className="chat-assistant-name">Moon 智能体</span>
      </div>
      <div className="chat-assistant-body md-body">
        {content ? (
          <ReactMarkdown remarkPlugins={[remarkGfm]} components={buildMdComponents((url) => setPreviewUrl(url))}>
            {content}
          </ReactMarkdown>
        ) : (
          <span style={{ opacity: 0.35 }}>…</span>
        )}
        {streaming && content && (
          <span className="inline-block align-text-bottom ml-0.5" style={{
            width: 2, height: 18, background: 'var(--color-primary)',
            animation: 'blink 1s steps(1) infinite',
          }} />
        )}
        <style>{`@keyframes blink { 0%,100%{opacity:1} 50%{opacity:0} }`}</style>
      </div>
      {actions}
      <ImagePreviewModal url={previewUrl} onClose={() => setPreviewUrl(null)} />
    </div>
  );
}
