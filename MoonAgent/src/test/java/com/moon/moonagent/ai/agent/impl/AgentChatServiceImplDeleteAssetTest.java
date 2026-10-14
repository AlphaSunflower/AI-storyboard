package com.moon.moonagent.ai.agent.impl;

import com.moon.moonagent.entity.AgentAsset;
import com.storyboard.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 素材删除的口径测试：下标必须和前端会话资产网格看到的一致，
 * 「删到最后一张」不能把资产重新变回可见。
 */
class AgentChatServiceImplDeleteAssetTest {

    private static AgentAsset asset(String url, String resultUrls) {
        AgentAsset asset = new AgentAsset();
        asset.setId("asset-1");
        asset.setConversationId("conversation-1");
        asset.setUserId("user-1");
        asset.setType("image");
        asset.setUrl(url);
        asset.setResultUrls(resultUrls);
        asset.setStatus("completed");
        return asset;
    }

    @Nested
    @DisplayName("visibleResultUrls —— 前端格子的下标口径")
    class VisibleResultUrls {

        @Test
        @DisplayName("保持 result_urls 的数组顺序")
        void keepsArrayOrder() {
            assertThat(AgentChatServiceImpl.visibleResultUrls(
                    asset(null, "[\"a.png\",\"b.png\",\"c.png\"]")))
                    .containsExactly("a.png", "b.png", "c.png");
        }

        @Test
        @DisplayName("不去重，否则历史重复数据会让下标和前端渲染的格子错位")
        void keepsDuplicates() {
            assertThat(AgentChatServiceImpl.visibleResultUrls(
                    asset(null, "[\"a.png\",\"a.png\",\"b.png\"]")))
                    .containsExactly("a.png", "a.png", "b.png");
        }

        @Test
        @DisplayName("读得懂历史对象数组，并丢掉空值")
        void readsLegacyObjectArray() {
            assertThat(AgentChatServiceImpl.visibleResultUrls(
                    asset(null, "[{\"url\":\"a.png\"},\"\",{\"url\":\"b.png\"}]")))
                    .containsExactly("a.png", "b.png");
        }

        @Test
        @DisplayName("数组为空或坏数据时回退到单 url 字段")
        void fallsBackToSingleUrl() {
            assertThat(AgentChatServiceImpl.visibleResultUrls(asset("only.png", "[]")))
                    .containsExactly("only.png");
            assertThat(AgentChatServiceImpl.visibleResultUrls(asset("only.png", "{broken")))
                    .containsExactly("only.png");
            assertThat(AgentChatServiceImpl.visibleResultUrls(asset("only.png", null)))
                    .containsExactly("only.png");
        }
    }

    @Nested
    @DisplayName("planResultRemoval —— 摘掉一张之后的落库方案")
    class PlanResultRemoval {

        @Test
        @DisplayName("还有剩图时资产保持原状态，只把 survivors 写回")
        void keepsRecordAliveWhenSurvivorsRemain() {
            AgentAsset target = asset("a.png", "[\"a.png\",\"b.png\",\"c.png\"]");

            AgentChatServiceImpl.planResultRemoval(
                    AgentChatServiceImpl.visibleResultUrls(target), 1).applyTo(target);

            assertThat(target.getStatus()).isEqualTo("completed");
            assertThat(target.getResultUrls()).isEqualTo("[\"a.png\",\"c.png\"]");
            assertThat(target.getUrl()).isEqualTo("a.png");
        }

        @Test
        @DisplayName("摘到一张不剩时整条转 deleted，且绝不写空数组（会给 url 回退留后门）")
        void marksDeletedOnLastResult() {
            AgentAsset target = asset("only.png", "[\"only.png\"]");

            AgentChatServiceImpl.planResultRemoval(
                    AgentChatServiceImpl.visibleResultUrls(target), 0).applyTo(target);

            assertThat(target.getStatus()).isEqualTo(AgentAsset.STATUS_DELETED);
            // 留痕：URL 与 result_urls 都不动，缩略图由前端按状态出占位符。
            assertThat(target.getResultUrls()).isEqualTo("[\"only.png\"]");
            assertThat(target.getUrl()).isEqualTo("only.png");
        }

        @Test
        @DisplayName("下标越界直接拒绝，不会误删别的图")
        void rejectsOutOfRangeIndex() {
            List<String> visible = List.of("a.png", "b.png");

            assertThatThrownBy(() -> AgentChatServiceImpl.planResultRemoval(visible, 2))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("素材不存在");
            assertThatThrownBy(() -> AgentChatServiceImpl.planResultRemoval(visible, -1))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Test
    @DisplayName("软删除用的是共享常量，前后端与异步写回守卫认的是同一个值")
    void deletedStatusIsSharedConstant() {
        assertThat(AgentAsset.STATUS_DELETED).isEqualTo("deleted");
    }
}
