package com.moon.moonagent.ai.agent.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AssetMatchingServiceImplTest {

    @Test
    void parseSceneNumber_acceptsBracketedAndRawNumbers() {
        assertEquals(37, AssetMatchingServiceImpl.parseSceneNumber("[分镜37]"));
        assertEquals(37, AssetMatchingServiceImpl.parseSceneNumber("分镜37"));
        assertEquals(37, AssetMatchingServiceImpl.parseSceneNumber("37"));
        assertEquals(0, AssetMatchingServiceImpl.parseSceneNumber("分镜"));
        assertEquals(0, AssetMatchingServiceImpl.parseSceneNumber(null));
    }
}
