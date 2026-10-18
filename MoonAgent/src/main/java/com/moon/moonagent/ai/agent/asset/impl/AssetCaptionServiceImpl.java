package com.moon.moonagent.ai.agent.asset.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.ai.agent.asset.AssetCaptionService;
import com.moon.moonagent.ai.agent.creative.CreativeModelRouter;
import com.moon.moonagent.entity.AgentAsset;
import com.moon.moonagent.mapper.AgentAssetMapper;
import com.moon.moonagent.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 素材语义描述实现：MinIO/本地读取 → 视觉模型判定用途 → 写回 agent_assets.description。
 *
 * <p>设计约束：
 * <ul>
 *   <li>异步 + 单飞（{@link #inFlight} 去重）：上传接口不被视觉调用拖慢，同一素材不重复打标；</li>
 *   <li>只回写 description 一列：用 LambdaUpdateWrapper 精确 set，绝不整实体 update，
 *       避免覆盖上传/生成线程并发写入的 status / url 等字段；</li>
 *   <li>任何异常只记日志：素材打标是增强能力，失败必须退化成「没有描述」，不能阻断上传。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AssetCaptionServiceImpl implements AssetCaptionService {

    private static final Logger log = LoggerFactory.getLogger(AssetCaptionServiceImpl.class);

    /** 打标只对图片有意义；视频/文档暂不支持，直接跳过。 */
    private static final Set<String> IMAGE_URL_SEGMENTS = Set.of("/api/files/images/");

    private static final String SYSTEM_PROMPT = """
            你是影视分镜的素材管理员。用户上传的这张图会被用作分镜生成时的参考素材。
            请判断它在分镜制作中的用途，并给出一句话画面描述，供导演在写分镜时判断「这一镜该不该用这张图」。
            只输出 JSON，不要代码块：
            {"role":"角色|场景|道具|风格|其他","name":"简短名称（≤8字）","description":"一句话画面描述（≤60字，含主体、外观、色调）"}
            """;

    private static final String USER_PROMPT = "请分析这张素材图并输出 JSON。";

    private final AgentAssetMapper assetMapper;
    private final FileStorageService fileStorageService;
    private final ChatClient.Builder chatClientBuilder;
    private final CreativeModelRouter modelRouter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 正在打标的素材 id（单飞去重），避免读路径与上传路径重复触发。 */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    /**
     * 已经尝试过的素材 id（一次进程生命周期内只兜底试一次）。
     *
     * <p>防止「视觉模型没配好 / MinIO 读不到」时，每次素材列表都把同一批素材重新打标一遍——
     * 那会变成反复失败的付费调用。失败就退回原始文件名当标签，不再重试。
     */
    private final Set<String> attempted = ConcurrentHashMap.newKeySet();

    /** 打标专用虚拟线程执行器：daemon、无池占用，不需要生命周期管理。 */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    public void captionAsync(String assetId) {
        submit(assetId, false);
    }

    @Override
    public void ensureCaptionAsync(String assetId) {
        submit(assetId, true);
    }

    private void submit(String assetId, boolean onlyWhenMissing) {
        if (assetId == null || assetId.isBlank()) return;
        // 兜底路径（读素材时触发）每个素材只试一次；上传路径（captionAsync）总是尝试。
        if (onlyWhenMissing && !attempted.add(assetId)) return;
        if (!inFlight.add(assetId)) return;
        try {
            executor.submit(() -> {
                try {
                    caption(assetId, onlyWhenMissing);
                } catch (Exception e) {
                    log.debug("素材打标失败(忽略): assetId={}, error={}", assetId, e.getMessage());
                } finally {
                    inFlight.remove(assetId);
                }
            });
        } catch (RuntimeException e) {
            inFlight.remove(assetId);
            log.debug("素材打标提交失败(忽略): assetId={}, error={}", assetId, e.getMessage());
        }
    }

    private void caption(String assetId, boolean onlyWhenMissing) {
        AgentAsset asset = assetMapper.selectById(assetId);
        if (asset == null) return;
        if (AgentAsset.STATUS_DELETED.equalsIgnoreCase(asset.getStatus())) return;
        if (onlyWhenMissing && asset.getDescription() != null && !asset.getDescription().isBlank()) return;
        String url = asset.getUrl();
        if (!isImageAsset(asset, url)) return;

        String dataUri = fileStorageService.toImageDataUri(url);
        if (dataUri == null || dataUri.isBlank()) {
            log.debug("素材打标跳过(无法读取图片): assetId={}, url={}", assetId, url);
            return;
        }
        int semicolon = dataUri.indexOf(';');
        int comma = dataUri.indexOf(',');
        if (semicolon <= 5 || comma <= semicolon) return;

        UserMessage message = UserMessage.builder()
                .text(USER_PROMPT)
                .media(Media.builder()
                        .mimeType(MimeType.valueOf(dataUri.substring(5, semicolon)))
                        .data(dataUri)
                        .build())
                .build();
        String raw = chatClientBuilder
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(modelRouter.resolve("vision"))
                        .timeout(Duration.ofSeconds(60))
                        .maxRetries(0))
                .build()
                .prompt()
                .system(SYSTEM_PROMPT)
                .messages(message)
                .call()
                .content();
        String description = compose(raw, objectMapper);
        if (description.isBlank()) return;
        assetMapper.update(null, new LambdaUpdateWrapper<AgentAsset>()
                .eq(AgentAsset::getId, assetId)
                .set(AgentAsset::getDescription, description));
        log.info("素材已打标: assetId={}, description={}", assetId, description);
    }

    /** 图片判定：优先看 MIME，其次看落库的素材类型与 URL（兼容历史数据 MIME 缺失）。 */
    private static boolean isImageAsset(AgentAsset asset, String url) {
        String mime = asset.getMimeType();
        if (mime != null && !mime.isBlank()) return mime.toLowerCase(Locale.ROOT).startsWith("image/");
        if ("image".equalsIgnoreCase(asset.getKind())) return true;
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        for (String segment : IMAGE_URL_SEGMENTS) {
            if (lower.contains(segment)) return true;
        }
        return false;
    }

    /**
     * 把模型输出拼成单行描述：「角色｜林知夏：短发女性，黑色风衣…」。
     * 模型没按 JSON 输出时退回截断原文，仍然比「会话图片」有信息量。
     *
     * <p>包内可见（非 private）以便单测直接覆盖拼装规则。
     */
    static String compose(String raw, ObjectMapper objectMapper) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) return "";
        String json = stripCodeFence(text);
        try {
            JsonNode root = objectMapper.readTree(json);
            String role = root.path("role").asText("").trim();
            String name = root.path("name").asText("").trim();
            String description = root.path("description").asText("").trim();
            String head = join(role, name);
            String body = description.isEmpty() ? name : description;
            String composed = head.isEmpty() ? body : head + "：" + body;
            return truncate(composed, 120);
        } catch (Exception ignored) {
            return truncate(text.replaceAll("\\s+", " "), 120);
        }
    }

    private static String join(String role, String name) {
        if (role.isEmpty()) return name;
        if (name.isEmpty()) return role;
        return role + "｜" + name;
    }

    private static String stripCodeFence(String text) {
        if (!text.startsWith("```")) return text;
        int firstLineEnd = text.indexOf('\n');
        if (firstLineEnd < 0) return text;
        int fenceEnd = text.lastIndexOf("```");
        if (fenceEnd <= firstLineEnd) return text;
        return text.substring(firstLineEnd + 1, fenceEnd).trim();
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
