package com.llmgateway.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.llmgateway.dto.admin.ChannelKeyRequest;
import com.llmgateway.entity.Channel;
import com.llmgateway.entity.ChannelKey;
import com.llmgateway.exception.BusinessException;
import com.llmgateway.mapper.ChannelKeyMapper;
import com.llmgateway.mapper.ChannelMapper;
import com.llmgateway.service.ChannelKeyService;
import com.llmgateway.service.KeyService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 多 Key 池实现。
 *
 * <p>冷却状态放在本机内存：网关目前是单实例，冷却只是「少打几次已知失败的 Key」，
 * 丢失最多让下一次请求再失败一次；不把冷却写库，避免每个请求都产生数据库写入。
 * 如果以后多实例部署，把 {@link #cooldownUntil} 换成 Redis 即可，接口不变。
 */
@Service
@RequiredArgsConstructor
public class ChannelKeyServiceImpl implements ChannelKeyService {

    private static final Logger log = LoggerFactory.getLogger(ChannelKeyServiceImpl.class);

    private final ChannelKeyMapper keyMapper;
    private final ChannelMapper channelMapper;
    private final KeyService keyService;

    /** keyId → 冷却截止时间（epoch ms） */
    private final Map<String, Long> cooldownUntil = new ConcurrentHashMap<>();
    /** keyId → 连续失败次数 */
    private final Map<String, Integer> failures = new ConcurrentHashMap<>();

    @Override
    public List<ChannelKey> list(String channelId) {
        return keyMapper.selectList(new LambdaQueryWrapper<ChannelKey>()
                .eq(ChannelKey::getChannelId, channelId)
                .orderByAsc(ChannelKey::getCreatedAt));
    }

    @Override
    public ChannelKey create(String channelId, ChannelKeyRequest request) {
        requireChannel(channelId);
        if (request.getApiKey() == null || request.getApiKey().isBlank()) {
            throw new BusinessException(40001, "新增 Key 必须填写 apiKey");
        }
        ChannelKey key = new ChannelKey();
        key.setChannelId(channelId);
        key.setLabel(request.getLabel() == null || request.getLabel().isBlank() ? null : request.getLabel().trim());
        key.setApiKey(keyService.encrypt(request.getApiKey().trim()));
        key.setWeight(normalizeWeight(request.getWeight()));
        key.setEnabled(request.getEnabled() == null ? true : request.getEnabled());
        key.setFailCount(0);
        key.setCreatedAt(OffsetDateTime.now());
        key.setUpdatedAt(OffsetDateTime.now());
        keyMapper.insert(key);
        return key;
    }

    @Override
    public ChannelKey update(String channelId, String keyId, ChannelKeyRequest request) {
        ChannelKey key = requireKey(channelId, keyId);
        if (request.getLabel() != null) {
            key.setLabel(request.getLabel().isBlank() ? null : request.getLabel().trim());
        }
        if (request.getApiKey() != null && !request.getApiKey().isBlank()) {
            key.setApiKey(keyService.encrypt(request.getApiKey().trim()));
            failures.remove(keyId);
            cooldownUntil.remove(keyId);
        }
        if (request.getWeight() != null) key.setWeight(normalizeWeight(request.getWeight()));
        if (request.getEnabled() != null) key.setEnabled(request.getEnabled());
        key.setUpdatedAt(OffsetDateTime.now());
        keyMapper.updateById(key);
        return key;
    }

    @Override
    public void delete(String channelId, String keyId) {
        requireKey(channelId, keyId);
        keyMapper.deleteById(keyId);
        failures.remove(keyId);
        cooldownUntil.remove(keyId);
    }

    @Override
    public KeySelection select(Channel channel) {
        List<ChannelKey> keys = keyMapper.selectList(new LambdaQueryWrapper<ChannelKey>()
                .eq(ChannelKey::getChannelId, channel.getId())
                .eq(ChannelKey::getEnabled, true));
        if (keys.isEmpty()) {
            return new KeySelection(null, keyService.decrypt(channel.getApiKey()));
        }
        long now = System.currentTimeMillis();
        List<ChannelKey> available = keys.stream()
                .filter(k -> cooldownUntil.getOrDefault(k.getId(), 0L) <= now)
                .toList();
        if (available.isEmpty()) {
            // 全部冷却：仍用一个（优先最早解冻的），避免整个渠道直接不可用
            available = keys;
            log.warn("渠道 {} 的所有 Key 都在冷却，仍尝试其中一个", channel.getId());
        }
        ChannelKey picked = weightedPick(available);
        return new KeySelection(picked.getId(), keyService.decrypt(picked.getApiKey()));
    }

    @Override
    public void markFailure(String keyId, int status) {
        if (keyId == null || keyId.isBlank()) return;
        int fails = failures.merge(keyId, 1, Integer::sum);
        long cooldownMs;
        if (status == 429) {
            cooldownMs = 30_000L;
        } else if (status == 401 || status == 403) {
            cooldownMs = 120_000L;
        } else if (status >= 500) {
            cooldownMs = 15_000L;
        } else {
            cooldownMs = 60_000L;
        }
        if (fails >= 5) cooldownMs = Math.max(cooldownMs, 300_000L);
        cooldownUntil.put(keyId, System.currentTimeMillis() + cooldownMs);
        log.warn("Key 失败进入冷却: key={}, status={}, fails={}, cooldown={}ms",
                keyId, status, fails, cooldownMs);
    }

    @Override
    public void markSuccess(String keyId) {
        if (keyId == null || keyId.isBlank()) return;
        failures.remove(keyId);
        cooldownUntil.remove(keyId);
    }

    private ChannelKey weightedPick(List<ChannelKey> keys) {
        int total = keys.stream().mapToInt(k -> normalizeWeight(k.getWeight())).sum();
        int target = ThreadLocalRandom.current().nextInt(Math.max(1, total));
        int acc = 0;
        for (ChannelKey key : keys) {
            acc += normalizeWeight(key.getWeight());
            if (target < acc) return key;
        }
        return keys.get(keys.size() - 1);
    }

    private int normalizeWeight(Integer weight) {
        return weight == null || weight < 1 ? 1 : weight;
    }

    private void requireChannel(String channelId) {
        if (channelMapper.selectById(channelId) == null) {
            throw new BusinessException(40401, "渠道不存在");
        }
    }

    private ChannelKey requireKey(String channelId, String keyId) {
        ChannelKey key = keyMapper.selectById(keyId);
        if (key == null || !channelId.equals(key.getChannelId())) {
            throw new BusinessException(40401, "Key 不存在或不属于该渠道");
        }
        return key;
    }
}
