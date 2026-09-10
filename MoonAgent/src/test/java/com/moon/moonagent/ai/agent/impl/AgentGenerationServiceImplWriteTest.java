package com.moon.moonagent.ai.agent.impl;

import com.moon.moonagent.ai.ImageGenerationService;
import com.moon.moonagent.ai.VideoGenerationService;
import com.moon.moonagent.ai.agent.AgentSceneItem;
import com.moon.moonagent.client.StoryboardClient;
import com.moon.moonagent.entity.Scene;
import com.moon.moonagent.mapper.AgentAssetMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AgentGenerationServiceImplWriteTest {

    @Mock
    private ImageGenerationService imageService;
    @Mock
    private VideoGenerationService videoService;
    @Mock
    private StoryboardClient storyboardClient;
    @Mock
    private AgentAssetMapper agentAssetMapper;

    @InjectMocks
    private AgentGenerationServiceImpl service;

    @Test
    @SuppressWarnings("unchecked")
    void replaceScript_renumbersFromOneAndPreservesDuration() {
        List<AgentSceneItem> input = IntStream.rangeClosed(9, 38)
                .mapToObj(number -> new AgentSceneItem(
                        number, "场景" + number, "图" + number, "视频" + number,
                        "", "固定", "中景", "环境音", 10))
                .toList();

        int count = service.replaceScript("project-1", input);

        ArgumentCaptor<List<Scene>> captor = ArgumentCaptor.forClass(List.class);
        verify(storyboardClient).deleteProjectScenes("project-1");
        verify(storyboardClient).batchInsertScenes(captor.capture());
        assertThat(count).isEqualTo(30);
        assertThat(captor.getValue()).extracting(Scene::getSceneNumber)
                .containsExactlyElementsOf(IntStream.rangeClosed(1, 30).boxed().toList());
        assertThat(captor.getValue()).allSatisfy(scene -> assertThat(scene.getDuration()).isEqualTo(10));
    }
}
