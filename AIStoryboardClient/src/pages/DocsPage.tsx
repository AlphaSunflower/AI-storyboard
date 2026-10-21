import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  ArrowRight, Clapperboard, FileText, Frame, Image as ImageIcon, Layers, Presentation, Sparkles, Video,
} from 'lucide-react';
import SpecularButton from '../components/SpecularButton';
import MoonLogo from '../components/agent/MoonLogo';
import LineSidebar from '../components/LineSidebar';
import Magnet from '../components/Magnet';
import AmbientGlow from '../components/AmbientGlow';
import '../styles/docs.css';
import '../styles/docs-moon.css';
import { NAV_ITEMS } from './docs/data';
import {
  CanvasSection, DocumentSection, FaqSection, ImageSection, LongVideoSection, MoonSection,
  PptSection, PromptSection, RecipeSection, StartSection, VideoSection, WorkflowSection,
} from './docs/sections';

/** Hero 右侧：一扇主窗（真实对话结构）+ 两张浮动回执，对应三种交付。 */
function HeroStack() {
  return (
    <div className="mx-hero-mock">
      <div className="mx-win">
        <div className="mx-win__bar">
          <span className="mx-win__dots" aria-hidden><i /><i /><i /></span>
          <span className="mx-win__title">Moon · 新对话</span>
          <span className="mx-win__meta">方案批准</span>
        </div>
        <div className="mx-hero-mock__body">
          <div className="mx-msg mx-msg--user" style={{ fontSize: 11.5, padding: '7px 10px' }}>
            把这段 40 秒旁白做成咖啡店短片
          </div>
          <div className="mx-msg mx-msg--ai" style={{ fontSize: 11.5, padding: '7px 10px' }}>
            40 秒超过单段 15 秒上限。先出 4 个镜头大纲，你确认后逐镜生成、再合并。
          </div>
          <div className="mx-plan" style={{ padding: '10px 12px' }}>
            <div className="mx-plan__title" style={{ fontSize: 12, marginBottom: 6 }}>
              镜头大纲 · 4 镜 / 40 秒
            </div>
            {[
              ['01', '雨夜招牌亮起', '10s'],
              ['02', '推门进店，暖光扑面', '10s'],
              ['03', '吧台手冲，蒸汽升起', '10s'],
              ['04', '窗边坐下，举杯', '10s'],
            ].map(([n, t, d]) => (
              <div className="mx-plan__row" key={n} style={{ fontSize: 10.5, padding: '3px 0' }}>
                <span>{n} · {t}</span>
                <b>{d}</b>
              </div>
            ))}
            <div className="mx-plan__actions" style={{ marginTop: 8 }}>
              <span className="mx-btn mx-btn--primary" style={{ padding: '4px 10px', fontSize: 10.5 }}>大纲可以，继续</span>
              <span className="mx-btn mx-btn--ghost" style={{ padding: '4px 10px', fontSize: 10.5 }}>改一下</span>
            </div>
          </div>
          <div className="mx-hero-mock__result">
            <span className="mx-hero-mock__thumbs" aria-hidden><i /><i /><i /><i /></span>
            <span style={{ fontSize: 10.5, color: 'var(--color-muted)' }}>
              <strong style={{ color: 'var(--color-ink)' }}>已合并 00:40</strong> · 1080P · 4 镜
            </span>
          </div>
        </div>
      </div>

      <div className="mx-hero-toast mx-hero-toast--canvas">
        <span className="mx-hero-toast__icon"><Frame size={12} strokeWidth={1.9} /></span>
        <span><strong>画布</strong> 6 个节点已连好</span>
      </div>
      <div className="mx-hero-toast mx-hero-toast--ppt">
        <span className="mx-hero-toast__icon"><Presentation size={12} strokeWidth={1.9} /></span>
        <span><strong>PPT</strong> 12 页已交付</span>
      </div>
    </div>
  );
}

