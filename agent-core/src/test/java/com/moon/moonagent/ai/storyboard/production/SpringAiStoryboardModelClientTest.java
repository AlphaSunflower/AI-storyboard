package com.moon.moonagent.ai.storyboard.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.ScriptChange;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpringAiStoryboardModelClientTest {

    @Test
    void appliesUniqueScriptChangesInOrder() {
        String revised = SpringAiStoryboardModelClient.applyChanges(
                "主角抵达车站。主角保持沉默。",
                List.of(
                        new ScriptChange("动机不足", "主角抵达车站。", "主角为寻找妹妹抵达车站。", ""),
                        new ScriptChange("情绪跳跃", "主角保持沉默。", "主角压住焦虑保持沉默。", "")));

        assertThat(revised).isEqualTo("主角为寻找妹妹抵达车站。主角压住焦虑保持沉默。");
    }

    @Test
    void rejectsScriptChangeWhenOriginalTextIsNotUnique() {
        assertThatThrownBy(() -> SpringAiStoryboardModelClient.applyChanges(
                "夜色很深。主角停下。夜色很深。",
                List.of(new ScriptChange("重复", "夜色很深。", "天亮了。", ""))))
                .isInstanceOf(com.storyboard.common.BusinessException.class)
                .hasMessageContaining("无法唯一定位");
    }

    @Test
    void repairsCommonModelJsonArrayDelimiterAndTrailingCommaErrors() throws Exception {
        String repaired = SpringAiStoryboardModelClient.repairJson("""
                {
                  "complete": false,
                  "missingElements": ["结局" "因果推进",],
                  "risks": ["职业失真",],
                  "summary": "需要补全",
                  "expansionPlan": "补足结局"
                }
                """);

        var root = new ObjectMapper().readTree(repaired);
        assertThat(root.path("missingElements")).hasSize(2);
        assertThat(root.path("risks").get(0).asText()).isEqualTo("职业失真");
    }
}
