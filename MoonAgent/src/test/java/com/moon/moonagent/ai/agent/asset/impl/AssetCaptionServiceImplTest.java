package com.moon.moonagent.ai.agent.asset.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 素材打标的输出拼装规则：模型给的标准 JSON、带代码围栏的 JSON、以及完全跑偏的纯文本
 * 都必须产出「有信息量的一行描述」——因为这一行是模型唯一能看到的素材语义。
 */
class AssetCaptionServiceImplTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void composesRoleAndNameAndDescriptionFromJson() {
        String raw = """
                {"role":"角色","name":"林知夏","description":"短发女性，黑色风衣，站在雨夜仓库门口"}
                """;

        assertThat(AssetCaptionServiceImpl.compose(raw, mapper))
                .isEqualTo("角色｜林知夏：短发女性，黑色风衣，站在雨夜仓库门口");
    }

    @Test
    void stripsMarkdownCodeFenceBeforeParsing() {
        String raw = """
                ```json
                {"role":"场景","name":"旧仓库","description":"钢架结构与昏黄吊灯"}
                ```
                """;

        assertThat(AssetCaptionServiceImpl.compose(raw, mapper))
                .isEqualTo("场景｜旧仓库：钢架结构与昏黄吊灯");
    }

    @Test
    void fallsBackToRawTextWhenModelDoesNotReturnJson() {
        assertThat(AssetCaptionServiceImpl.compose("一张雨夜霓虹街景参考图\n  偏青蓝色调", mapper))
                .isEqualTo("一张雨夜霓虹街景参考图 偏青蓝色调");
    }

    @Test
    void keepsRoleOnlyWhenNameMissing() {
        assertThat(AssetCaptionServiceImpl.compose(
                "{\"role\":\"道具\",\"description\":\"一把旧木椅\"}", mapper))
                .isEqualTo("道具：一把旧木椅");
    }

    @Test
    void returnsEmptyForBlankOutput() {
        assertThat(AssetCaptionServiceImpl.compose("   ", mapper)).isEmpty();
        assertThat(AssetCaptionServiceImpl.compose(null, mapper)).isEmpty();
    }

    @Test
    void truncatesOverlongOutput() {
        String longText = "角".repeat(300);

        assertThat(AssetCaptionServiceImpl.compose(longText, mapper)).hasSize(120);
    }
}
