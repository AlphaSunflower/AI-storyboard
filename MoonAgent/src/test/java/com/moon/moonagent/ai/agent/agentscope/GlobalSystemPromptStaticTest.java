package com.moon.moonagent.ai.agent.agentscope;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全局系统提示词的静态不变量。
 *
 * <p>这些断言防的是提示词腐化的四类真实事故：点名运行时可能缺席的 Skill 工具组、
 * 留下未替换的 {@code {{}}} 占位符、引用不存在的工具名、以及点名的工具名互相冲突。
 */
class GlobalSystemPromptStaticTest {

    private static final String PROMPT_LOCATION = "agent-assets/global-system-prompt.txt";

    /**
     * 运行时可能整体缺席的工具名：图片/视频由 Skill 工具组注册，画布依赖本地 Bridge 进程，
     * 沙箱与网页检索受配置开关约束。全局提示词静态点名它们会在能力缺席时制造虚假承诺。
     */
    private static final Set<String> RUNTIME_OPTIONAL_TOOLS = Set.of(
            "generate_image", "generate_image_batch", "refineImage",
            "generate_video",
            "canvas_graph_get_state", "canvas_graph_create_nodes", "canvas_graph_create_edge",
            "canvas_graph_import_media", "canvas_graph_import_media_batch",
            "canvas_graph_layout_graph",
            "canvas_execute_node", "canvas_execute_graph",
            "canvas_get_execution", "canvas_cancel_execution",
            "canvas_prepare_session",
            "run_python_script",
            "web_search", "web_fetch");

    private static final Pattern SOURCE_IDENTIFIER =
            Pattern.compile("(?<![\\w\"])[a-z][a-z0-9]*(?:_[a-z0-9]+)+");

    @Test
    void promptMustNotNameRuntimeOptionalTools() throws IOException {
        String prompt = readClasspath(PROMPT_LOCATION);

        for (String tool : RUNTIME_OPTIONAL_TOOLS) {
            assertThat(prompt)
                    .as("全局提示词不得静态点名运行时可能缺席的工具 %s；"
                            + "可用性由 <capabilities> 注入块声明", tool)
                    .doesNotContain(tool);
        }
    }

    @Test
    void promptMustNotCarryUnresolvedTemplatePlaceholders() throws IOException {
        String prompt = readClasspath(PROMPT_LOCATION);

        // Nacos 分支不做变量替换，占位符会原样进入模型上下文。
        assertThat(prompt).doesNotContain("{{").doesNotContain("}}");
    }

    @Test
    void promptMustNotReferenceUnknownToolLikeIdentifiers() throws IOException {
        String prompt = readClasspath(PROMPT_LOCATION);
        Set<String> known = knownToolNames();

        Matcher matcher = SOURCE_IDENTIFIER.matcher(prompt);
        Set<String> unknown = new LinkedHashSet<>();
        while (matcher.find()) {
            String candidate = matcher.group();
            if (candidate.length() > 60) continue;
            if (candidate.equals("taskId")) continue;
            if (known.contains(candidate)) continue;
            if (isProseIdentifier(candidate)) continue;
            unknown.add(candidate);
        }

        assertThat(unknown)
                .as("提示词出现了无法解析为已注册工具的蛇形标识符，属于「点名不存在的东西」")
                .isEmpty();
    }

    @Test
    void namedSubagentsMustResolveInRegistry() {
        List<String> registered = AgentScopeSubagents.readOnlyExecutors().stream()
                .map(io.agentscope.harness.agent.subagent.SubagentDeclaration::getName)
                .toList();

        assertThat(registered).containsExactlyInAnyOrder(
                "creative-planner", "prompt-director", "reference-curator",
                "continuity-editor");
    }

    @Test
    void skillsMustNotReferenceAssetsOutsideTheirOwnPackage() throws IOException {
        // Nacos 发布时每个 Skill 目录独立打包（publish_assets.py 要求 SKILL.md 且只收本目录文件），
        // 因此跨目录引用在 Nacos 版本里必然断链——这类引用只能在发布后才暴露。
        Path skillsRoot = Path.of("src", "main", "resources", "skills");
        if (!Files.isDirectory(skillsRoot)) return;

        Pattern crossDirectoryReference = Pattern.compile("skills/[a-z0-9-]+/[A-Za-z0-9._-]+\\.md");
        List<Path> skillFiles;
        try (var stream = Files.walk(skillsRoot)) {
            skillFiles = stream.filter(path -> path.getFileName().toString().equals("SKILL.md"))
                    .toList();
        }

        for (Path skillFile : skillFiles) {
            String ownSkill = skillFile.getParent().getFileName().toString();
            String body = Files.readString(skillFile, StandardCharsets.UTF_8);
            Matcher matcher = crossDirectoryReference.matcher(body);
            while (matcher.find()) {
                String referenced = matcher.group();
                String targetSkill = referenced.split("/")[1];
                assertThat(targetSkill)
                        .as("%s 引用了其他 Skill 包内的资产 %s；发布到 Nacos 后会断链，"
                                + "应改为自包含或在共享清单中显式发布", skillFile, referenced)
                        .isEqualTo(ownSkill);
            }
        }
    }

