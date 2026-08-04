import { assetUrl } from '../../config';

/** 轻量渲染：**加粗**、换行、![]() 图片、[]() 链接、视频 URL 直接识别 */
function renderContent(content: string) {
  const lines = content.split('\n');
  return lines.map((line, i) => {
    // 图片 ![alt](url)
    const imgMatch = line.match(/!\[([^\]]*)\]\(([^)]+)\)/);
    if (imgMatch) {
      return (
        <img
          key={i}
          src={assetUrl(imgMatch[2])}
          alt={imgMatch[1]}
          style={{ maxWidth: '100%', maxHeight: 220, borderRadius: 8, margin: '4px 0', display: 'block' }}
        />
      );
    }
    // 视频 URL（.mp4/.webm 直接渲染）
    const videoMatch = line.match(/https?:\/\/\S+\.(mp4|webm)(\?\S*)?/i);
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
        <img
          key={i}
          src={raw.startsWith('data:') || raw.startsWith('http') ? raw : assetUrl(raw)}
          alt=""
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
  return (
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
        {content ? renderContent(content) : <span style={{ opacity: 0.6 }}>…</span>}
      </div>
    </div>
  );
}
