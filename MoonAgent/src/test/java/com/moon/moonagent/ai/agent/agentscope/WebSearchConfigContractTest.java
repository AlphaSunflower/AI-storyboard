package com.moon.moonagent.ai.agent.agentscope;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ai.agent.agentscope.web-search} 的绑定契约：配置键必须与
 * {@link AgentScopeProperties.WebSearch} 的属性名一一对应，中文注释里写错 key 是
 * 静默失效（工具不注册但不报错）的常见来源。
 */
class WebSearchConfigContractTest {

    private static final String RESOURCE = "src/main/resources/application.yml";
    /** 键 → 必须出现的 env 变量名。 */
    private static final String[][] KEYS = {
            {"enabled:", "AGENTSCOPE_WEB_SEARCH_ENABLED"},
            {"provider:", "AGENTSCOPE_WEB_SEARCH_PROVIDER"},
            {"searxng-url:", "AGENTSCOPE_SEARXNG_URL"},
            {"searxng-language:", "AGENTSCOPE_SEARXNG_LANGUAGE"},
            {"base-url:", "AGENTSCOPE_WEB_SEARCH_BASE_URL"},
            {"api-key:", "AGENTSCOPE_WEB_SEARCH_API_KEY"},
            {"model:", "AGENTSCOPE_WEB_SEARCH_MODEL"},
            {"max-results:", "AGENTSCOPE_WEB_SEARCH_MAX_RESULTS"},
            {"timeout:", "AGENTSCOPE_WEB_SEARCH_TIMEOUT"},
            {"max-uses:", "AGENTSCOPE_WEB_SEARCH_MAX_USES"},
            {"max-tokens:", "AGENTSCOPE_WEB_SEARCH_MAX_TOKENS"},
            {"cache-ttl:", "AGENTSCOPE_WEB_SEARCH_CACHE_TTL"},
            {"cache-max-entries:", "AGENTSCOPE_WEB_SEARCH_CACHE_MAX_ENTRIES"},
    };

    @Test
    void webSearchSectionDeclaresEveryBoundKey() throws Exception {
        Path path = Path.of(RESOURCE);
        if (!Files.isRegularFile(path)) return;
        String yaml = Files.readString(path, StandardCharsets.UTF_8);

        int start = yaml.indexOf("web-search:");
        assertThat(start).as("application.yml 必须保留 web-search 配置段").isGreaterThan(0);
        String section = yaml.substring(start, Math.min(yaml.length(), start + 3_000));

        for (String[] key : KEYS) {
            assertThat(section)
                    .as("缺少 web-search.%s，对应属性无法被环境变量覆盖", key[0])
                    .contains(key[0]);
            assertThat(section)
                    .as("web-search.%s 未接 %s", key[0], key[1])
                    .contains(key[1]);
        }
    }

    @Test
    void defaultProviderIsSelfHostedAndKeepsTheMessagesBaseForDeepseek() throws Exception {
        Path path = Path.of(RESOURCE);
        if (!Files.isRegularFile(path)) return;
        String yaml = Files.readString(path, StandardCharsets.UTF_8);

        // 默认自托管：不填任何 key 也能用。
        assertThat(yaml).contains("provider: ${AGENTSCOPE_WEB_SEARCH_PROVIDER:searxng}");
        // DeepSeek 后端仍保留 Anthropic 兼容基址：原生搜索只存在于 Messages 协议上，
        // 默认值若被改成 chat-completions 基址，这条路径会整片 404（本次事故的形态）。
        assertThat(yaml).contains("https://api.deepseek.com/anthropic/v1");
        assertThat(yaml).doesNotContain("firecrawl");
    }
}
