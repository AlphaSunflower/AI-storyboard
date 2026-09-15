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

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
        // 调用方以 octet-stream 发送 multipart 字节流（@RequestBody byte[] 收 multipart 会被 Spring 解析器
        // 消费 body → 500，图改图 edits 同款 workaround）；转发前须从 body 提取 boundary 重建上游 multipart
        // Content-Type——否则上游按 octet-stream 解析收不到文件 part。
        String boundary = extractBoundary(bodyBytes);
        String upstreamContentType = "multipart/form-data; boundary=" + boundary;
        byte[] upstreamBody = withPurposeField(bodyBytes, boundary);
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
        // MiniMax 要求 purpose 是 multipart 表单字段，不是 query 参数。
        String suffix = "/v1/files/upload";
        try {
            HttpResponse<String> resp = upstreamClient.postMultipart(
                    base, suffix, apiKey, upstreamContentType, upstreamBody, UPLOAD_TIMEOUT_MS);
            String body = resp.body();
            if (resp.statusCode() != 200) {
                String err = upstreamClient.extractError(body);
                log.warn("MiniMax 文件上传返回 {}: {}", resp.statusCode(), err);
                throw new BusinessException(50201, "upstream file upload failed: " + err);
            }
            JsonNode root = objectMapper.readTree(body);
            // 200 也可能带 base_resp 逻辑错误（如 invalid file purpose）→ 明确抛错而非笼统 missing file_id
            JsonNode baseResp = root.path("base_resp");
            if (baseResp.has("status_code") && baseResp.path("status_code").asInt(-1) != 0) {
                throw new BusinessException(50201, "upstream file upload rejected: "
                        + baseResp.path("status_msg").asText("") + " (status_code=" + baseResp.path("status_code").asInt() + ")");
            }
            String fileId = root.path("file").path("file_id").asText(
                    root.path("file_id").asText(""));
            if (fileId.isBlank()) {
                throw new BusinessException(50201, "upstream file upload missing file_id: " + body);
            }
            log.info("MiniMax 文件上传成功: file_id={}", fileId);
            return "mm_file://" + fileId;
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            lastErr = e;
        }
        throw new BusinessException(50201, "upstream file upload failed: "
                + (lastErr == null ? "unknown" : lastErr.getMessage()));
    }

    /** 从 multipart 字节流提取 boundary。 */
    private String extractBoundary(byte[] body) {
        // 前两字节必须是 "--"（multipart 规范），否则无 boundary 可提取
        if (body.length < 2 || body[0] != '-' || body[1] != '-') {
            throw new BusinessException(40001, "multipart body 必须以 boundary 开头");
        }
        for (int i = 2; i < body.length - 1; i++) {
            if (body[i] == '\r' && body[i + 1] == '\n') {
                // 跳过前导 "--"，取纯 boundary（Content-Type boundary 参数不含 --）
                return new String(body, 2, i - 2, StandardCharsets.ISO_8859_1);
            }
        }
        throw new BusinessException(40001, "multipart body 缺少 boundary 结束符");
    }

    /** 在 closing boundary 前注入 purpose 表单字段。 */
    private byte[] withPurposeField(byte[] body, String boundary) {
        byte[] closing = ("--" + boundary + "--").getBytes(StandardCharsets.ISO_8859_1);
        int pos = lastIndexOf(body, closing);
        if (pos < 0) throw new BusinessException(40001, "multipart body 缺少 closing boundary");
        byte[] part = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"purpose\"\r\n\r\n"
                + "video_generation_input\r\n")
                .getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream out = new ByteArrayOutputStream(body.length + part.length);
        out.write(body, 0, pos);
        out.write(part, 0, part.length);
        out.write(body, pos, body.length - pos);
        return out.toByteArray();
    }

    private static int lastIndexOf(byte[] source, byte[] target) {
        outer:
        for (int i = source.length - target.length; i >= 0; i--) {
            for (int j = 0; j < target.length; j++) {
                if (source[i + j] != target[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
