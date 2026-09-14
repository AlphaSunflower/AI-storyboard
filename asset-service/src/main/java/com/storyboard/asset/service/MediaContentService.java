package com.storyboard.asset.service;

import com.storyboard.asset.storage.StoredObject;

public interface MediaContentService {

    StoredObject open(String userId, String mediaId, String rangeHeader);

    StoredObject openSigned(String mediaId, String token, String rangeHeader);
}
