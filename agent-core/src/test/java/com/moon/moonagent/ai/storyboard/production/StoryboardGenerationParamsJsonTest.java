package com.moon.moonagent.ai.storyboard.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.StoryboardGenerationParams;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生成参数的 JSON 往返。
 *
 * <p>这个对象会写进异步任务的 requestJson、再由后续任务回读——一旦序列化/反序列化不闭合，
 * 表现是「参数静默变空」（实测踩过：{@code isEmpty()} 被当成字段 {@code empty} 写进 JSON，
 * 回读时 Jackson 默认遇到未知字段直接抛异常，调用方 catch 后按空参数处理）。
 */
class StoryboardGenerationParamsJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void roundTripsWithoutLeakingHelperMethods() throws Exception {
        StoryboardGenerationParams params = new StoryboardGenerationParams(
                "doubao-seedream", "2K", "high", 2,
                "MiniMax-H3", "2K", "9:16", 30, List.of(4, 5, 6, 7, 8));

        String json = mapper.writeValueAsString(params);
        assertThat(json)
                .doesNotContain("\"empty\"")
                .contains("\"videoResolution\":\"2K\"")
                .contains("\"allowedDurations\":[4,5,6,7,8]");

        StoryboardGenerationParams back = mapper.readValue(json, StoryboardGenerationParams.class);
        assertThat(back).isEqualTo(params);
    }

    @Test
    void toleratesUnknownFieldsAddedByFutureVersions() throws Exception {
        StoryboardGenerationParams back = mapper.readValue("""
                {"videoResolution":"2K","empty":false,"futureKnob":"x","allowedDurations":[4,6]}
                """, StoryboardGenerationParams.class);

        assertThat(back.videoResolution()).isEqualTo("2K");
        assertThat(back.allowedDurations()).containsExactly(4, 6);
    }

    @Test
    void emptyParamsSerializeAndDeserializeAsEmpty() throws Exception {
        String json = mapper.writeValueAsString(StoryboardGenerationParams.EMPTY);
        StoryboardGenerationParams back = mapper.readValue(json, StoryboardGenerationParams.class);

        assertThat(back.isEmpty()).isTrue();
        assertThat(back.allowedDurations()).isEmpty();
    }
}
