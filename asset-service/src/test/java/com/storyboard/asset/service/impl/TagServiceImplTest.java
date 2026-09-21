package com.storyboard.asset.service.impl;

import com.storyboard.asset.repository.LibraryRepository;
import com.storyboard.asset.service.AssetCatalogService;
import com.storyboard.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TagServiceImplTest {

    private LibraryRepository repository;
    private TagServiceImpl service;

    @BeforeEach
    void setUp() {
        repository = mock(LibraryRepository.class);
        service = new TagServiceImpl(repository, mock(AssetCatalogService.class));
    }

    @Test
    void deletesOwnedTag() {
        when(repository.deleteTag("u1", "tag-1")).thenReturn(true);

        service.delete("u1", "tag-1");

        verify(repository).deleteTag("u1", "tag-1");
    }

    @Test
    void rejectsMissingOrForeignTag() {
        when(repository.deleteTag("u1", "tag-1")).thenReturn(false);

        assertThatThrownBy(() -> service.delete("u1", "tag-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("标签不存在");
    }
}
