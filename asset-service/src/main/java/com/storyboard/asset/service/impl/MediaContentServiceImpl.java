package com.storyboard.asset.service.impl;

import com.storyboard.asset.client.AssetContracts.MediaRef;
import com.storyboard.asset.repository.LibraryRepository;
import com.storyboard.asset.security.MediaUrlSigner;
import com.storyboard.asset.service.MediaContentService;
import com.storyboard.asset.storage.ObjectStorage;
import com.storyboard.asset.storage.StoredObject;
import com.storyboard.common.BusinessException;
import org.springframework.stereotype.Service;

@Service
public class MediaContentServiceImpl implements MediaContentService {

    private final LibraryRepository repository;
    private final ObjectStorage storage;
    private final MediaUrlSigner signer;

    public MediaContentServiceImpl(LibraryRepository repository, ObjectStorage storage, MediaUrlSigner signer) {
        this.repository = repository;
        this.storage = storage;
        this.signer = signer;
    }

    @Override
    public StoredObject open(String userId, String mediaId, String rangeHeader) {
        MediaRef media = repository.findMedia(userId, mediaId)
                .orElseThrow(() -> new BusinessException(40401, "媒体不存在"));
        if (!storage.exists(media.objectKey())) throw new BusinessException(40401, "媒体对象不存在");
        return storage.get(media.objectKey(), rangeHeader);
    }

    @Override
    public StoredObject openSigned(String mediaId, String token, String rangeHeader) {
        String owner = repository.findMediaOwner(mediaId)
                .orElseThrow(() -> new BusinessException(40401, "媒体不存在"));
        signer.verify(mediaId, owner, token);
        return open(owner, mediaId, rangeHeader);
    }
}
