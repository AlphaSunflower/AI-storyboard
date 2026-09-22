package com.moon.moonagent.orchestration;

public interface ChainExecutor {
    ChainType chainType();

    ChainResult execute(ChainExecutionContext context);
}
