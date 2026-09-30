package com.moon.moonagent.ai.agent.creative;

import com.moon.moonagent.entity.AgentConversation;

import java.util.List;
import java.util.Map;

public interface CreativeCritiqueService {

    MediaCritique reviewImages(AgentConversation conversation, String turnId, String itemId,
                               String prompt, List<String> imageUrls,
                               ConstraintLedger ledger, int repairAttempt);

    MediaCritique reviewMedia(AgentConversation conversation, String turnId, String itemId,
                              String mediaType, String prompt, List<String> frameUrls,
                              ConstraintLedger ledger, int repairAttempt);

    MediaCritique reviewVideo(AgentConversation conversation, String turnId, String itemId,
                              String prompt, String videoUrl,
                              ConstraintLedger ledger, int repairAttempt);

    MediaCritique record(MediaCritique critique);

    List<MediaCritique> listByTurn(String projectId, String conversationId, String turnId);

    Map<String, Object> asToolResult(MediaCritique critique);
}