export function DocsPage() {
  const navigate = useNavigate();
  const rootRef = useRef<HTMLDivElement>(null);
  const [activeId, setActiveId] = useState<string>(NAV_ITEMS[0].id);
  const embedded = new URLSearchParams(window.location.search).get('embedded') === '1';
  const chatPath = embedded ? '/chat?embedded=1' : '/chat';

  // 阅读进度（顶部 2px 细线）
  useEffect(() => {
    let frame = 0;
    const update = () => {
      cancelAnimationFrame(frame);
      frame = requestAnimationFrame(() => {
        const doc = document.documentElement;
        const max = doc.scrollHeight - doc.clientHeight;
        const pct = max > 0 ? (doc.scrollTop / max) * 100 : 0;
        doc.style.setProperty('--mx-progress', `${pct.toFixed(2)}%`);
      });
    };
    update();
    window.addEventListener('scroll', update, { passive: true });
    window.addEventListener('resize', update);
    return () => {
      window.removeEventListener('scroll', update);
      window.removeEventListener('resize', update);
      cancelAnimationFrame(frame);
    };
  }, []);

  // 章节高亮（左侧 LineSidebar 与手机端胶囊共用）
  useEffect(() => {
    const observer = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          if (entry.isIntersecting) setActiveId(entry.target.id);
        });
      },
      { rootMargin: '-38% 0px -55% 0px', threshold: 0 },
    );
    NAV_ITEMS.forEach((item) => {
      const el = document.getElementById(item.id);
      if (el) observer.observe(el);
    });
    return () => observer.disconnect();
  }, []);

  const activeIndex = Math.max(0, NAV_ITEMS.findIndex((n) => n.id === activeId));

  return (
    <div ref={rootRef} className="docs-page">
      <div className="mx-progress" aria-hidden />
      <AmbientGlow />

      <div className="docs-sidebar">
        <LineSidebar
          key={activeId}
          items={NAV_ITEMS.map((s) => s.label)}
          accentColor="#cc785c"
          textColor="#3d3d3a"
          markerColor="#8e8b82"
          showIndex
          showMarker
          proximityRadius={100}
          maxShift={22}
          falloff="smooth"
          markerLength={44}
          markerGap={0}
          tickScale={0.5}
          scaleTick
          itemGap={16}
          fontSize={0.9}
          smoothing={100}
          defaultActive={activeIndex}
          onItemClick={(idx) => {
            const id = NAV_ITEMS[idx]?.id;
            if (id) document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
          }}
        />
      </div>

      <nav className="docs-nav">
        <a className="docs-nav__brand" href={chatPath} style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <MoonLogo variant="mark" size={22} tone="brand" />
          <span style={{ color: 'var(--color-ink)' }}>Moon</span>
          <span className="mx-nav-brand-sub" style={{ color: 'var(--color-primary)' }}>智能体指南</span>
        </a>
        <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
          <a
            href="#recipes"
            className="mx-mono mx-nav-meta"
            style={{ color: 'var(--color-muted)', textDecoration: 'none', letterSpacing: '.08em' }}
            onClick={(e) => {
              e.preventDefault();
              document.getElementById('recipes')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
            }}
          >
            配方速查
          </a>
          <Magnet padding={60} magnetStrength={25}>
            <SpecularButton
              size="sm"
              radius={10}
              tintOpacity={0}
              textColor="#cc785c"
              lineColor="#cc785c"
              baseColor="#cc785c"
              intensity={0.9}
              thickness={1.2}
              onClick={() => navigate(chatPath)}
            >
              进入工作台
            </SpecularButton>
          </Magnet>
        </div>
      </nav>

      <div className="mx-mobile-nav" role="navigation" aria-label="章节导航">
        {NAV_ITEMS.map((item) => (
          <button
            key={item.id}
            type="button"
            className={activeId === item.id ? 'is-active' : ''}
            onClick={() => document.getElementById(item.id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })}
          >
            {item.label}
          </button>
        ))}
      </div>

      <header className="mx-hero">
        <div className="docs-container">
          <div className="mx-hero__inner">
            <div>
              <div className="mx-hero__eyebrow">MOON 智能体 · 图文使用指南</div>
              <h1 className="mx-hero__title">
                <span>一句话，</span>
                <span>从一张图<em>到一条片</em>。</span>
              </h1>
              <p className="mx-hero__sub">
                Moon 是这套系统里唯一的智能体。图片、视频、长片、画布、文档与 PPT，
                你用一句话说目标，它自己挑技能、调模型、写文件、搭画布；关键时刻把决定权交回给你。
                这一页用可点的界面演示，带你从第一张图走到一条完整工作流。
              </p>
              <div className="mx-hero__cta">
                <Magnet padding={70} magnetStrength={25}>
                  <SpecularButton
                    size="lg"
                    radius={12}
                    tint="#cc785c"
                    tintOpacity={1}
                    textColor="#ffffff"
                    lineColor="#ffffff"
                    baseColor="#ffffff"
                    intensity={1}
                    thickness={1.2}
                    onClick={() => navigate(chatPath)}
                  >
                    进入 Moon 工作台
                  </SpecularButton>
                </Magnet>
                <button type="button" className="mx-btn" style={{ padding: '12px 18px', fontSize: 13 }} onClick={() => navigate('/canvas/')}>
                  <Frame size={14} strokeWidth={1.9} />
                  打开画布
                  <ArrowRight size={13} strokeWidth={2} />
                </button>
              </div>
              <div className="mx-hero__hint">本页演示均为本地交互，不调用模型、不消耗额度</div>
            </div>
            <HeroStack />
          </div>
        </div>
      </header>

      <MoonSection />
      <StartSection />
      <ImageSection />
      <VideoSection />
      <LongVideoSection />
      <CanvasSection />
      <WorkflowSection />
      <DocumentSection />
      <PptSection />
      <PromptSection />
      <RecipeSection />
      <FaqSection />

      <section className="docs-section" style={{ paddingTop: 0 }}>
        <div className="docs-container" data-reveal>
          <div
            style={{
              padding: 'clamp(24px, 4vw, 40px)',
              borderRadius: 20,
              background: 'var(--color-surface-dark)',
              color: 'var(--color-on-dark)',
              display: 'flex',
              flexWrap: 'wrap',
              gap: 20,
              alignItems: 'center',
              justifyContent: 'space-between',
            }}
          >
            <div style={{ maxWidth: 520 }}>
              <div className="mx-kicker" style={{ color: '#e9a184', marginBottom: 10 }}>下一步</div>
              <div style={{ font: '500 clamp(24px, 3vw, 34px)/1.25 var(--font-display)', letterSpacing: '-.02em', marginBottom: 10 }}>
                别先学工具，先说一个目标。
              </div>
              <div style={{ font: 'var(--text-body-md)', color: 'var(--color-on-dark-soft)', lineHeight: 1.75 }}>
                从「做一张海报」开始，或者直接要一条 15 秒视频。第一轮跑完，你就知道整套系统怎么转。
              </div>
            </div>
            <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
              <button type="button" className="mx-btn mx-btn--primary" style={{ padding: '11px 16px' }} onClick={() => navigate(chatPath)}>
                <Sparkles size={14} strokeWidth={1.9} />
                进入工作台
              </button>
              <button
                type="button"
                className="mx-btn"
                style={{ padding: '11px 16px', background: 'transparent', borderColor: 'rgba(250,249,245,.24)', color: 'var(--color-on-dark)' }}
                onClick={() => document.getElementById('long')?.scrollIntoView({ behavior: 'smooth', block: 'start' })}
              >
                <Clapperboard size={14} strokeWidth={1.9} />
                先看长视频
              </button>
            </div>
          </div>
        </div>
      </section>

      <footer className="docs-footer">
        <div className="docs-footer__inner">
          <div className="docs-footer__brand">
            <span style={{ fontSize: 20, fontWeight: 650, letterSpacing: '-.4px', color: 'var(--color-on-dark)', display: 'inline-flex', alignItems: 'center', gap: 8 }}>
              <MoonLogo variant="mark" size={20} tone="brand" />
              Moon <span style={{ color: 'var(--color-primary)' }}>智能体</span>
            </span>
          </div>
          <div className="docs-footer__text" style={{ maxWidth: 520, margin: '0 auto' }}>
            图片、视频、长片、画布、文档、PPT——同一个入口，关键节点由你确认。
          </div>
          <div style={{ display: 'flex', gap: 18, justifyContent: 'center', marginTop: 18, flexWrap: 'wrap' }}>
            {[
              { label: '生成图片', id: 'image', icon: <ImageIcon size={12} strokeWidth={1.9} /> },
              { label: '生成长视频', id: 'long', icon: <Video size={12} strokeWidth={1.9} /> },
              { label: '画布与节点', id: 'canvas', icon: <Layers size={12} strokeWidth={1.9} /> },
              { label: '写文档', id: 'document', icon: <FileText size={12} strokeWidth={1.9} /> },
              { label: '做 PPT', id: 'ppt', icon: <Presentation size={12} strokeWidth={1.9} /> },
            ].map((l) => (
              <a
                key={l.id}
                href={`#${l.id}`}
                onClick={(e) => {
                  e.preventDefault();
                  document.getElementById(l.id)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
                }}
                style={{ display: 'inline-flex', alignItems: 'center', gap: 5, color: 'var(--color-on-dark-soft)', font: 'var(--text-caption)', textDecoration: 'none' }}
              >
                {l.icon}
                {l.label}
              </a>
            ))}
          </div>
        </div>
      </footer>
    </div>
  );
}
