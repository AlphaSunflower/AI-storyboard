import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Check, Copy, Info, Sparkles, TriangleAlert } from 'lucide-react';

/* ─────────────────────────────────────────────────────────────
 *  教学页共用小件：演示窗外壳、复制、提示条、步骤流、标签页。
 *  所有演示只读本地 state，不发请求、不落库。
 * ──────────────────────────────────────────────────────────── */

export function Win({
  title,
  meta,
  dark,
  plain,
  children,
  className,
}: {
  title: ReactNode;
  meta?: ReactNode;
  dark?: boolean;
  plain?: boolean;
  children: ReactNode;
  className?: string;
}) {
  return (
    <div className={`mx-win${dark ? ' mx-win--dark' : ''}${plain ? ' mx-win--plain' : ''}${className ? ` ${className}` : ''}`}>
      <div className="mx-win__bar">
        <span className="mx-win__dots" aria-hidden>
          <i />
          <i />
          <i />
        </span>
        <span className="mx-win__title">{title}</span>
        {meta ? <span className="mx-win__meta">{meta}</span> : null}
      </div>
      <div className="mx-win__body">{children}</div>
    </div>
  );
}

/** 复制按钮：用当前浏览器能力写入剪贴板，失败时静默（不打断阅读）。 */
export function CopyButton({ text, label = '复制' }: { text: string; label?: string }) {
  const [done, setDone] = useState(false);
  const timer = useRef<number | null>(null);

  useEffect(() => () => {
    if (timer.current !== null) window.clearTimeout(timer.current);
  }, []);

  const copy = () => {
    const finish = () => {
      setDone(true);
      if (timer.current !== null) window.clearTimeout(timer.current);
      timer.current = window.setTimeout(() => setDone(false), 1600);
    };
    if (navigator.clipboard?.writeText) {
      void navigator.clipboard.writeText(text).then(finish).catch(() => undefined);
    } else {
      finish();
    }
  };

  return (
    <button type="button" className="mx-copy" onClick={copy} aria-label={done ? '已复制' : label}>
      {done ? <Check size={11} strokeWidth={2.2} /> : <Copy size={11} strokeWidth={2} />}
      {done ? '已复制' : label}
    </button>
  );
}

/** 可复制的示例话术块。 */
export function PromptBox({ text, children, strong }: { text: string; children?: ReactNode; strong?: ReactNode }) {
  return (
    <div className="mx-prompt-box">
      <CopyButton text={text} />
      {strong ? <strong style={{ display: 'block', marginBottom: 4 }}>{strong}</strong> : null}
      {children ?? text}
    </div>
  );
}

export function Callout({
  tone = 'note',
  children,
}: {
  tone?: 'note' | 'tip' | 'warn';
  children: ReactNode;
}) {
  const Icon = tone === 'tip' ? Sparkles : tone === 'warn' ? TriangleAlert : Info;
  return (
    <div className={`mx-callout${tone === 'tip' ? ' mx-callout--tip' : tone === 'warn' ? ' mx-callout--warn' : ''}`}>
      <span className="mx-callout__icon" aria-hidden>
        <Icon size={13} strokeWidth={1.9} />
      </span>
      <div>{children}</div>
    </div>
  );
}

export function SectionHead({
  eyebrow,
  title,
  lead,
  aside,
}: {
  eyebrow: string;
  title: ReactNode;
  lead: ReactNode;
  aside?: ReactNode;
}) {
  return (
    <div className="docs-section__head" style={aside ? { display: 'flex', justifyContent: 'space-between', gap: 24, flexWrap: 'wrap', alignItems: 'flex-end' } : undefined}>
      <div>
        <div className="docs-section__eyebrow">{eyebrow}</div>
        <h2 className="docs-section__title">{title}</h2>
        <p className="docs-section__lead">{lead}</p>
      </div>
      {aside}
    </div>
  );
}

export type FlowItem = { title: string; desc: ReactNode; tag?: string };

export function Flow({ items, cols }: { items: FlowItem[]; cols?: 3 | 4 | 5 }) {
  return (
    <div className={`mx-flow${cols && cols !== 4 ? ` mx-flow--${cols}` : ''}`}>
      {items.map((item, i) => (
        <div className="mx-flow__item" key={item.title}>
          <div className="mx-flow__n">{String(i + 1).padStart(2, '0')}{item.tag ? ` · ${item.tag}` : ''}</div>
          <div className="mx-flow__t">{item.title}</div>
          <div className="mx-flow__d">{item.desc}</div>
        </div>
      ))}
    </div>
  );
}

export function Tabs<T extends string>({
  items,
  value,
  onChange,
}: {
  items: { id: T; label: string }[];
  value: T;
  onChange: (id: T) => void;
}) {
  return (
    <div className="mx-tabs" role="tablist" aria-label="演示模式">
      {items.map((item) => (
        <button
          key={item.id}
          type="button"
          role="tab"
          aria-selected={value === item.id}
          className={`mx-tab${value === item.id ? ' is-active' : ''}`}
          onClick={() => onChange(item.id)}
        >
          {item.label}
        </button>
      ))}
    </div>
  );
}

export function LeadList({ items }: { items: { title: ReactNode; desc: ReactNode }[] }) {
  return (
    <div className="mx-lead-list">
      {items.map((item, i) => (
        <div className="mx-lead-item" key={i}>
          <span className="mx-lead-item__n">{i + 1}</span>
          <div>
            <div className="mx-lead-item__t">{item.title}</div>
            <div className="mx-lead-item__d">{item.desc}</div>
          </div>
        </div>
      ))}
    </div>
  );
}

export function Kbd({ children }: { children: ReactNode }) {
  return <kbd className="mx-kbd">{children}</kbd>;
}

/** 带序号的标注点（绝对定位在演示窗容器内）。 */
export function Pin({ n, style }: { n: number; style: { left: string | number; top: string | number } }) {
  return (
    <span className="mx-pin" style={style} aria-hidden>
      {n}
    </span>
  );
}

export function FileCard({ icon, name, meta }: { icon: ReactNode; name: string; meta: string }) {
  return (
    <span className="mx-file">
      <span className="mx-file__icon" aria-hidden>{icon}</span>
      <span>
        <span className="mx-file__name">{name}</span>
        <span className="mx-file__meta" style={{ display: 'block' }}>{meta}</span>
      </span>
    </span>
  );
}

export function MiniLabel({ children }: { children: ReactNode }) {
  return <div className="mx-mini-label">{children}</div>;
}
