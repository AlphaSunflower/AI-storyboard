import { useState } from 'react';
import { createPortal } from 'react-dom';
import { Grid3X3, Images, LoaderCircle, Rotate3D, Sparkles, X } from 'lucide-react';
import { useGatewayModels } from '../hooks/useGatewayModels';
import {
  multiAngleRequest,
  selectMultiAngleModel,
  type MultiAngleMode,
  type MultiAngleRequest,
} from '../canvas/multiAngle';

export function MultiAngleDialog({
  sourceLabel,
  sourceName,
  onClose,
  onConfirm,
  present = true,
}: {
  sourceLabel: string;
  sourceName: string;
  onClose: () => void;
  onConfirm: (request: MultiAngleRequest) => Promise<void>;
  present?: boolean;
}) {
  const { models, loading, error } = useGatewayModels('image');
  const [mode, setMode] = useState<MultiAngleMode>('grid');
  const [submitting, setSubmitting] = useState(false);
  const [feedback, setFeedback] = useState<string | null>(null);
  const model = selectMultiAngleModel(models);
  const modelLabel = models.find((item) => item.value === model)?.label ?? model;

  const submit = async () => {
    if (!model || submitting) return;
    setFeedback(null);
    setSubmitting(true);
    try {
      await onConfirm(multiAngleRequest(mode, model));
    } catch (caught) {
      setFeedback(caught instanceof Error ? caught.message : '多角度提交失败');
    } finally {
      setSubmitting(false);
    }
  };

  return createPortal(
    <div
      className="multi-angle-dialog motion-fade"
      data-present={present}
      role="dialog"
      aria-modal="true"
      aria-label="多角度"
      onPointerDown={(event) => event.stopPropagation()}
      onClick={(event) => { if (event.target === event.currentTarget) onClose(); }}
    >
      <section>
        <header>
          <div>
            <span><Rotate3D size={15} /> 多角度</span>
            <strong>{sourceLabel}</strong>
            <small>{sourceName}</small>
          </div>
          <button type="button" aria-label="关闭多角度" onClick={onClose}><X size={18} /></button>
        </header>

        <div className="multi-angle-dialog__modes">
          <button
            type="button"
            className={mode === 'grid' ? 'is-active' : ''}
            aria-pressed={mode === 'grid'}
            onClick={() => setMode('grid')}
          >
            <Grid3X3 size={24} />
            <strong>宫格图</strong>
            <span>一张 2×2 四视角拼图</span>
          </button>
          <button
            type="button"
            className={mode === 'multi' ? 'is-active' : ''}
            aria-pressed={mode === 'multi'}
            onClick={() => setMode('multi')}
          >
            <Images size={24} />
            <strong>多图</strong>
            <span>4 张独立图片，AI 决定角度</span>
          </button>
        </div>

        <dl className="multi-angle-dialog__summary">
          <div><dt>模型</dt><dd>{loading ? '读取中…' : modelLabel ?? '不可用'}</dd></div>
          <div><dt>尺寸</dt><dd>2K</dd></div>
          <div><dt>数量</dt><dd>{mode === 'grid' ? '1 张' : '4 张'}</dd></div>
        </dl>

        {!loading && !model && <div className="multi-angle-dialog__notice" role="status">缺少支持多角度的 Seedream 模型</div>}
        {error && <div className="multi-angle-dialog__notice" role="status">{error}</div>}
        {feedback && <div className="multi-angle-dialog__notice is-error" role="status">{feedback}</div>}

        <footer>
          <button type="button" onClick={onClose}>取消</button>
          <button type="button" disabled={loading || !model || submitting} onClick={() => void submit()}>
            {submitting ? <LoaderCircle className="is-spinning" size={15} /> : <Sparkles size={15} />}
            {submitting ? '提交中…' : '生成多角度'}
          </button>
        </footer>
      </section>
    </div>,
    document.body,
  );
}
