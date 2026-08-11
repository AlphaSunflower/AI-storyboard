package com.llmgateway.service;

import com.llmgateway.dto.admin.ChannelRequest;
import com.llmgateway.entity.Channel;

import java.util.List;

/** 渠道管理服务：渠道 CRUD（Key AES 加密落库、响应脱敏） */
public interface ChannelService {

    /** 创建渠道：name/baseUrl/apiKey 必填校验，type 默认 openai_compatible，apiKey AES 加密落库，返回脱敏实体 */
    Channel create(ChannelRequest request);

    /** 渠道列表（按 priority 升序，apiKey 脱敏为 ***） */
    List<Channel> list();

    /** 更新渠道：仅更新非 null 字段；传新 apiKey 才重加密；返回脱敏实体 */
    Channel update(String id, ChannelRequest request);

    /** 删除渠道（不存在抛 40401） */
    void delete(String id);

    /** 按 id 取渠道（内部读取，不脱敏；不存在抛 40401） */
    Channel getById(String id);

    /** 渠道总数（自动带 @TableLogic 过滤） */
    long countAll();

    /** 启用中的渠道数 */
    long countEnabled();
}
