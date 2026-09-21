package com.storyboard.asset.domain;

import com.storyboard.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FolderPathTest {

    @Test
    void extractsFolderSegmentsFromAWindowsOrPosixRelativePath() {
        assertThat(FolderPath.folderSegments("角色\\正面\\01.png")).containsExactly("角色", "正面");
        assertThat(FolderPath.folderSegments("角色/正面/01.png")).containsExactly("角色", "正面");
    }

    @Test
    void ignoresUnsafeSegmentsAndKeepsTheFileNameOutOfTheTree() {
        assertThat(FolderPath.folderSegments("../角色/./正面/01.png")).containsExactly("角色", "正面");
        assertThat(FolderPath.folderSegments("01.png")).isEmpty();
    }

    @Test
    void rejectsPathsDeeperThanTheSupportedLimit() {
        String path = String.join("/", java.util.Collections.nCopies(33, "层")) + "/01.png";

        assertThatThrownBy(() -> FolderPath.folderSegments(path))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("目录层级");
    }
}
