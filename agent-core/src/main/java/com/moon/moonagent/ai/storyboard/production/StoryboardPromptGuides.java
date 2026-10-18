package com.moon.moonagent.ai.storyboard.production;

/**
 * 分镜提示词的两段方法论约束（中文压缩版）。
 *
 * <p>来源：Seedance 2.0 sequence 技能包（MIT，作者 Iamemily2050，
 * https://github.com/Emily2040/seedance-2.0 ）中的 {@code references/directors-read.md}
 * 与 {@code references/shot-table.md}。这里**只借鉴方法论，不搬 surface 层**——
 * 它里面的 Seedance 专属时长预算、{@code [asset:xxx]} 引用标签语法对 MiniMax-H3 / veo / doubao
 * 无效，我们的能力边界一律以网关 {@code /v1/models} 为准。
 *
 * <p>两条核心洞察：
 * <ul>
 *   <li><b>写提示词前先"读戏"</b>：先判定叙事 / 观察表演 / 功能演示，</li>
 *   <li><b>模型会用最常见默认值填满每个空白单元格，而默认永远不好看</b>——
 *       所以机位侧、轴线、光位、动作起止这些格子必须显式填，不能留给模型猜。</li>
 * </ul>
 */
public final class StoryboardPromptGuides {

    private StoryboardPromptGuides() {
    }

    /**
     * 阶段 A（大纲）用：先读戏，再出镜；每行必须带信息增量。
     *
     * <p>不引入新字段（大纲仍只有 镜号 / 一句画面 / 角色 / 地点 / 资产），
     * 只约束这一句"画面"里必须装什么，避免为了方法论把大纲重新撑大、把延迟加回来。
     */
    public static final String DIRECTORS_READ = """

            出镜前先在内部完成「读戏」，不要输出这部分内容：
            - 判定这组镜头属于哪一类：叙事（有一个不可逆的选择/关系变化）、观察表演（动作与节奏本身就是内容）、
              功能演示（观众要看清某个东西怎么用）。三类都要给"观众在这一镜该感到/看懂了什么"一句话。
            - 叙事类必须明确：主角想要什么、这一镜的阻力是什么、这一镜结束时局面发生了什么改变。
            - 非叙事类不要硬编戏剧冲突，也不要拿"情绪"顶替信息。
            每一行的 scriptContent（≤40 字）必须同时装下：谁 + 在哪 + 做什么 + 这一镜多出了什么信息（信息增量）。
            没有信息增量的镜头不要写；宁可少一镜。
            """;

    /**
     * 阶段 B（正式镜头）用：先定平面图与轴线，再逐镜填满每一格。
     *
     * <p>刻意把「机位侧 / 朝向 / 光位」放进 sceneDescription 与提示词，
     * 而不是塞进 cameraMovement——后者已被 {@link StoryboardLexicon} 收敛成字典词，
     * 混入"房间左侧"这类空间描述会破坏枚举。
     */
    public static final String SHOT_TABLE = """

            先把整段戏的"平面图"在内部定下来（不要单独输出）：地标（门、桌、窗、柜台…）、
            谁在哪一侧、朝向什么、主光从哪个方向来、什么颜色。整批镜头共用同一张平面图与同一条轴线。
            然后每一镜都必须显式填满下面每一格，模型会用最常见默认值填补空白，而默认永远不好看：
            - 机位：站在哪个地标的哪一侧、相对轴线是正打还是反打（写进 sceneDescription）；
            - 景别：取字典里的标准词（shotType）；
            - 运镜：取字典里的标准词 + 必要时速度（cameraMovement）；
            - 朝向：人物在这一镜起始朝向与结束时朝向（写进 sceneDescription）；
            - 光位：主光方向与色温变化（写进 sceneDescription 与提示词）；
            - 动作起止：这一镜开始时在哪、结束时停在哪，必须有可验收的终点（写进 imagePrompt / videoPrompt）；
            - 时长：与动作量匹配，动作多给长一点，静帧式收束给最短档。
            imagePrompt 与 videoPrompt 里不要出现"叙事/非叙事/第一镜/情绪曲线/轴线"这类内部标签，
            只写看得见、听得见的东西。
            """;

    /** 廉价词/坏质感词表（anti-slop）：模型最爱加、加完就掉档的那批。词内不允许空格（会拼进逗号串）。 */
    public static final java.util.List<String> ANTI_SLOP_TERMS = java.util.List.of(
            "塑料感", "过饱和", "过度磨皮", "廉价滤镜", "HDR过曝", "死黑",
            "构图呆板", "主体居中呆板", "背景杂乱", "乱码文字", "多余手指",
            "五官错位", "肢体粘连", "镜头光晕滥用", "重复纹理");
}
