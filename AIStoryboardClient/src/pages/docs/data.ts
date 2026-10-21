import type { ReactNode } from 'react';

/** 左侧章节导航（顺序 = 页面顺序）。 */
export const SECTIONS = [
  { id: 'moon', label: '认识 Moon' },
  { id: 'start', label: '60 秒上手' },
  { id: 'image', label: '生成图片' },
  { id: 'video', label: '生成视频' },
  { id: 'long', label: '生成长视频' },
  { id: 'canvas', label: '画布与节点' },
  { id: 'workflow', label: '设计工作流' },
  { id: 'document', label: '写文档' },
  { id: 'ppt', label: '做 PPT' },
  { id: 'prompt', label: '提示词方法' },
  { id: 'recipes', label: '照抄配方' },
  { id: 'faq', label: '命令与问答' },
] as const;

export const NAV_ITEMS = SECTIONS.map((s) => ({ ...s }));

export type Capability = {
  id: string;
  title: string;
  desc: string;
  say: string;
  anchor: string;
  wide?: boolean;
};

export const CAPABILITIES: Capability[] = [
  {
    id: 'long',
    title: '生成长视频',
    desc: '单段上限 15 秒，超了就拆成分镜：大纲审批 → 逐镜生成 → 自动衔接 → 合并成片。',
    say: '把这段旁白做成 40 秒的咖啡店短片',
    anchor: 'long',
    wide: true,
  },
  {
    id: 'image',
    title: '生成图片',
    desc: '文生图、参考图改图、批量变体、局部重绘。',
    say: '做一张竖版 9:16 的开业海报',
    anchor: 'image',
  },
  {
    id: 'video',
    title: '生成视频',
    desc: '文生视频、图生视频、首尾帧、参考视频。',
    say: '把这张图变成 5 秒的推镜',
    anchor: 'video',
  },
  {
    id: 'canvas',
    title: '画布与节点',
    desc: '在无限画布上摆节点、连线、分组，按依赖批量执行。',
    say: '在画布上搭一条 剧本→分镜→生图→生视频 的流程',
    anchor: 'canvas',
  },
  {
    id: 'workflow',
    title: '设计工作流',
    desc: '把一次成功的做法，固化成可复用的节点图与话术。',
    say: '帮我设计一条可复用的短视频工作流',
    anchor: 'workflow',
  },
  {
    id: 'document',
    title: '写文档',
    desc: '方案、脚本、清单、预算、报告，落成 Word / Excel / PDF。',
    say: '写一份拍摄执行方案，导出 Word 和 PDF',
    anchor: 'document',
  },
  {
    id: 'ppt',
    title: '做 PPT',
    desc: '从零生成、模板填充、图片转 PPT、旧稿美化，交付可编辑 PPTX。',
    say: '做一份 12 页产品发布会 PPT',
    anchor: 'ppt',
  },
  {
    id: 'prompt',
    title: 'AIGC 方法',
    desc: '提示词六要素、一次只改一处、资产一致性与批量。',
    say: '把这些产品图改成统一的白底主图',
    anchor: 'prompt',
  },
  {
    id: 'storyboard',
    title: '分镜与剧本',
    desc: '剧本拆解、镜头大纲、逐镜写库；确认之后才花钱。',
    say: '把这段剧本拆成 8 个分镜',
    anchor: 'long',
  },
  {
    id: 'assets',
    title: '资产与一致性',
    desc: '人物 / 道具 / 场景资产卡，跨镜不漂移。',
    say: '把这个人物做成资产，后面每张都用他',
    anchor: 'prompt',
  },
  {
    id: 'batch',
    title: '批量与异步',
    desc: '批量出图、异步出片、多会话并行；提交完就能去干别的。',
    say: '这 8 个镜头一起生成，我等着就行',
    anchor: 'video',
  },
];

export const COMMANDS: { input: string; what: string; when: string }[] = [
  { input: '/ppt · /pptx · /ppt-master', what: '引用 PPT 技能，生成可编辑演示文稿', when: '要做 PPT、课件、模板填充' },
  { input: '/canvas · /canvas-exit', what: '进入 / 退出画布编辑模式', when: '要在对话里直接改当前画布' },
  { input: '/status', what: '看上下文占用、缓存命中、轮次与速度', when: '感觉变慢、想知道还剩多少上下文' },
  { input: '/compact', what: '压缩历史，保留最近若干轮', when: '长会话继续聊，但不想清空' },
  { input: '/clear · /reset', what: '真删除该会话全部上下文（素材保留）', when: '换方向重来，又不想新建会话' },
  { input: '/side · /branch', what: '基于当前对话开一个临时分支', when: '想试另一种方案，不污染主线' },
];

