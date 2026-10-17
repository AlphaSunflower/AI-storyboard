import type { AgentToolDraft } from '../../api/agent';

/**
 * 工具调用的「说人话」层：把后端原样的工具名、状态和结果载荷，翻译成用户在
 * 对话里能一眼读懂的东西。渲染组件只消费这里的结果，不自己判状态。
 *
 * 关于中文名表：MoonAgent 的 `AgentScopeToolLabels` 已经是权威映射，但后端目前
 * 只把它用在 `AGENT_TRACE` 的 title 上，**没有**放进工具草稿。所以这里保留一份
 * 前端镜像。未收录的工具回落到「下划线转空格」的原名（也就是今天的表现），
 * 因此后端新增工具时最坏情况只是名字不够中文，不会空白、不会报错。
 */

/** 工具名 → 中文动作名。与 `AgentScopeToolLabels` 逐条对齐，改动需两边同步。 */
const TOOL_DISPLAY_NAMES: Record<string, string> = {
  // 媒体生成
  generate_image: '生成图片',
  generate_image_batch: '批量生成图片',
  generate_video: '生成视频',
  produce_video_journey: '一键成片',
  refineImage: '完善图片',
  review_generated_media: '检查生成结果',
  get_media_critiques: '读取生成检查记录',
  // 交互与画布
  request_human_input: '请求用户补充信息',
  canvas_prepare_session: '准备并打开画布',
  // 画布图命令（与后端 AgentScopeToolLabels.canvasGraphDisplayName 逐条对齐）
  canvas_graph_apply_plan: '构建画布（一次提交整图）',
  canvas_graph_create_nodes: '批量创建画布节点',
  canvas_graph_create_node: '创建画布节点',
  canvas_graph_create_edges: '批量连接画布节点',
  canvas_graph_create_edge: '连接画布节点',
  canvas_graph_update_node: '更新画布节点',
  canvas_graph_import_media_batch: '批量导入画布素材',
  canvas_graph_import_media: '导入画布素材',
  canvas_graph_create_result_slots: '创建结果槽',
  canvas_graph_mark_result_slots: '标记结果槽',
  canvas_graph_layout_graph: '整理画布布局',
  canvas_graph_get_state: '读取画布状态',
  canvas_graph_get_node: '读取画布状态',
  canvas_graph_list_node_types: '读取画布状态',
  // 分镜
  start_storyboard_production: '启动剧情与分镜流程',
  revise_storyboard_production: '修订分镜草稿',
  approve_storyboard_production: '确认当前分镜阶段',
  generate_storyboard_image: '生成分镜图片',
  generate_storyboard_images: '批量生成分镜图片',
  generate_storyboard_video: '生成分镜视频',
  generate_storyboard_videos: '批量生成分镜视频',
  list_storyboard_draft: '查询分镜草稿',
  list_project_storyboard: '查询项目分镜',
  update_project_storyboard_shot: '修改项目分镜参数',
  upsert_storyboard_shot: '添加或修改分镜草稿',
  delete_storyboard_draft_shot: '删除分镜草稿',
  reorder_storyboard_draft: '调整分镜草稿顺序',
  delete_project_storyboard_shot: '删除项目分镜',
  clear_project_storyboard: '清空项目分镜',
  merge_storyboard_videos: '合并完整分镜视频',
  // 创作记忆与时间线
  get_creative_context: '读取项目创作记忆',
  update_creative_context: '更新项目创作记忆',
  extract_creative_constraints: '提取创作约束',
  save_timeline_manifest: '保存时间线',
  get_timeline_manifest: '读取时间线',
  // 素材与任务
  list_session_assets: '查询会话素材',
  resolve_reference_assets: '查找参考素材',
  get_generation_task_status: '查询生成任务状态',
  list_asset_groups: '查询资产组',
  task_list: '查询后台任务',
  task_output: '读取后台任务结果',
  wait_async_results: '等待后台任务',
  agent_list: '查询子智能体',
  // 沙箱文件与命令
  read_file: '读取文件',
  write_file: '写入文件',
  edit_file: '编辑文件',
  grep_files: '搜索文件内容',
  glob_files: '查找文件',
  list_files: '列出文件',
  execute: '执行命令',
  run_python_script: '执行 Python 脚本',
  deliver_artifact: '交付产物',
  // 网络与能力装配
  web_search: '网页搜索',
  web_fetch: '抓取网页',
  load_skill_through_path: '读取技能',
  reset_equipped_tools: '调整可用工具',
};

