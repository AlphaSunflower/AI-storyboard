package com.llmgateway.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.llmgateway.dto.admin.ChannelRequest;
import com.llmgateway.entity.Channel;
import com.llmgateway.exception.BusinessException;
import com.llmgateway.mapper.ChannelMapper;
import com.llmgateway.service.ChannelService;
import com.llmgateway.service.KeyService;
import com.llmgateway.service.ModelCatalogCache;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

/** 渠道管理服务实现：Key 写入 AES 加密，读取永远不返回明文 */
@Service
@RequiredArgsConstructor
public class ChannelServiceImpl implements ChannelService {

    private final ChannelMapper channelMapper;
    private final KeyService keyService;
    /** 渠道的 enabled/priority 直接决定 /v1/models 的成员与顺序，任何变更都要清模型目录缓存 */
    private final ModelCatalogCache catalogCache;

    @Override
    public Channel create(ChannelRequest request) {
        if (request.getName() == null || request.getName().isBlank()
                || request.getBaseUrl() == null || request.getBaseUrl().isBlank()) {
            throw new BusinessException(40001, "name/baseUrl/apiKey 不能为空");
        }
        String type = request.getType() == null ? "openai_compatible" : request.getType();
        // Ollama 本地渠道无鉴权，apiKey 可选；其余渠道必填
        if (!"ollama".equals(type)
                && (request.getApiKey() == null || request.getApiKey().isBlank())) {
            throw new BusinessException(40001, "name/baseUrl/apiKey 不能为空");
        }
        Channel channel = new Channel();
        channel.setName(request.getName());
        channel.setType(type);
        channel.setBaseUrl(request.getBaseUrl());
        channel.setApiKey(request.getApiKey() == null || request.getApiKey().isBlank()
                ? "" : keyService.encrypt(request.getApiKey()));   // Ollama 无 Key 存空串；其余 AES 加密
        channel.setModels(request.getModels());
        channel.setEnabled(request.getEnabled() == null ? true : request.getEnabled());
        channel.setPriority(request.getPriority() == null ? 0 : request.getPriority());
        channel.setCreatedAt(OffsetDateTime.now());
        channel.setUpdatedAt(OffsetDateTime.now());
        channelMapper.insert(channel);
        // 新渠道可能立刻挂上路由：清掉全部类型的模型列表缓存（禁用渠道的模型不该继续出现在列表里）
        catalogCache.evictAll();
        channel.setApiKey("***");   // 返回脱敏
        return channel;
    }

    @Override
    public List<Channel> list() {
        List<Channel> channels = channelMapper.selectList(new LambdaQueryWrapper<Channel>()
                .orderByAsc(Channel::getPriority));
        channels.forEach(c -> c.setApiKey("***"));
        return channels;
    }

    @Override
    public Channel update(String id, ChannelRequest request) {
        Channel channel = channelMapper.selectById(id);
        if (channel == null) throw new BusinessException(40401, "渠道不存在");
        if (request.getName() != null) channel.setName(request.getName());
        if (request.getType() != null) channel.setType(request.getType());
        if (request.getBaseUrl() != null) channel.setBaseUrl(request.getBaseUrl());
        if (request.getApiKey() != null && !request.getApiKey().isBlank()) {
            channel.setApiKey(keyService.encrypt(request.getApiKey()));  // 传新 Key 才重加密
        }
        if (request.getModels() != null) channel.setModels(request.getModels());  // models 可更新为空串清空
        if (request.getEnabled() != null) channel.setEnabled(request.getEnabled());
        if (request.getPriority() != null) channel.setPriority(request.getPriority());
        channel.setUpdatedAt(OffsetDateTime.now());
        channelMapper.updateById(channel);
        // enabled/priority/baseUrl/models 任一变化都会影响模型目录；渠道是按类型共享的，只能全量失效
        catalogCache.evictAll();
        channel.setApiKey("***");
        return channel;
    }

    @Override
    public void delete(String id) {
        if (channelMapper.deleteById(id) == 0) throw new BusinessException(40401, "渠道不存在");
        catalogCache.evictAll();
    }

    @Override
    public Channel getById(String id) {
        Channel channel = channelMapper.selectById(id);
        if (channel == null) throw new BusinessException(40401, "渠道不存在");
        return channel;
    }

    @Override
    public long countAll() {
        Long count = channelMapper.selectCount(null);
        return count == null ? 0 : count;
    }

    @Override
    public long countEnabled() {
        Long count = channelMapper.selectCount(new LambdaQueryWrapper<Channel>()
                .eq(Channel::getEnabled, true));
        return count == null ? 0 : count;
    }
}
