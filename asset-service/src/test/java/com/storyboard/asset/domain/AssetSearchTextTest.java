package com.storyboard.asset.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AssetSearchTextTest {

    @Test
    void includesNameDescriptionAiDescriptionAndTags() {
        String searchText = AssetSearchText.build(
                "  赛博 人物 ",
                "雨夜街道",
                "银发机械臂",
                List.of("角色", "Cyberpunk")
        );

        assertThat(searchText)
                .contains("赛博 人物")
                .contains("雨夜街道")
                .contains("银发机械臂")
                .contains("角色")
                .contains("cyberpunk");
    }
}
