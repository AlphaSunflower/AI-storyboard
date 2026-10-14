package com.moon.moonagent.ai.agent.agentscope;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTML → Markdown 转换的最小契约：结构保留、脚本噪声剔除、实体解码、
 * 以及畸形深嵌套页面不失控（撞深度上限时明确标记不完整）。
 */
class WebHtmlMarkdownTest {

    @Test
    void keepsStructureAndDropsScriptContent() {
        String html = """
                <html><head><title>Doc title</title><style>b{color:red}</style></head>
                <body><h2>Section</h2><p>Hello <b>world</b></p>
                <ul><li>one</li><li>two</li></ul>
                <script>alert('x')</script></body></html>
                """;

        WebHtmlMarkdown.Result result = WebHtmlMarkdown.convert(html);

        assertThat(result.title()).isEqualTo("Doc title");
        assertThat(result.complete()).isTrue();
        // <b> 是行内强调：内容按需要才包 ** —— 紧跟在 "Hello " 之后不会粘成 "Hello**world**"。
        assertThat(result.markdown())
                .contains("## Section")
                .contains("Hello **world**")
                .contains("- one")
                .contains("- two")
                .doesNotContain("alert")
                .doesNotContain("color:red");
    }

    @Test
    void rendersLinksImagesListsAndEntities() {
        String html = """
                <p>See <a href="https://example.com/a">docs</a> &amp; <a href="/rel">home</a></p>
                <ol><li>first</li><li>second</li></ol>
                <p><img src="https://example.com/i.png" alt="pic"></p>
                <p>Caf&eacute; &nbsp; 5 &lt; 6 &#65;</p>
                <pre><code>let a = 1;</code></pre>
                """;

        WebHtmlMarkdown.Result result = WebHtmlMarkdown.convert(html);
        String markdown = result.markdown();

        assertThat(markdown)
                .contains("(https://example.com/a)")
                .contains("(/rel)")
                .contains("1. first")
                .contains("2. second")
                .contains("![pic](https://example.com/i.png)")
                .contains("Café 5 < 6 A")
                .contains("```")
                .contains("let a = 1;");
    }

    @Test
    void inlineLinkBoundariesKeepTheirSpaces() {
        String html = "<p>See <a href=\"/x\">docs</a> &amp; <a href=\"/r\">home</a></p>";

        String markdown = WebHtmlMarkdown.convert(html).markdown();

        // 两侧空格来自原文，去掉就会把 docs 和 & 粘成一个词。
        assertThat(markdown).isEqualTo("See docs (/x) & home (/r)");
    }

    @Test
    void tableCellsKeepTheirBoundaries() {
        String html = """
                <table><tr><th>Name</th><th>Value</th></tr>
                <tr><td>alpha</td><td>1</td></tr></table>
                """;

        String markdown = WebHtmlMarkdown.convert(html).markdown();

        assertThat(markdown)
                .contains("Name")
                .contains("Value")
                .contains("alpha")
                .contains("1");
    }

    @Test
    void malformedNestingIsBoundedAndFlaggedInsteadOfExploding() {
        // 超过 MAX_DEPTH 后**不再向下遍历**：这是保守前缀语义 —— 深层内容会被丢弃，
        // 因此 complete=false 是调用方唯一能依赖的信号（不能指望深层文本还在）。
        // 生成量要远超 jsoup 自身的解析截断（实测约 510 层），否则这条守卫测不到。
        StringBuilder html = new StringBuilder("<div>start ");
        for (int depth = 0; depth < 3_000; depth++) html.append("<div>");
        html.append("deep");

        WebHtmlMarkdown.Result result = WebHtmlMarkdown.convert(html.toString());

        assertThat(result.complete()).isFalse();
        assertThat(result.markdown()).isEqualTo("start");
    }

    @Test
    void unclosedScriptDoesNotLeakItsMarkupIntoTheOutput() {
        String html = "<p>visible</p><script>var link = '<a href=\"https://evil.test\">x</a>';";

        String markdown = WebHtmlMarkdown.convert(html).markdown();

        assertThat(markdown).contains("visible").doesNotContain("evil.test");
    }

    @Test
    void adjacentTextNodesFromEntitiesDoNotProduceDoubledSpaces() {
        // jsoup 会把 "Café &nbsp; 5" 拆成相邻文本节点，各自折叠会叠出双空格。
        String markdown = WebHtmlMarkdown.convert("<p>Caf&eacute; &nbsp; 5</p>").markdown();

        assertThat(markdown).isEqualTo("Café 5");
    }

    @Test
    void nestedListsKeepTheirLevelsAndNumbering() {
        String html = """
                <ul><li>one<ul><li>nested a</li><li>nested b</li></ul></li><li>two</li></ul>
                """;

        String markdown = WebHtmlMarkdown.convert(html).markdown();

        assertThat(markdown)
                .contains("- one")
                .contains("  - nested a")
                .contains("  - nested b")
                .contains("- two");
    }

    @Test
    void plainTextPassesThroughWithCollapsedWhitespace() {
        String markdown = WebHtmlMarkdown.convert("just   some\n\ntext").markdown();

        assertThat(markdown).isEqualTo("just some text");
    }
}
