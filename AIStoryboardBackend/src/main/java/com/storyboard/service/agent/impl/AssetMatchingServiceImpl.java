package com.storyboard.service.agent.impl;

import com.storyboard.dto.response.AssetVO;
import com.storyboard.service.agent.AssetMatchingService;
import com.storyboard.service.agent.AssetRelevanceResult;
import com.storyboard.service.agent.SceneAssetMatch;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import com.storyboard.service.ai.GatewayModelService;

/**
 * 资产判定服务实现：ChatClient 走 LLM 网关（对话模型从网关动态获取，超时 120s），
 * 结构化输出用纯解析（不发 response_format，规避网关不兼容）。
 *
 * <p>降级语义：LLM 调用/解析失败 → judgeRelevance 返回「相关」（门禁放行，不阻塞生成）、
 * matchScenes 返回空列表（不自动关联，分镜照常）——资产联动是增强，不是生成前置条件。
 */
@Service
@RequiredArgsConstructor
public class AssetMatchingServiceImpl implements AssetMatchingService {

    private static final Logger log = LoggerFactory.getLogger(AssetMatchingServiceImpl.class);

    /** 关联性判定结构化输出 */
    public record RelevanceOutput(boolean relevant, String reason) {}

    /** 分镜关联结构化输出（sceneNumber 与输入分镜列表的分镜号对齐） */
    public record MatchOutput(List<SceneItem> scenes) {
        public record SceneItem(int sceneNumber, List<String> assetIds) {}
    }

    private final ChatClient.Builder chatClientBuilder;
    private final GatewayModelService gatewayModelService;

    /** 懒加载 ChatClient（复用网关默认对话模型，超时 120s） */
    private volatile ChatClient chatClient;

    @Override
    public AssetRelevanceResult judgeRelevance(String prompt, List<AssetVO> assets) {
        if (prompt == null || prompt.isBlank() || assets == null || assets.isEmpty()) {
            return new AssetRelevanceResult(true, "");
        }
        try {
            String raw = chatClient().prompt()
                    .system("你是影视资产统筹。用户为创作项目勾选了一批资产（人物/道具/场景设定），并给出创作提示词。"
                            + "注意：提示词可能包含「重新生成/继续完善」等简短指令和【最近对话上下文】段落（按时间顺序的对话记录），"
                            + "必须结合上下文判断本次创作是否会用这些资产（人物出场、道具出现、场景发生地等）——"
                            + "只要上下文或历史需求提及了该资产（如指名道姓或明确描述），即判定强关联；"
                            + "仅当整段提示词与上下文都完全未涉及该资产时才判定不相关。"
                            + "输出 JSON：{\"relevant\": true/false, \"reason\": \"判定理由，中文 ≤30 字，说明为什么相关或不相关\"}。"
                            + "只输出 JSON。")
                    .user("用户勾选的资产：\n" + assetSheetText(assets)
                            + "\n\n用户提示词（含上下文）：\n" + prompt)
                    .call()
                    .content();
            RelevanceOutput out = new BeanOutputConverter<>(RelevanceOutput.class).convert(raw);
            if (out == null) return new AssetRelevanceResult(true, "");
            return new AssetRelevanceResult(out.relevant(), out.reason() == null ? "" : out.reason());
        } catch (Exception e) {
            log.warn("关联性判定 LLM 调用失败，按相关放行: {}", e.getMessage());
            return new AssetRelevanceResult(true, "");
        }
    }

    @Override
    public List<SceneAssetMatch> matchScenes(List<Map<String, Object>> scenes, List<AssetVO> assets) {
        if (scenes == null || scenes.isEmpty() || assets == null || assets.isEmpty()) return List.of();
        try {
            StringBuilder sceneText = new StringBuilder();
            for (int i = 0; i < scenes.size(); i++) {
                Object n = scenes.get(i).get("sceneNumber");
                Object c = scenes.get(i).get("scriptContent");
                sceneText.append(i + 1).append(". [分镜").append(n == null ? i + 1 : n)
                        .append("] ").append(c == null ? "" : c).append("\n");
            }
            String raw = chatClient().prompt()
                    .system("你是影视分镜资产匹配师。给定分镜列表（按剧情内容）和资产清单（人物/道具/场景），"
                            + "判断每个分镜中会出现哪些资产（按剧情内容判断：人物出场、道具使用、场景发生地）。"
                            + "只关联剧情明确出现的资产，不要臆测；某个分镜不出现任何资产时 assetIds 给空数组。"
                            + "输出 JSON：{\"scenes\":[{\"sceneNumber\":1,\"assetIds\":[\"资产id\"]}]}，"
                            + "sceneNumber 必须原样使用分镜列表中的分镜号、每个分镜都要有条目。"
                            + "资产 ID 必须原样使用下方给出的 id。只输出 JSON。")
                    .user("资产清单：\n" + assetSheetText(assets)
                            + "\n\n分镜列表：\n" + sceneText)
                    .call()
                    .content();
            MatchOutput out = new BeanOutputConverter<>(MatchOutput.class).convert(raw);
            if (out == null || out.scenes() == null) return List.of();
            List<SceneAssetMatch> result = new ArrayList<>();
            for (MatchOutput.SceneItem item : out.scenes()) {
                if (item == null || item.assetIds() == null) continue;
                result.add(new SceneAssetMatch(item.sceneNumber(),
                        item.assetIds().stream().filter(java.util.Objects::nonNull).toList()));
            }
            log.info("分镜自动关联判定完成: scenes={}, matches={}", scenes.size(), result.size());
            return result;
        } catch (Exception e) {
            log.warn("分镜自动关联判定 LLM 调用失败，跳过关联: {}", e.getMessage());
            return List.of();
        }
    }

    /** 资产清单文本（name/type/description/id，供 LLM 判定） */
    private String assetSheetText(List<AssetVO> assets) {
        StringBuilder sb = new StringBuilder();
        for (AssetVO a : assets) {
            sb.append("- [id=").append(a.id()).append("] 类型=").append(a.type())
              .append(" 名称=").append(a.name());
            if (a.description() != null && !a.description().isBlank()) {
                sb.append("（").append(a.description().trim()).append("）");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /** 懒加载 ChatClient：对话模型从网关动态获取（与编排 planClient 一致），超时 120s */
    private ChatClient chatClient() {
        if (chatClient == null) {
            synchronized (this) {
                if (chatClient == null) {
                    chatClient = chatClientBuilder
                            .defaultOptions(OpenAiChatOptions.builder()
                                    .model(gatewayModelService.getDefaultTextModel())
                                    .timeout(Duration.ofSeconds(120)))
                            .build();
                }
            }
        }
        return chatClient;
    }
}
