package com.llmgateway.vendor.video.dto;

/**
 * 视频厂商响应规则接口（sealed）——规则统一、数据不统一。
 * <p>
 * 所有厂商解析出的结果都实现本接口（公共稳定字段 vendor/taskId/status/progress/url/error），
 * 但每个厂商一个 sealed 子类型（{@link MinimaxVideoResult} / {@link LaozhangVideoResult}），
 * 子类型可携带各自专用字段；业务侧用 type-switch 识别是哪一家的响应，绝不做字段名猜测。
 * <p>
 * 新增协议 = 新增一个 sealed 子类型 + 一个适配器，不用改本接口。
 */
public sealed interface VideoVendorResult permits MinimaxVideoResult, LaozhangVideoResult {

    /** 厂商标识（channel.type 派生，如 minimax / openai_compatible）——用于识别与日志，绝不猜测 */
    String vendor();

    /** 上游任务 id（各厂商解析 task_id / id / taskId 后统一成 taskId） */
    String taskId();

    /** 已归一的任务阶段：pending / processing / succeeded / failed */
    String status();

    /** 上游进度（无则 null） */
    String progress();

    /** 媒体直链（minimax 轮询到 succeeded 时可取到 content.url / 限时 download_url；否则 null） */
    String url();

    /** 失败原因（failed 时非 null） */
    String error();
}
