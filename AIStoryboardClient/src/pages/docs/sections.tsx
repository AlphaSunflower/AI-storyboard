import type { ReactNode } from 'react';
import {
  ArrowRight, Boxes, Check, CircleCheck, Clapperboard, Clock, FileText, Frame,
  Image as ImageIcon, Layers, ListChecks, MousePointer2, Presentation, Sparkles, Video, Zap,
} from 'lucide-react';
import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from '../../components/ui/accordion';
import {
  Callout, CopyButton, Flow, Kbd, LeadList, PromptBox, SectionHead, Win,
} from './shared';
import { ChatDemoWindow } from './ChatDemo';
import { ImageStudio, PromptAnatomy } from './ImageDemo';
import { DurationScale, VideoStudio } from './VideoDemo';
import { TimelineComposer } from './LongVideoDemo';
import { CanvasGraph, NodeAnatomy } from './CanvasDemo';
import { WorkflowPatterns, WorkflowPrompt } from './WorkflowDemo';
import { DocumentPreview } from './DocumentDemo';
import { PptDeck } from './PptDemo';
import {
  AIGC_MATRIX, CAPABILITIES, CANVAS_SHORTCUTS, COMMANDS, FAQS, LONG_VIDEO_COMPARE, RECIPES, SHORTCUTS,
} from './data';

const jump = (id: string) => document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' });

const CAP_ICON: Record<string, ReactNode> = {
  long: <Clock size={17} strokeWidth={1.8} />,
  image: <ImageIcon size={17} strokeWidth={1.8} />,
  video: <Video size={17} strokeWidth={1.8} />,
  canvas: <Frame size={17} strokeWidth={1.8} />,
  workflow: <Layers size={17} strokeWidth={1.8} />,
  document: <FileText size={17} strokeWidth={1.8} />,
  ppt: <Presentation size={17} strokeWidth={1.8} />,
  prompt: <Sparkles size={17} strokeWidth={1.8} />,
  storyboard: <Clapperboard size={17} strokeWidth={1.8} />,
  assets: <Boxes size={17} strokeWidth={1.8} />,
  batch: <Zap size={17} strokeWidth={1.8} />,
};

/* ── 01 认识 Moon ───────────────────────────────────────── */
export function MoonSection() {
  return (
    <section className="docs-section docs-section--alt" id="moon">
      <div className="docs-container" data-reveal>
        <SectionHead
          eyebrow="认识 MOON"
          title="不是聊天框，是整套创作系统的执行入口"
          lead="你说目标，Moon 自己判断该用哪个技能：出图、出片、拆分镜、搭画布、写文件、做 PPT。需要你拍板的地方它会停下来问，其余时候直接把结果做出来。"
        />

        <div className="mx-cap-grid">
          {CAPABILITIES.map((cap, i) => (
            <button
              key={cap.id}
              type="button"
              className={`mx-cap${cap.wide ? ' mx-cap--wide' : ''}`}
              onClick={() => jump(cap.anchor)}
            >
              <span className="mx-cap__top">
                <span className="mx-cap__icon">{CAP_ICON[cap.id] ?? <Sparkles size={17} strokeWidth={1.8} />}</span>
                <span className="mx-cap__idx">{String(i + 1).padStart(2, '0')}</span>
              </span>
              <span className="mx-cap__title">{cap.title}</span>
              <span className="mx-cap__desc">{cap.desc}</span>
              <span className="mx-cap__say"><b>可以说：</b>{cap.say}</span>
            </button>
          ))}
        </div>

        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))', gap: 14, marginTop: 28 }}>
          {[
            {
              icon: <Sparkles size={15} strokeWidth={1.8} />,
              title: '对话直出',
              desc: '一句话就是一个任务：生图、生视频、改图、写文档。不需要先建项目、选模型。',
              tag: '最快',
            },
            {
              icon: <ListChecks size={15} strokeWidth={1.8} />,
              title: '分镜流水线',
              desc: '超过 15 秒的片子走这条：先给镜头大纲，你确认后再逐镜生成、合并。',
              tag: '长视频',
            },
            {
              icon: <Frame size={15} strokeWidth={1.8} />,
              title: '画布编排',
              desc: '把节点、连线、分组摆在一张画布上，按依赖批量跑；结构看得见，也能复用。',
              tag: '可复用',
            },
          ].map((item) => (
            <div key={item.title} style={{ padding: 18, border: '1px solid var(--color-hairline)', borderRadius: 14, background: '#fff' }}>
              <div className="mx-spread" style={{ marginBottom: 10 }}>
                <span style={{ width: 30, height: 30, borderRadius: 9, display: 'grid', placeItems: 'center', background: 'var(--color-primary-tint)', color: 'var(--color-primary)' }}>{item.icon}</span>
                <span className="mx-chip mx-chip--static" style={{ fontSize: 10 }}>{item.tag}</span>
              </div>
              <div style={{ font: 'var(--text-title-sm)', color: 'var(--color-ink)', marginBottom: 4 }}>{item.title}</div>
              <div className="mx-note" style={{ color: 'var(--color-muted)', fontSize: 12.5, lineHeight: 1.7 }}>{item.desc}</div>
            </div>
          ))}
        </div>

        <div style={{ marginTop: 22 }}>
          <Callout tone="tip">
            <strong>一句话原则：</strong>先说清「要什么、给谁看、多长、什么风格」，再补素材。Moon 不怕你说得多，怕你说得空。
          </Callout>
        </div>
      </div>
    </section>
  );
}

