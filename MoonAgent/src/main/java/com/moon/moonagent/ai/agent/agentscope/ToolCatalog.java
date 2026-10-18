package com.moon.moonagent.ai.agent.agentscope;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 工具元数据的单一真源。
 *
 * <p>一个工具只在这里登记一次：名称、中文标签、审计/审批策略、所属技能组。
 * Toolkit 注册、权限策略、工具卡中文名、技能组门控全部从这里派生；新增/删除工具
 * 不再需要在 5 个地方同步名单。
 *
 * <p>不变的约束：
 * <ul>
 *   <li>没有 {@code groups} 的工具是常驻工具（技能激活前就可见）；</li>
 *   <li>有 {@code groups} 的工具只在对应技能组激活后可见；</li>
 *   <li>{@code policy == null} 表示沿用工具自身的 {@code readOnly} 推导 READ/WRITE，
 *       只有需要外部动作分类/预检的工具才显式登记。</li>
 * </ul>
 */
public final class ToolCatalog {

    static final String STORYBOARD_TOOLS = "storyboard-tools";
    static final String IMAGE_TOOLS = "image-tools";
    static final String VIDEO_TOOLS = "video-tools";
    static final String CANVAS_DESIGN_TOOLS = "canvas-design-tools";
    static final String CANVAS_RUN_TOOLS = "canvas-run-tools";

    /**
     * 画布链路可用性探针：这两个动态工具齐全才说明 Canvas MCP 真的连上了。
     * 动态工具无法逐条进目录，但探针口径必须只有一份。
     */
    static final List<String> CANVAS_PROBE_TOOLS = List.of(
            "canvas_graph_get_state", "canvas_graph_layout_graph");

    /** 技能组：名称、描述、激活它的技能、初始是否激活。 */
    record Group(String name, String description, String skill, boolean activeByDefault) {
    }

    /** 单个工具的完整元数据。 */
    record Spec(String name, String label, ToolPolicy policy, List<String> groups) {
    }

    /**
     * 一个会话可见的工具组合。
     *
     * <p>这是“每会话组合”的落点：技能白名单决定模型能加载哪些 Skill，组白名单决定
     * 会话里允许激活哪些工具组。两个集合都由会话/项目/模式推导，不看套餐、不看用户等级。
     */
    record Profile(Set<String> skills, Set<String> groups) {
    }

    private static final Map<String, Group> GROUPS = Map.of(
            STORYBOARD_TOOLS, new Group(
                    STORYBOARD_TOOLS, "Storyboard production tools", "storyboard", false),
            IMAGE_TOOLS, new Group(
                    IMAGE_TOOLS, "Image generation and reference-edit tools", "image", false),
            VIDEO_TOOLS, new Group(
                    VIDEO_TOOLS, "Asynchronous video generation tools", "video", false),
            CANVAS_DESIGN_TOOLS, new Group(
                    CANVAS_DESIGN_TOOLS, "Canvas graph design tools", "canvas-design", false),
            CANVAS_RUN_TOOLS, new Group(
                    CANVAS_RUN_TOOLS, "Canvas graph execution tools", "canvas-run", false)
    );

    private static final Set<String> ALL_SKILLS = Set.of(
            "storyboard", "image", "video", "canvas-design", "canvas-run", "general");

    private static final Map<String, Spec> SPECS = buildSpecs();

    private ToolCatalog() {
    }

    static Map<String, Group> groups() {
        return GROUPS;
    }

    static List<Spec> specs() {
        return List.copyOf(SPECS.values());
    }

    static Optional<Spec> spec(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(SPECS.get(name));
    }

    /** 动态包校验用：工具是否登记在唯一目录里。 */
    public static boolean isRegistered(String name) {
        return name != null && SPECS.containsKey(name);
    }

    static String label(String name) {
        Spec spec = name == null ? null : SPECS.get(name);
        return spec == null ? null : spec.label();
    }

