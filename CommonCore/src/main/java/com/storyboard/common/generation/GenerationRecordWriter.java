package com.storyboard.common.generation;

/**
 * 生成记录落库端口。各服务用本地 MyBatis 实体实现，业务层只依赖本接口。
 */
public interface GenerationRecordWriter {

    String create(GenerationRecord record);

    void update(String recordId, GenerationRecordUpdate update);

    void attachTaskId(String recordId, String taskId);

    void updateByTaskId(String taskId, GenerationRecordUpdate update);
}
