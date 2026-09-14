package com.storyboard.asset.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 资产组展开：保持顺序、去重、按媒体类型校验上限。 */
public final class GroupSnapshotExpander {

    private GroupSnapshotExpander() {
    }

    public record Item(
            String mediaId,
            String assetId,
            String blobId,
            String objectKey,
            String mediaType,
            String role,
            int sortOrder
    ) {
    }

    public static List<Item> expand(List<Item> items, int maxImages, int maxVideos, int maxAudios) {
        Map<String, Item> unique = new LinkedHashMap<>();
        if (items != null) {
            items.stream()
                    .filter(item -> item != null && item.mediaId() != null)
                    .sorted(Comparator.comparingInt(Item::sortOrder))
                    .forEach(item -> unique.putIfAbsent(item.mediaId(), item));
        }
        List<Item> sorted = new ArrayList<>(unique.values());
        assertWithinLimit(sorted, "IMAGE", maxImages, "图片");
        assertWithinLimit(sorted, "VIDEO", maxVideos, "视频");
        assertWithinLimit(sorted, "AUDIO", maxAudios, "音频");
        List<Item> result = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            Item item = sorted.get(i);
            result.add(new Item(item.mediaId(), item.assetId(), item.blobId(), item.objectKey(),
                    item.mediaType(), item.role(), i));
        }
        return List.copyOf(result);
    }

    private static void assertWithinLimit(List<Item> items, String type, int max, String label) {
        long count = items.stream().filter(item -> type.equalsIgnoreCase(item.mediaType())).count();
        if (count > max) {
            throw new IllegalArgumentException(label + "数量超过上限 " + max);
        }
    }
}
