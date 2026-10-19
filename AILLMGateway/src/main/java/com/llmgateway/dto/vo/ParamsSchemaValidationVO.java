package com.llmgateway.dto.vo;

import java.util.List;

/**
 * 参数 Schema 校验结果。
 *
 * @param valid      是否可保存
 * @param errors     阻断保存的问题（path 定位到具体字段）
 * @param warnings   可保存但值得提示的问题
 * @param normalized 解析后的 JSON（Map/List，解析失败为 null）
 */
public record ParamsSchemaValidationVO(
        boolean valid,
        List<Issue> errors,
        List<Issue> warnings,
        Object normalized) {

    public record Issue(String path, String message) {
    }
}
