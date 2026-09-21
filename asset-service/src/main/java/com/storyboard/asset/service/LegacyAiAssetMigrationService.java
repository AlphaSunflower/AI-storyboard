package com.storyboard.asset.service;

/** 一次性迁移旧 public.assets/asset_images 到统一资产组。 */
public interface LegacyAiAssetMigrationService {

    int migrate(String ownerUserId);
}