/** 未收录工具的兜底：保留原名可读性，但不留一串下划线。 */
function humanizeUnknownTool(raw: string): string {
  return raw.replace(/[_-]+/g, ' ');
}

/** 工具的中文动作名；空名回落为「工具调用」而不是空白。 */
export function toolDisplayName(name: string): string {
  const raw = (name ?? '').trim();
  if (!raw) return '工具调用';
  return TOOL_DISPLAY_NAMES[raw] ?? humanizeUnknownTool(raw);
}

/**
 * 工具归类，供渲染层挑图标。这里只给语义桶，不引入 React，表现层保持纯函数。
 */
export type ToolIconKey =
  | 'image' | 'video' | 'storyboard' | 'search' | 'file'
  | 'terminal' | 'skill' | 'agent' | 'task' | 'generic';

export function toolIconKey(name: string): ToolIconKey {
  const raw = (name ?? '').trim();
  if (!raw) return 'generic';
  if (/storyboard/.test(raw)) return 'storyboard';
  if (/image|media/.test(raw)) return 'image';
  if (/video|journey/.test(raw)) return 'video';
  if (/web_search|grep|glob|resolve_reference|asset_group/.test(raw)) return 'search';
  if (/file|artifact|asset/.test(raw)) return 'file';
  if (/run_python|execute|terminal|command/.test(raw)) return 'terminal';
  if (/skill|equipped/.test(raw)) return 'skill';
  if (/agent|council|human/.test(raw)) return 'agent';
  if (/task|timeline|context|constraint/.test(raw)) return 'task';
  return 'generic';
}

/** 传输层状态 → 语气色阶。渲染层据此上色，不再自己判断成败。 */
export type ToolTone = 'running' | 'done' | 'failed' | 'idle';

const STATUS_LABELS: Record<string, string> = {
  node_started: '调用中',
  node_running: '执行中',
  node_completed: '已完成',
  node_failed: '失败',
};

const STATUS_TONES: Record<string, ToolTone> = {
  node_started: 'running',
  node_running: 'running',
  node_completed: 'done',
  node_failed: 'failed',
};

/** 状态文案。未知状态回落到状态原文，再兜底「执行中」。 */
export function toolStatusLabel(status: string): string {
  return STATUS_LABELS[status] ?? (status || '执行中');
}

export function toolStatusTone(status: string): ToolTone {
  return STATUS_TONES[status] ?? 'idle';
}

/**
 * 工具「跑完了」不等于「做成了」。
 *
 * `web_fetch` 连接失败时会带着 `{"ok":false,...,"code":"TOOL_FAILED"}` **正常返回**，
 * 传输层状态依然是 `node_completed`，于是卡片会挂着一个绿色的「已完成」——
 * 这是截图里能看到的问题。这里只在输出确实是 JSON、且顶层明确声明失败时才给提示。
 *
 * 解析不出来就什么都不说：宁可少一句提示，也不要把正常输出误标成失败。
 */
export function toolOutcomeNote(text: string): string | null {
  const value = (text ?? '').trim();
  if (!value || (value[0] !== '{' && value[0] !== '[')) return null;
  let parsed: unknown;
  try {
    parsed = JSON.parse(value);
  } catch {
    return null;
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return null;
  const record = parsed as Record<string, unknown>;
  const declaredFailure = record.ok === false || record.resultStatus === 'error';
  return declaredFailure ? '结果报错' : null;
}

/** 折叠态的一行摘要长度上限。 */
export const TOOL_PREVIEW_CHARS = 120;

/**
 * 折叠态摘要：优先原始输出，没有输出时退回入参（失败的工具常常只有入参可看）。
 * 不追加省略号字符，截断表现交给 CSS 的 text-overflow，避免污染可复制的文本。
 */
export function toolPreviewText(draft: Pick<AgentToolDraft, 'text' | 'input'>): string {
  if (draft.text) return draft.text.slice(0, TOOL_PREVIEW_CHARS);
  if (draft.input) return draft.input.slice(0, TOOL_PREVIEW_CHARS);
  return '';
}
