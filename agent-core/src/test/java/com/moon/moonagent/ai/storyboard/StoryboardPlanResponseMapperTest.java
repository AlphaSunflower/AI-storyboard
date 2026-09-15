package com.moon.moonagent.ai.storyboard;

import com.storyboard.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StoryboardPlanResponseMapperTest {

    private final StoryboardPlanResponseMapper mapper = new StoryboardPlanResponseMapper();

    @Test
    void mapsStructuredResponseInOrdinalOrderWithStableIds() {
        String raw = """
                {
                  "shots": [
                    {
                      "shotId": "shot-b",
                      "segmentId": "segment-2",
                      "shotOrdinal": 2,
                      "sceneDescription": "Second beat",
                      "shotType": "close-up",
                      "cameraMovement": "push in",
                      "durationSeconds": 3,
                      "imagePrompt": "second image prompt",
                      "videoPrompt": "second video prompt",
                      "negativePrompt": "blur",
                      "referenceHints": ["hero"]
                    },
                    {
                      "shotId": "shot-a",
                      "segmentId": "segment-1",
                      "shotOrdinal": 1,
                      "sceneDescription": "First beat",
                      "shotType": "wide",
                      "cameraMovement": "static",
                      "durationSeconds": 4,
                      "imagePrompt": "first image prompt",
                      "videoPrompt": "first video prompt",
                      "negativePrompt": "",
                      "referenceHints": []
                    }
                  ]
                }
                """;

        List<StoryboardShot> shots = mapper.toShots(raw);

        assertEquals(List.of("shot-a", "shot-b"), shots.stream().map(StoryboardShot::shotId).toList());
        assertEquals(List.of(1, 2), shots.stream().map(StoryboardShot::shotOrdinal).toList());
        assertEquals("First beat", shots.getFirst().sceneDescription());
        assertEquals("second video prompt", shots.getLast().videoPrompt());
    }

    @Test
    void fillsMissingStableIdentityFieldsDeterministically() {
        String raw = """
                {
                  "shots": [
                    {
                      "segmentId": "",
                      "shotOrdinal": 1,
                      "sceneDescription": "Only shot",
                      "shotType": "medium",
                      "cameraMovement": "pan",
                      "durationSeconds": 2,
                      "imagePrompt": "image",
                      "videoPrompt": "video",
                      "negativePrompt": "",
                      "referenceHints": null
                    }
                  ]
                }
                """;

        StoryboardShot first = mapper.toShots(raw).getFirst();
        StoryboardShot second = mapper.toShots(raw).getFirst();

        assertEquals("shot-1", first.shotId());
        assertEquals("segment-1", first.segmentId());
        assertEquals(first.shotId(), second.shotId());
        assertEquals(first.segmentId(), second.segmentId());
        assertEquals(List.of(), first.referenceHints());
    }

    @Test
    void rejectsMalformedOrIncompleteModelOutput() {
        assertThrows(BusinessException.class, () -> mapper.toShots("not json"));
        assertThrows(BusinessException.class, () -> mapper.toShots("""
                {"shots":[{"shotOrdinal":1,"sceneDescription":"missing fields"}]}
                """));
    }
}
