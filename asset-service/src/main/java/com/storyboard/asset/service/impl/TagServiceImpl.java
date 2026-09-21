package com.storyboard.asset.service.impl;

import com.storyboard.asset.client.AssetContracts.AssignTagsRequest;
import com.storyboard.asset.client.AssetContracts.CreateTagRequest;
import com.storyboard.asset.client.AssetContracts.TagRef;
import com.storyboard.asset.repository.LibraryRepository;
import com.storyboard.asset.service.AssetCatalogService;
import com.storyboard.asset.service.TagService;
import com.storyboard.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class TagServiceImpl implements TagService {

    private final LibraryRepository repository;
    private final AssetCatalogService assetCatalogService;

    public TagServiceImpl(LibraryRepository repository, AssetCatalogService assetCatalogService) {
        this.repository = repository;
        this.assetCatalogService = assetCatalogService;
    }

    @Override
    public List<TagRef> list(String userId) {
        return repository.listTagsByUser(userId);
    }

    @Override
    @Transactional
    public TagRef create(CreateTagRequest request) {
        if (request == null || request.ownerUserId() == null || request.name() == null || request.name().isBlank()) {
            throw new BusinessException(40001, "标签名称不能为空");
        }
        String tagId = repository.createTag(request.ownerUserId(), request.name(), request.color());
        return repository.listTagsByUser(request.ownerUserId()).stream()
                .filter(tag -> tag.id().equals(tagId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(50000, "标签创建失败"));
    }

    @Override
    @Transactional
    public void delete(String userId, String tagId) {
        if (tagId == null || tagId.isBlank() || !repository.deleteTag(userId, tagId)) {
            throw new BusinessException(40401, "标签不存在");
        }
    }

    @Override
    @Transactional
    public void replaceAssetTags(String actorUserId, String assetId, AssignTagsRequest request) {
        assetCatalogService.get(actorUserId, assetId);
        repository.replaceTags(actorUserId, assetId, request == null ? List.of() : request.tagIds(), "MANUAL");
    }
}
