import type { RenderIntentKind, ToolRenderIntent } from '../../api/agent';
import { isKnownRenderKind, renderIntentTitle } from '../../lib/renderIntent';

/**
 * 渲染意图卡片：把 `render: {kind, id, status, items[], version}` 渲染成结构化块。
 *
 * 约束：
 * - 纯函数式、无副作用，因此直播与重放两条路径结果一致；
 * - 未知 `kind`（`fallback`）不报错，退化成「键值 + JSON」的通用展示；
 * - 只消费 `items`，缺失时展示空态，不猜内容。
 */

const CARD_STYLE: Record<string, string | number> = {
  width: '100%',
  maxWidth: '100%',
  marginTop: 8,
  marginBottom: 4,
  padding: '10px 12px',
  borderRadius: 12,
  border: '1px solid var(--color-hairline)',
  background: 'var(--color-canvas)',
  fontSize: 12,
  lineHeight: 1.6,
  color: 'var(--color-body)',
  overflowWrap: 'anywhere',
};

function asRecord(value: unknown): Record<string, unknown> | null {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

function textOf(value: unknown): string {
  if (value === null || value === undefined) return '';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  try {
    return JSON.stringify(value);
  } catch {
    return '';
  }
}

function itemTitle(item: unknown, index: number): string {
  // 字符串 item 本身就是文案；对象才去挑 title/label/name/path 字段。
  if (typeof item === 'string') return item;
  if (typeof item === 'number' || typeof item === 'boolean') return String(item);
  const record = asRecord(item);
  const title = textOf(record?.title ?? record?.label ?? record?.name ?? record?.path);
  if (title) return title;
  return textOf(item) || `第 ${index + 1} 项`;
}

function statusColor(status: string): string {
  const normalized = status.toLowerCase();
  if (normalized.includes('fail') || normalized.includes('error')) return 'var(--color-danger, #b4453a)';
  if (normalized.includes('run') || normalized.includes('progress') || normalized.includes('pending')) {
    return 'var(--color-warning, #a97a1f)';
  }
  if (normalized.includes('complete') || normalized.includes('done') || normalized.includes('success')) {
    return 'var(--color-success, #2f7d52)';
  }
  return 'var(--color-muted)';
}

function CardHeader({ intent, fallback }: { intent: ToolRenderIntent; fallback?: boolean }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 6 }}>
      <span style={{ fontSize: 11, letterSpacing: 0.6, color: 'var(--color-muted)', textTransform: 'uppercase' }}>
        {renderIntentTitle(intent)}
      </span>
      {intent.status && (
        <span style={{ fontSize: 11, color: statusColor(intent.status) }}>{intent.status}</span>
      )}
      {fallback && (
        <span style={{ fontSize: 11, color: 'var(--color-muted-soft)' }}>通用展示</span>
      )}
    </div>
  );
}

