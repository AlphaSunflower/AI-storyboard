package com.moon.moonagent.ai.agent.document;

import java.util.List;

public interface DocumentSummaryModel {

    String summarizeChunk(String markdown);

    String merge(List<String> summaries);
}