/* ── 02 60 秒上手 ───────────────────────────────────────── */
export function StartSection() {
  return (
    <section className="docs-section" id="start">
      <div className="docs-container" data-reveal>
        <SectionHead
          eyebrow="60 秒上手"
          title="第一轮对话，只要做对四件事"
          lead="不用先学工具。选一个目标、把素材拖进来、说清交付物、在确认卡上做决定——剩下的交给 Moon。下面这个演示可以点。"
        />

        <Flow
          items={[
            { title: '选项目 / 新对话', desc: '顶部切项目，左侧「+ 新建对话」。一个项目可以并行多个会话。', tag: '入口' },
            { title: '说目标 + 贴素材', desc: '一句话说清要什么；参考图直接拖进输入框，或在正文里 @ 引用。', tag: '输入' },
            { title: '在确认卡上拍板', desc: '分镜大纲、图片参数、写文件、画布执行都会先问你。改、确认、拒绝都行。', tag: 'HITL' },
            { title: '取结果 / 继续改', desc: '结果进「产出素材」，聊天里出文件卡；不满意就说「继续完善」。', tag: '交付' },
          ]}
        />

        <div style={{ marginTop: 24 }}>
          <div className="mx-mini-label">先选路，再动手</div>
          <div className="mx-table-wrap">
            <table className="mx-table">
              <thead><tr><th>你要的</th><th>走哪条</th><th>为什么</th></tr></thead>
              <tbody>
                <tr><td><b>一张图 / 改图 / 多方案</b></td><td>对话框直出</td><td>一句话一个任务，30–60 秒出图，不满意继续改</td></tr>
                <tr><td><b>15 秒以内的视频</b></td><td>对话框直出</td><td>单段生成，写清动作与运镜即可</td></tr>
                <tr><td><b>超过 15 秒 / 多镜头</b></td><td>分镜流水线</td><td>拆镜 → 逐镜 → 合并，才有连贯性与可控性</td></tr>
                <tr><td><b>批量、复用、交给别人</b></td><td>画布工作流</td><td>结构可视化，分组执行，能存下来反复跑</td></tr>
                <tr><td><b>文档 / 表格 / PPT</b></td><td>对话框 + 文件</td><td>直接产出可编辑文件，进资产库预览与下载</td></tr>
              </tbody>
            </table>
          </div>
        </div>

        <div className="mx-split mx-split--top" style={{ marginTop: 26 }}>
          <ChatDemoWindow />
          <div className="mx-stack-16">
            <div>
              <div className="mx-mini-label">输入框里其实有三种输入</div>
              <LeadList
                items={[
                  { title: <>一句话目标</>, desc: '「做一张雨夜咖啡馆的海报，竖版 9:16」——判断交给 Moon，不需要写工具名。' },
                  { title: <>素材</>, desc: '拖图 / 拖视频进来，或点回形针上传；它会先看图，再决定是改图、作参考还是当首帧。' },
                  { title: <>@ 引用</>, desc: <>输入 <Kbd>@</Kbd> 引用分镜、文档、素材或另一段对话，让它在已有内容上继续做。</> },
                ]}
              />
            </div>

            <div>
              <div className="mx-mini-label">决定它什么时候停下来问你</div>
              <div className="mx-stack-8">
                {[
                  ['方案批准', '先给方案，你批准后执行（生成类任务常用）'],
                  ['逐项批准', '每个工具调用都确认，最稳，也最慢'],
                  ['完全访问', '连续执行，不打断；适合已经验证过的流程'],
                ].map(([name, desc], i) => (
                  <div key={name} style={{ display: 'flex', gap: 10, alignItems: 'center', padding: '9px 12px', border: '1px solid var(--color-hairline)', borderRadius: 9, background: i === 0 ? 'var(--color-primary-tint)' : '#fff' }}>
                    <span style={{ width: 6, height: 6, borderRadius: '50%', background: i === 0 ? 'var(--color-primary)' : 'var(--color-hairline)' }} />
                    <span style={{ font: 'var(--text-caption)', color: 'var(--color-ink)', minWidth: 64 }}>{name}</span>
                    <span className="mx-note" style={{ fontSize: 10.5 }}>{desc}</span>
                  </div>
                ))}
              </div>
            </div>

            <Callout>
              生成中还想补一句话？直接发送即可：它会被插入当前回合，不打断正在跑的工具。想停下来，用停止按钮。
            </Callout>
          </div>
        </div>
      </div>
    </section>
  );
}

