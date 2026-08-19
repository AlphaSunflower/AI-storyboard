package com.llmgateway.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.llmgateway.config.GatewayConfig;
import com.llmgateway.dto.admin.ConfigUpdateRequest;
import com.llmgateway.entity.SysConfig;
import com.llmgateway.exception.BusinessException;
import com.llmgateway.mapper.SysConfigMapper;
import com.llmgateway.service.SysConfigService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** 系统可调配置实现：已知键白名单校验 + upsert；@PostConstruct 把 DB 值加载进 GatewayConfig（缺行/解析失败保留代码默认） */
@Service
@RequiredArgsConstructor
@Slf4j
public class SysConfigServiceImpl implements SysConfigService {

    /** 已知配置键 → 解析校验 + 写入 GatewayConfig 的规格 */
    private static final Map<String, KeySpec> SPECS = Map.of(
        "gateway.upstream.connect-timeout-ms", new KeySpec("上游连接超时（毫秒）",
            v -> {
                long l = Long.parseLong(v);
                if (l < 1000 || l > 300000) throw new IllegalArgumentException("取值须在 [1000,300000]");
                return l;
            },
            (c, v) -> c.getUpstream().setConnectTimeoutMs((Long) v)),
        "gateway.upstream.request-timeout-ms", new KeySpec("上游请求超时（毫秒；SSE 流式取 max(该值,300s)）",
            v -> {
                long l = Long.parseLong(v);
                if (l < 1000 || l > 600000) throw new IllegalArgumentException("取值须在 [1000,600000]");
                return l;
            },
            (c, v) -> c.getUpstream().setRequestTimeoutMs((Long) v)),
        "gateway.upstream.retry-count", new KeySpec("上游 429/5xx 重试次数（指数退避）",
            v -> {
                int i = Integer.parseInt(v);
                if (i < 0 || i > 10) throw new IllegalArgumentException("取值须在 [0,10]");
                return i;
            },
            (c, v) -> c.getUpstream().setRetryCount((Integer) v))
    );

    private final SysConfigMapper sysConfigMapper;
    private final GatewayConfig gatewayConfig;

    /** 启动时把 DB 值加载进 GatewayConfig.Upstream（缺行/值非法 → 保持代码默认值，只告警不阻断启动） */
    @PostConstruct
    void loadIntoGatewayConfig() {
        // 单次查询替代 N 次 selectOne（性能优化）
        Map<String, SysConfig> rows = sysConfigMapper.selectList(null).stream()
                .collect(java.util.stream.Collectors.toMap(SysConfig::getConfigKey, r -> r, (a, b) -> a));
        SPECS.forEach((key, spec) -> {
            SysConfig row = rows.get(key);
            if (row == null) return;
            try {
                spec.binder().accept(gatewayConfig, spec.parser().apply(row.getConfigValue().trim()));
            } catch (Exception e) {
                log.warn("系统配置加载失败，保留默认值: key={}, value={}, error={}", key, row.getConfigValue(), e.getMessage());
            }
        });
    }

    @Override
    public List<SysConfig> getAll() {
        return sysConfigMapper.selectList(null);
    }

    @Override
    public List<SysConfig> updateValues(ConfigUpdateRequest request) {
        if (request.items() == null || request.items().isEmpty()) {
            throw new BusinessException(40001, "配置项不能为空");
        }
        OffsetDateTime now = OffsetDateTime.now();
        for (ConfigUpdateRequest.ConfigItem item : request.items()) {
            KeySpec spec = SPECS.get(item.key());
            if (spec == null) throw new BusinessException(40001, "未知配置项: " + item.key());
            if (item.value() == null || item.value().isBlank()) {
                throw new BusinessException(40001, "配置值不能为空: " + item.key());
            }
            // 先校验（解析+范围），非法直接抛 40001，不落库
            try {
                spec.parser().apply(item.value().trim());
            } catch (IllegalArgumentException e) {
                throw new BusinessException(40001, "配置值不合法 " + item.key() + "：" + e.getMessage());
            }
            upsert(item.key().trim(), item.value().trim(), spec, now);
        }
        return getAll();
    }

    /** 按键 upsert：已存在更新 value/updatedAt，不存在插入（id=key，可读可溯） */
    private void upsert(String key, String value, KeySpec spec, OffsetDateTime now) {
        SysConfig row = sysConfigMapper.selectOne(new LambdaQueryWrapper<SysConfig>()
                .eq(SysConfig::getConfigKey, key));
        if (row == null) {
            SysConfig entity = new SysConfig();
            entity.setId(key);
            entity.setConfigKey(key);
            entity.setConfigValue(value);
            entity.setRemark(spec.label());
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            sysConfigMapper.insert(entity);
        } else {
            row.setConfigValue(value);
            row.setUpdatedAt(now);
            sysConfigMapper.updateById(row);
        }
    }

    /** 配置键规格：label 中文说明、parser 值解析+范围校验、binder 写入 GatewayConfig */
    private record KeySpec(String label, Function<String, Object> parser, BiConsumer<GatewayConfig, Object> binder) {}
}
