package com.moon.moonagent.ai.agent.skill;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class SkillPackageTest {

    private static final Path SKILLS_ROOT = Path.of("src/main/resources/skills");

    @Test
    void loadsOnlyTheSixProductionSkills() throws Exception {
        Set<String> names;
        try (Stream<Path> paths = Files.list(SKILLS_ROOT)) {
            names = paths.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toSet());
        }

        assertThat(names).containsExactlyInAnyOrder(
                "storyboard", "image", "video", "canvas-design", "canvas-run", "general");
    }

    @Test
    void usesStandardFrontmatterAndMoonMetadata() throws Exception {
        Map<String, Object> image = frontmatter(SKILLS_ROOT.resolve("image/SKILL.md"));

        assertThat(image).containsKeys("name", "description", "metadata", "allowed-tools");
        assertThat(image).doesNotContainKeys("triggers", "priority", "menu_order");
        assertThat(String.valueOf(image.get("allowed-tools")))
                .contains("generate_image", "generate_image_batch");
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) image.get("metadata");
        @SuppressWarnings("unchecked")
        Map<String, Object> moon = (Map<String, Object>) metadata.get("moon");
        assertThat(moon).containsEntry("model_role", "image");
    }

    /**
     * 灰度/滚动发布护栏：新工具（整图提交 / 批量连线）缺失时必须降级，而不是中止本轮。
     *
     * <p>真实风险：两个画布技能都把 {@code canvas_graph_apply_plan} 写成默认建图方式。
     * 若部署的 canvas 服务还是旧版本，模型会拿到 {@code Tool not found}；而技能里
     * 「画布工具不可用就立刻停手」的条款会让画布功能在发布窗口内整体不可用。
     * 因此两个技能都必须同时具备：① 新工具名在 {@code allowed-tools}（否则被工具面过滤掉）；
     * ② 明确的「服务端版本较旧 → 继续本轮、退回分批建图」降级分支。
     */
    @Test
    void canvasSkillsDeclareNewToolsAndVersionFallback() throws Exception {
        for (String skill : new String[]{"canvas-design", "canvas-run"}) {
            Path path = SKILLS_ROOT.resolve(skill + "/SKILL.md");
            String content = Files.readString(path, StandardCharsets.UTF_8);

            assertThat(content)
                    .as("%s 必须声明整图提交与批量连线工具", skill)
                    .contains("canvas_graph_apply_plan", "canvas_graph_create_edges");
            // 2026-10-17 决策变更：删除版本降级分支。
            // 原断言要求"必须保留旧服务端的降级分支"，理由是画布服务与 MoonAgent 可能版本不一致；
            // 现改为要求"没有降级路径"——两者必须同版本发布，出现工具缺失是部署问题，必须暴露给人修，
            // 不能用"退回逐节点建图、分批完成"把版本不一致掩盖过去（那会让线上问题静默存活）。
            assertThat(content)
                    .as("%s 必须声明没有版本降级路径（新工具缺失即判定服务不可用，不得退回逐节点建图）", skill)
                    .contains("没有版本降级路径")
                    .doesNotContain("服务端版本较旧", "已按节点分批完成");

            Map<String, Object> frontmatter = frontmatter(path);
            assertThat(String.valueOf(frontmatter.get("allowed-tools")))
                    .as("%s 的 allowed-tools 必须包含新工具，否则模型看不到它们", skill)
                    .contains("canvas_graph_apply_plan", "canvas_graph_create_edges");
        }
    }

    /**
     * 画布删除能力的静态不变量：**不能有任何地方暗示智能体可以删除**。
     *
     * <p>真实事故（2026-10-16 用户截图）：`canvas-design` 的 description 写了"…或删除节点与连线时使用"、
     * 规则 21 又写"删除节点或连线前必须先请求确认"，而工具面里根本没有删除工具。模型据此推断
     * "应该有删除接口"，先后猜了 `canvas_graph_delete_nodes` / `canvas_graph_delete_node` /
     * `canvas_graph_remove_nodes` 三个名字，全部 `Tool not found`，白费两轮才停手。
     *
     * <p>删除只能由用户在画布中手动完成。因此这里锁三件事：① 技能必须显式声明"没有删除能力"；
     * ② 必须给出用户侧的手动操作方式（否则模型只能干说"做不到"）；③ 工具面与正文都不得出现删除工具名。
     */
    @Test
    void canvasDesignSkillNeverImpliesDeleteCapability() throws Exception {
        Path path = SKILLS_ROOT.resolve("canvas-design/SKILL.md");
        String content = Files.readString(path, StandardCharsets.UTF_8);

        assertThat(content)
                .as("必须显式声明智能体没有删除能力")
                .contains("没有删除能力")
                .as("必须给出用户手动删除的方式，而不是只说做不到")
                .contains("Ctrl+A", "右键", "Delete", "Backspace", "Ctrl+D");
        assertThat(content)
                .as("不得再把删除写成智能体的能力（旧的规则 21 措辞）")
                .doesNotContain("删除节点或连线前必须先读取目标");

        String allowedTools = String.valueOf(frontmatter(path).get("allowed-tools"));
        assertThat(allowedTools)
                .as("allowed-tools 里不得出现任何删除工具名（正文可以点名它们'不存在'，工具面不行）")
                .doesNotContain("delete", "remove");
    }

    /**
     * 两段式流程：本轮只搭结构，生成要等用户确认。
     *
     * <p>真实背景：`canvas_execute_node` / `canvas_execute_graph` 曾在自动放行清单里，智能体会在
     * 同一轮把整轮生成跑掉（每张图 30–60s、每段视频 2–5min），用户既没有"先只看结构"的机会，
     * 也要一直等着。现在执行已改为 ASK（见 `MoonAgentRuntimePermissionTest`），技能负责告知模型别抢跑。
     */
    @Test
    void canvasDesignSkillKeepsGenerationBehindUserConfirmation() throws Exception {
        String content = Files.readString(
                SKILLS_ROOT.resolve("canvas-design/SKILL.md"), StandardCharsets.UTF_8);

        assertThat(content)
                .as("必须写明本轮只搭结构，并在结尾主动询问是否生成")
                .contains("只搭结构", "需要我把工作流里的图片");
    }

    /**
     * 一步建图：素材必须内联在 plan 里，而不是"先单独导入一次"。
     *
     * <p>真实背景：服务端 `apply_plan` 已支持内联 `media`（一次调用 = 节点 + 素材 + 结果槽 +
     * 连线 + 布局，只 +1 revision）。但如果提示词仍要求"先用 canvas_graph_import_media_batch
     * 导入全部素材"，模型就会走两步——每多一步就是几十秒的模型思考，服务端这笔优化等于白做。
     */
    @Test
    void canvasDesignSkillKeepsMediaInlineInThePlan() throws Exception {
        String content = Files.readString(
                SKILLS_ROOT.resolve("canvas-design/SKILL.md"), StandardCharsets.UTF_8);

        assertThat(content)
                .as("必须写明建图时素材内联在 plan 的 media 字段里")
                .contains("建图时素材直接写在 plan 里", "mediaId 与 url 二选一");
        assertThat(content)
                .as("不得再要求建图前先跑一次独立导入（那会把一次建图变成两步）")
                .doesNotContain("必须使用 canvas_graph_import_media_batch 在同一个 mediaItems 数组中一次导入全部");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> frontmatter(Path path) throws Exception {
        String content = Files.readString(path, StandardCharsets.UTF_8);
        int start = content.indexOf("---");
        int end = content.indexOf("\n---", start + 3);
        assertThat(start).isZero();
        assertThat(end).isGreaterThan(start);
        return new Yaml().load(content.substring(start + 3, end));
    }
}