    @Test
    void skillsMustOnlyDeclareToolsThatExistInTheirToolGroups() throws IOException {
        // image/video 的工具由 Skill 工具组提供，名字必须与注册常量一致，否则激活后无工具可用。
        Path imageSkill = Path.of("src", "main", "resources", "skills", "image", "SKILL.md");
        if (!Files.isRegularFile(imageSkill)) return;
        String body = Files.readString(imageSkill, StandardCharsets.UTF_8);

        assertThat(body).contains(AgentScopeImageTools.GENERATE_IMAGE);
        assertThat(body).contains(AgentScopeImageTools.GENERATE_IMAGE_BATCH);
    }

    private static boolean isProseIdentifier(String candidate) {
        // 允许的自然语言复合词（非工具名）白名单，避免误报中文提示词里的约定俗成写法。
        return Set.of("task_id", "output_dir", "sub_agent", "read_only").contains(candidate);
    }

    /**
     * 已注册工具名的并集：框架工具 + 自研 AgentScope 工具常量 + 常驻注册的工具。
     * 只扫描声明常量，不从 Spring 上下文启动，保证测试无外部依赖。
     */
    private static Set<String> knownToolNames() throws IOException {
        Set<String> names = new LinkedHashSet<>(FrameworkToolAuditMiddleware.AUDITED_TOOLS);
        names.addAll(Set.of(
                AgentScopeSessionTools.LIST_SESSION_ASSETS,
                AgentScopeSessionTools.RESOLVE_REFERENCE_ASSETS,
                AgentScopeSessionTools.GET_TASK_STATUS,
                AgentScopeSessionTools.LIST_CONVERSATION_TASKS,
                AgentScopeSessionTools.GET_AGENT_TASK,
                AgentScopeStructuredTools.BUILD_MEDIA_SPEC,
                AgentScopeStructuredTools.ASSESS_GENERATION_RISK,
                AgentScopeAssetGroupTools.LIST_ASSET_GROUPS,
                AgentScopeInteractionTools.REQUEST_HUMAN_INPUT,
                AgentScopeInteractionTools.CHOOSE_STORYBOARD_COMMIT,
                AgentScopeWorkflowTools.RESOLVE_WORKFLOW,
                AgentScopeVideoMergeTools.MERGE_STORYBOARD_VIDEOS,
                AgentScopeDocumentTools.LIST_CONVERSATION_DOCUMENTS,
                AgentScopeDocumentTools.READ_DOCUMENT,
                AgentScopeCreativeTools.GET_CONTEXT,
                AgentScopeCreativeTools.UPDATE_CONTEXT,
                AgentScopeCreativeTools.EXTRACT_CONSTRAINTS,
                AgentScopeCreativeTools.REVIEW_MEDIA,
                AgentScopeCreativeTools.LIST_CRITIQUES,
                AgentScopeCreativeTools.SAVE_TIMELINE,
                AgentScopeCreativeTools.GET_TIMELINE,
                AgentScopeStoryboardTools.START_STORYBOARD,
                AgentScopeStoryboardTools.REVISE_STORYBOARD,
                AgentScopeStoryboardTools.APPROVE_STORYBOARD,
                AgentScopeStoryboardTools.GENERATE_STORYBOARD_IMAGE,
                AgentScopeStoryboardTools.GENERATE_STORYBOARD_VIDEO,
                AgentScopeStoryboardTools.GENERATE_STORYBOARD_VIDEOS,
                AgentScopeStoryboardTools.GENERATE_STORYBOARD_IMAGES,
                AgentScopeStoryboardTools.UPSERT_STORYBOARD_SHOT,
                AgentScopeStoryboardTools.LIST_STORYBOARD_DRAFT,
                AgentScopeStoryboardTools.LIST_PROJECT_STORYBOARD,
                AgentScopeStoryboardTools.UPDATE_PROJECT_STORYBOARD_SHOT,
                AgentScopeStoryboardTools.DELETE_STORYBOARD_DRAFT_SHOT,
                AgentScopeStoryboardTools.REORDER_STORYBOARD_DRAFT,
                AgentScopeStoryboardTools.DELETE_PROJECT_STORYBOARD_SHOT,
                AgentScopeStoryboardTools.CLEAR_PROJECT_STORYBOARD,
                AgentScopeImageTools.GENERATE_IMAGE,
                AgentScopeImageTools.GENERATE_IMAGE_BATCH,
                AgentScopeVideoTools.GENERATE_VIDEO,
                AgentScopeCanvasTools.PREPARE_CANVAS_SESSION,
                AgentScopeCanvasRunTools.CREATE_CANVAS_RUN,
                "canvas_execute_node", "canvas_execute_graph",
                "canvas_get_execution", "canvas_cancel_execution",
                "refineImage", "run_python_script",
                "canvas_graph_get_state", "canvas_graph_create_nodes",
                "canvas_graph_create_edge", "canvas_graph_import_media",
                "canvas_graph_import_media_batch", "canvas_graph_layout_graph"));
        return names;
    }

    private static String readClasspath(String location) throws IOException {
        try (var input = new ClassPathResource(location).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
