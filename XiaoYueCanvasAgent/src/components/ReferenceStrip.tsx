import { memo } from 'react';
import { useAssetPreviewUrl } from '../hooks/useAssetPreviewUrl';
import type { ReferenceImageEntry } from '../canvas/referenceImagePool';

/**
 * 参考图条（对齐参考工程 ReferenceStrip，但配色走当前深墨绿+acid 主题）。
 * 显示上游参考图缩略图条：@N 角标编号、点击插入 @图N、已引用高亮描边 + ✓、× 删除。
 * 只显示「上游」来源（asset 库来源后续按需扩展）。
 */
export interface ReferenceStripProps {
  entries: ReferenceImageEntry[];
  /** 当前 prompt 里已引用的编号集合 */
  referencedNumbers: Set<number>;
  onInsertRef: (number: number) => void;
  onDeleteRef?: (entry: ReferenceImageEntry) => void;
}

interface StripItemProps {
  entry: ReferenceImageEntry;
  isReferenced: boolean;
  onInsert: (number: number) => void;
  onDelete?: (entry: ReferenceImageEntry) => void;
}

const StripItem = memo(function StripItem({ entry, isReferenced, onInsert, onDelete }: StripItemProps) {
  const url = useAssetPreviewUrl(entry.assetId);
  const label = entry.sourceNodeName || `图${entry.number}`;
  return (
    <div
      className="reference-strip__item nodrag"
      title={`${label} · 图${entry.number}（点击插入 @图${entry.number}）`}
      onClick={() => onInsert(entry.number)}
    >
      <div className={`reference-strip__thumb ${isReferenced ? 'is-referenced' : ''}`}>
        {url ? <img src={url} alt={`图${entry.number}`} loading="lazy" decoding="async" draggable={false} /> : <span className="reference-strip__thumb-empty" />}
        <span className="reference-strip__num">@{entry.number}</span>
        {isReferenced && <span className="reference-strip__check">✓</span>}
        {onDelete && (
          <button
            className="reference-strip__del nodrag"
            aria-label={`删除参考图${entry.number}`}
            onClick={(e) => { e.stopPropagation(); onDelete(entry); }}
          >×</button>
        )}
      </div>
      <span className={`reference-strip__label ${isReferenced ? 'is-referenced' : ''}`}>{label}</span>
    </div>
  );
});

export const ReferenceStrip = memo(function ReferenceStrip({
  entries,
  referencedNumbers,
  onInsertRef,
  onDeleteRef,
}: ReferenceStripProps) {
  if (!entries.length) return null;
  return (
    <div className="reference-strip nodrag" role="list" aria-label="参考图">
      <span className="reference-strip__hint">点击图片加入提示词</span>
      {entries.map((entry) => (
        <StripItem
          key={`ref-${entry.number}`}
          entry={entry}
          isReferenced={referencedNumbers.has(entry.number)}
          onInsert={onInsertRef}
          onDelete={onDeleteRef}
        />
      ))}
    </div>
  );
});
