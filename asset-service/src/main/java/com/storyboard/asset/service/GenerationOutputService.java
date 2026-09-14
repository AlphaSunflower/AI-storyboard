package com.storyboard.asset.service;

import com.storyboard.asset.client.AssetContracts.AssetSummary;
import com.storyboard.asset.client.AssetContracts.GenerationOutputEvent;

public interface GenerationOutputService {

    AssetSummary register(GenerationOutputEvent event);
}