export const SHORTCUTS: { keys: string; what: string }[] = [
  { keys: 'Enter', what: '发送（生成中则是插入当前回合）' },
  { keys: 'Shift + Enter', what: '换行' },
  { keys: 'Ctrl / ⌘ + Shift + 2', what: '会话状态' },
  { keys: 'Ctrl / ⌘ + Shift + 3', what: '压缩上下文' },
  { keys: 'Ctrl / ⌘ + Shift + 4', what: '清空上下文' },
  { keys: 'Ctrl / ⌘ + Shift + 5', what: '侧边分支' },
];

export const CANVAS_SHORTCUTS: { keys: string; what: string }[] = [
  { keys: '1 – 9', what: '快速创建节点库里的节点' },
  { keys: '左键拖拽', what: '框选（中键才是平移）' },
  { keys: 'O', what: '整理当前编排 / 整张画布' },
  { keys: 'Q', what: '打开任务队列' },
  { keys: 'Delete / Backspace', what: '删除选中节点或连线' },
  { keys: '?', what: '完整快捷键表' },
];

export type Recipe = {
  title: string;
  tag: string;
  prompt: string;
  steps: string[];
  output: string;
  anchor: string;
};

export const RECIPES: Recipe[] = [
  {
    title: '电商主图套图',
    tag: '图片 · 批量',
    prompt: '做 4 张同款手冲壶的电商主图：白底、暖木台面、晨光侧逆光、45° 俯拍；每张换个卖点角度，不出现文字和水印。',
    steps: ['一次说清 4 个角度', '批量提交，不逐张等', '挑一张做「继续完善」'],
    output: '4 张 1:1 主图 + 可继续改的源图',
    anchor: 'image',
  },
  {
    title: '15 秒产品广告',
    tag: '视频 · 单段',
    prompt: '做一条 15 秒的手冲壶广告：开场水流入壶，中段蒸汽升腾，结尾产品定格；镜头两次推近，低饱和暖调，16:9。',
    steps: ['首帧用产品图', '写清两个动作 + 两次运镜', '生成后不满意只改一句'],
    output: '1 段 15 秒 1080P 视频',
    anchor: 'video',
  },
  {
    title: '40 秒品牌短片',
    tag: '长视频 · 分镜',
    prompt: '把这段 40 秒旁白做成咖啡店短片：雨夜进店、手冲、坐下举杯；统一低饱和暖调，人物用「阿伟」资产，每镜不超过 12 秒。',
    steps: ['让它先出镜头大纲', '确认大纲再逐镜生成', '全部完成后再合并'],
    output: '4 镜 + 合并成片 00:40',
    anchor: 'long',
  },
  {
    title: '课程 PPT 12 页',
    tag: 'PPT',
    prompt: '用 /ppt 做一份 12 页的「AI 影像入门」课件：深色底、珊瑚色强调、每页一个观点、第 6 页放一张对比表、最后一页留练习。',
    steps: ['先确认画布与页数', '让它给大纲再生成', '在 PPTX 里改文字与配图'],
    output: '可编辑 PPTX + 每页 PNG',
    anchor: 'ppt',
  },
  {
    title: '项目执行方案',
    tag: '文档',
    prompt: '写一份《雨夜咖啡馆》拍摄执行方案：包含目标、镜头清单表、资产与一致性要求、预算与时间；导出 Word 和 PDF，各 3 页以内。',
    steps: ['给受众与用途', '指定章节与表格', '确认后导出两种格式'],
    output: 'DOCX + PDF + 可编辑 Markdown',
    anchor: 'document',
  },
  {
    title: '画布批量变体',
    tag: '画布 · 工作流',
    prompt: '在画布上搭一条批量变体流：一张参考图接 4 个 AI 图片节点出 4 个变体，结果槽分开；全部放进「主图变体」组，不要替我执行。',
    steps: ['说清节点与连线', '要求结果槽互相独立', '自己点分组执行'],
    output: '4 个变体 + 可复用节点图',
    anchor: 'canvas',
  },
];

