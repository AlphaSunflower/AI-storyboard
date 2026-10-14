package com.moon.moonagent.ai.agent.agentscope;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * HTML → Markdown 转换器（{@code web_fetch} 用），对齐 DSH 的
 * {@code @deepseek-ai/dsh-tool-web/src/fetch.ts} 意图：抓到的页面交给模型前先转成
 * Markdown，而不是把整页标签塞进上下文。
 *
 * <p>上游客户端用 turndown + domino DOM + GFM 插件；本模块用 jsoup 做同类的事：
 * 解析成 DOM → 按文档顺序生成 Markdown。相比此前的手写正则实现，真正建 DOM
 * 才能正确处理畸形标签、隐式嵌套（{@code <li>} 自动补 {@code <ul>}）和未闭合的
 * {@code script} —— 正则版在这些形态上会漏内容或把脚本正文当正文。
 *
 * <p>安全：输出是**重新生成的 Markdown**，不搬运任何原始属性，因此不存在把事件处理器
 * 或内联脚本带进模型上下文的问题；{@link #SKIP_TAGS} 里的元素（脚本、样式、框架、
 * 表单控件等）连子节点一起跳过。
 *
 * <p>稳健性：遍历带深度上限 {@link #MAX_DEPTH} 并按文档顺序递归下降，畸形深嵌套页面
 * 不会失控；撞上限时返回 {@code complete=false}，调用方据此知道输出是保守前缀。
 */
final class WebHtmlMarkdown {

    /**
     * 元素嵌套深度上限。
     *
     * <p>取值依据（实测，不是猜的）：jsoup 自己的树构建器在解析阶段就把嵌套截到约 510 层
     * （3000 层 {@code <div>} 的输入，最终 DOM 深度是 510），因此**阈值必须明显小于它**，
     * 否则这条守卫永远不会触发、等于没有。256 远高于真实页面的几十层，又留出足够余量，
     * 使「撞上限 → complete=false」在畸形深嵌套输入上真的会发生。
     */
    static final int MAX_DEPTH = 256;

    /** 不产生正文、或必须整块丢弃的元素（含子节点）。 */
    private static final List<String> SKIP_TAGS = List.of(
            "html", "head", "title", "meta", "link", "base", "template",
            "script", "style", "noscript", "svg", "canvas", "iframe", "object", "embed",
            "applet", "map", "area", "col", "colgroup", "form", "input", "button",
            "select", "option", "optgroup", "textarea", "datalist", "output", "progress",
            "meter", "audio", "video", "source", "track", "dialog", "menu");

    /** 行内元素：不单独成段，只在需要时补边界空格。 */
    private static final List<String> INLINE_TAGS = List.of(
            "a", "abbr", "b", "bdi", "bdo", "cite", "code", "data", "del", "dfn", "em", "i",
            "ins", "kbd", "mark", "q", "rp", "rt", "ruby", "s", "samp", "small", "span",
            "strong", "sub", "sup", "time", "u", "var", "wbr", "font", "big", "nobr");

    /** 这些行内标签用 Markdown 强调标记包起来（仅当内容为纯文本时）。 */
    private static final List<String> BOLD_TAGS = List.of("strong", "b");
    private static final List<String> ITALIC_TAGS = List.of("em", "i");

    /** 保留原始空白的元素：内部不做空白折叠。 */
    private static final List<String> PRESERVE_TAGS = List.of("pre", "textarea");

    /** 列表容器。 */
    private static final List<String> LIST_TAGS = List.of("ul", "ol");

    private WebHtmlMarkdown() {
    }

    /**
     * 解析结果：Markdown 正文、页面标题，以及是否因嵌套过深提前收尾。
     *
     * @param markdown 转换后的正文（未按 {@code max_chars} 截断，截断由调用方负责）
     * @param title    {@code <title>} 文本，缺失时为空串
     * @param complete {@code false} 表示撞到 {@link #MAX_DEPTH} 而停止向下遍历：
     *                 此时输出是**保守前缀**，超深子树的内容已被丢弃（不是保留了但没标记）
     */
    record Result(String markdown, String title, boolean complete) {
    }

    /** 从原始 HTML 抽取 {@code <title>}；无标题时返回空串。 */
    static String extractTitle(String html) {
        if (html == null || html.isBlank()) return "";
        return Jsoup.parse(html).title().replaceAll("\\s+", " ").trim();
    }

    /**
     * 把一个完整 HTML 文档转成 Markdown。
     *
     * @param html 原始 HTML，可为 {@code null}
     * @return 转换结果；入参为空时返回空结果
     */
    static Result convert(String html) {
        if (html == null || html.isBlank()) return new Result("", "", true);
        Document document = Jsoup.parse(html);
        String title = document.title().replaceAll("\\s+", " ").trim();
        Writer writer = new Writer();
        writer.walk(document.body(), 1);
        return new Result(writer.tidy(), title, writer.complete);
    }

    /** 实体解码：交给 jsoup 的解析器工具（覆盖全部命名/数字实体），未知实体原样保留。 */
    static String decodeEntities(String value) {
        if (value == null) return "";
        return Parser.unescapeEntities(value, false);
    }

    /**
     * 单次转换的写状态：输出缓冲、列表栈与「是否撞到深度上限」。
     *
     * <p>所有文本都经 {@link #appendText} 写入，空白归一只有这一处，因此不会出现
     * {@code Helloworld} 粘连或行尾空格。
     */
    private static final class Writer {

        private final StringBuilder out = new StringBuilder(16 * 1024);
        /** 列表栈：进入 ul/ol 时压入，用于生成 {@code -} / {@code 1.} 与嵌套缩进。 */
        private final Deque<ListState> lists = new ArrayDeque<>();
        private boolean complete = true;

        /** 遍历一个节点；{@code depth} 超过上限时停止深入并标记不完整。 */
        private void walk(Node node, int depth) {
            if (node instanceof TextNode text) {
                appendText(text.getWholeText());
                return;
            }
            if (!(node instanceof Element element)) return;
            if (depth > MAX_DEPTH) {
                complete = false;
                return;
            }
            String tag = element.tagName().toLowerCase(Locale.ROOT);
            if (SKIP_TAGS.contains(tag)) return;
            if (PRESERVE_TAGS.contains(tag)) {
                appendPreserved(element);
                return;
            }
            if ("br".equals(tag)) {
                newline();
                return;
            }
            if (INLINE_TAGS.contains(tag)) {
                inline(element, tag, depth);
                return;
            }
            block(element, tag, depth);
        }

        /** 块级元素：按标签补段前段后边界，并处理列表/表格/代码块等结构。 */
        private void block(Element element, String tag, int depth) {
            if (tag.matches("h[1-6]")) {
                blankLine();
                out.append("#".repeat(Integer.parseInt(tag.substring(1)))).append(' ');
                children(element, depth);
                blankLine();
                return;
            }
            switch (tag) {
                case "li" -> {
                    newline();
                    int level = Math.max(0, lists.size() - 1);
                    out.append("  ".repeat(level));
                    ListState state = lists.peek();
                    if (state != null && state.ordered) {
                        state.count++;
                        out.append(state.count).append(". ");
                    } else {
                        out.append("- ");
                    }
                    children(element, depth);
                    newline();
                }
                case "blockquote" -> {
                    blankLine();
                    out.append("> ");
                    children(element, depth);
                    blankLine();
                }
                case "td", "th" -> {
                    if (!"th".equals(tag)) out.append('|');
                    out.append(' ');
                    children(element, depth);
                    out.append(" |");
                }
                case "tr" -> {
                    newline();
                    children(element, depth);
                    newline();
                }
                case "hr" -> blankLine().append("---\n");
                case "img" -> image(element);
                default -> {
                    if (LIST_TAGS.contains(tag)) {
                        blankLine();
                        lists.push(new ListState("ol".equals(tag)));
                        children(element, depth);
                        if (!lists.isEmpty()) lists.pop();
                        blankLine();
                        return;
                    }
                    blankLine();
                    children(element, depth);
                    blankLine();
                }
            }
        }

        /** 行内元素：只做必要的边界空格与强调/链接标记。 */
        private void inline(Element element, String tag, int depth) {
            if ("img".equals(tag)) {
                image(element);
                return;
            }
            if ("a".equals(tag)) {
                String text = element.text().strip();
                String href = element.attr("href").trim();
                if (!text.isEmpty()) {
                    appendText(text);
                    if (needsParenSpace()) out.append(' ');
                }
                if (!href.isBlank() && !href.startsWith("#")) out.append('(').append(href).append(')');
                return;
            }
            // 强调：只在「内容全是文本」时包 ** / *，避免把已经是链接或混合内容的子树包坏。
            if (BOLD_TAGS.contains(tag) || ITALIC_TAGS.contains(tag)) {
                String text = element.text();
                if (text.isBlank()) return;
                boolean plainText = true;
                for (Node child : element.childNodes()) {
                    if (!(child instanceof TextNode)) {
                        plainText = false;
                        break;
                    }
                }
                if (plainText) {
                    String marker = BOLD_TAGS.contains(tag) ? "**" : "*";
                    appendText(marker + decodeEntities(text).strip() + marker);
                    return;
                }
            }
            children(element, depth);
        }

        /** 追加链接目标前是否需要补空格（当前输出非行首且末尾不是空白或左括号）。 */
        private boolean needsParenSpace() {
            if (out.length() == 0) return false;
            char last = out.charAt(out.length() - 1);
            return last != ' ' && last != '\n' && last != '(';
        }

        /** 图片转 Markdown 图片语法；无 src 时退化为 alt 文本。 */
        private void image(Element element) {
            String source = element.attr("src").trim();
            String alt = element.attr("alt").strip();
            if (source.isBlank()) {
                appendText(alt);
                return;
            }
            if (needsSpace()) out.append(' ');
            out.append("![").append(alt.replace("]", "\\]")).append("](").append(source).append(')');
        }

        /** 按文档顺序遍历子节点。 */
        private void children(Element element, int depth) {
            for (Node child : element.childNodes()) walk(child, depth + 1);
        }

        /** 保留空白的内容（pre/textarea）：原样输出成围栏代码块。 */
        private void appendPreserved(Element element) {
            String text = element.wholeText();
            if (text.isBlank()) return;
            blankLine().append("```\n").append(text.strip()).append("\n```\n");
            blankLine();
        }

        /**
         * 文本唯一写入口：解码实体、折叠空白，并按原文边界决定是否补空格。
         *
         * <p>边界空格必须由「原文是否真的带空白」决定，否则 {@code <a>docs</a> &amp; <a>home</a>}
         * 会粘成 {@code docs&home}。
         *
         * <p>{@code &nbsp;} 必须先归一成普通空格：jsoup 把它保留为 U+00A0，
         * 而 Java 的 {@code \s} 与 {@link Character#isWhitespace} 都**不**把它当空白，
         * 于是它会漏过折叠、漏过 tidy，最终在模型上下文里变成肉眼可见的双空格。
         */
        private void appendText(String raw) {
            if (raw == null || raw.isEmpty()) return;
            String text = decodeEntities(raw).replace('\u00a0', ' ').replace('\u3000', ' ');
            if (text.isEmpty()) return;
            boolean leading = Character.isWhitespace(text.charAt(0));
            boolean trailing = Character.isWhitespace(text.charAt(text.length() - 1));
            String collapsed = text.replaceAll("\\s+", " ").strip();
            if (collapsed.isEmpty()) {
                if (leading && needsSpace()) out.append(' ');
                return;
            }
            if (leading && needsSpace()) out.append(' ');
            out.append(collapsed);
            if (trailing && out.length() > 0) {
                char last = out.charAt(out.length() - 1);
                if (last != ' ' && last != '\n') out.append(' ');
            }
        }

        /** 当前输出末尾是否需要先补一个空格（行首或末尾已是空白则不需要）。 */
        private boolean needsSpace() {
            if (out.length() == 0) return false;
            char last = out.charAt(out.length() - 1);
            return last != ' ' && last != '\n';
        }

        /** 补齐一个换行（不重复添加）。 */
        private StringBuilder newline() {
            if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
            return out;
        }

        /** 补齐空行（先去行尾空格，再压缩连续空行）。 */
        private StringBuilder blankLine() {
            while (out.length() > 0 && out.charAt(out.length() - 1) == ' ') {
                out.setLength(out.length() - 1);
            }
            if (out.length() == 0) return out;
            if (out.charAt(out.length() - 1) != '\n') out.append('\n');
            if (out.length() < 2 || out.charAt(out.length() - 2) != '\n') out.append('\n');
            return out;
        }

        /**
         * 收尾：去行尾空白、把空白折叠留下的连续空格压成一个、压缩三连空行、去掉首尾空行。
         *
         * <p>为什么必须压连续空格：jsoup 会把实体与文本拆成相邻的文本节点
         * （{@code "Café &nbsp; 5"} → {@code "Café "} + {@code " "} + {@code " 5"}），
         * 每个节点各自折叠就会留下叠出来的双空格。
         *
         * <p>为什么用 {@code (?<=\S)} / {@code (?=\S)} 夹住：只压「内容之间」的空格，
         * 否则列表的层级缩进（{@code "  - nested"}）也会被压成一个空格，嵌套层级全丢。
         */
        private String tidy() {
            return out.toString()
                    .replaceAll("[ \\t]+\\n", "\n")
                    .replaceAll("(?<=\\S) {2,}(?=\\S)", " ")
                    .replaceAll("\\n{3,}", "\n\n")
                    .strip();
        }

        /** 一层的列表状态。 */
        private static final class ListState {
            private final boolean ordered;
            private int count;

            private ListState(boolean ordered) {
                this.ordered = ordered;
            }
        }
    }
}
