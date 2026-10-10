package com.moon.moonagent.dto.response;

import java.util.List;

public record MessagePageVO(
        List<AgentMessageVO> records,
        String nextCursor,
        boolean hasMore
) {
}
