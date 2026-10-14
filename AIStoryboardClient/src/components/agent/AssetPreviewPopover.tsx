import { createPortal } from 'react-dom';
import { FileText } from 'lucide-react';
import { assetUrl } from '../../config';
import './AssetPreviewPopover.css';

export type AssetPreviewKind = 'image' | 'video' | 'document';

export interface AssetPreviewTarget {
  url: string;
  kind: AssetPreviewKind;
  /** 被悬浮格子的视口矩形：浮层据此对齐，并夹在可视范围内。 */
  rect: { top: number; bottom: number; left: number };
}

const PREVIEW_WIDTH = 260;
const PREVIEW_MAX_HEIGHT = 300;
const GAP = 12;
const VIEWPORT_PADDING = 12;

/** 浮层贴着侧边栏左侧展开：横向位置由被悬浮格子决定，纵向跟随并夹进视口。 */
function resolvePosition(rect: AssetPreviewTarget['rect']) {
  const left = Math.max(VIEWPORT_PADDING, rect.left - PREVIEW_WIDTH - GAP);
  const centre = (rect.top + rect.bottom) / 2;
  const top = Math.max(
    VIEWPORT_PADDING,
    Math.min(centre - PREVIEW_MAX_HEIGHT / 2, window.innerHeight - PREVIEW_MAX_HEIGHT - VIEWPORT_PADDING),
  );
  return { left, top };
}

/**
 * 素材悬浮预览：鼠标停在会话资产格子上时，在侧边栏左侧浮出大图。
 * 浮层只负责看，不接任何指针事件 —— 否则它会挡住自己触发的 hover，让预览疯狂闪烁。
 */
export function AssetPreviewPopover({ target }: { target: AssetPreviewTarget | null }) {
  if (!target) return null;
  const { left, top } = resolvePosition(target.rect);

  return createPortal(
    <div
      className={`asset-preview asset-preview--${target.kind}`}
      style={{ left, top, width: PREVIEW_WIDTH, maxHeight: PREVIEW_MAX_HEIGHT }}
      aria-hidden="true"
    >
      {target.kind === 'image' && (
        <img src={assetUrl(target.url)} alt="" className="asset-preview__media" />
      )}
      {target.kind === 'video' && (
        <video
          src={assetUrl(target.url)}
          className="asset-preview__media"
          autoPlay
          muted
          loop
          playsInline
          preload="metadata"
        />
      )}
      {target.kind === 'document' && (
        <span className="asset-preview__document">
          <FileText size={22} strokeWidth={1.6} />
          <span>文档</span>
        </span>
      )}
    </div>,
    document.body,
  );
}
