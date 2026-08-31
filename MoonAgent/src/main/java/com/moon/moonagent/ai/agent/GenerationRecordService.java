package com.moon.moonagent.ai.agent;

import com.moon.moonagent.entity.AgentAsset;

import java.time.LocalDate;
import java.util.Map;

/**
 * 用户级生成记录服务：列表 / 详情 / 重新拉取 / 重试。
 * 记录来源 {@code agent_assets}（type=image|video，排除 reference），跨项目、跨会话按用户聚合。
 */
public interface GenerationRecordService {

    /**
     * 用户级生成记录分页列表（created_at 倒序）。
     *
     * @param genType 生成类型（text_to_image/image_to_image/text_to_video/ref_to_video/...；空=全部）
     * @param status  success(completed/succeeded) / failed；空=全部（含生成中）
     * @return Map{records,total,page,size}
     */
    Map<String, Object> listRecords(String userId, String genType, String status,
                                    LocalDate from, LocalDate to, int page, int size);

    /** 单条记录详情（归属校验）。 */
    AgentAsset getRecord(String userId, String id);

    /**
     * 重新拉取：仅视频且 taskId 非空；调用上游轮询并更新记录。
     * 图片或缺失 taskId 抛 40001。
     */
    AgentAsset refetch(String userId, String id);

    /**
     * 重试：仅 failed 记录；按原 gen_type/prompt/model/params/reference_urls 重新生成，
     * 产出新记录并返回 {id, taskId}。
     */
    Map<String, String> retry(String userId, String id);
}
