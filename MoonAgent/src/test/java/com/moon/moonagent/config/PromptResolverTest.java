package com.moon.moonagent.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * PromptResolver 单元测试 —— skill body 路径与 prompt YAML 路径共用的 {@code {{var}}} 替换语义。
 *
 * <p>覆盖：替换命中、未知占位符原样保留、空 vars / 空模板 / null 模板兜底。不启动 Spring 上下文。
 */
class PromptResolverTest {

    @Test
    void replacesKnownVar() {
        assertEquals("Hi Moon", PromptResolver.resolve("Hi {{name}}", Map.of("name", "Moon")));
    }

    @Test
    void leavesUnknownVarUntouched() {
        assertEquals("x {{missing}}", PromptResolver.resolve("{{a}} {{missing}}", Map.of("a", "x")));
    }

    @Test
    void emptyVarsReturnsTemplateUnchanged() {
        assertEquals("x {{a}}", PromptResolver.resolve("x {{a}}", Map.of()));
    }

    @Test
    void nullVarsReturnsTemplateUnchanged() {
        assertEquals("x {{a}}", PromptResolver.resolve("x {{a}}", null));
    }

    @Test
    void emptyTemplateReturnsEmpty() {
        assertEquals("", PromptResolver.resolve("", Map.of("a", "b")));
    }

    @Test
    void nullTemplateReturnsNull() {
        assertNull(PromptResolver.resolve(null, Map.of("a", "b")));
    }
}
