package com.moon.moonagent.ai.agent.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 显式切链短语判定（人在回路）：只有用户输入命中明确的「切链指令」才允许从当前链切到目标链，
 * 避免普通描述（如「根据这个资产做一个多图转场」）被意图模型误判为「生成视频」而切断分镜链。
 *
 * <p>命中短语（2026-09-06 定案）：我要分镜 / 我要故事板 / 改成图片 / 改成视频 /
 * 不是视频 / 不是图片 / 切到... / 审查分镜。
 */
@Component
public class RouteChangeService {

    private static final Logger log = LoggerFactory.getLogger(RouteChangeService.class);

    /** 短语 → 目标 intentType；同一短语命中多个时按数组顺序取首个。 */
    private static final List<Map.Entry<String, String>> PHRASE_TO_INTENT = List.of(
            Map.entry("我要分镜", "intent-aisplit"),
            Map.entry("我要故事板", "intent-aisplit"),
            Map.entry("生成分镜", "intent-aisplit"),
            Map.entry("改成图片", "intent-pic"),
            Map.entry("改成视频", "intent-video"),
            Map.entry("审查分镜", "intent-scene-review"),
            Map.entry("我要审查", "intent-scene-review"),
            Map.entry("不是视频", "intent-pic"),
            Map.entry("不是图片", "intent-video")
    );

    /**
     * 是否命中显式切链短语。
     *
     * @param customText 用户自定义输入（可空）
     * @return true=命中（应走 switch_to_run 重新识别/切链）；false=留在当前链
     */
    public boolean shouldSwitch(String customText) {
        return detectTargetIntent(customText) != null;
    }

    /**
     * 根据显式切链短语判定目标 intentType。
     *
     * @param customText 用户自定义输入（可空）
     * @return 目标 intentType；未命中返回 null
     */
    public String detectTargetIntent(String customText) {
        if (customText == null || customText.isBlank()) return null;
        String text = customText.trim();
        // 切到...：后续跟分镜/故事板/图片/视频/审查
        String ct = cutTo(text);
        if (ct != null) return ct;
        for (Map.Entry<String, String> e : PHRASE_TO_INTENT) {
            if (text.contains(e.getKey())) {
                log.debug("RouteChangeService: 命中切链短语 {} → {}", e.getKey(), e.getValue());
                return e.getValue();
            }
        }
        return null;
    }

    /** 解析「切到X/改成X/切换到X」的 X → 目标 intent；无法识别返回 null。 */
    private String cutTo(String text) {
        String prefix = null;
        for (String p : new String[]{"切到", "切换到", "改成"}) {
            if (text.contains(p)) { prefix = p; break; }
        }
        if (prefix == null) return null;
        int idx = text.indexOf(prefix);
        String rest = text.substring(idx + prefix.length()).trim();
        if (rest.isEmpty()) return null;
        if (rest.contains("分镜") || rest.contains("故事板")) return "intent-aisplit";
        if (rest.contains("图片") || rest.contains("图")) return "intent-pic";
        if (rest.contains("视频")) return "intent-video";
        if (rest.contains("审查")) return "intent-scene-review";
        return null;
    }
}
