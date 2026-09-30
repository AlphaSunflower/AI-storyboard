package com.moon.moonagent.service;

import java.util.Collection;

public interface StorageCleanupService {
    void enqueue(String objectUrl);
    void enqueueAll(Collection<String> objectUrls);
}
