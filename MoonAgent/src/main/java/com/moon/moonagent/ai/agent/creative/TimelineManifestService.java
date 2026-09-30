package com.moon.moonagent.ai.agent.creative;

import com.moon.moonagent.entity.AgentConversation;

public interface TimelineManifestService {

    TimelineManifest save(AgentConversation conversation, String turnId, TimelineManifest manifest);

    TimelineManifest get(String projectId, String conversationId, String manifestId);

    TimelineManifest latest(String projectId, String conversationId);
}