function TableBody({ items }: { items: unknown[] }) {
  const rows = items.map((item) => {
    const record = asRecord(item);
    if (record) return Object.entries(record).map(([, value]) => textOf(value));
    return [textOf(item)];
  });
  if (rows.length === 0) return <EmptyBody />;
  const width = Math.max(...rows.map((row) => row.length));
  return (
    <div style={{ overflowX: 'auto' }}>
      <table style={{ borderCollapse: 'collapse', width: '100%', fontSize: 12 }}>
        <tbody>
          {rows.map((row, rowIndex) => (
            <tr key={rowIndex}>
              {Array.from({ length: width }, (_, cellIndex) => (
                <td
                  key={cellIndex}
                  style={{
                    border: '1px solid var(--color-hairline)',
                    padding: '3px 6px',
                    verticalAlign: 'top',
                    whiteSpace: 'pre-wrap',
                  }}
                >
                  {row[cellIndex] ?? ''}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function ListBody({ items }: { items: unknown[] }) {
  if (items.length === 0) return <EmptyBody />;
  return (
    <ol style={{ margin: 0, paddingLeft: 18, display: 'flex', flexDirection: 'column', gap: 3 }}>
      {items.map((item, index) => (
        <li key={index}>{itemTitle(item, index)}</li>
      ))}
    </ol>
  );
}

function GalleryBody({ items }: { items: unknown[] }) {
  const urls = items
    .map((item) => {
      if (typeof item === 'string') return { url: item, label: item };
      const record = asRecord(item);
      return { url: textOf(record?.url ?? record?.src), label: itemTitle(item, 0) };
    })
    .filter((entry) => !!entry.url);
  if (urls.length === 0) return <EmptyBody />;
  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
      {urls.map((entry, index) => (
        <img
          key={`${entry.url}-${index}`}
          src={entry.url}
          alt={entry.label}
          loading="lazy"
          style={{
            width: 72, height: 72, objectFit: 'cover',
            borderRadius: 8, border: '1px solid var(--color-hairline)',
          }}
        />
      ))}
    </div>
  );
}

function ProgressBody({ items }: { items: unknown[] }) {
  if (items.length === 0) return <EmptyBody />;
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
      {items.map((item, index) => {
        const record = asRecord(item);
        const raw = Number(record?.percent ?? record?.progress ?? record?.value ?? 0);
        const percent = Number.isFinite(raw) ? Math.max(0, Math.min(100, raw)) : 0;
        return (
          <div key={index}>
            <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 11, color: 'var(--color-muted)' }}>
              <span>{itemTitle(item, index)}</span>
              <span>{percent}%</span>
            </div>
            <div style={{ height: 6, borderRadius: 999, background: 'var(--color-surface-soft)', overflow: 'hidden' }}>
              <div style={{ width: `${percent}%`, height: '100%', background: 'var(--color-primary)' }} />
            </div>
          </div>
        );
      })}
    </div>
  );
}

function DiffBody({ items }: { items: unknown[] }) {
  const lines = items.flatMap((item) => {
    const record = asRecord(item);
    const raw = textOf(record?.diff ?? record?.text ?? item);
    return raw ? raw.split('\n') : [];
  });
  if (lines.length === 0) return <EmptyBody />;
  return (
    <pre style={{ margin: 0, fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: 12, whiteSpace: 'pre-wrap' }}>
      {lines.map((line, index) => {
        const color = line.startsWith('+') && !line.startsWith('+++')
          ? 'var(--color-success, #2f7d52)'
          : line.startsWith('-') && !line.startsWith('---')
            ? 'var(--color-danger, #b4453a)'
            : 'inherit';
        return <div key={index} style={{ color }}>{line || ' '}</div>;
      })}
    </pre>
  );
}

function FormBody({ items }: { items: unknown[] }) {
  if (items.length === 0) return <EmptyBody />;
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
      {items.map((item, index) => {
        const record = asRecord(item);
        const value = textOf(record?.value ?? record?.default ?? '');
        return (
          <div key={index} style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
            <span style={{ fontSize: 11, color: 'var(--color-muted)' }}>{itemTitle(item, index)}</span>
            <input
              readOnly
              value={value}
              aria-label={itemTitle(item, index)}
              style={{
                width: '100%', boxSizing: 'border-box', padding: '4px 8px', fontSize: 12,
                border: '1px solid var(--color-hairline)', borderRadius: 8,
                background: 'var(--color-panel)', color: 'var(--color-ink)',
              }}
            />
          </div>
        );
      })}
    </div>
  );
}

function PreviewBody({ items, intent }: { items: unknown[]; intent: ToolRenderIntent }) {
  const first = items.length > 0 ? items[0] : intent;
  const record = asRecord(first);
  const url = textOf(record?.url ?? record?.src);
  const description = textOf(record?.description ?? record?.summary ?? record?.message);
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      <span style={{ color: 'var(--color-ink)' }}>{itemTitle(first, 0)}</span>
      {description && <span style={{ color: 'var(--color-muted)' }}>{description}</span>}
      {url && (
        <a href={url} target="_blank" rel="noreferrer" style={{ color: 'var(--color-primary)' }}>
          {url}
        </a>
      )}
    </div>
  );
}

function EmptyBody() {
  return <div style={{ color: 'var(--color-muted-soft)' }}>暂无可展示内容</div>;
}

/** 未知 kind：展示已知元信息 + JSON 兜底，不吞掉服务端数据。 */
function FallbackBody({ intent }: { intent: ToolRenderIntent }) {
  if (intent.items && intent.items.length > 0) return <ListBody items={intent.items} />;
  return (
    <pre style={{
      margin: 0, fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: 12,
      whiteSpace: 'pre-wrap', maxHeight: 200, overflowY: 'auto',
    }}>
      {textOf(intent)}
    </pre>
  );
}

export function RenderBlockCard({ intent, fallback = false }: {
  intent: ToolRenderIntent;
  fallback?: boolean;
}) {
  const items = Array.isArray(intent.items) ? intent.items : [];
  const known = isKnownRenderKind(intent.kind);
  const body = !known ? <FallbackBody intent={intent} /> : (() => {
    switch (intent.kind as RenderIntentKind) {
      case 'table': return <TableBody items={items} />;
      case 'list': return <ListBody items={items} />;
      case 'gallery': return <GalleryBody items={items} />;
      case 'progress': return <ProgressBody items={items} />;
      case 'diff': return <DiffBody items={items} />;
      case 'form': return <FormBody items={items} />;
      case 'preview': return <PreviewBody items={items} intent={intent} />;
      default: return <FallbackBody intent={intent} />;
    }
  })();

  return (
    <div style={CARD_STYLE} data-render-kind={intent.kind}>
      <CardHeader intent={intent} fallback={fallback || !known} />
      {body}
    </div>
  );
}