/* ── 03 生成图片 ────────────────────────────────────────── */
export function ImageSection() {
  return (
    <section className="docs-section docs-section--alt" id="image">
      <div className="docs-container" data-reveal>
        <SectionHead
          eyebrow="生成图片"
          title="四种出图方式，先选对再写字"
          lead="文生图、图生图、批量、继续完善——它们不是四个功能，而是同一条「出图 → 挑 → 改」链路的不同入口。先点一遍演示，再看提示词怎么拆。"
        />

        <Win title="图片生成 · 可点演示" meta="DEMO · 不消耗额度">
          <ImageStudio />
        </Win>

        <div className="mx-split mx-split--top" style={{ marginTop: 26 }}>
          <PromptAnatomy />
          <div className="mx-stack-16">
            <LeadList
              items={[
                { title: '文生图：没有参考图时，提示词决定一切', desc: '主体、动作、环境、光线、镜头、风格、约束，七段里至少写清前四段。' },
                { title: '图生图：原图是底，文字只写「改什么」', desc: '「色调调暖、背景换成清晨吧台、去掉桌面杂物」比重新描述整张图稳得多。' },
                { title: '批量：一次说清每个变体的差异', desc: '4 张图不要复制同一句提示词；让每张换角度、换卖点、换配色，才有得挑。' },
                { title: '继续完善：一次只改一处', desc: '「人物再近一点」「把背景虚化」——一次一个变量，才知道是哪句起了作用。' },
              ]}
            />
            <Callout tone="tip">
              <strong>跨图一致性靠资产卡：</strong>人物、道具、场景建卡（多图 + 文字约束），生成时自动注入。
              只靠文字描述，同一个人的发型和围裙每张都会变。
            </Callout>
            <div className="mx-row-8">
              {['30–60 秒/张', '尺寸随模型：1:1 / 竖版 / 横版', '结果自动进资产库'].map((t) => (
                <span key={t} className="mx-chip mx-chip--static" style={{ fontSize: 10 }}>{t}</span>
              ))}
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}

/* ── 04 生成视频 ────────────────────────────────────────── */
export function VideoSection() {
  return (
    <section className="docs-section" id="video">
      <div className="docs-container" data-reveal>
        <SectionHead
          eyebrow="生成视频"
          title="让静帧动起来：四种参考方式，一条 15 秒上限"
          lead="视频不是「图片 + 时间」，要写动作和运镜。参考素材怎么给，决定了结果稳不稳：一张图当首帧、两张当首尾帧、一段视频学运动。"
        />

        <Win title="视频生成 · 可点演示" meta="DEMO · 异步任务">
          <VideoStudio />
        </Win>

        <div className="mx-split mx-split--top" style={{ marginTop: 26 }}>
          <div className="mx-stack-16">
            <DurationScale />
            <Callout tone="warn">
              <strong>异步语义：</strong>工具一返回任务号，这一轮就结束了。不要等、不要催，也不要把「已受理」当成「已完成」；
              结果完成时会自动补进对话。
            </Callout>
          </div>
          <div className="mx-stack-12">
            <div className="mx-mini-label">四种参考方式怎么选</div>
            <div className="mx-table-wrap">
              <table className="mx-table">
                <thead>
                  <tr><th>方式</th><th>给什么</th><th>适合</th></tr>
                </thead>
                <tbody>
                  <tr><td><b style={{ whiteSpace: 'nowrap' }}>文生视频</b></td><td>什么都不给，只写提示词</td><td>氛围片、转场、没有固定主体的镜头</td></tr>
                  <tr><td><b style={{ whiteSpace: 'nowrap' }}>图生视频</b></td><td>1 张图，默认当首帧</td><td>已有分镜图，想让它动起来</td></tr>
                  <tr><td><b style={{ whiteSpace: 'nowrap' }}>首尾帧</b></td><td>2 张图，顺序 = 首帧 → 尾帧</td><td>镜头之间有明确起止画面</td></tr>
                  <tr><td><b style={{ whiteSpace: 'nowrap' }}>参考视频</b></td><td>1 段视频，学运镜与节奏</td><td>想复刻某种运动质感</td></tr>
                </tbody>
              </table>
            </div>
            <div className="mx-note">
              写视频提示词的顺序：<strong style={{ color: 'var(--color-ink)' }}>主体动作 → 运镜方向与速度 → 光线变化 → 时长/画幅</strong>。
              只有「静止描述」时，画面会像一张会呼吸的照片。
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}

/* ── 05 生成长视频 ──────────────────────────────────────── */
export function LongVideoSection() {
  return (
    <section className="docs-section docs-section--alt" id="long">
      <div className="docs-container" data-reveal>
        <SectionHead
          eyebrow="生成长视频 · 核心"
          title="15 秒一段，拼成一条完整的片子"
          lead="长视频不是「一句话生成两分钟」，而是一条可检查的流水线：大纲 → 逐镜 → 合并。每一镜都能重做，缺片会被拦下，成片按时长与顺序校验。"
        />

        <Flow
          cols={5}
          items={[
            { title: '说清用途与时长', desc: '给谁看、什么调性、总共多长；有旁白就一起贴进来。', tag: '目标' },
            { title: '镜头大纲（等你确认）', desc: '≤120 字梗概 + 每镜一句画面；不写运镜和提示词。', tag: '审批' },
            { title: '逐镜补全', desc: '景别、运镜、图片提示词、视频提示词、时长，写进项目分镜。', tag: '写库' },
            { title: '批量生图 / 生视频', desc: '首帧先出图，再按 4–15 秒逐镜出片；首尾帧自动衔接。', tag: '执行' },
            { title: '合并成片', desc: '校验片数与总时长，按顺序拼接；缺片不会静默跳过。', tag: '交付' },
          ]}
        />

        <div style={{ marginTop: 22 }}>
          <TimelineComposer />
        </div>

        <div className="mx-split mx-split--top" style={{ marginTop: 26 }}>
          <div className="mx-stack-12">
            <div className="mx-mini-label">三种做长片的路径</div>
            <div className="mx-table-wrap">
              <table className="mx-table">
                <thead>
                  <tr><th>做法</th><th>时长</th><th>一致性</th><th>适合</th></tr>
                </thead>
                <tbody>
                  {LONG_VIDEO_COMPARE.map((row) => (
                    <tr key={row.way}>
                      <td><b>{row.way}</b></td>
                      <td>{row.duration}</td>
                      <td>{row.consistency}</td>
                      <td>{row.fit}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="mx-note">
              合并前必须所有镜头都落地。缺一段就停，是产品故意设计的：宁可让你补一拍，也不交一条断片。
            </div>
          </div>

          <div className="mx-stack-12">
            <div className="mx-mini-label">可以直接这样说</div>
            <PromptBox
              text="把这段 2 分钟的旁白做成一条完整的咖啡店短片：低饱和暖调，人物用「阿伟」资产，每镜 8–12 秒；先出镜头大纲给我确认，确认后再逐镜生成并合并。"
              strong={<>2 分钟 ≈ 8–12 个镜头</>}
            >
              把这段 2 分钟的旁白做成一条完整的咖啡店短片：低饱和暖调，人物用「阿伟」资产，每镜 8–12 秒；先出镜头大纲给我确认，确认后再逐镜生成并合并。
            </PromptBox>
            <div className="mx-notes mx-stack-8">
              {[
                ['先看大纲，再让它花钱', '大纲阶段只有文字，改起来最便宜；确认后才逐镜生成。'],
                ['一镜一事', '一个镜头只写一个动作；想讲两件事就拆两镜。'],
                ['首尾帧是衔接的关键', '相邻镜头用上一镜尾帧当下一镜首帧，运动才连得上。'],
                ['合并是最后一步', '不要边生成边合并；等全部镜头落地，再统一校验、拼接。'],
              ].map(([t, d]) => (
                <div key={t} style={{ display: 'flex', gap: 8 }}>
                  <CircleCheck size={13} strokeWidth={2} color="var(--color-success)" style={{ flexShrink: 0, marginTop: 2 }} />
                  <span className="mx-note" style={{ fontSize: 11.5 }}>
                    <strong style={{ color: 'var(--color-ink)' }}>{t}：</strong>{d}
                  </span>
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}

/* ── 06 画布与节点 ──────────────────────────────────────── */
const CANVAS_NODES: { name: string; what: string; port: string }[] = [
  { name: '文字 / 剧本', what: '旁白、剧本、提示词容器', port: '输出文本' },
  { name: '图片 / 视频', what: '素材占位，可作参考', port: '输出媒体' },
  { name: '分镜拆解', what: '剧本 → 结构化镜头列表', port: '输出镜头' },
  { name: '镜头', what: '单镜的图 / 视频载体', port: '输出媒体' },
  { name: '图片生成', what: '文生图 / 图生图 / 批量完善', port: 'prompt + reference' },
  { name: '视频生成', what: '文生 / 首尾帧 / 参考视频', port: 'prompt + reference（有序）' },
  { name: '视频合并', what: '按连线顺序拼接', port: '多段视频' },
  { name: '上传 / 项目库', what: '本地文件与项目素材进画布', port: '输出媒体' },
];

export function CanvasSection() {
  return (
    <section className="docs-section" id="canvas">
      <div className="docs-container" data-reveal>
        <SectionHead
          eyebrow="画布与节点"
          title="把一条创作流程，摆成看得见的一张图"
          lead="画布是一个独立工作台：左边是节点库，中间是无限画布，右边是节点属性。你可以自己拖，也可以让 Moon 一次把整张图搭好——但执行按钮始终在你手上。"
        />

        <CanvasGraph />

        <div className="mx-row-8" style={{ marginTop: 12 }}>
          <span className="mx-chip mx-chip--static" style={{ fontSize: 10 }}>入口 1 · 对话里打开画布小窗</span>
          <span className="mx-chip mx-chip--static" style={{ fontSize: 10 }}>入口 2 · 独立画布站点 /canvas</span>
          <span className="mx-note" style={{ fontSize: 10.5 }}>同一份数据；智能体改完，画布会在几秒内自动刷新。</span>
        </div>

        <div className="mx-split mx-split--top" style={{ marginTop: 26 }}>
          <div className="mx-stack-12">
            <div className="mx-mini-label">节点库里有什么</div>
            <div className="mx-table-wrap">
              <table className="mx-table">
                <thead>
                  <tr><th>节点</th><th>作用</th><th>端口</th></tr>
                </thead>
                <tbody>
                  {CANVAS_NODES.map((n) => (
                    <tr key={n.name}>
                      <td><b>{n.name}</b></td>
                      <td>{n.what}</td>
                      <td><code>{n.port}</code></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="mx-note">
              工具名不用记：在画布左侧点图标，或在对话里说「加一个视频生成节点」，效果一样。
            </div>
          </div>

          <div className="mx-stack-16">
            <div>
              <div className="mx-mini-label">两种搭法，选一个</div>
              <div className="mx-stack-8">
                <div style={{ padding: 14, borderRadius: 10, border: '1px solid var(--color-hairline)', background: '#fff' }}>
                  <div className="mx-row-8" style={{ marginBottom: 4 }}>
                    <MousePointer2 size={13} strokeWidth={1.9} color="var(--color-primary)" />
                    <strong style={{ font: 'var(--text-title-sm)', color: 'var(--color-ink)' }}>自己拖</strong>
                  </div>
                  <div className="mx-note" style={{ fontSize: 11.5 }}>从左侧节点库拖出节点，连端口，选中后右侧改参数。适合微调、改结构。</div>
                </div>
                <div style={{ padding: 14, borderRadius: 10, border: '1px solid var(--color-primary)', background: 'var(--color-primary-tint)' }}>
                  <div className="mx-row-8" style={{ marginBottom: 4 }}>
                    <Sparkles size={13} strokeWidth={1.9} color="var(--color-primary)" />
                    <strong style={{ font: 'var(--text-title-sm)', color: 'var(--color-ink)' }}>让 Moon 搭</strong>
                  </div>
                  <div className="mx-note" style={{ fontSize: 11.5 }}>说清节点、连线、分组，它一次提交整张图，不逐条执行。适合从 0 到 1。</div>
                </div>
              </div>
            </div>

            <Callout tone="warn">
              <strong>三条硬规则：</strong>
              ① 生产节点（图片 / 视频生成）必须有结果槽，再连下游；
              ② 参考素材统一走 <code>reference</code> 端口，顺序就是素材顺序（首尾帧 = 前两张）；
              ③ 只有分组上才有「执行」，智能体不能替你开跑。
            </Callout>
          </div>
        </div>

        <div style={{ marginTop: 24 }}>
          <NodeAnatomy />
        </div>

        <div style={{ marginTop: 24 }}>
          <div className="mx-mini-label">从 0 到跑起来</div>
          <Flow
            cols={5}
            items={[
              { title: '新建画布', desc: '一个项目可以有多张画布；命名成「雨夜开场」这种一眼能认的名字。', tag: '准备' },
              { title: '放节点', desc: '左侧拖出节点，或直接让 Moon 一次建好；先放剧本 / 分镜 / 生成节点。', tag: '结构' },
              { title: '连线', desc: '参考素材走 reference（有序）；生产节点先建结果槽，再连下游。', tag: '依赖' },
              { title: '分组', desc: '把这条流里参与生成的节点放进同一组，起一个能看懂的名字。', tag: '执行单位' },
              { title: '点执行', desc: '分组标题上点执行，任务队列看进度；结果落进结果槽。', tag: '跑起来' },
            ]}
          />
        </div>

        <div className="mx-row-8" style={{ marginTop: 18 }}>
          <span className="mx-mini-label" style={{ margin: 0 }}>画布快捷键</span>
          {CANVAS_SHORTCUTS.slice(0, 5).map((s) => (
            <span key={s.keys} className="mx-chip mx-chip--static" style={{ fontSize: 10 }}>
              <Kbd>{s.keys}</Kbd>
              <span style={{ marginLeft: 6 }}>{s.what}</span>
            </span>
          ))}
        </div>
      </div>
    </section>
  );
}

/* ── 07 设计工作流 ──────────────────────────────────────── */
export function WorkflowSection() {
  return (
    <section className="docs-section docs-section--alt" id="workflow">
      <div className="docs-container" data-reveal>
        <SectionHead
          eyebrow="设计工作流"
          title="工作流不是流程图，是「能跑的结构」"
          lead="一张好的工作流只回答四个问题：从什么开始、每一步产出什么、谁依赖谁、在哪儿停下来等人。下面三种结构覆盖了大多数创作场景。"
        />

        <WorkflowPatterns />

        <div className="mx-split mx-split--top" style={{ marginTop: 26 }}>
          <div className="mx-stack-16">
            <div>
              <div className="mx-mini-label">设计一条工作流的五步</div>
              <LeadList
                items={[
                  { title: '先定终点', desc: '要一条成片、一组主图，还是一份可复用模板？终点决定要哪些节点。' },
                  { title: '只留一条主线', desc: '一条流只做一件事。想同时做开场和转场，就分成两个组，而不是把线绕成一团。' },
                  { title: '每个生产节点配结果槽', desc: '图片 / 视频节点 → 空占位 → 下游。少了结果槽，下游拿不到媒体。' },
                  { title: '用分组表达「一起跑」', desc: '同一组 = 一次执行；跨组按依赖等待。分组名写清楚，例如「雨夜开场 · 8 镜」。' },
                  { title: '先搭结构，再点执行', desc: '结构阶段不花钱；确认节点与连线没问题，再在分组上点执行。' },
                ]}
              />
            </div>
            <Callout>
              <strong>什么时候值得建工作流？</strong>同一套做法要用第二次、要批量跑、要交给别人复用的时候。
              一次性的任务，直接在对话里说完更快。
            </Callout>
          </div>

          <div className="mx-stack-16">
            <WorkflowPrompt />
            <div>
              <div className="mx-mini-label">最常见的三个坑</div>
              <div className="mx-stack-8">
                {[
                  ['把生成节点直接连下游', '中间少了结果槽，下游的媒体输入永远是空的。'],
                  ['一条流塞两个目标', '又要出图又要出片，还混着改图，最后谁也看不清依赖。'],
                  ['让智能体「顺便执行」', '它只会返回预览。执行必须由你在分组上点，这是额度与安全边界。'],
                ].map(([t, d]) => (
                  <div key={t} style={{ display: 'flex', gap: 8 }}>
                    <span style={{ color: 'var(--color-error)', font: '600 12px/1.4 var(--font-code)', flexShrink: 0 }}>✕</span>
                    <span className="mx-note" style={{ fontSize: 11.5 }}>
                      <strong style={{ color: 'var(--color-ink)' }}>{t}：</strong>{d}
                    </span>
                  </div>
                ))}
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}

/* ── 08 写文档 ──────────────────────────────────────────── */
export function DocumentSection() {
  return (
    <section className="docs-section" id="document">
      <div className="docs-container" data-reveal>
        <SectionHead
          eyebrow="写文档"
          title="从一句话，到一份能发出去的文档"
          lead="Moon 不只是会写正文：它会按你的结构产出 Word、Excel、PDF、Markdown，把表格、清单和图表一起做进去，最后落成聊天里的文件卡。"
        />

        <div className="mx-split mx-split--top">
          <DocumentPreview />
          <div className="mx-stack-16">
            <div>
              <div className="mx-mini-label">怎么问，才一次写对</div>
              <LeadList
                items={[
                  { title: '说清用途与受众', desc: '「给制片看的执行方案」和「给客户看的提案」结构完全不同。' },
                  { title: '指定章节与表格', desc: '目标、清单、预算、时间——把目录说出来，比让它自由发挥稳。' },
                  { title: '给格式与页数', desc: '「导出 Word 和 PDF，各 3 页以内」；需要表格就说「第二页放镜头清单表」。' },
                  { title: '先大纲，后全文', desc: '长文档先让它列目录，你改完再展开，返工最少。' },
                ]}
              />
            </div>

            <div>
              <div className="mx-mini-label">能交付的格式</div>
              <div className="mx-row-8">
                {['Markdown', 'Word .docx', 'Excel .xlsx', 'CSV', 'PDF', 'PNG / SVG 图表'].map((f) => (
                  <span key={f} className="mx-chip mx-chip--static" style={{ fontSize: 10 }}>{f}</span>
                ))}
              </div>
            </div>

            <Callout tone="tip">
              <strong>产出即资产：</strong>文档会被扫描进「产出素材」，可在资产库里预览 Word / PDF / PPTX，不用先下载再看。
              单个文件上限 50MB，超了先拆分或压缩。
            </Callout>
          </div>
        </div>

        <div className="mx-split mx-split--top" style={{ marginTop: 26 }}>
          <div>
            <div className="mx-mini-label">文档结构这样搭</div>
            <LeadList
              items={[
                { title: '结论先行', desc: '第一段就回答「做什么、为什么、要谁配合」；领导只看这一段也能拍板。' },
                { title: '依据与现状', desc: '数据、约束、已有素材与前置结论，让后面的方案站得住。' },
                { title: '执行清单', desc: '镜头 / 任务 / 负责人 / 时间，用表格；能勾选的不要写成段落。' },
                { title: '附录与版本', desc: '资产清单、参考链接、版本号与日期；改到第几版一眼能看到。' },
              ]}
            />
          </div>
          <div>
            <div className="mx-mini-label">四条写作规则</div>
            <div className="mx-stack-8">
              {[
                ['一段一件事', '长段落拆成短段；每段开头就是它的结论。'],
                ['数字要能核对', '预算、时长、数量写清单位和来源，不要用「若干」「一些」。'],
                ['术语先定义', '内部黑话第一次出现时给一句解释，文档要能脱离对话独立阅读。'],
                ['交付即文件', '定稿导出 PDF / DOCX；Markdown 留作可编辑源，别只发聊天截图。'],
              ].map(([t, d]) => (
                <div key={t} style={{ padding: '10px 12px', border: '1px solid var(--color-hairline)', borderRadius: 9, background: '#fff' }}>
                  <div style={{ font: 'var(--text-caption)', color: 'var(--color-ink)', marginBottom: 2 }}>{t}</div>
                  <div className="mx-note" style={{ fontSize: 10.5 }}>{d}</div>
                </div>
              ))}
            </div>
          </div>
        </div>

        <Flow
          items={[
            { title: '给目标与结构', desc: '用途、受众、目录、页数、交付格式一次说清。', tag: '输入' },
            { title: '先看目录', desc: '长文档先出大纲；目录不对，改一行比改十页便宜。', tag: '确认' },
            { title: '生成与配图', desc: '正文、表格、图表一起做；需要配图就顺手生图。', tag: '执行' },
            { title: '预览 / 下载 / 再改', desc: '聊天里点文件卡预览，或说「第 2 节再展开一点」。', tag: '交付' },
          ]}
        />
      </div>
    </section>
  );
}

/* ── 09 做 PPT ──────────────────────────────────────────── */
export function PptSection() {
  return (
    <section className="docs-section docs-section--alt" id="ppt">
      <div className="docs-container" data-reveal>
        <SectionHead
          eyebrow="做 PPT"
          title="先定设计参数，再让每一页只讲一件事"
          lead="PPT 是最容易「看起来很努力、实际没法讲」的交付物。Moon 会先跟你确认画布、页数、配色、字体和交付格式，再按一条叙事线生成可编辑的 PPTX。"
        />

        <div className="mx-split mx-split--top">
          <div className="mx-stack-12">
            <PptDeck />
            <div className="mx-row-8">
              <span className="mx-chip mx-chip--static" style={{ fontSize: 10 }}>/ppt 触发</span>
              <span className="mx-chip mx-chip--static" style={{ fontSize: 10 }}>可编辑 PPTX</span>
              <span className="mx-chip mx-chip--static" style={{ fontSize: 10 }}>每页 PNG 预览</span>
            </div>

            <div>
              <div className="mx-mini-label">确认卡上会出现什么</div>
              <div className="mx-prompt-box" style={{ fontFamily: 'var(--font-code)', fontSize: 10.5, lineHeight: 1.9 }}>
                沟通目标：产品发布会 · 投资人 + 媒体<br />
                画布：1280×720（16:9）｜正文 30px｜共 12 页<br />
                配色：底 #141413 / 面板 #1F1E1B / 强调 #CC785C<br />
                字体：标题与正文同族；数字等宽<br />
                交付：output/发布会.pptx + preview/page-01.png
              </div>
            </div>

            <Callout tone="warn">
              <strong>没有实时预览链接是正常的：</strong>生成环境在容器里，浏览器访问不到容器内的地址。
              预览以 <code>output/preview</code> 里的图片为准，PPTX 本身可以直接下载再改。
            </Callout>
          </div>

          <div className="mx-stack-16">
            <div>
              <div className="mx-mini-label">四条路线，按现状选</div>
              <div className="mx-stack-8">
                {[
                  ['从零生成', '只有主题和受众，让它出大纲 + 全套页面。'],
                  ['模板填充', '有品牌模板，把内容填进既有版式，不乱改母版。'],
                  ['图片转 PPT', '有一堆截图 / 图片，先 OCR 与排版，再重建成可编辑页。'],
                  ['旧稿美化', '已有 PPTX，统一字体、配色、留白与图表样式。'],
                ].map(([t, d]) => (
                  <div key={t} style={{ display: 'flex', gap: 10, padding: '10px 12px', border: '1px solid var(--color-hairline)', borderRadius: 9, background: '#fff' }}>
                    <Check size={13} strokeWidth={2.2} color="var(--color-primary)" style={{ flexShrink: 0, marginTop: 2 }} />
                    <div>
                      <div style={{ font: 'var(--text-caption)', color: 'var(--color-ink)' }}>{t}</div>
                      <div className="mx-note" style={{ fontSize: 10.5 }}>{d}</div>
                    </div>
                  </div>
                ))}
              </div>
            </div>

            <div>
              <div className="mx-mini-label">设计上只记五条</div>
              <LeadList
                items={[
                  { title: '一页一个观点', desc: '标题就是结论，正文只放支撑它的事实；讲不完的拆成两页。' },
                  { title: '三色就够', desc: '底色、面板色、一个强调色。强调色只标结论、数字和关键动作。' },
                  { title: '字号按画布定', desc: '1280×720 下正文 24–30px，标题 40–54px；投影场合再大一号。' },
                  { title: '能用图就不用句子', desc: '趋势用折线、构成用条形、对比用表格；一页最多一张主图。' },
                  { title: '留白是设计', desc: '页边距至少 48px，元素之间留出呼吸；塞满的页面没人看。' },
                ]}
              />
            </div>
          </div>
        </div>

        <div style={{ marginTop: 30 }}>
          <div className="mx-mini-label">一条 12 页的叙事骨架</div>
          <div className="mx-table-wrap">
            <table className="mx-table">
              <thead><tr><th>页</th><th>作用</th><th>写什么</th><th>别这样</th></tr></thead>
              <tbody>
                <tr><td>1</td><td>封面</td><td>一句主张 + 场合 / 日期</td><td>只放公司名和日期</td></tr>
                <tr><td>2</td><td>议程</td><td>三件事，不要超过三条</td><td>列 8 条议程</td></tr>
                <tr><td>3–4</td><td>问题</td><td>现状、痛点、为什么现在</td><td>直接跳到产品功能</td></tr>
                <tr><td>5–8</td><td>方案</td><td>能力、演示、对比、证据</td><td>一页塞四个功能</td></tr>
                <tr><td>9–10</td><td>数据</td><td>一张主图 + 一句结论</td><td>放一张看不清的截图</td></tr>
                <tr><td>11</td><td>落地</td><td>节奏、资源、下一步</td><td>只有愿景没有行动</td></tr>
                <tr><td>12</td><td>收尾</td><td>一句行动号召 + 联系方式</td><td>只写「谢谢」</td></tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </section>
  );
}

/* ── 10 提示词方法 ──────────────────────────────────────── */
export function PromptSection() {
  return (
    <section className="docs-section" id="prompt">
      <div className="docs-container" data-reveal>
        <SectionHead
          eyebrow="AIGC 方法"
          title="会写提示词，不如会「只改一处」"
          lead="提示词不是咒语，是一份可执行的 brief。写清主体与约束，然后一次只动一个变量——这套方法在图片、视频、文档、PPT 上都成立。"
        />

        <PromptAnatomy kind="video" />

        <div className="mx-split mx-split--top" style={{ marginTop: 26 }}>
          <div>
            <div className="mx-mini-label">三条迭代原则</div>
            <LeadList
              items={[
                { title: '一次只改一处', desc: '同时改光线、构图和风格，你永远不知道是哪句起了作用。' },
                { title: '能给参考就别形容', desc: '「像这张图的质感」比十个形容词准；参考图还能锁住人物长相。' },
                { title: '先写否定项', desc: '不出现文字、不要水印、不要多余手指——负面约束比正面描述更能救图。' },
              ]}
            />
            <div style={{ marginTop: 16 }}>
              <div className="mx-mini-label">常见写法 → 改法</div>
              <div className="mx-table-wrap">
                <table className="mx-table">
                  <thead><tr><th>太笼统</th><th>改成</th></tr></thead>
                  <tbody>
                    <tr><td>一个男人在咖啡店</td><td>35 岁咖啡师，深绿围裙，站在暖木吧台后注水，晨光从左侧打入</td></tr>
                    <tr><td>高级感海报</td><td>竖版 9:16，低饱和暖调，大面积留白，标题区在右上，不出现文字</td></tr>
                    <tr><td>让画面动起来</td><td>镜头缓慢推近手部，热水注入滤杯，蒸汽向上飘散</td></tr>
                    <tr><td>做个好看的 PPT</td><td>12 页，深色底 + 珊瑚强调，每页一个观点，第 6 页放对比表</td></tr>
                  </tbody>
                </table>
              </div>
            </div>
          </div>

          <div className="mx-stack-16">
            <div>
              <div className="mx-mini-label">AIGC 能接的活</div>
              <div className="mx-table-wrap">
                <table className="mx-table">
                  <thead><tr><th>场景</th><th>怎么说</th><th>交付</th></tr></thead>
                  <tbody>
                    {AIGC_MATRIX.map((row) => (
                      <tr key={row.scene}>
                        <td><b>{row.scene}</b></td>
                        <td>{row.ask}</td>
                        <td>{row.output}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>

            <Callout tone="tip">
              <strong>一致性只有一个解法：</strong>把会重复出现的东西（人、道具、场景、品牌元素）变成可引用的资产，
              每次生成都挂同一份，而不是每次重新描述一遍。
            </Callout>

            <div className="mx-row-8">
              <Clock size={12} strokeWidth={1.9} color="var(--color-muted)" />
              <span className="mx-note" style={{ fontSize: 10.5 }}>预期管理：图 30–60 秒；单段视频 2–5 分钟；长片按镜累加；PPT 与文档取决于页数。</span>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}

/* ── 11 配方 ────────────────────────────────────────────── */
export function RecipeSection() {
  return (
    <section className="docs-section docs-section--alt" id="recipes">
      <div className="docs-container" data-reveal>
        <SectionHead
          eyebrow="照抄配方"
          title="六个能直接用的组合，改一个变量就能复用"
          lead="下面每一句都可以原样发给 Moon。第一次照抄，第二次换主体或时长，第三次你就有了自己的模板。"
        />

        <div className="mx-recipes">
          {RECIPES.map((r) => (
            <article className="mx-recipe" key={r.title}>
              <div className="mx-recipe__head">
                <h3 className="mx-recipe__title">{r.title}</h3>
                <span className="mx-recipe__tag">{r.tag}</span>
              </div>
              <div className="mx-recipe__prompt">
                <CopyButton text={r.prompt} />
                <span style={{ paddingRight: 44 }}>{r.prompt}</span>
              </div>
              <div className="mx-recipe__steps">
                {r.steps.map((s, i) => (
                  <span key={s}><b>{String(i + 1).padStart(2, '0')}</b>{s}</span>
                ))}
              </div>
              <div className="mx-spread" style={{ marginTop: 'auto', paddingTop: 4 }}>
                <span className="mx-note" style={{ fontSize: 10.5 }}>产出：{r.output}</span>
                <button type="button" className="mx-btn" onClick={() => jump(r.anchor)}>
                  看讲解
                  <ArrowRight size={11} strokeWidth={2} />
                </button>
              </div>
            </article>
          ))}
        </div>

        <div style={{ marginTop: 20 }}>
          <Callout>
            <strong>配方的用法：</strong>不要一次改三处。先换主体，再换风格，最后调时长与比例；
            每次只跑一个变量，你才知道哪一句真正影响结果。
          </Callout>
        </div>
      </div>
    </section>
  );
}

/* ── 12 命令与问答 ──────────────────────────────────────── */
export function FaqSection() {
  return (
    <section className="docs-section" id="faq">
      <div className="docs-container" data-reveal>
        <SectionHead
          eyebrow="命令与问答"
          title="把常用动作变成肌肉记忆"
          lead="输入框打「/」就是命令面板；「@」是引用资料。剩下的问题，多半能在下面找到答案。"
        />

        <div className="mx-table-wrap">
          <table className="mx-table">
            <thead><tr><th>输入</th><th>作用</th><th>什么时候用</th></tr></thead>
            <tbody>
              {COMMANDS.map((c) => (
                <tr key={c.input}>
                  <td><code>{c.input}</code></td>
                  <td>{c.what}</td>
                  <td>{c.when}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        <div className="mx-split mx-split--top" style={{ marginTop: 24 }}>
          <div>
            <div className="mx-mini-label">键盘快捷键</div>
            <div className="mx-table-wrap">
              <table className="mx-table">
                <thead><tr><th>按键</th><th>动作</th></tr></thead>
                <tbody>
                  {SHORTCUTS.map((s) => (
                    <tr key={s.keys}>
                      <td><Kbd>{s.keys}</Kbd></td>
                      <td>{s.what}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>

          <div className="mx-stack-12">
            <div className="mx-mini-label">三个容易混淆的动作</div>
            <div className="mx-stack-8">
              {[
                ['生成中按 Enter', '不是打断，是把这句话插入当前回合；想停就点停止按钮。'],
                ['/clear 与 /compact', '前者真删除上下文（素材保留），后者只压缩历史、保留最近几轮。'],
                ['@ 引用', '引用分镜、文档、素材或另一段对话；它会先读，再基于读到的内容做。'],
              ].map(([t, d]) => (
                <div key={t} style={{ padding: '10px 12px', border: '1px solid var(--color-hairline)', borderRadius: 9, background: '#fff' }}>
                  <div style={{ font: 'var(--text-caption)', color: 'var(--color-ink)', marginBottom: 2 }}>{t}</div>
                  <div className="mx-note" style={{ fontSize: 10.5 }}>{d}</div>
                </div>
              ))}
            </div>
          </div>
        </div>

        <div style={{ marginTop: 26 }}>
          <div className="mx-mini-label">常见问题</div>
          <Accordion defaultValue={['faq-0']} className="docs-faq">
            {FAQS.map((f, i) => (
              <AccordionItem key={f.q} value={`faq-${i}`} className="docs-faq__item">
                <AccordionTrigger className="docs-faq__q">{f.q}</AccordionTrigger>
                <AccordionContent className="docs-faq__a">{f.a}</AccordionContent>
              </AccordionItem>
            ))}
          </Accordion>
        </div>
      </div>
    </section>
  );
}
