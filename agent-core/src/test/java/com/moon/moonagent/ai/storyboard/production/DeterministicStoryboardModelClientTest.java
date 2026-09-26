package com.moon.moonagent.ai.storyboard.production;

import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.StoryboardProductionRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicStoryboardModelClientTest {

    @Test
    void producesTwoPseudoShotsPerSegmentAndFinalShotWithoutExternalCalls() {
        DeterministicStoryboardModelClient client = new DeterministicStoryboardModelClient();
        var request = new StoryboardProductionRequest(
                "u", "p", "c", "", "完整剧情，最终有结局。", "movie", "",
                List.of(), List.of());
        var assessment = client.assessPlot(request, request.assets());
        var optimization = client.optimizeScript(request, assessment, List.of(), request.assets());
        var segment = client.segmentStory(optimization.optimizedScript(), optimization.storyBible()).getFirst();
        var pseudo = client.planPseudoShots(request, segment, optimization.storyBible(), List.of(), request.assets());
        var shot = client.generateShot(new StoryboardProductionModels.ShotGenerationRequest(
                "run", pseudo.getFirst(), optimization.storyBible(), request.assets()));

        assertThat(assessment.complete()).isTrue();
        assertThat(pseudo).hasSize(2);
        assertThat(shot.shotId()).isEqualTo(pseudo.getFirst().shotId());
    }
}
