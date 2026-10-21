import { useState } from 'react';
import { Copy } from 'lucide-react';
import { Tabs, CopyButton } from './shared';

type Pattern = 'linear' | 'fanout' | 'groups';

function Arrow() {
  return <span className="mx-wf__arrow" aria-hidden><span>→</span></span>;
}

function Node({ kind, title, desc, variant }: { kind: string; title: string; desc: string; variant?: 'gen' | 'slot' | 'group' }) {
  return (
    <div className={`mx-wf__node${variant ? ` mx-wf__node--${variant}` : ''}`}>
      <div className="mx-wf__k">{kind}</div>
      <div className="mx-wf__t">{title}</div>
      <div className="mx-wf__d">{desc}</div>
    </div>
  );
}

export function WorkflowPatterns() {
  const [pattern, setPattern] = useState<Pattern>('linear');

  return (
    <div className="mx-stack-12">
      <div className="mx-spread">
        <Tabs
          items={[
            { id: 'linear', label: '线性流水线' },
            { id: 'fanout', label: '扇出选优' },
            { id: 'groups', label: '分组并行' },
          ]}
          value={pattern}
          onChange={setPattern}
        />
        <span className="mx-note" style={{ fontSize: 10.5 }}>三种够用了：先线性，再考虑并行。</span>
      </div>

      {pattern === 'linear' && (
        <div className="mx-wf">
          <Node kind="text" title="剧本" desc="旁白 / 分镜描述" />
          <Arrow />
          <Node kind="storyboard" title="分镜拆解" desc="8 镜 · 结构化" />
          <Arrow />
          <Node kind="imageGen" title="AI 图片 ×4" desc="每镜 1 张首帧" variant="gen" />
          <Arrow />
          <Node kind="videoGen" title="生成视频 ×4" desc="首尾帧 · 5s" variant="gen" />
          <Arrow />
          <Node kind="merge" title="视频合并" desc="按序拼接成 20s" />
          <div className="mx-wf__aside">
            <b>执行入口在分组上</b>
            <span>全部节点放进同一组，标题上才会出现「执行」；结构阶段不花钱。</span>
          </div>
        </div>
      )}

      {pattern === 'fanout' && (
        <div className="mx-stack-8">
          <div className="mx-wf">
            <Node kind="image" title="一张参考图" desc="锁定人物 / 场景" />
            <Arrow />
            <Node kind="imageGen" title="AI 图片 ×4" desc="同题 4 个变体" variant="gen" />
            <Arrow />
            <Node kind="slot" title="结果 1–4" desc="4 个空占位" variant="slot" />
            <Arrow />
            <Node kind="human" title="你选 1 张" desc="点选即产物" />
            <Arrow />
            <Node kind="videoGen" title="生成视频" desc="选中的图当首帧" variant="gen" />
            <div className="mx-wf__aside">
              <b>挑选是人工节点</b>
              <span>4 个变体并行出图，你挑 1 张再进视频；别让模型替你决定。</span>
            </div>
          </div>
          <div className="mx-note">
            扇出适合「同一主体多方案」：4 个变体并行出图，你挑一张再进视频。挑选是人工节点，不要让模型替你决定。
          </div>
        </div>
      )}

      {pattern === 'groups' && (
        <div className="mx-stack-8">
          <div className="mx-wf" style={{ borderStyle: 'dashed' }}>
            <Node kind="group A" title="开场组" desc="图 → 视频 ×2" variant="group" />
            <Arrow />
            <Node kind="imageGen" title="AI 图片 ×2" desc="招牌 / 推门" variant="gen" />
            <Arrow />
            <Node kind="videoGen" title="生成视频 ×2" desc="5s + 5s" variant="gen" />
          </div>
          <div className="mx-wf" style={{ borderStyle: 'dashed' }}>
            <Node kind="group B" title="转场组" desc="图 → 视频 ×1" variant="group" />
            <Arrow />
            <Node kind="imageGen" title="AI 图片 ×1" desc="手冲特写" variant="gen" />
            <Arrow />
            <Node kind="videoGen" title="生成视频 ×1" desc="8s" variant="gen" />
          </div>
          <div className="mx-wf" style={{ border: 0, padding: '0 18px 18px' }}>
            <Node kind="merge" title="视频合并" desc="两组结果按顺序拼" />
            <div className="mx-wf__aside">
              <b>组是执行单位</b>
              <span>两组各自点执行、互不等待；合并节点等上游都落地。</span>
            </div>
          </div>
          <div className="mx-note">
            两个组各自点「执行」，互不等待；合并节点会等上游都落地。组是执行单位，也是你并行干活的边界。
          </div>
        </div>
      )}
    </div>
  );
}

const PROMPT = `在画布上搭一条「雨夜咖啡馆」工作流：剧本节点接分镜拆解，生成 8 个镜头；每镜接一个 AI 图片节点出 1 张首帧，再按顺序接视频生成（首尾帧，5 秒）；最后接视频合并。全部放进分组「雨夜开场」，不要替我执行，我自己点执行。`;

export function WorkflowPrompt() {
  return (
    <div className="mx-stack-8">
      <div className="mx-field__label">
        <b>可直接说的一句话</b>
        <span className="mx-mono">目标 + 节点 + 顺序 + 分组 + 执行方式</span>
      </div>
      <div className="mx-prompt-box">
        <CopyButton text={PROMPT} />
        <span style={{ paddingRight: 42 }}>{PROMPT}</span>
      </div>
      <div className="mx-row-8">
        <span className="mx-chip mx-chip--static" style={{ fontSize: 10 }}><Copy size={10} strokeWidth={2} style={{ verticalAlign: -1, marginRight: 4 }} />这句话里包含 5 件事</span>
        <span className="mx-note" style={{ fontSize: 10.5 }}>目标是什么 · 要哪些节点 · 谁连谁 · 怎么分组 · 要不要替你执行</span>
      </div>
    </div>
  );
}