export const FAQS: { q: string; a: ReactNode }[] = [
  {
    q: '生成一张图要多久？视频呢？',
    a: '图片通常 30–60 秒一张，批量会排在同一批里；单段视频一般 2–5 分钟。视频是异步任务：提交后这一轮就结束了，可以切去别的会话干活，结果会自动补进对话。',
  },
  {
    q: '为什么视频只能 15 秒？',
    a: '15 秒是单次生成模型的上限，不是产品随便定的。超过就拆镜：一条 40 秒的片子通常 3–4 镜，2 分钟大约 8–10 镜，逐镜生成后再合并。',
  },
  {
    q: '长视频一定要走分镜吗？',
    a: '超过 15 秒就必须走。分镜除了绕开单段上限，还负责三件事：镜头之间首尾帧衔接、人物与场景不漂移、缺片时能被发现而不是拼出一条断片。',
  },
  {
    q: '我说一句它就开始生成，能先看方案吗？',
    a: '可以。左下角的审批模式有三档：方案批准（先看方案）、逐项批准（每个工具都确认）、完全访问（连续执行）。分镜流程本身会先给镜头大纲，等确认后才逐镜补全。',
  },
  {
    q: '为什么让 Moon 删画布节点，它说做不到？',
    a: '这是权限边界，不是能力缺陷：智能体只有「建 / 改 / 连 / 布局」的工具，没有删除工具，也不能用改标签、隐藏之类的方式假装删除。删除由你在画布上做：选中后 Delete，或右键删除、Ctrl+A 全选。',
  },
  {
    q: '画布搭好了，它为什么不自己跑？',
    a: '执行入口只存在于画布分组上，而且必须由你点。智能体提交的「执行」只会返回预览，不会真的开跑——这样批量生成不会在你没看结构时把额度烧掉。',
  },
  {
    q: 'PPT 为什么没有实时预览链接？',
    a: '生成环境在容器里，浏览器访问不到容器内的 127.0.0.1。所以交付方式是：可编辑 PPTX + output/preview 里每页的 PNG，直接在聊天里看图和下载。',
  },
  {
    q: '同一个人物，每张图长得都不一样？',
    a: '把人物、道具、场景建成资产卡（多张图 + 文字约束），生成时会自动注入；或者在对话里用 @ 引用同一张参考图。只靠文字描述，长相一定会漂。',
  },
  {
    q: '生成的东西去哪了？',
    a: '图片、视频、文档、表格、PPT 都进「产出素材 / 资产库」，聊天里也会出现文件卡。不用翻聊天记录找附件，资产库可按项目、类型筛选和预览。',
  },
  {
    q: '说错了、想重来怎么办？',
    a: '三种粒度：停止当前回合（已生成的文字会保留）、编辑上一条消息重发、/clear 真删除这个会话的上下文。只想省上下文但继续聊，用 /compact。',
  },
  {
    q: '能同时做几件事？',
    a: '不同会话可以并行，同一会话严格串行；视频、批量生图、分镜是异步任务，提交后不占着对话。想插一句话而不打断当前回合，生成中直接发送即可（会插入当前回合）。',
  },
  {
    q: '还能像以前那样在编辑器里手动改分镜吗？',
    a: '可以。三栏编辑器仍然保留：选分镜、改提示词、逐镜生成或重生成。Moon 负责把结构和初稿一次性搭好，精修放回编辑器或画布；两边读的是同一份项目分镜。',
  },
  {
    q: '它会记住我的喜好吗？',
    a: '会记「项目记忆」：跨会话仍然有效的目标、视觉风格、硬约束、已确认的决策。一次性要求和闲聊不记。你可以在账号页查看和删除这些记忆，也可以关掉整个记忆开关。',
  },
];

export const LONG_VIDEO_COMPARE: { way: string; duration: string; consistency: string; control: string; fit: string }[] = [
  { way: '单段直出', duration: '4–15 秒', consistency: '看提示词', control: '最低', fit: '一个镜头、一条广告、一次转场' },
  { way: '分镜 → 逐镜 → 合并', duration: '几十秒到几分钟', consistency: '强（资产 + 首尾帧）', control: '高（可逐镜重做）', fit: '短片、广告、故事片段（推荐）' },
  { way: '画布分组批量', duration: '按节点累加', consistency: '强（同一套素材）', control: '最高（结构可视化）', fit: '批量变体、多段并行、可复用流程' },
];

export const AIGC_MATRIX: { scene: string; ask: string; output: string }[] = [
  { scene: '电商 / 产品', ask: '同款产品 4 个角度，白底主图，统一光线', output: '4 张主图 + 详情页配图' },
  { scene: '品牌 / 海报', ask: '竖版海报，留标题位，统一品牌色', output: '海报 + 社媒方图 / 横图' },
  { scene: '影视 / 短片', ask: '分镜大纲 → 逐镜图 → 逐镜视频 → 合并', output: '分镜表 + 成片' },
  { scene: '课程 / 汇报', ask: '12 页课件，每页一个观点，深色高级感', output: 'PPTX + 预览图' },
  { scene: '文档 / 方案', ask: '执行方案：目标、清单表、预算、时间', output: 'Word / PDF / Excel' },
  { scene: '画布 / 批处理', ask: '一张参考图 → 4 个变体 → 挑 1 张进视频', output: '可复用的节点图' },
];
