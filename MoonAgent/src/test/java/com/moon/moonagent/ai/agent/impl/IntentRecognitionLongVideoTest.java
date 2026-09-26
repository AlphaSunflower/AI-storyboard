package com.moon.moonagent.ai.agent.impl;

import com.moon.moonagent.ai.AgentAiConfigProperties;
import com.moon.moonagent.ai.GatewayModelService;
import com.moon.moonagent.ai.agent.skill.AgentSkill;
import com.moon.moonagent.ai.agent.skill.SkillManager;
import com.moon.moonagent.config.PromptConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IntentRecognitionLongVideoTest {

    @Test
    void twoMinuteDirectVideoRequestRoutesToStoryboardIntent() {
        IntentRecognitionServiceImpl service = new IntentRecognitionServiceImpl(
                mock(ChatClient.Builder.class),
                mock(GatewayModelService.class),
                mock(PromptConfig.class),
                mock(SkillManager.class),
                new AgentAiConfigProperties());

        assertThat(service.recognize("帮我生成一个两分钟的三层别墅视频", List.of()).type())
                .isEqualTo("intent-storyboard");
    }

    @Test
    void storyboardImageAndReviewPhrasesRouteToStoryboardTools() {
        SkillManager skills = mock(SkillManager.class);
        when(skills.allOrdered()).thenReturn(List.of(new AgentSkill(
                "intent-storyboard",
                "storyboard",
                "剧情与分镜",
                "剧情与分镜",
                "text",
                List.of(),
                java.util.Map.of(),
                List.of("分镜|故事板|剧本"),
                20,
                40,
                List.of(),
                java.util.Map.of(),
                List.of())));
        IntentRecognitionServiceImpl service = new IntentRecognitionServiceImpl(
                mock(ChatClient.Builder.class),
                mock(GatewayModelService.class),
                mock(PromptConfig.class),
                skills,
                new AgentAiConfigProperties());
        service.initRules();

        assertThat(service.ruleMatch("把分镜的所有图片生成出来")).isEqualTo("intent-storyboard");
        assertThat(service.ruleMatch("审查分镜")).isEqualTo("intent-storyboard");
        assertThat(service.ruleMatch("查看分镜列表")).isEqualTo("intent-storyboard");
    }
}
