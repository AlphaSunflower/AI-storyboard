package com.storyboard.common.generation;

import java.util.List;

/** 生成记录终态或任务关联更新。 */
public record GenerationRecordUpdate(
        String status,
        String url,
        List<String> resultUrls,
        String error,
        Long durationMs
) {

    public GenerationRecordUpdate {
        resultUrls = resultUrls == null ? List.of() : List.copyOf(resultUrls);
    }

    public static GenerationRecordUpdate completed(String url, List<String> resultUrls, Long durationMs) {
        return new GenerationRecordUpdate("completed", url, resultUrls, null, durationMs);
    }

    public static GenerationRecordUpdate failed(String error, Long durationMs) {
        return new GenerationRecordUpdate("failed", null, List.of(), error, durationMs);
    }
}
