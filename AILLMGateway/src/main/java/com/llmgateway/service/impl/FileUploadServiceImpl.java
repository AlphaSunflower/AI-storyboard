package com.llmgateway.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.entity.Channel;
import com.llmgateway.exception.BusinessException;
import com.llmgateway.mapper.ChannelMapper;
import com.llmgateway.service.FileUploadService;
import com.llmgateway.service.KeyService;
import com.llmgateway.service.UpstreamClient;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.http.HttpResponse;
import java.util.List;

/**
 * 文件上传代理实现：转发 MiniMax POST /v1/files/upload，解析 file_id 返回 mm_file://。
 * 视频多模态参考素材经主后端 → 网关 → MiniMax 上传（业务 uploads 目录网关不可直达，
 * 且大文件 base64 会超出 64 MB 请求体限制，必须走平台 file_id 引用）。
 */
@Service
@RequiredArgsConstructor
public class FileUploadServiceImpl implements FileUploadService {

    private static final Logger log = LoggerFactory.getLogger(FileUploadServiceImpl.class);

    /** 上传大文件长超时（参考视频最大 50 MB） */
    private static final long UPLOAD_TIMEOUT_MS = 300_000L;

    private final ChannelMapper channelMapper;
    private final KeyService keyService;
    private final UpstreamClient upstreamClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String upload(String contentType, byte[] bodyBytes) {
        // 文件上传固定走 minimax 渠道（mm_file:// 生态；Laozhang 无对应机制）
        List<Channel> minimaxChannels = channelMapper.selectList(new LambdaQueryWrapper<Channel>()
                .eq(Channel::getType, "minimax")
                .eq(Channel::getEnabled, true)
                .orderByAsc(Channel::getPriority));
        if (minimaxChannels.isEmpty()) {
            throw new BusinessException(50301, "no available minimax channel for file upload");
        }
        Channel channel = minimaxChannels.getFirst();
        String apiKey = keyService.decrypt(channel.getApiKey());
        String base = channel.getBaseUrl().endsWith("/")
                ? channel.getBaseUrl().substring(0, channel.getBaseUrl().length() - 1)
                : channel.getBaseUrl();

        Exception lastErr = null;
        // 先不带 purpose 上传；上游 400 提示参数/purpose 必填时，换带 purpose=video_generation 重试一次
        for (String suffix : new String[] { "/v1/files/upload", "/v1/files/upload?purpose=video_generation" }) {
            try {
                HttpResponse<String> resp = upstreamClient.postMultipart(
                        base, suffix, apiKey, contentType, bodyBytes, UPLOAD_TIMEOUT_MS);
                if (resp.statusCode() != 200) {
                    String err = upstreamClient.extractError(resp.body());
                    log.warn("MiniMax 文件上传返回 {}: {}", resp.statusCode(), err);
                    boolean retryable = resp.statusCode() == 400
                            && (resp.body().contains("purpose") || resp.body().contains("param"));
                    if (!retryable) {
                        throw new BusinessException(50201, "upstream file upload failed: " + err);
                    }
                    continue;
                }
                JsonNode root = objectMapper.readTree(resp.body());
                String fileId = root.path("file").path("file_id").asText(
                        root.path("file_id").asText(""));
                if (fileId.isBlank()) {
                    throw new BusinessException(50201, "upstream file upload missing file_id: " + resp.body());
                }
                log.info("MiniMax 文件上传成功: file_id={}", fileId);
                return "mm_file://" + fileId;
            } catch (BusinessException be) {
                throw be;
            } catch (Exception e) {
                lastErr = e;
            }
        }
        throw new BusinessException(50201, "upstream file upload failed: "
                + (lastErr == null ? "unknown" : lastErr.getMessage()));
    }
}
