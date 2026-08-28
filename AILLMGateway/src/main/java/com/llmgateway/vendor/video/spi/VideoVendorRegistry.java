package com.llmgateway.vendor.video.spi;

import com.llmgateway.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 视频厂商适配器注册表 —— 按渠道类型（channel.type）显式分发，绝不按响应字段名反猜。
 * <p>
 * Spring 收集所有 {@link VideoVendorAdapter} bean（Map key = bean 名），
 * {@link #init()} 按 {@link VideoVendorAdapter#channelType()} 建索引。
 * 新增协议：仅需新增一个适配器 bean（声明新 channelType），此处零改动。
 */
@Component
@RequiredArgsConstructor
public class VideoVendorRegistry {

    /** Spring 注入所有 VideoVendorAdapter bean（key=bean 名） */
    private final Map<String, VideoVendorAdapter> adapters;

    /** channelType -> adapter 索引 */
    private final Map<String, VideoVendorAdapter> byType = new HashMap<>();

    @PostConstruct
    void init() {
        if (adapters != null) {
            adapters.values().forEach(a -> byType.put(a.channelType(), a));
        }
    }

    /** 按渠道类型取适配器；未注册的协议明确抛错（不兜底、不猜） */
    public VideoVendorAdapter resolve(String channelType) {
        VideoVendorAdapter a = channelType == null ? null : byType.get(channelType);
        if (a == null) {
            throw new BusinessException(50301, "无视频供应商适配器: channelType=" + channelType);
        }
        return a;
    }

    /** 已注册的渠道类型集合（调试/健康检查用） */
    List<String> supportedTypes() {
        return List.copyOf(byType.keySet());
    }
}
