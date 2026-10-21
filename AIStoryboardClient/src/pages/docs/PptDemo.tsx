import { useState } from 'react';
import { ChevronLeft, ChevronRight, Presentation } from 'lucide-react';

type Slide =
  | { kind: 'cover'; eyebrow: string; title: string; sub: string }
  | { kind: 'agenda'; eyebrow: string; title: string; items: string[] }
  | { kind: 'section'; eyebrow: string; index: string; title: string }
  | { kind: 'bullets'; eyebrow: string; title: string; sub: string; items: string[] }
  | { kind: 'data'; eyebrow: string; title: string; sub: string; bars: { label: string; value: number }[] }
  | { kind: 'closing'; eyebrow: string; title: string; sub: string };

const SLIDES: Slide[] = [
  { kind: 'cover', eyebrow: 'MOON · 2026 产品发布', title: '把想法，做成片', sub: '一个对话框，图 / 视频 / 长片 / 画布 / PPT' },
  { kind: 'agenda', eyebrow: 'AGENDA', title: '今天讲三件事', items: ['01  一句话能做出什么', '02  长片与画布怎么落地', '03  交付与协作方式'] },
  { kind: 'section', eyebrow: 'PART 01', index: '01', title: '能力：五种交付，同一个入口' },
  { kind: 'bullets', eyebrow: 'CAPABILITY', title: '一个对话框，五种交付', sub: '不用记工具名，说目标就行', items: ['图片：文生图 / 图生图 / 批量变体', '视频：文生 / 图生 / 首尾帧 / 参考视频', '长片：分镜 → 逐镜 → 合并', '画布：节点 + 连线 + 分组执行', '文档与 PPT：落成可下载文件'] },
  { kind: 'data', eyebrow: 'LATENCY', title: '一次生成要等多久', sub: '图为 30–60 秒；视频 2–5 分钟；长片按镜累加', bars: [{ label: '图片', value: 28 }, { label: '单段视频', value: 62 }, { label: '8 镜长片', value: 92 }, { label: 'PPT 12 页', value: 48 }] },
  { kind: 'closing', eyebrow: 'NEXT', title: '现在，说出你的第一个目标', sub: '从一张海报开始，也可以直接要一条 40 秒短片。' },
];

export function PptDeck() {
  const [index, setIndex] = useState(0);
  const slide = SLIDES[index];
  const go = (dir: number) => setIndex((i) => (i + dir + SLIDES.length) % SLIDES.length);

  return (
    <div className="mx-stack-8">
      <div className={`mx-slide${slide.kind === 'cover' || slide.kind === 'closing' || slide.kind === 'section' ? ' mx-slide--dark' : ''}`}>
        <div className="mx-slide__eyebrow">{slide.eyebrow}</div>

        {slide.kind === 'cover' && (
          <>
            <h4 className="mx-slide__title" style={{ fontSize: 28, marginTop: 'auto' }}>{slide.title}</h4>
            <div className="mx-slide__sub">{slide.sub}</div>
            <div className="mx-slide__footer"><span>MOON AGENT</span><span>01 / 06</span></div>
          </>
        )}

        {slide.kind === 'agenda' && (
          <>
            <h4 className="mx-slide__title">{slide.title}</h4>
            <div className="mx-slide__bullets" style={{ marginTop: 14, gap: 8 }}>
              {slide.items.map((item) => (
                <span key={item} style={{ font: '11px/1.6 var(--font-body)' }}>
                  <i style={{ width: 0, height: 0, borderRadius: 0, background: 'none' }} />
                  {item}
                </span>
              ))}
            </div>
            <div className="mx-slide__footer"><span>AGENDA</span><span>02 / 06</span></div>
          </>
        )}

        {slide.kind === 'section' && (
          <>
            <div style={{ font: '500 64px/1 var(--font-display)', color: 'var(--color-primary)', marginTop: 'auto', letterSpacing: '-0.04em' }}>{slide.index}</div>
            <h4 className="mx-slide__title" style={{ fontSize: 22, marginTop: 10 }}>{slide.title}</h4>
            <div className="mx-slide__footer"><span>PART 01</span><span>03 / 06</span></div>
          </>
        )}

        {slide.kind === 'bullets' && (
          <>
            <h4 className="mx-slide__title">{slide.title}</h4>
            <div className="mx-slide__sub">{slide.sub}</div>
            <div className="mx-slide__bullets">
              {slide.items.map((item) => (
                <span key={item}><i />{item}</span>
              ))}
            </div>
            <div className="mx-slide__footer"><span>CAPABILITY</span><span>04 / 06</span></div>
          </>
        )}

        {slide.kind === 'data' && (
          <>
            <h4 className="mx-slide__title">{slide.title}</h4>
            <div className="mx-slide__sub">{slide.sub}</div>
            <div className="mx-slide__bars">
              {slide.bars.map((bar) => (
                <b key={bar.label} style={{ height: `${bar.value}%` }} title={`${bar.label} ${bar.value}%`} />
              ))}
            </div>
            <div style={{ display: 'flex', gap: 7, marginTop: 6 }}>
              {slide.bars.map((bar) => (
                <span key={bar.label} style={{ flex: 1, textAlign: 'center', font: '8px/1.3 var(--font-code)', color: 'var(--color-muted)' }}>{bar.label}</span>
              ))}
            </div>
            <div className="mx-slide__footer"><span>LATENCY</span><span>05 / 06</span></div>
          </>
        )}

        {slide.kind === 'closing' && (
          <>
            <h4 className="mx-slide__title" style={{ fontSize: 24, marginTop: 'auto' }}>{slide.title}</h4>
            <div className="mx-slide__sub">{slide.sub}</div>
            <div className="mx-slide__footer"><span>THANK YOU</span><span>06 / 06</span></div>
          </>
        )}
      </div>

      <div className="mx-slide-nav">
        <button type="button" className="mx-btn" onClick={() => go(-1)} aria-label="上一页">
          <ChevronLeft size={13} strokeWidth={2} />
        </button>
        <button type="button" className="mx-btn" onClick={() => go(1)} aria-label="下一页">
          <ChevronRight size={13} strokeWidth={2} />
        </button>
        <div className="mx-thumbs">
          {SLIDES.map((s, i) => (
            <button
              key={s.kind + i}
              type="button"
              className={`mx-thumb${i === index ? ' is-active' : ''}`}
              onClick={() => setIndex(i)}
              aria-label={`第 ${i + 1} 页`}
            >
              <i /><i /><i />
            </button>
          ))}
        </div>
        <span className="mx-mono" style={{ color: 'var(--color-muted)' }}>
          {String(index + 1).padStart(2, '0')} / {String(SLIDES.length).padStart(2, '0')}
        </span>
      </div>

      <div className="mx-row-8">
        <Presentation size={12} strokeWidth={1.8} color="var(--color-primary)" />
        <span className="mx-note" style={{ fontSize: 10.5 }}>
          交付的是可编辑 PPTX（不是图片贴出来的假幻灯片），另附每页 PNG 预览。
        </span>
      </div>
    </div>
  );
}
