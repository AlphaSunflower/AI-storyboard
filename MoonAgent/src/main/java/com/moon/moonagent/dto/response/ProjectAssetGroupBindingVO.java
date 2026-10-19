package com.moon.moonagent.dto.response;

import java.time.OffsetDateTime;

/**
 * 项目维度的资产组绑定：资产组在哪些对话里被使用。
 *
 * @param groupVersion 绑定时冻结的资产组版本；与资产组当前 version 不一致说明快照过期
 * @param boundAt      绑定的最后更新时间
 */
public record ProjectAssetGroupBindingVO(
        String groupId,
        String conversationId,
        String conversationTitle,
        String snapshotId,
        long groupVersion,
        String target,
        OffsetDateTime boundAt
) {
}