    static Optional<ToolPolicy> policy(String name) {
        Spec spec = name == null ? null : SPECS.get(name);
        return spec == null ? Optional.empty() : Optional.ofNullable(spec.policy());
    }

    /**
     * 会话/轮次的工具组合。
     *
     * @param canvasEdit 是否画布编辑回合；画布回合不加载 storyboard 技能与工具组
     * @return 该轮允许的技能与工具组
     */
    static Profile profileFor(boolean canvasEdit) {
        if (!canvasEdit) {
            return new Profile(ALL_SKILLS, GROUPS.keySet());
        }
        Set<String> skills = new LinkedHashSet<>(ALL_SKILLS);
        skills.remove("storyboard");
        Set<String> groups = new LinkedHashSet<>(GROUPS.keySet());
        groups.remove(STORYBOARD_TOOLS);
        return new Profile(Set.copyOf(skills), Set.copyOf(groups));
    }

    private static Map<String, Spec> buildSpecs() {
        Map<String, Spec> specs = new LinkedHashMap<>();

        // ── 常驻：会话 / 结构化 / 资产组 / 交互 / 工作流 / 文档 / 沙箱 / 网络 / 创作读取 ──
        add(specs, spec(AgentScopeSessionTools.LIST_SESSION_ASSETS, "查询会话素材"));
        add(specs, spec(AgentScopeSessionTools.RESOLVE_REFERENCE_ASSETS, "查找参考素材"));
        add(specs, spec(AgentScopeSessionTools.GET_TASK_STATUS, "查询生成任务状态"));
        add(specs, spec(AgentScopeSessionTools.LIST_CONVERSATION_TASKS, "查询会话任务"));
        add(specs, spec(AgentScopeSessionTools.GET_AGENT_TASK, "读取后台任务详情"));
        add(specs, spec(AgentScopeStructuredTools.BUILD_MEDIA_SPEC, "组装媒体生成规格"));
        add(specs, spec(AgentScopeStructuredTools.ASSESS_GENERATION_RISK, "判断生成风险"));
        add(specs, spec(AgentScopeAssetGroupTools.LIST_ASSET_GROUPS, "查询资产组"));
        add(specs, spec(AgentScopeInteractionTools.REQUEST_HUMAN_INPUT, "请求用户补充信息"));
        add(specs, spec(AgentScopeInteractionTools.CHOOSE_STORYBOARD_COMMIT,
                "选择分镜写入方式"));
        add(specs, spec(AgentScopeWorkflowTools.RESOLVE_WORKFLOW, "识别用户工作流"));
        add(specs, gated(AgentScopeOrchestrationTools.RUN_TASK_GRAPH, "编排子智能体任务图",
                new ToolPolicy("READ", "", false, true)));
        add(specs, gated(AgentScopeOrchestrationTools.RUN_WRITE_TASK_GRAPH, "编排可写子智能体任务图",
                new ToolPolicy("WRITE", "", false, false)));
        add(specs, spec(AgentScopeDocumentTools.LIST_CONVERSATION_DOCUMENTS, "列出会话文档"));
        add(specs, spec(AgentScopeDocumentTools.READ_DOCUMENT, "读取文档"));
        add(specs, spec(AgentScopeSandboxTools.RUN_PYTHON_SCRIPT, "执行 Python 脚本"));
        add(specs, spec(AgentScopeMemoryTools.REMEMBER_MEMORY, "记住项目事实"));
        add(specs, spec(AgentScopeMemoryTools.RECALL_MEMORY, "召回项目记忆"));
        add(specs, spec(AgentScopeWebTools.WEB_SEARCH, "网页搜索"));
        add(specs, spec(AgentScopeWebTools.WEB_FETCH, "抓取网页"));
        add(specs, spec(AgentScopeCreativeTools.GET_CONTEXT, "读取项目创作记忆"));
        add(specs, spec(AgentScopeCreativeTools.EXTRACT_CONSTRAINTS, "提取创作约束"));
        add(specs, spec(AgentScopeCreativeTools.REVIEW_MEDIA, "检查生成结果"));
        add(specs, spec(AgentScopeCreativeTools.LIST_CRITIQUES, "读取生成检查记录"));

        // ── 分镜：整组挂在 storyboard 技能上 ──
        add(specs, gated(AgentScopeStoryboardTools.START_STORYBOARD, "启动剧情与分镜流程",
                new ToolPolicy("LONG_TOOL", "storyboard-start", false, false),
                STORYBOARD_TOOLS));
        add(specs, gated(AgentScopeStoryboardTools.REVISE_STORYBOARD, "修订分镜草稿",
                new ToolPolicy("STORYBOARD_WRITE", "storyboard-revise", false, false),
                STORYBOARD_TOOLS));
        add(specs, gated(AgentScopeStoryboardTools.APPROVE_STORYBOARD, "确认当前分镜阶段",
                new ToolPolicy("LONG_TOOL", "storyboard-approve", false, false),
                STORYBOARD_TOOLS));
        add(specs, gated(AgentScopeStoryboardTools.GENERATE_STORYBOARD_IMAGE, "生成分镜图片",
                new ToolPolicy("MEDIA_IMAGE", "storyboard-image", false, false),
                STORYBOARD_TOOLS));
        add(specs, gated(AgentScopeStoryboardTools.GENERATE_STORYBOARD_VIDEO, "生成分镜视频",
                new ToolPolicy("MEDIA_VIDEO", "storyboard-video", false, false),
                STORYBOARD_TOOLS));
        add(specs, gated(AgentScopeStoryboardTools.GENERATE_STORYBOARD_VIDEOS, "批量生成分镜视频",
                new ToolPolicy("MEDIA_VIDEO_BATCH", "storyboard-video-batch", false, false),
                STORYBOARD_TOOLS));
        add(specs, gated(AgentScopeStoryboardTools.UPSERT_STORYBOARD_SHOT, "添加或修改分镜草稿",
                new ToolPolicy("STORYBOARD_WRITE", "", false, false),
                STORYBOARD_TOOLS));
        add(specs, gated(AgentScopeStoryboardTools.LIST_STORYBOARD_DRAFT, "查询分镜草稿",
                new ToolPolicy("READ", "", false, true),
                STORYBOARD_TOOLS));
        add(specs, gated(AgentScopeStoryboardTools.LIST_PROJECT_STORYBOARD, "查询项目分镜",
                new ToolPolicy("READ", "", false, true),
                STORYBOARD_TOOLS));
        add(specs, gated(AgentScopeStoryboardTools.READ_PROJECT_SCENE, "读取项目分镜场景",
                new ToolPolicy("READ", "", false, true),
                STORYBOARD_TOOLS));
        add(specs, gated(AgentScopeStoryboardTools.UPDATE_PROJECT_STORYBOARD_SHOT,
                "修改项目分镜参数",
                new ToolPolicy("STORYBOARD_WRITE", "", false, false),
                STORYBOARD_TOOLS));
        add(specs, gated(AgentScopeStoryboardTools.DELETE_STORYBOARD_DRAFT_SHOT, "删除分镜草稿",
                new ToolPolicy("STORYBOARD_WRITE", "storyboard-draft-delete", false, false),
                STORYBOARD_TOOLS));
        add(specs, gated(AgentScopeStoryboardTools.REORDER_STORYBOARD_DRAFT, "调整分镜草稿顺序",
                new ToolPolicy("STORYBOARD_WRITE", "storyboard-draft-reorder", false, false),
                STORYBOARD_TOOLS));
        add(specs, gated(AgentScopeStoryboardTools.DELETE_PROJECT_STORYBOARD_SHOT,
                "删除项目分镜",
                new ToolPolicy("STORYBOARD_DELETE", "storyboard-project-delete", false, false),
                STORYBOARD_TOOLS));
        add(specs, gated(AgentScopeStoryboardTools.CLEAR_PROJECT_STORYBOARD, "清空项目分镜",
                new ToolPolicy("STORYBOARD_DELETE", "storyboard-project-clear", false, false),
                STORYBOARD_TOOLS));
        add(specs, gated(AgentScopeStoryboardTools.GENERATE_STORYBOARD_IMAGES,
                "批量生成分镜图片",
                new ToolPolicy("MEDIA_IMAGE_BATCH", "storyboard-image-batch", false, false),
                STORYBOARD_TOOLS));
        add(specs, gated(AgentScopeVideoMergeTools.MERGE_STORYBOARD_VIDEOS,
                "合并完整分镜视频",
                new ToolPolicy("MEDIA_VIDEO", "storyboard-video-merge", false, false),
                STORYBOARD_TOOLS));

        // ── 生图 / 生视频：技能组门控 ──
        add(specs, gated(AgentScopeImageTools.GENERATE_IMAGE, "生成图片",
                new ToolPolicy("MEDIA_IMAGE", "media-image", true, false),
                IMAGE_TOOLS));
        add(specs, gated(AgentScopeImageTools.GENERATE_IMAGE_BATCH, "批量生成图片",
                new ToolPolicy("MEDIA_IMAGE_BATCH", "media-image-batch", true, false),
                IMAGE_TOOLS));
        add(specs, gated("refineImage", "完善图片",
                new ToolPolicy("MEDIA_IMAGE", "", false, false),
                IMAGE_TOOLS));
        add(specs, gated(AgentScopeVideoTools.GENERATE_VIDEO, "生成视频",
                new ToolPolicy("MEDIA_VIDEO", "media-video", true, false),
                VIDEO_TOOLS));
        add(specs, gated(AgentScopeJourneyTools.PRODUCE_VIDEO_JOURNEY,
                "一键成片（生图 → 视频 / 分镜全流程）",
                new ToolPolicy("MEDIA_VIDEO", "media-video-journey", true, false),
                VIDEO_TOOLS));
        // canvas_create_run 由画布注册链路单独注册（需要 run 适配器）；这里只登记标签与策略。
        add(specs, gated(AgentScopeCanvasRunTools.CREATE_CANVAS_RUN, "创建画布运行",
                new ToolPolicy("WRITE", "canvas-run-create", false, false),
                CANVAS_DESIGN_TOOLS, CANVAS_RUN_TOOLS));

        // ── 创作记忆写入 / 时间线：按技能多组挂载 ──
        add(specs, gated(AgentScopeCreativeTools.UPDATE_CONTEXT, "更新项目创作记忆",
                new ToolPolicy("CREATIVE_CONTEXT_WRITE", "", false, false),
                STORYBOARD_TOOLS, IMAGE_TOOLS, VIDEO_TOOLS, CANVAS_DESIGN_TOOLS));
        add(specs, gated(AgentScopeCreativeTools.SAVE_TIMELINE, "保存时间线",
                new ToolPolicy("TIMELINE_WRITE", "", false, false),
                STORYBOARD_TOOLS, VIDEO_TOOLS));
        add(specs, gated(AgentScopeCreativeTools.GET_TIMELINE, "读取时间线",
                new ToolPolicy("READ", "", false, true),
                STORYBOARD_TOOLS, VIDEO_TOOLS));

        return java.util.Collections.unmodifiableMap(specs);
    }

    private static Spec spec(String name, String label) {
        return new Spec(name, label, null, List.of());
    }

    private static Spec gated(String name, String label, ToolPolicy policy, String... groups) {
        return new Spec(name, label, policy, List.of(groups));
    }

    private static void add(Map<String, Spec> specs, Spec spec) {
        if (specs.put(spec.name(), spec) != null) {
            throw new IllegalStateException("duplicate tool spec: " + spec.name());
        }
    }
}
