# MinIO 孤儿清理与项目库回收站 3 天 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 MinIO 只保留仍被项目库或旧画布/旧 AI 资产引用的图片与视频对象，并把项目库回收站保留期从 7 天改为 3 天、到期自动进入既有清理队列。

**Architecture:** 在 asset-service 内新增三层组件：`ObjectStorage.listObjects` 提供桶列举能力；`ObjectReferenceGuard`（组合 `LegacyObjectReferenceRepository` 读 public schema、`LibraryRepository` 读 asset schema）提供唯一的引用判定；`StorageOrphanScanner` 定时扫描 `images/`、`videos/` 并把无引用对象写入既有 `asset.library_storage_cleanup_tasks` 队列，删除仍由既有 `StorageCleanupWorker` 执行。回收站到期清理抽出共享的 `AssetPurgeService`，由 `TrashPurgeWorker` 定时驱动，与手动 purge 走同一条代码路径。

**Tech Stack:** Java 21、Spring Boot 3、Spring JDBC（`JdbcClient` / `NamedParameterJdbcTemplate`）、AWS SDK v2 S3 client、PostgreSQL、Flyway、JUnit 5 + Mockito + AssertJ。

**Spec:** `docs/superpowers/specs/2026-09-28-minio-orphan-cleanup-and-3day-trash-design.md`

## Global Constraints

- 清理范围仅限 `images/` 与 `videos/` 前缀；`audios/`、`files/` 及任何其他前缀一律不扫描、不入队、不删除。
- 只删除 MinIO 对象，绝不删除数据库记录。
- 保护集合是并集，任一条命中即不清理：① 项目库引用（`asset.library_media`、`library_group_items`、`library_group_snapshot_items`，资产状态不在 `PURGE_PENDING`/`PURGED`）② `public.canvas_assets.object_key`（不看 `status` 与 `library_sync_state`）③ `public.asset_images.url` ④ `public.scene_reference_images.image_url`、`public.scenes.image_url`、`public.scenes.video_url`、`public.agent_assets.url`、`public.agent_assets.result_urls` ⑤ `asset.library_upload_sessions` 中 `status = 'CREATED' AND expires_at > now()` ⑥ `lastModified > now() - gracePeriod` 的宽限期对象。
- public schema 表不存在时视为“该来源不存在”；查询执行本身报错时整轮扫描中止，不产生任何新清理任务（fail-safe）。
- 历史 URL 无法解析为 object key 时不猜测，直接忽略该 URL。
- 删除前二次确认必须与扫描使用同一实现（`ObjectReferenceGuard`），不得保留第二套判定。
- `trashRetentionDays` 默认 3，替换三处硬编码 7 天：`AssetCatalogServiceImpl.trash`、`AssetCatalogServiceImpl.bulkTrash`、`LibraryRepository.trashAssetsInFolders`。
- 回收站到期资产：到期且无活跃引用 → 入队并置 `PURGED`；到期但仍有活跃引用 → 置 `PURGE_BLOCKED`，对象保留。
- 主动扫描默认关闭（`orphan-scan-enabled=false`），默认频率 6 小时一轮，默认宽限期 24 小时。
- 验证命令（已在 clean 工作区验证可运行，Java 25 亦可编译/运行）：
  `& "E:\Development\apache-maven-3.9.15\bin\mvn.cmd" -pl asset-service -am test "-Dtest=<TestClass>" "-Dsurefire.failIfNoSpecifiedTests=false"`
- 除测试外的每一步都以独立 commit 收尾；commit message 遵循 `feat:` / `test:` / `refactor:` 前缀。

---

## 文件结构

**新增**

- `asset-service/src/main/java/com/storyboard/asset/domain/ObjectKeys.java` — URL/JSON → object key 的纯解析工具，供引用判定与旧资产迁移共用。
- `asset-service/src/main/java/com/storyboard/asset/repository/LegacyObjectReferenceRepository.java` — 只读 public schema 的旧画布/旧 AI 资产/旧分镜引用来源，含 `to_regclass` 能力探测与短 TTL 缓存。
- `asset-service/src/main/java/com/storyboard/asset/cleanup/ObjectReferenceGuard.java` — 唯一的引用判定组件。
- `asset-service/src/main/java/com/storyboard/asset/worker/StorageOrphanScanner.java` — 定时桶扫描 + 入队。
- `asset-service/src/main/java/com/storyboard/asset/service/AssetPurgeService.java` — 手动 purge 与到期自动清理的共享逻辑。
- `asset-service/src/main/java/com/storyboard/asset/worker/TrashPurgeWorker.java` — 定时驱动到期清理。
- 对应测试：`MinioObjectStorageTest`、`ObjectKeysTest`、`LegacyObjectReferenceRepositoryTest`、`ObjectReferenceGuardTest`、`StorageOrphanScannerTest`、`AssetPurgeServiceTest`、`TrashPurgeWorkerTest`。

**修改**

- `ObjectStorage.java` / `MinioObjectStorage.java` — 新增分页列举。
- `StorageProperties.java` — 新增 `trashRetentionDays` 与孤儿扫描配置。
- `LibraryRepository.java` — 新增批量引用判定、上传会话判定、到期回收站领取；参数化回收期；删除 `objectKeyHasReferences`。
- `StorageCleanupWorker.java` — 二次确认改用 `ObjectReferenceGuard`。
- `AssetCatalogServiceImpl.java` / `LibraryFolderServiceImpl.java` — 回收期取配置；purge 委托 `AssetPurgeService`。
- `LegacyAiAssetMigrationServiceImpl.java` — 复用 `ObjectKeys.fromUrl`。
- `asset-service/src/main/resources/application.yml` — 新增配置项与默认值。

**依赖顺序**

Task 1 → Task 2 → Task 3 → Task 4 → Task 5 → Task 6 → Task 7 → Task 8 → Task 9。
Task 4 依赖 Task 2、3；Task 5、7 依赖 Task 4；Task 8 依赖 Task 5（`ObjectReferenceGuard` 已生效）。

---

### Task 1: ObjectStorage 分页列举能力

**Files:**
- Modify: `asset-service/src/main/java/com/storyboard/asset/storage/ObjectStorage.java`
- Modify: `asset-service/src/main/java/com/storyboard/asset/storage/MinioObjectStorage.java`
- Test: `asset-service/src/test/java/com/storyboard/asset/storage/MinioObjectStorageTest.java`

**Interfaces:**
- Consumes: 无（本任务是最底层）
- Produces:
  - `ObjectStorage.ObjectSummary(String key, long sizeBytes, java.time.Instant lastModified)`
  - `ObjectStorage.ObjectPage(java.util.List<ObjectSummary> objects, String nextContinuationToken)`
  - `ObjectStorage.ObjectPage listObjects(String prefix, String continuationToken, int maxKeys)`
  - 测试用构造器 `MinioObjectStorage(StorageProperties properties, S3Client client)`

- [ ] **Step 1: 写失败测试**

创建 `asset-service/src/test/java/com/storyboard/asset/storage/MinioObjectStorageTest.java`：

```java
package com.storyboard.asset.storage;

import com.storyboard.asset.config.StorageProperties;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MinioObjectStorageTest {

    private static StorageProperties properties() {
        return new StorageProperties(
                "http://localhost:9000", "minioadmin", "minioadmin123", "storyboard", "us-east-1",
                0, 0, 0);
    }

    @Test
    void listsObjectsWithPaginationToken() {
        S3Client client = mock(S3Client.class);
        MinioObjectStorage storage = new MinioObjectStorage(properties(), client);
        Instant lastModified = Instant.parse("2026-09-01T00:00:00Z");
        when(client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(ListObjectsV2Response.builder()
                .contents(S3Object.builder()
                        .key("images/a.png")
                        .size(123L)
                        .lastModified(lastModified)
                        .build())
                .isTruncated(true)
                .nextContinuationToken("token-2")
                .build());

        ObjectStorage.ObjectPage page = storage.listObjects("images/", null, 1000);

        assertThat(page.objects()).hasSize(1);
        assertThat(page.objects().get(0).key()).isEqualTo("images/a.png");
        assertThat(page.objects().get(0).sizeBytes()).isEqualTo(123L);
        assertThat(page.objects().get(0).lastModified()).isEqualTo(lastModified);
        assertThat(page.nextContinuationToken()).isEqualTo("token-2");
    }

    @Test
    void returnsNullTokenWhenListingIsComplete() {
        S3Client client = mock(S3Client.class);
        MinioObjectStorage storage = new MinioObjectStorage(properties(), client);
        when(client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(ListObjectsV2Response.builder()
                .contents(S3Object.builder().key("videos/b.mp4").size(9L).lastModified(Instant.EPOCH).build())
                .isTruncated(false)
                .build());

        ObjectStorage.ObjectPage page = storage.listObjects("videos/", null, 1000);

        assertThat(page.objects()).hasSize(1);
        assertThat(page.nextContinuationToken()).isNull();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `& "E:\Development\apache-maven-3.9.15\bin\mvn.cmd" -pl asset-service -am test "-Dtest=MinioObjectStorageTest" "-Dsurefire.failIfNoSpecifiedTests=false"`
Expected: 编译失败，报 `listObjects`、`ObjectPage`、`ObjectSummary` 不存在（或 `MinioObjectStorage(StorageProperties, S3Client)` 构造器不存在）。

- [ ] **Step 3: 最小实现**

在 `ObjectStorage.java` 末尾（`MultipartUpload` record 之前）新增：

```java
    ObjectPage listObjects(String prefix, String continuationToken, int maxKeys);

    record ObjectSummary(String key, long sizeBytes, java.time.Instant lastModified) {
    }

    record ObjectPage(java.util.List<ObjectSummary> objects, String nextContinuationToken) {
    }
```

在 `MinioObjectStorage.java` 中：

1. 增加 import：`software.amazon.awssdk.services.s3.model.ListObjectsV2Request`、`software.amazon.awssdk.services.s3.model.ListObjectsV2Response`、`software.amazon.awssdk.services.s3.model.S3Object`、`java.util.List`。
2. 增加测试用构造器与字段哨兵：

```java
    private final S3Client injectedClient;

    public MinioObjectStorage(StorageProperties properties) {
        this(properties, null);
    }

    MinioObjectStorage(StorageProperties properties, S3Client injectedClient) {
        this.properties = properties;
        this.injectedClient = injectedClient;
    }
```

3. 在 `init()` 开头加入：`if (injectedClient != null) { this.client = injectedClient; } else { ...原有构建逻辑... }`，并把 presigner 的构建保留在 `else` 分支内（`injectedClient` 非空时 presigner 为 `null`，测试不触及）。
4. 新增方法：

```java
    @Override
    public ObjectPage listObjects(String prefix, String continuationToken, int maxKeys) {
        ListObjectsV2Request.Builder request = ListObjectsV2Request.builder()
                .bucket(properties.bucket())
                .prefix(prefix)
                .maxKeys(maxKeys);
        if (continuationToken != null && !continuationToken.isBlank()) {
            request.continuationToken(continuationToken);
        }
        ListObjectsV2Response response = client.listObjectsV2(request.build());
        List<ObjectSummary> objects = response.contents().stream()
                .map((S3Object object) -> new ObjectSummary(
                        object.key(), object.size() == null ? 0L : object.size(), object.lastModified()))
                .toList();
        String next = Boolean.TRUE.equals(response.isTruncated()) ? response.nextContinuationToken() : null;
        return new ObjectPage(objects, next);
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `& "E:\Development\apache-maven-3.9.15\bin\mvn.cmd" -pl asset-service -am test "-Dtest=MinioObjectStorageTest" "-Dsurefire.failIfNoSpecifiedTests=false"`
Expected: BUILD SUCCESS，2 个测试通过。

- [ ] **Step 5: 提交**

```bash
git add asset-service/src/main/java/com/storyboard/asset/storage/ObjectStorage.java asset-service/src/main/java/com/storyboard/asset/storage/MinioObjectStorage.java asset-service/src/test/java/com/storyboard/asset/storage/MinioObjectStorageTest.java
git commit -m "feat(asset): 增加 MinIO 对象分页列举能力"
```

---

### Task 2: ObjectKeys URL 解析工具

**Files:**
- Create: `asset-service/src/main/java/com/storyboard/asset/domain/ObjectKeys.java`
- Modify: `asset-service/src/main/java/com/storyboard/asset/service/impl/LegacyAiAssetMigrationServiceImpl.java:130-145`
- Test: `asset-service/src/test/java/com/storyboard/asset/domain/ObjectKeysTest.java`

**Interfaces:**
- Consumes: 无
- Produces:
  - `ObjectKeys.fromUrl(String url)` → `String`（无法解析返回 `null`）
  - `ObjectKeys.fromJsonArray(String json)` → `java.util.Set<String>`（解析失败返回空集合）

- [ ] **Step 1: 写失败测试**

创建 `asset-service/src/test/java/com/storyboard/asset/domain/ObjectKeysTest.java`：

```java
package com.storyboard.asset.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ObjectKeysTest {

    @Test
    void parsesApiFilesUrlWithQuery() {
        assertThat(ObjectKeys.fromUrl("/api/files/images/a.png?token=1"))
                .isEqualTo("images/a.png");
    }

    @Test
    void parsesBarePrefixUrl() {
        assertThat(ObjectKeys.fromUrl("videos/b.mp4")).isEqualTo("videos/b.mp4");
        assertThat(ObjectKeys.fromUrl("audios/c.mp3")).isEqualTo("audios/c.mp3");
        assertThat(ObjectKeys.fromUrl("files/d.pdf")).isEqualTo("files/d.pdf");
    }

    @Test
    void parsesAbsoluteApiFilesUrl() {
        assertThat(ObjectKeys.fromUrl("http://localhost:8080/api/files/images/e.jpg"))
                .isEqualTo("images/e.jpg");
    }

    @Test
    void returnsNullForUnresolvableUrl() {
        assertThat(ObjectKeys.fromUrl(null)).isNull();
        assertThat(ObjectKeys.fromUrl("")).isNull();
        assertThat(ObjectKeys.fromUrl("data:image/png;base64,AAAA")).isNull();
        assertThat(ObjectKeys.fromUrl("https://example.com/pic.png")).isNull();
        assertThat(ObjectKeys.fromUrl("/uploads/local.png")).isNull();
    }

    @Test
    void parsesJsonArrayOfUrls() {
        assertThat(ObjectKeys.fromJsonArray("[\"/api/files/images/a.png\",\"videos/b.mp4\"]"))
                .containsExactlyInAnyOrder("images/a.png", "videos/b.mp4");
    }

    @Test
    void ignoresUnresolvableEntriesInJsonArray() {
        assertThat(ObjectKeys.fromJsonArray("[\"https://example.com/x.png\",\"images/ok.png\"]"))
                .containsExactly("images/ok.png");
        assertThat(ObjectKeys.fromJsonArray("not-json")).isEmpty();
        assertThat(ObjectKeys.fromJsonArray(null)).isEmpty();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `& "E:\Development\apache-maven-3.9.15\bin\mvn.cmd" -pl asset-service -am test "-Dtest=ObjectKeysTest" "-Dsurefire.failIfNoSpecifiedTests=false"`
Expected: 编译失败，`ObjectKeys` 不存在。

- [ ] **Step 3: 最小实现**

创建 `asset-service/src/main/java/com/storyboard/asset/domain/ObjectKeys.java`：

```java
package com.storyboard.asset.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 历史 URL / JSON 数组到 object key 的保守解析；无法确定时返回 null 或空集合。 */
public final class ObjectKeys {

    private static final String API_FILES_MARKER = "/api/files/";
    private static final List<String> PREFIXES = List.of("images/", "videos/", "audios/", "files/");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ObjectKeys() {
    }

    public static String fromUrl(String url) {
        if (url == null || url.isBlank()) return null;
        String clean = url.trim();
        int query = clean.indexOf('?');
        if (query >= 0) clean = clean.substring(0, query);
        int fragment = clean.indexOf('#');
        if (fragment >= 0) clean = clean.substring(0, fragment);
        int marker = clean.indexOf(API_FILES_MARKER);
        if (marker >= 0) clean = clean.substring(marker + API_FILES_MARKER.length());
        for (String prefix : PREFIXES) {
            if (clean.startsWith(prefix) && clean.length() > prefix.length()) return clean;
        }
        return null;
    }

    public static Set<String> fromJsonArray(String json) {
        if (json == null || json.isBlank()) return Set.of();
        Set<String> keys = new LinkedHashSet<>();
        try {
            JsonNode node = MAPPER.readTree(json);
            if (!node.isArray()) return Set.of();
            for (JsonNode element : node) {
                if (!element.isTextual()) continue;
                String key = fromUrl(element.asText());
                if (key != null) keys.add(key);
            }
        } catch (Exception e) {
            return Set.of();
        }
        return keys;
    }
}
```

在 `LegacyAiAssetMigrationServiceImpl.java` 中删除私有静态方法 `objectKey(String url)`，把调用点 `String objectKey = objectKey(image.url());` 改为 `String objectKey = com.storyboard.asset.domain.ObjectKeys.fromUrl(image.url());`，并在 import 区加入 `com.storyboard.asset.domain.ObjectKeys`。

- [ ] **Step 4: 运行测试确认通过**

Run: `& "E:\Development\apache-maven-3.9.15\bin\mvn.cmd" -pl asset-service -am test "-Dtest=ObjectKeysTest" "-Dsurefire.failIfNoSpecifiedTests=false"`
Expected: BUILD SUCCESS，6 个测试通过。

- [ ] **Step 5: 提交**

```bash
git add asset-service/src/main/java/com/storyboard/asset/domain/ObjectKeys.java asset-service/src/main/java/com/storyboard/asset/service/impl/LegacyAiAssetMigrationServiceImpl.java asset-service/src/test/java/com/storyboard/asset/domain/ObjectKeysTest.java
git commit -m "refactor(asset): 抽取历史 URL 到 object key 的解析工具"
```

---

### Task 3: StorageProperties 回收期与扫描配置

**Files:**
- Modify: `asset-service/src/main/java/com/storyboard/asset/config/StorageProperties.java`
- Modify: `asset-service/src/main/resources/application.yml`
- Modify: `asset-service/src/test/java/com/storyboard/asset/config/AssetPropertiesConfigTest.java`
- Modify: `asset-service/src/test/java/com/storyboard/asset/storage/MinioObjectStorageTest.java:26-31`

**Interfaces:**
- Consumes: 无
- Produces:
  - `StorageProperties.trashRetentionDays()` → `int`（默认 3）
  - `StorageProperties.orphanScanEnabled()` → `boolean`（默认 `false`）
  - `StorageProperties.orphanScanIntervalHours()` → `int`（默认 6）
  - `StorageProperties.orphanScanGraceHours()` → `int`（默认 24）
  - `StorageProperties` 构造器从 8 参扩展为 12 参，新增顺序为
    `int trashRetentionDays, boolean orphanScanEnabled, int orphanScanIntervalHours, int orphanScanGraceHours`

- [ ] **Step 1: 写失败测试**

把 `asset-service/src/test/java/com/storyboard/asset/config/AssetPropertiesConfigTest.java` 整体替换为：

```java
package com.storyboard.asset.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AssetPropertiesConfigTest {

    @Test
    void gatewayApiKeyFallsBackToLegacyAiGatewayProperty() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource(
                "test-overrides",
                Map.of("ai.gateway.api-key", "lg-test")
        ));
        loadApplicationYaml(environment);

        AssetProperties properties = Binder.get(environment)
                .bind("storyboard.asset", AssetProperties.class)
                .orElseThrow(() -> new IllegalStateException("asset properties did not bind"));

        assertThat(properties.gatewayApiKey()).isEqualTo("lg-test");
    }

    @Test
    void storageDefaultsToThreeDayTrashRetentionAndDisabledOrphanScan() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        loadApplicationYaml(environment);

        StorageProperties properties = Binder.get(environment)
                .bind("storyboard.storage", StorageProperties.class)
                .orElseThrow(() -> new IllegalStateException("storage properties did not bind"));

        assertThat(properties.trashRetentionDays()).isEqualTo(3);
        assertThat(properties.orphanScanEnabled()).isFalse();
        assertThat(properties.orphanScanIntervalHours()).isEqualTo(6);
        assertThat(properties.orphanScanGraceHours()).isEqualTo(24);
    }

    @Test
    void storageBindsExplicitOverrides() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        Map<String, Object> overrides = new HashMap<>();
        overrides.put("storyboard.storage.trash-retention-days", "5");
        overrides.put("storyboard.storage.orphan-scan-enabled", "true");
        overrides.put("storyboard.storage.orphan-scan-interval-hours", "2");
        overrides.put("storyboard.storage.orphan-scan-grace-hours", "12");
        environment.getPropertySources().addFirst(new MapPropertySource("test-overrides", overrides));
        loadApplicationYaml(environment);

        StorageProperties properties = Binder.get(environment)
                .bind("storyboard.storage", StorageProperties.class)
                .orElseThrow(() -> new IllegalStateException("storage properties did not bind"));

        assertThat(properties.trashRetentionDays()).isEqualTo(5);
        assertThat(properties.orphanScanEnabled()).isTrue();
        assertThat(properties.orphanScanIntervalHours()).isEqualTo(2);
        assertThat(properties.orphanScanGraceHours()).isEqualTo(12);
    }

    private static void loadApplicationYaml(StandardEnvironment environment) throws Exception {
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
    }
}
```

同时把 `asset-service/src/test/java/com/storyboard/asset/storage/MinioObjectStorageTest.java` 的 `properties()` 改成 12 参：

```java
    private static StorageProperties properties() {
        return new StorageProperties(
                "http://localhost:9000", "minioadmin", "minioadmin123", "storyboard", "us-east-1",
                0, 0, 0, 0, false, 0, 0);
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `& "E:\Development\apache-maven-3.9.15\bin\mvn.cmd" -pl asset-service -am test "-Dtest=AssetPropertiesConfigTest,MinioObjectStorageTest" "-Dsurefire.failIfNoSpecifiedTests=false"`
Expected: 测试编译失败，提示 `trashRetentionDays()` 等方法不存在，或 12 参构造器参数个数不匹配。

- [ ] **Step 3: 最小实现**

把 `asset-service/src/main/java/com/storyboard/asset/config/StorageProperties.java` 整体替换为：

```java
package com.storyboard.asset.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "storyboard.storage")
public record StorageProperties(
        String endpoint,
        String accessKey,
        String secretKey,
        String bucket,
        String region,
        long uploadPartSizeBytes,
        int uploadSessionTtlHours,
        int signedUrlTtlMinutes,
        int trashRetentionDays,
        boolean orphanScanEnabled,
        int orphanScanIntervalHours,
        int orphanScanGraceHours
) {
    public static final int DEFAULT_TRASH_RETENTION_DAYS = 3;
    public static final int DEFAULT_ORPHAN_SCAN_INTERVAL_HOURS = 6;
    public static final int DEFAULT_ORPHAN_SCAN_GRACE_HOURS = 24;

    public StorageProperties {
        endpoint = endpoint == null ? "http://localhost:9000" : endpoint;
        accessKey = accessKey == null ? "minioadmin" : accessKey;
        secretKey = secretKey == null ? "minioadmin123" : secretKey;
        bucket = bucket == null ? "storyboard" : bucket;
        region = region == null ? "us-east-1" : region;
        uploadPartSizeBytes = uploadPartSizeBytes <= 0 ? 8L * 1024 * 1024 : uploadPartSizeBytes;
        uploadSessionTtlHours = uploadSessionTtlHours <= 0 ? 24 : uploadSessionTtlHours;
        signedUrlTtlMinutes = signedUrlTtlMinutes <= 0 ? 15 : signedUrlTtlMinutes;
        trashRetentionDays = trashRetentionDays <= 0 ? DEFAULT_TRASH_RETENTION_DAYS : trashRetentionDays;
        orphanScanIntervalHours =
                orphanScanIntervalHours <= 0 ? DEFAULT_ORPHAN_SCAN_INTERVAL_HOURS : orphanScanIntervalHours;
        orphanScanGraceHours = orphanScanGraceHours <= 0 ? DEFAULT_ORPHAN_SCAN_GRACE_HOURS : orphanScanGraceHours;
    }
}
```

在 `asset-service/src/main/resources/application.yml` 的 `storyboard.storage` 段末尾（`signed-url-ttl-minutes` 之后）追加：

```yaml
    trash-retention-days: ${STORYBOARD_TRASH_RETENTION_DAYS:3}
    orphan-scan-enabled: ${STORYBOARD_ORPHAN_SCAN_ENABLED:false}
    orphan-scan-interval-hours: ${STORYBOARD_ORPHAN_SCAN_INTERVAL_HOURS:6}
    orphan-scan-grace-hours: ${STORYBOARD_ORPHAN_SCAN_GRACE_HOURS:24}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `& "E:\Development\apache-maven-3.9.15\bin\mvn.cmd" -pl asset-service -am test "-Dtest=AssetPropertiesConfigTest,MinioObjectStorageTest" "-Dsurefire.failIfNoSpecifiedTests=false"`
Expected: BUILD SUCCESS，`AssetPropertiesConfigTest` 3 个、`MinioObjectStorageTest` 2 个测试全部通过。

- [ ] **Step 5: 提交**

```bash
git add asset-service/src/main/java/com/storyboard/asset/config/StorageProperties.java asset-service/src/main/resources/application.yml asset-service/src/test/java/com/storyboard/asset/config/AssetPropertiesConfigTest.java asset-service/src/test/java/com/storyboard/asset/storage/MinioObjectStorageTest.java
git commit -m "feat(asset): 增加回收站保留期与孤儿扫描配置"
```

---

### Task 4: 引用来源仓库（LegacyObjectReferenceRepository + LibraryRepository 批量判定）

**Files:**
- Create: `asset-service/src/main/java/com/storyboard/asset/repository/LegacyObjectReferenceRepository.java`
- Modify: `asset-service/src/main/java/com/storyboard/asset/repository/LibraryRepository.java`（在 `hasActiveReferences` 之后、`objectKeyHasReferences` 之前插入）
- Test: `asset-service/src/test/java/com/storyboard/asset/repository/LegacyObjectReferenceRepositoryTest.java`
- Test: `asset-service/src/test/java/com/storyboard/asset/repository/LibraryRepositoryReferenceSqlTest.java`

**Interfaces:**
- Consumes: `ObjectKeys.fromUrl` / `ObjectKeys.fromJsonArray`（Task 2）
- Produces:
  - `LegacyObjectReferenceRepository(JdbcClient jdbc)`
  - `LegacyObjectReferenceRepository.referencedKeys(Collection<String> candidates)` → `Set<String>`（candidates 中被历史来源引用的子集）
  - `LibraryRepository.referencedObjectKeys(Collection<String> objectKeys)` → `Set<String>`（项目库引用子集）
  - `LibraryRepository.activeUploadSessionObjectKeys(Collection<String> objectKeys)` → `Set<String>`（未过期上传会话占用子集）
  - 包私有常量 `LegacyObjectReferenceRepository.CANVAS_ASSETS_SQL` / `ASSET_IMAGES_SQL` / `SCENE_REFERENCE_IMAGES_SQL` / `SCENES_IMAGE_SQL` / `SCENES_VIDEO_SQL` / `AGENT_ASSET_URL_SQL` / `AGENT_RESULT_URLS_SQL`
  - 包私有常量 `LibraryRepository.REFERENCED_OBJECT_KEYS_SQL` / `ACTIVE_UPLOAD_SESSION_KEYS_SQL`

> 说明：本仓库没有任何数据库集成测试基础设施（无 Testcontainers、无 repository 层测试）。因此本任务用 **SQL 守卫测试** 锁定保护语义（表名、状态过滤、参数占位），任何后续重构若静默放宽过滤条件都会让测试失败；真实 SQL 语义由 Task 9 的人工验收脚本覆盖。

- [ ] **Step 1: 写失败测试**

创建 `asset-service/src/test/java/com/storyboard/asset/repository/LegacyObjectReferenceRepositoryTest.java`：

```java
package com.storyboard.asset.repository;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class LegacyObjectReferenceRepositoryTest {

    @Test
    void coversEveryProtectedLegacySource() {
        assertThat(LegacyObjectReferenceRepository.CANVAS_ASSETS_SQL)
                .contains("public.canvas_assets").contains("object_key IN (:keys)");
        assertThat(LegacyObjectReferenceRepository.ASSET_IMAGES_SQL)
                .contains("public.asset_images").contains("url");
        assertThat(LegacyObjectReferenceRepository.SCENE_REFERENCE_IMAGES_SQL)
                .contains("public.scene_reference_images").contains("image_url");
        assertThat(LegacyObjectReferenceRepository.SCENES_IMAGE_SQL)
                .contains("public.scenes").contains("image_url");
        assertThat(LegacyObjectReferenceRepository.SCENES_VIDEO_SQL)
                .contains("public.scenes").contains("video_url");
        assertThat(LegacyObjectReferenceRepository.AGENT_ASSET_URL_SQL)
                .contains("public.agent_assets").contains("url");
        assertThat(LegacyObjectReferenceRepository.AGENT_RESULT_URLS_SQL)
                .contains("public.agent_assets").contains("result_urls");
    }

    @Test
    void normalizesApiFilesPrefixAndStripsQueryString() {
        assertThat(LegacyObjectReferenceRepository.ASSET_IMAGES_SQL)
                .contains("/api/files/").contains("split_part").contains("?");
        assertThat(LegacyObjectReferenceRepository.SCENES_VIDEO_SQL)
                .contains("/api/files/").contains("video_url");
    }

    @Test
    void emptyCandidatesSkipDatabase() {
        JdbcClient jdbc = mock(JdbcClient.class);
        LegacyObjectReferenceRepository repository = new LegacyObjectReferenceRepository(jdbc);

        assertThat(repository.referencedKeys(List.of())).isEmpty();
        assertThat(repository.referencedKeys(null)).isEmpty();

        verifyNoInteractions(jdbc);
    }
}
```

创建 `asset-service/src/test/java/com/storyboard/asset/repository/LibraryRepositoryReferenceSqlTest.java`：

```java
package com.storyboard.asset.repository;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LibraryRepositoryReferenceSqlTest {

    @Test
    void referencedKeysSqlCoversAllLibrarySources() {
        String sql = LibraryRepository.REFERENCED_OBJECT_KEYS_SQL;
        assertThat(sql).contains("asset.library_media");
        assertThat(sql).contains("asset.library_group_items");
        assertThat(sql).contains("asset.library_group_snapshot_items");
        assertThat(sql).contains("IN (:keys)");
    }

    @Test
    void referencedKeysSqlExcludesPurgeStates() {
        assertThat(LibraryRepository.REFERENCED_OBJECT_KEYS_SQL)
                .contains("NOT IN ('PURGE_PENDING', 'PURGED')");
    }

    @Test
    void uploadSessionSqlOnlyProtectsLiveSessions() {
        assertThat(LibraryRepository.ACTIVE_UPLOAD_SESSION_KEYS_SQL)
                .contains("asset.library_upload_sessions")
                .contains("status = 'CREATED'")
                .contains("expires_at > now()")
                .contains("IN (:keys)");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `& "E:\Development\apache-maven-3.9.15\bin\mvn.cmd" -pl asset-service -am test "-Dtest=LegacyObjectReferenceRepositoryTest,LibraryRepositoryReferenceSqlTest" "-Dsurefire.failIfNoSpecifiedTests=false"`
Expected: 编译失败，`LegacyObjectReferenceRepository` 与 `LibraryRepository.REFERENCED_OBJECT_KEYS_SQL` 不存在。

- [ ] **Step 3: 最小实现**

创建 `asset-service/src/main/java/com/storyboard/asset/repository/LegacyObjectReferenceRepository.java`：

```java
package com.storyboard.asset.repository;

import com.storyboard.asset.domain.ObjectKeys;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * public schema 历史引用来源（旧画布 / 旧 AI 资产 / 旧分镜）的只读判定。
 * 表或列不存在视为该来源不存在；查询执行报错时向上抛出，由调用方整体 fail-safe。
 */
@Repository
public class LegacyObjectReferenceRepository {

    static final Duration PROBE_TTL = Duration.ofMinutes(5);

    static final String CANVAS_ASSETS_SQL = """
            SELECT DISTINCT object_key AS key
            FROM public.canvas_assets
            WHERE object_key IN (:keys)
            """;

    static final String ASSET_IMAGES_SQL = urlKeySql("public.asset_images", "url");
    static final String SCENE_REFERENCE_IMAGES_SQL =
            urlKeySql("public.scene_reference_images", "image_url");
    static final String SCENES_IMAGE_SQL = urlKeySql("public.scenes", "image_url");
    static final String SCENES_VIDEO_SQL = urlKeySql("public.scenes", "video_url");
    static final String AGENT_ASSET_URL_SQL = urlKeySql("public.agent_assets", "url");
    static final String AGENT_RESULT_URLS_SQL = """
            SELECT DISTINCT result_urls
            FROM public.agent_assets
            WHERE result_urls LIKE '%images/%' OR result_urls LIKE '%videos/%'
            """;

    private final JdbcClient jdbc;
    private final Map<String, Boolean> probeResults = new ConcurrentHashMap<>();
    private final Map<String, Instant> probeTimes = new ConcurrentHashMap<>();

    public LegacyObjectReferenceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 返回 candidates 中仍被 public schema 历史来源引用的子集。 */
    public Set<String> referencedKeys(Collection<String> candidates) {
        List<String> keys = candidates == null ? List.of() : candidates.stream()
                .filter(key -> key != null && !key.isBlank())
                .distinct()
                .toList();
        if (keys.isEmpty()) return Set.of();

        Set<String> referenced = new LinkedHashSet<>();
        if (sourceExists("public.canvas_assets", "object_key")) {
            referenced.addAll(queryKeys(CANVAS_ASSETS_SQL, keys));
        }
        if (sourceExists("public.asset_images", "url")) {
            referenced.addAll(queryKeys(ASSET_IMAGES_SQL, keys));
        }
        if (sourceExists("public.scene_reference_images", "image_url")) {
            referenced.addAll(queryKeys(SCENE_REFERENCE_IMAGES_SQL, keys));
        }
        if (sourceExists("public.scenes", "image_url")) {
            referenced.addAll(queryKeys(SCENES_IMAGE_SQL, keys));
        }
        if (sourceExists("public.scenes", "video_url")) {
            referenced.addAll(queryKeys(SCENES_VIDEO_SQL, keys));
        }
        if (sourceExists("public.agent_assets", "url")) {
            referenced.addAll(queryKeys(AGENT_ASSET_URL_SQL, keys));
        }
        if (sourceExists("public.agent_assets", "result_urls")) {
            Set<String> fromJson = new LinkedHashSet<>();
            for (String json : jdbc.sql(AGENT_RESULT_URLS_SQL).query(String.class).list()) {
                fromJson.addAll(ObjectKeys.fromJsonArray(json));
            }
            fromJson.retainAll(keys);
            referenced.addAll(fromJson);
        }
        return referenced;
    }

    private Set<String> queryKeys(String sql, List<String> keys) {
        return new LinkedHashSet<>(jdbc.sql(sql).param("keys", keys).query(String.class).list());
    }

    private boolean sourceExists(String qualifiedTable, String column) {
        String cacheKey = qualifiedTable + "." + column;
        Instant checkedAt = probeTimes.get(cacheKey);
        if (checkedAt != null && Duration.between(checkedAt, Instant.now()).compareTo(PROBE_TTL) < 0) {
            return Boolean.TRUE.equals(probeResults.get(cacheKey));
        }
        String[] parts = qualifiedTable.split("\\.", 2);
        Boolean exists = jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM information_schema.columns
                            WHERE table_schema = :schema
                              AND table_name = :table
                              AND column_name = :column
                        )
                        """)
                .param("schema", parts[0])
                .param("table", parts.length > 1 ? parts[1] : parts[0])
                .param("column", column)
                .query(Boolean.class)
                .single();
        probeResults.put(cacheKey, Boolean.TRUE.equals(exists));
        probeTimes.put(cacheKey, Instant.now());
        return Boolean.TRUE.equals(exists);
    }

    private static String urlKeySql(String table, String column) {
        return """
                SELECT key FROM (
                    SELECT DISTINCT split_part(regexp_replace(%s, '^.*/api/files/', ''), '?', 1) AS key
                    FROM %s
                    WHERE %s IS NOT NULL
                ) normalized
                WHERE normalized.key IN (:keys)
                """.formatted(column, table, column);
    }
}
```

在 `asset-service/src/main/java/com/storyboard/asset/repository/LibraryRepository.java` 的 `hasActiveReferences` 方法之后、`objectKeyHasReferences` 方法之前插入：

```java
    static final String REFERENCED_OBJECT_KEYS_SQL = """
            SELECT DISTINCT b.object_key AS key
            FROM asset.library_media m
            JOIN asset.library_blobs b ON b.id = m.blob_id
            JOIN asset.library_assets a ON a.id = m.asset_id
            WHERE b.object_key IN (:keys) AND a.status NOT IN ('PURGE_PENDING', 'PURGED')
            UNION
            SELECT DISTINCT b.object_key AS key
            FROM asset.library_group_items gi
            JOIN asset.library_media m ON m.id = gi.media_id
            JOIN asset.library_blobs b ON b.id = m.blob_id
            JOIN asset.library_groups g ON g.id = gi.group_id
            JOIN asset.library_assets a ON a.id = g.asset_id
            WHERE b.object_key IN (:keys) AND a.status NOT IN ('PURGE_PENDING', 'PURGED')
            UNION
            SELECT DISTINCT si.object_key AS key
            FROM asset.library_group_snapshot_items si
            JOIN asset.library_group_snapshots s ON s.id = si.snapshot_id
            JOIN asset.library_groups g ON g.id = s.group_id
            JOIN asset.library_assets a ON a.id = g.asset_id
            WHERE si.object_key IN (:keys) AND a.status NOT IN ('PURGE_PENDING', 'PURGED')
            """;

    static final String ACTIVE_UPLOAD_SESSION_KEYS_SQL = """
            SELECT DISTINCT object_key AS key
            FROM asset.library_upload_sessions
            WHERE object_key IN (:keys)
              AND status = 'CREATED'
              AND expires_at > now()
            """;

    public Set<String> referencedObjectKeys(Collection<String> objectKeys) {
        List<String> keys = normalizeObjectKeys(objectKeys);
        if (keys.isEmpty()) return Set.of();
        return new LinkedHashSet<>(client.sql(REFERENCED_OBJECT_KEYS_SQL)
                .param("keys", keys)
                .query(String.class)
                .list());
    }

    public Set<String> activeUploadSessionObjectKeys(Collection<String> objectKeys) {
        List<String> keys = normalizeObjectKeys(objectKeys);
        if (keys.isEmpty()) return Set.of();
        return new LinkedHashSet<>(client.sql(ACTIVE_UPLOAD_SESSION_KEYS_SQL)
                .param("keys", keys)
                .query(String.class)
                .list());
    }

    private static List<String> normalizeObjectKeys(Collection<String> objectKeys) {
        return objectKeys == null ? List.of() : objectKeys.stream()
                .filter(key -> key != null && !key.isBlank())
                .distinct()
                .toList();
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `& "E:\Development\apache-maven-3.9.15\bin\mvn.cmd" -pl asset-service -am test "-Dtest=LegacyObjectReferenceRepositoryTest,LibraryRepositoryReferenceSqlTest" "-Dsurefire.failIfNoSpecifiedTests=false"`
Expected: BUILD SUCCESS，两个测试类全部通过。

- [ ] **Step 5: 提交**

```bash
git add asset-service/src/main/java/com/storyboard/asset/repository/LegacyObjectReferenceRepository.java asset-service/src/main/java/com/storyboard/asset/repository/LibraryRepository.java asset-service/src/test/java/com/storyboard/asset/repository/LegacyObjectReferenceRepositoryTest.java asset-service/src/test/java/com/storyboard/asset/repository/LibraryRepositoryReferenceSqlTest.java
git commit -m "feat(asset): 增加历史与项目库引用来源的批量判定"
```

---

### Task 5: ObjectReferenceGuard 统一引用判定

**Files:**
- Create: `asset-service/src/main/java/com/storyboard/asset/cleanup/ObjectReferenceGuard.java`
- Test: `asset-service/src/test/java/com/storyboard/asset/cleanup/ObjectReferenceGuardTest.java`

**Interfaces:**
- Consumes: `LibraryRepository.referencedObjectKeys` / `activeUploadSessionObjectKeys`（Task 4）、`LegacyObjectReferenceRepository.referencedKeys`（Task 4）
- Produces:
  - `ObjectReferenceGuard(LibraryRepository, LegacyObjectReferenceRepository)`
  - `ObjectReferenceGuard.isReferenced(String objectKey)` → `boolean`
  - `ObjectReferenceGuard.referencedObjectKeys(Collection<String> candidates)` → `Set<String>`

- [ ] **Step 1: 写失败测试**

创建 `asset-service/src/test/java/com/storyboard/asset/cleanup/ObjectReferenceGuardTest.java`：

```java
package com.storyboard.asset.cleanup;

import com.storyboard.asset.repository.LegacyObjectReferenceRepository;
import com.storyboard.asset.repository.LibraryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ObjectReferenceGuardTest {

    private LibraryRepository libraryRepository;
    private LegacyObjectReferenceRepository legacyRepository;
    private ObjectReferenceGuard guard;

    @BeforeEach
    void setUp() {
        libraryRepository = mock(LibraryRepository.class);
        legacyRepository = mock(LegacyObjectReferenceRepository.class);
        guard = new ObjectReferenceGuard(libraryRepository, legacyRepository);
    }

    @Test
    void unionsLibraryUploadSessionAndLegacyReferences() {
        when(libraryRepository.referencedObjectKeys(anyCollection())).thenReturn(Set.of("images/lib.png"));
        when(libraryRepository.activeUploadSessionObjectKeys(anyCollection()))
                .thenReturn(Set.of("images/uploading.png"));
        when(legacyRepository.referencedKeys(anyCollection())).thenReturn(Set.of("images/legacy.png"));

        Set<String> referenced = guard.referencedObjectKeys(
                List.of("images/lib.png", "images/uploading.png", "images/legacy.png", "images/orphan.png"));

        assertThat(referenced).containsExactlyInAnyOrder(
                "images/lib.png", "images/uploading.png", "images/legacy.png");
    }

    @Test
    void skipsLaterSourcesOnceEverythingIsCovered() {
        when(libraryRepository.referencedObjectKeys(anyCollection())).thenReturn(Set.of("images/a.png"));

        assertThat(guard.referencedObjectKeys(List.of("images/a.png"))).containsExactly("images/a.png");

        verify(legacyRepository, never()).referencedKeys(anyCollection());
    }

    @Test
    void isReferencedDelegatesToBatchCheck() {
        when(libraryRepository.referencedObjectKeys(anyCollection())).thenReturn(Set.of("videos/a.mp4"));

        assertThat(guard.isReferenced("videos/a.mp4")).isTrue();
        assertThat(guard.isReferenced("videos/b.mp4")).isFalse();
    }

    @Test
    void emptyOrBlankCandidatesDoNotTouchRepositories() {
        assertThat(guard.referencedObjectKeys(List.of())).isEmpty();
        assertThat(guard.referencedObjectKeys(List.of("  "))).isEmpty();
        assertThat(guard.isReferenced(null)).isFalse();
        assertThat(guard.isReferenced("")).isFalse();

        verify(libraryRepository, never()).referencedObjectKeys(anyCollection());
    }

    @Test
    void propagatesSourceFailureInsteadOfSwallowingIt() {
        when(libraryRepository.referencedObjectKeys(anyCollection())).thenReturn(Set.of());
        when(libraryRepository.activeUploadSessionObjectKeys(anyCollection()))
                .thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> guard.referencedObjectKeys(List.of("images/a.png")))
                .isInstanceOf(IllegalStateException.class);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `& "E:\Development\apache-maven-3.9.15\bin\mvn.cmd" -pl asset-service -am test "-Dtest=ObjectReferenceGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false"`
Expected: 编译失败，`ObjectReferenceGuard` 不存在。

- [ ] **Step 3: 最小实现**

创建 `asset-service/src/main/java/com/storyboard/asset/cleanup/ObjectReferenceGuard.java`：

```java
package com.storyboard.asset.cleanup;

import com.storyboard.asset.repository.LegacyObjectReferenceRepository;
import com.storyboard.asset.repository.LibraryRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 唯一的 object key 引用判定组件：扫描入队与删除前二次确认都走这里。
 * 任一来源查询失败时直接抛出，调用方必须按 fail-safe 跳过本轮，不得吞错。
 */
@Component
public class ObjectReferenceGuard {

    private final LibraryRepository libraryRepository;
    private final LegacyObjectReferenceRepository legacyRepository;

    public ObjectReferenceGuard(
            LibraryRepository libraryRepository,
            LegacyObjectReferenceRepository legacyRepository
    ) {
        this.libraryRepository = libraryRepository;
        this.legacyRepository = legacyRepository;
    }

    public boolean isReferenced(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) return false;
        return !referencedObjectKeys(List.of(objectKey)).isEmpty();
    }

    /** 返回 candidates 中仍被任一保护来源引用的子集；查询失败时抛出，不做任何吞错。 */
    public Set<String> referencedObjectKeys(Collection<String> candidates) {
        Set<String> pending = new LinkedHashSet<>();
        if (candidates != null) {
            for (String candidate : candidates) {
                if (candidate != null && !candidate.isBlank()) pending.add(candidate);
            }
        }
        if (pending.isEmpty()) return Set.of();

        Set<String> referenced = new LinkedHashSet<>();
        referenced.addAll(libraryRepository.referencedObjectKeys(pending));
        pending.removeAll(referenced);
        if (pending.isEmpty()) return referenced;

        referenced.addAll(libraryRepository.activeUploadSessionObjectKeys(pending));
        pending.removeAll(referenced);
        if (pending.isEmpty()) return referenced;

        referenced.addAll(legacyRepository.referencedKeys(pending));
        return referenced;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `& "E:\Development\apache-maven-3.9.15\bin\mvn.cmd" -pl asset-service -am test "-Dtest=ObjectReferenceGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false"`
Expected: BUILD SUCCESS，5 个测试通过。

- [ ] **Step 5: 提交**

```bash
git add asset-service/src/main/java/com/storyboard/asset/cleanup/ObjectReferenceGuard.java asset-service/src/test/java/com/storyboard/asset/cleanup/ObjectReferenceGuardTest.java
git commit -m "feat(asset): 增加统一的 object key 引用判定组件"
```
### Task 6: StorageOrphanScanner 定时扫描与入队

**Files:**
- Create: `asset-service/src/main/java/com/storyboard/asset/worker/StorageOrphanScanner.java`
- Test: `asset-service/src/test/java/com/storyboard/asset/worker/StorageOrphanScannerTest.java`

**Interfaces:**
- Consumes:
  - `ObjectStorage.listObjects(String prefix, String continuationToken, int maxKeys)` / `ObjectStorage.ObjectPage` / `ObjectStorage.ObjectSummary`（Task 1）
  - `ObjectReferenceGuard.referencedObjectKeys(Collection<String>)`（Task 5）
  - `LibraryRepository.createCleanupTask(String objectKey)`（既有，天然幂等）
  - `StorageProperties.orphanScanEnabled()` / `orphanScanGraceHours()`（Task 3）
- Produces:
  - `StorageOrphanScanner(ObjectStorage storage, ObjectReferenceGuard guard, LibraryRepository repository, StorageProperties properties)`
  - `public void scanOnce()` — 可测的一轮扫描入口
  - `public void run()` — `@Scheduled` 包装，频率由 `storyboard.storage.orphan-scan-interval-hours` 驱动

**关键约束（照抄到实现里）:**
- 只扫描 `images/` 与 `videos/` 两个前缀；其他前缀一律不列举。
- 先收集全部候选、再一次性批量判定引用、最后才入队。任一步抛错都整轮跳过，**不产生任何新清理任务**（不能边翻页边入队）。
- `orphanScanEnabled=false` 时直接 return，连桶都不列举。
- `lastModified` 晚于 `now() - graceHours` 的对象跳过（宽限期）；`lastModified` 为 null 时保守跳过。

- [ ] **Step 1: 写失败测试**

创建 `asset-service/src/test/java/com/storyboard/asset/worker/StorageOrphanScannerTest.java`：

```java
package com.storyboard.asset.worker;

import com.storyboard.asset.cleanup.ObjectReferenceGuard;
import com.storyboard.asset.config.StorageProperties;
import com.storyboard.asset.repository.LibraryRepository;
import com.storyboard.asset.storage.ObjectStorage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StorageOrphanScannerTest {

    private static StorageProperties properties(boolean enabled, int graceHours) {
        return new StorageProperties(
                "http://localhost:9000", "minioadmin", "minioadmin123", "storyboard", "us-east-1",
                0, 0, 0, 3, enabled, 6, graceHours);
    }

    private static ObjectStorage.ObjectSummary object(String key, Instant lastModified) {
        return new ObjectStorage.ObjectSummary(key, 10L, lastModified);
    }

    private static ObjectStorage.ObjectPage single(ObjectStorage.ObjectSummary object) {
        return new ObjectStorage.ObjectPage(List.of(object), null);
    }

    private static ObjectStorage.ObjectPage empty() {
        return new ObjectStorage.ObjectPage(List.of(), null);
    }

    @Test
    void enqueuesOnlyUnreferencedImageAndVideoObjects() {
        ObjectStorage storage = mock(ObjectStorage.class);
        ObjectReferenceGuard guard = mock(ObjectReferenceGuard.class);
        LibraryRepository repository = mock(LibraryRepository.class);
        Instant old = Instant.now().minus(48, ChronoUnit.HOURS);
        when(storage.listObjects("images/", null, StorageOrphanScanner.PAGE_SIZE))
                .thenReturn(new ObjectStorage.ObjectPage(
                        List.of(object("images/orphan.png", old), object("images/shared.png", old)), null));
        when(storage.listObjects("videos/", null, StorageOrphanScanner.PAGE_SIZE))
                .thenReturn(single(object("videos/orphan.mp4", old)));
        when(guard.referencedObjectKeys(anyCollection())).thenReturn(Set.of("images/shared.png"));

        new StorageOrphanScanner(storage, guard, repository, properties(true, 24)).scanOnce();

        verify(repository).createCleanupTask("images/orphan.png");
        verify(repository).createCleanupTask("videos/orphan.mp4");
        verify(repository, never()).createCleanupTask("images/shared.png");
    }

    @Test
    void skipsObjectsNewerThanGracePeriod() {
        ObjectStorage storage = mock(ObjectStorage.class);
        ObjectReferenceGuard guard = mock(ObjectReferenceGuard.class);
        LibraryRepository repository = mock(LibraryRepository.class);
        Instant recent = Instant.now().minus(1, ChronoUnit.HOURS);
        when(storage.listObjects("images/", null, StorageOrphanScanner.PAGE_SIZE))
                .thenReturn(single(object("images/fresh.png", recent)));
        when(storage.listObjects("videos/", null, StorageOrphanScanner.PAGE_SIZE)).thenReturn(empty());

        new StorageOrphanScanner(storage, guard, repository, properties(true, 24)).scanOnce();

        verify(repository, never()).createCleanupTask(anyString());
        verify(guard, never()).referencedObjectKeys(anyCollection());
    }

    @Test
    void doesNothingWhenScanDisabled() {
        ObjectStorage storage = mock(ObjectStorage.class);
        ObjectReferenceGuard guard = mock(ObjectReferenceGuard.class);
        LibraryRepository repository = mock(LibraryRepository.class);

        new StorageOrphanScanner(storage, guard, repository, properties(false, 24)).scanOnce();

        verify(storage, never()).listObjects(anyString(), nullable(String.class), anyInt());
        verify(repository, never()).createCleanupTask(anyString());
    }

    @Test
    void abortsWholeRoundWhenReferenceLookupFails() {
        ObjectStorage storage = mock(ObjectStorage.class);
        ObjectReferenceGuard guard = mock(ObjectReferenceGuard.class);
        LibraryRepository repository = mock(LibraryRepository.class);
        Instant old = Instant.now().minus(48, ChronoUnit.HOURS);
        when(storage.listObjects("images/", null, StorageOrphanScanner.PAGE_SIZE))
                .thenReturn(single(object("images/a.png", old)));
        when(storage.listObjects("videos/", null, StorageOrphanScanner.PAGE_SIZE)).thenReturn(empty());
        when(guard.referencedObjectKeys(anyCollection())).thenThrow(new IllegalStateException("db down"));

        assertThatCode(() ->
                new StorageOrphanScanner(storage, guard, repository, properties(true, 24)).scanOnce())
                .doesNotThrowAnyException();

        verify(repository, never()).createCleanupTask(anyString());
    }

    @Test
    void abortsWholeRoundWhenListingFails() {
        ObjectStorage storage = mock(ObjectStorage.class);
        ObjectReferenceGuard guard = mock(ObjectReferenceGuard.class);
        LibraryRepository repository = mock(LibraryRepository.class);
        when(storage.listObjects("images/", null, StorageOrphanScanner.PAGE_SIZE))
                .thenThrow(new RuntimeException("minio down"));

        assertThatCode(() ->
                new StorageOrphanScanner(storage, guard, repository, properties(true, 24)).scanOnce())
                .doesNotThrowAnyException();

        verify(repository, never()).createCleanupTask(anyString());
    }

    @Test
    void followsContinuationTokens() {
        ObjectStorage storage = mock(ObjectStorage.class);
        ObjectReferenceGuard guard = mock(ObjectReferenceGuard.class);
        LibraryRepository repository = mock(LibraryRepository.class);
        Instant old = Instant.now().minus(48, ChronoUnit.HOURS);
        when(storage.listObjects("images/", null, StorageOrphanScanner.PAGE_SIZE))
                .thenReturn(new ObjectStorage.ObjectPage(
                        List.of(object("images/a.png", old)), "token-2"));
        when(storage.listObjects("images/", "token-2", StorageOrphanScanner.PAGE_SIZE))
                .thenReturn(single(object("images/b.png", old)));
        when(storage.listObjects("videos/", null, StorageOrphanScanner.PAGE_SIZE)).thenReturn(empty());
        when(guard.referencedObjectKeys(anyCollection())).thenReturn(Set.of());

        new StorageOrphanScanner(storage, guard, repository, properties(true, 24)).scanOnce();

        verify(repository).createCleanupTask("images/a.png");
        verify(repository).createCleanupTask("images/b.png");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `& "E:\Development\apache-maven-3.9.15\bin\mvn.cmd" -pl asset-service -am test "-Dtest=StorageOrphanScannerTest" "-Dsurefire.failIfNoSpecifiedTests=false"`
Expected: 编译失败，`StorageOrphanScanner` 不存在。

- [ ] **Step 3: 最小实现**

创建 `asset-service/src/main/java/com/storyboard/asset/worker/StorageOrphanScanner.java`：

```java
package com.storyboard.asset.worker;

import com.storyboard.asset.cleanup.ObjectReferenceGuard;
import com.storyboard.asset.config.StorageProperties;
import com.storyboard.asset.repository.LibraryRepository;
import com.storyboard.asset.storage.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 主动扫描 MinIO 中 images/、videos/ 前缀的对象，把超出宽限期且无任何引用的对象写入既有清理队列。
 * fail-safe：列举或引用判定任一环节失败时整轮跳过，不产生任何新清理任务。
 */
@Component
public class StorageOrphanScanner {

    private static final Logger log = LoggerFactory.getLogger(StorageOrphanScanner.class);
    static final List<String> SCAN_PREFIXES = List.of("images/", "videos/");
    static final int PAGE_SIZE = 500;

    private final ObjectStorage storage;
    private final ObjectReferenceGuard guard;
    private final LibraryRepository repository;
    private final StorageProperties properties;

    public StorageOrphanScanner(
            ObjectStorage storage,
            ObjectReferenceGuard guard,
            LibraryRepository repository,
            StorageProperties properties
    ) {
        this.storage = storage;
        this.guard = guard;
        this.repository = repository;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "#{${storyboard.storage.orphan-scan-interval-hours:6} * 3600000}")
    public void run() {
        scanOnce();
    }

    /** 扫描一轮：先收集全部候选，再一次性判定引用，最后才入队，保证任一步失败都不产生新任务。 */
    public void scanOnce() {
        if (!properties.orphanScanEnabled()) return;
        try {
            List<ObjectStorage.ObjectSummary> candidates = collectCandidates();
            if (candidates.isEmpty()) return;

            Set<String> keys = new LinkedHashSet<>();
            for (ObjectStorage.ObjectSummary object : candidates) keys.add(object.key());
            Set<String> referenced = guard.referencedObjectKeys(keys);

            int queued = 0;
            for (ObjectStorage.ObjectSummary object : candidates) {
                if (referenced.contains(object.key())) continue;
                repository.createCleanupTask(object.key());
                queued++;
            }
            log.info("MinIO 孤儿扫描完成: candidates={}, protected={}, queued={}",
                    keys.size(), referenced.size(), queued);
        } catch (Exception e) {
            log.warn("MinIO 孤儿扫描整轮跳过（fail-safe）: {}", e.getMessage(), e);
        }
    }

    private List<ObjectStorage.ObjectSummary> collectCandidates() {
        Instant cutoff = Instant.now().minus(Duration.ofHours(properties.orphanScanGraceHours()));
        List<ObjectStorage.ObjectSummary> candidates = new ArrayList<>();
        for (String prefix : SCAN_PREFIXES) {
            String token = null;
            do {
                ObjectStorage.ObjectPage page = storage.listObjects(prefix, token, PAGE_SIZE);
                for (ObjectStorage.ObjectSummary object : page.objects()) {
                    if (object.lastModified() == null || object.lastModified().isAfter(cutoff)) continue;
                    candidates.add(object);
                }
                token = page.nextContinuationToken();
            } while (token != null && !token.isBlank());
        }
        return candidates;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `& "E:\Development\apache-maven-3.9.15\bin\mvn.cmd" -pl asset-service -am test "-Dtest=StorageOrphanScannerTest" "-Dsurefire.failIfNoSpecifiedTests=false"`
Expected: BUILD SUCCESS，6 个测试通过。

- [ ] **Step 5: 提交**

```bash
git add asset-service/src/main/java/com/storyboard/asset/worker/StorageOrphanScanner.java asset-service/src/test/java/com/storyboard/asset/worker/StorageOrphanScannerTest.java
git commit -m "feat(asset): 增加 MinIO 孤儿对象定时扫描"
```

---

### Task 7: StorageCleanupWorker 二次确认改用 ObjectReferenceGuard

**Files:**
- Modify: `asset-service/src/main/java/com/storyboard/asset/worker/StorageCleanupWorker.java`
- Modify: `asset-service/src/main/java/com/storyboard/asset/repository/LibraryRepository.java`（删除 `objectKeyHasReferences`）
- Modify: `asset-service/src/test/java/com/storyboard/asset/worker/StorageCleanupWorkerTest.java`

**Interfaces:**
- Consumes: `ObjectReferenceGuard.isReferenced(String)`（Task 5）
- Produces: `StorageCleanupWorker(LibraryRepository repository, ObjectStorage storage, ObjectReferenceGuard objectReferenceGuard)`

**背景:** 扫描入队与删除前二次确认必须使用同一套引用判定，否则旧画布/旧 AI 资产的保护只在扫描那次生效，删除时可能被误删。用 `ObjectReferenceGuard` 取代只查项目库的 `LibraryRepository.objectKeyHasReferences`。

- [ ] **Step 1: 更新失败测试**

用下面内容整体替换 `asset-service/src/test/java/com/storyboard/asset/worker/StorageCleanupWorkerTest.java`：

```java
package com.storyboard.asset.worker;

import com.storyboard.asset.cleanup.ObjectReferenceGuard;
import com.storyboard.asset.repository.LibraryRepository;
import com.storyboard.asset.storage.ObjectStorage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StorageCleanupWorkerTest {

    private static LibraryRepository.CleanupTask task() {
        return new LibraryRepository.CleanupTask("task-1", "images/shared.png", 1);
    }

    @Test
    void keepsObjectWhenAnotherAssetStillReferencesIt() {
        LibraryRepository repository = mock(LibraryRepository.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        ObjectReferenceGuard guard = mock(ObjectReferenceGuard.class);
        StorageCleanupWorker worker = new StorageCleanupWorker(repository, storage, guard);
        when(repository.claimCleanupTasks(anyString(), eq(20))).thenReturn(List.of(task()));
        when(guard.isReferenced("images/shared.png")).thenReturn(true);

        worker.run();

        verify(storage, never()).delete("images/shared.png");
        verify(repository).completeCleanup("task-1");
    }

    @Test
    void deletesObjectWhenNoReferenceRemains() {
        LibraryRepository repository = mock(LibraryRepository.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        ObjectReferenceGuard guard = mock(ObjectReferenceGuard.class);
        StorageCleanupWorker worker = new StorageCleanupWorker(repository, storage, guard);
        when(repository.claimCleanupTasks(anyString(), eq(20))).thenReturn(List.of(task()));
        when(guard.isReferenced("images/shared.png")).thenReturn(false);
        when(storage.exists("images/shared.png")).thenReturn(true);

        worker.run();

        verify(storage).delete("images/shared.png");
        verify(repository).completeCleanup("task-1");
    }

    @Test
    void recordsFailureWhenStorageDeleteThrows() {
        LibraryRepository repository = mock(LibraryRepository.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        ObjectReferenceGuard guard = mock(ObjectReferenceGuard.class);
        StorageCleanupWorker worker = new StorageCleanupWorker(repository, storage, guard);
        when(repository.claimCleanupTasks(anyString(), eq(20))).thenReturn(List.of(task()));
        when(guard.isReferenced("images/shared.png")).thenReturn(false);
        when(storage.exists("images/shared.png")).thenReturn(true);
        org.mockito.Mockito.doThrow(new RuntimeException("s3 down"))
                .when(storage).delete("images/shared.png");

        worker.run();

        verify(repository).failCleanup("task-1", 1, "s3 down");
        verify(repository, never()).completeCleanup("task-1");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `& "E:\Development\apache-maven-3.9.15\bin\mvn.cmd" -pl asset-service -am test "-Dtest=StorageCleanupWorkerTest" "-Dsurefire.failIfNoSpecifiedTests=false"`
Expected: 编译失败，`StorageCleanupWorker(LibraryRepository, ObjectStorage, ObjectReferenceGuard)` 构造器不存在。

- [ ] **Step 3: 最小实现**

`StorageCleanupWorker.java` 改成：

```java
package com.storyboard.asset.worker;

import com.storyboard.asset.cleanup.ObjectReferenceGuard;
import com.storyboard.asset.repository.LibraryRepository;
import com.storyboard.asset.storage.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class StorageCleanupWorker {

    private static final Logger log = LoggerFactory.getLogger(StorageCleanupWorker.class);

    private final LibraryRepository repository;
    private final ObjectStorage storage;
    private final ObjectReferenceGuard objectReferenceGuard;
    private final String workerId = "asset-cleanup-" + UUID.randomUUID();

    public StorageCleanupWorker(
            LibraryRepository repository,
            ObjectStorage storage,
            ObjectReferenceGuard objectReferenceGuard
    ) {
        this.repository = repository;
        this.storage = storage;
        this.objectReferenceGuard = objectReferenceGuard;
    }

    @Scheduled(fixedDelay = 30_000)
    public void run() {
        for (LibraryRepository.CleanupTask task : repository.claimCleanupTasks(workerId, 20)) {
            try {
                if (objectReferenceGuard.isReferenced(task.objectKey())) {
                    repository.completeCleanup(task.id());
                    continue;
                }
                if (storage.exists(task.objectKey())) storage.delete(task.objectKey());
                repository.completeCleanup(task.id());
            } catch (Exception e) {
                repository.failCleanup(task.id(), task.attemptCount(), e.getMessage());
                log.warn("MinIO 清理失败: taskId={}, key={}, attempt={}, error={}",
                        task.id(), task.objectKey(), task.attemptCount(), e.getMessage());
            }
        }
    }
}
```

同时在 `LibraryRepository.java` 中**删除** `objectKeyHasReferences(String objectKey)` 整个方法（现第 501 行起，到 `markPurgeBlocked` 之前）。

- [ ] **Step 4: 运行测试确认通过并确认无残留调用**

Run: `& "E:\Development\apache-maven-3.9.15\bin\mvn.cmd" -pl asset-service -am test "-Dtest=StorageCleanupWorkerTest" "-Dsurefire.failIfNoSpecifiedTests=false"`
Expected: BUILD SUCCESS，3 个测试通过。

Run: `git grep -n "objectKeyHasReferences"`
Expected: 无输出（方法与其唯一调用点都已删除）。

- [ ] **Step 5: 提交**

```bash
git add asset-service/src/main/java/com/storyboard/asset/worker/StorageCleanupWorker.java asset-service/src/main/java/com/storyboard/asset/repository/LibraryRepository.java asset-service/src/test/java/com/storyboard/asset/worker/StorageCleanupWorkerTest.java
git commit -m "refactor(asset): 清理前二次确认改用统一引用判定"
```

---
### Task 8: AssetPurgeService 抽取 + 回收期改为配置化 3 天

**Files:**
- Create: `asset-service/src/main/java/com/storyboard/asset/service/AssetPurgeService.java`
- Modify: `asset-service/src/main/java/com/storyboard/asset/service/impl/AssetCatalogServiceImpl.java`
- Modify: `asset-service/src/main/java/com/storyboard/asset/service/impl/LibraryFolderServiceImpl.java`
- Modify: `asset-service/src/main/java/com/storyboard/asset/repository/LibraryRepository.java`
- Test: `asset-service/src/test/java/com/storyboard/asset/service/AssetPurgeServiceTest.java`
- Modify test: `asset-service/src/test/java/com/storyboard/asset/service/impl/AssetCatalogServiceImplTest.java`
- Modify test: `asset-service/src/test/java/com/storyboard/asset/service/impl/LibraryFolderServiceImplTest.java`

**Interfaces:**
- Consumes: `StorageProperties.trashRetentionDays()`（Task 3）、`LibraryRepository.unlinkAllProjects/hasActiveReferences/listMedia/createCleanupTask/markPurgeBlocked/markPurged`（既有）
- Produces:
  - `AssetPurgeService(LibraryRepository repository)`
  - `AssetPurgeService.purgeManually(String assetId)` → `void`（手动彻底删除，有引用抛 40901）
  - `AssetPurgeService.purgeDue(String assetId)` → `PurgeOutcome`（到期自动清理，有引用返回 `BLOCKED` 不抛）
  - `AssetPurgeService.PurgeOutcome` = `PURGED | BLOCKED | SKIPPED`
  - `LibraryRepository.markPurgePending(String assetId)`（由双参改为单参）
  - `LibraryRepository.trashAssetsInFolders(String userId, AssetScope scope, String projectId, Collection<String> folderIds, int retentionDays)`
  - `AssetCatalogServiceImpl(LibraryRepository, ProjectAccessPort, MediaUrlSigner, AssetPurgeService, StorageProperties)`
  - `LibraryFolderServiceImpl(LibraryRepository, ProjectAccessPort, AssetCatalogService, StorageProperties)`

**关键约束（照抄到实现里）:**
- 手动 purge：先 `unlinkAllProjects`，再判定活跃引用；仍有引用 → `markPurgeBlocked` + 抛 `BusinessException(40901, "资产仍被项目引用，已转为待处理状态")`；成功 → 入队 + `markPurged`。行为与现状完全一致。
- 到期自动清理（`purgeDue`）：**不**解除项目关联；仍有活跃引用 → `markPurgeBlocked` 返回 `BLOCKED`（对象保留）；`markPurgePending` 未命中 → `SKIPPED`；否则入队 + `markPurged` 返回 `PURGED`。
- `markPurgePending` 仍只允许 `status = 'TRASHED'` 转换，保证状态机不被绕过。
- 三处硬编码 7 天全部改为 `properties.trashRetentionDays()`；SQL 侧用 `make_interval(days => :retentionDays)`，不再写死 `interval '7 days'`。

- [ ] **Step 1: 写失败测试**

创建 `asset-service/src/test/java/com/storyboard/asset/service/AssetPurgeServiceTest.java`：

```java
package com.storyboard.asset.service;

import com.storyboard.asset.repository.LibraryRepository;
import com.storyboard.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssetPurgeServiceTest {

    private LibraryRepository repository;
    private AssetPurgeService service;

    @BeforeEach
    void setUp() {
        repository = mock(LibraryRepository.class);
        service = new AssetPurgeService(repository);
    }

    @Test
    void purgeManuallyUnlinksThenEnqueuesAndMarksPurged() {
        when(repository.hasActiveReferences("a1")).thenReturn(false);
        when(repository.markPurgePending("a1")).thenReturn(true);

        service.purgeManually("a1");

        verify(repository).unlinkAllProjects("a1");
        verify(repository).markPurgePending("a1");
        verify(repository).markPurged("a1");
        verify(repository, never()).markPurgeBlocked("a1");
    }

    @Test
    void purgeManuallyBlocksWhenActiveReferencesRemain() {
        when(repository.hasActiveReferences("a1")).thenReturn(true);

        assertThatThrownBy(() -> service.purgeManually("a1"))
                .isInstanceOf(BusinessException.class);

        verify(repository).markPurgeBlocked("a1");
        verify(repository, never()).markPurgePending("a1");
        verify(repository, never()).markPurged("a1");
    }

    @Test
    void purgeDueDoesNotUnlinkProjectsAndBlocksWhenStillReferenced() {
        when(repository.hasActiveReferences("a1")).thenReturn(true);

        assertThat(service.purgeDue("a1")).isEqualTo(AssetPurgeService.PurgeOutcome.BLOCKED);

        verify(repository, never()).unlinkAllProjects("a1");
        verify(repository).markPurgeBlocked("a1");
        verify(repository, never()).markPurged("a1");
    }

    @Test
    void purgeDueEnqueuesAndMarksPurgedWhenNoReferenceRemains() {
        when(repository.hasActiveReferences("a1")).thenReturn(false);
        when(repository.markPurgePending("a1")).thenReturn(true);

        assertThat(service.purgeDue("a1")).isEqualTo(AssetPurgeService.PurgeOutcome.PURGED);

        verify(repository, never()).unlinkAllProjects("a1");
        verify(repository).markPurged("a1");
    }

    @Test
    void purgeDueSkipsWhenStateAlreadyChanged() {
        when(repository.hasActiveReferences("a1")).thenReturn(false);
        when(repository.markPurgePending("a1")).thenReturn(false);

        assertThat(service.purgeDue("a1")).isEqualTo(AssetPurgeService.PurgeOutcome.SKIPPED);

        verify(repository, never()).markPurged("a1");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `& "E:\Development\apache-maven-3.9.15\bin\mvn.cmd" -pl asset-service -am test "-Dtest=AssetPurgeServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false"`
Expected: 编译失败，`AssetPurgeService` 不存在。

- [ ] **Step 3: 最小实现**

**3a.** 创建 `asset-service/src/main/java/com/storyboard/asset/service/AssetPurgeService.java`：

```java
package com.storyboard.asset.service;

import com.storyboard.asset.client.AssetContracts.MediaRef;
import com.storyboard.asset.repository.LibraryRepository;
import com.storyboard.common.BusinessException;
import org.springframework.stereotype.Service;

/**
 * 手动彻底删除与回收站到期自动清理共用的清理编排。
 * 只把对象写入既有清理队列，真正的 MinIO 删除仍由 StorageCleanupWorker 执行。
 */
@Service
public class AssetPurgeService {

    private final LibraryRepository repository;

    public AssetPurgeService(LibraryRepository repository) {
        this.repository = repository;
    }

    /** 手动彻底删除：先解除项目关联，再判定剩余活跃引用；仍有引用则阻塞并抛 40901。 */
    public void purgeManually(String assetId) {
        repository.unlinkAllProjects(assetId);
        if (repository.hasActiveReferences(assetId)) {
            repository.markPurgeBlocked(assetId);
            throw new BusinessException(40901, "资产仍被项目引用，已转为待处理状态");
        }
        if (!repository.markPurgePending(assetId)) {
            throw new BusinessException(40901, "清理状态已变化");
        }
        enqueueAndMarkPurged(assetId);
    }

    /** 回收站到期自动清理：不解除关联；仍有活跃引用则阻塞并保留对象。 */
    public PurgeOutcome purgeDue(String assetId) {
        if (repository.hasActiveReferences(assetId)) {
            repository.markPurgeBlocked(assetId);
            return PurgeOutcome.BLOCKED;
        }
        if (!repository.markPurgePending(assetId)) return PurgeOutcome.SKIPPED;
        enqueueAndMarkPurged(assetId);
        return PurgeOutcome.PURGED;
    }

    private void enqueueAndMarkPurged(String assetId) {
        for (MediaRef media : repository.listMedia(assetId)) {
            repository.createCleanupTask(media.objectKey());
        }
        repository.markPurged(assetId);
    }

    public enum PurgeOutcome {
        PURGED, BLOCKED, SKIPPED
    }
}
```

**3b.** `LibraryRepository.java`：

把 `markPurgePending(String userId, String assetId)` 改成单参版本（调用方已在 `requireAsset` 校验归属）：

```java
    public boolean markPurgePending(String assetId) {
        return client.sql("""
                        UPDATE asset.library_assets
                        SET status = 'PURGE_PENDING', version = version + 1, updated_at = now()
                        WHERE id = :id AND status = 'TRASHED'
                        """)
                .param("id", assetId)
                .update() == 1;
    }
```

把 `trashAssetsInFolders` 的签名与 SQL 改成：

```java
    public void trashAssetsInFolders(
            String userId,
            AssetScope scope,
            String projectId,
            Collection<String> folderIds,
            int retentionDays
    ) {
        List<String> normalized = folderIds == null ? List.of() : folderIds.stream().distinct().toList();
        if (normalized.isEmpty()) return;
        client.sql("""
                        UPDATE asset.library_assets a
                        SET status = 'TRASHED',
                            deleted_at = now(),
                            purge_after = now() + make_interval(days => :retentionDays),
                            version = version + 1,
                            updated_at = now()
                        WHERE a.owner_user_id = :userId
                          AND a.status = 'ACTIVE'
                          AND EXISTS (
                              SELECT 1
                              FROM asset.library_asset_folders af
                              WHERE af.asset_id = a.id
                                AND af.owner_user_id = :userId
                                AND af.scope = :scope
                                AND af.owner_project_id IS NOT DISTINCT FROM :projectId
                                AND af.folder_id IN (:folderIds)
                          )
                        """)
                .param("userId", userId)
                .param("scope", scope.name())
                .param("projectId", scope == AssetScope.PROJECT ? projectId : null)
                .param("folderIds", normalized)
                .param("retentionDays", retentionDays)
                .update();
    }
```

**3c.** `AssetCatalogServiceImpl.java`：

新增 import：`com.storyboard.asset.config.StorageProperties`、`com.storyboard.asset.service.AssetPurgeService`（同包无需）、`com.storyboard.asset.client.AssetContracts.MediaRef` 若因此变为未使用则移除。构造器与字段改为：

```java
    private final LibraryRepository repository;
    private final ProjectAccessPort projectAccess;
    private final MediaUrlSigner signer;
    private final AssetPurgeService purgeService;
    private final StorageProperties properties;

    public AssetCatalogServiceImpl(
            LibraryRepository repository,
            ProjectAccessPort projectAccess,
            MediaUrlSigner signer,
            AssetPurgeService purgeService,
            StorageProperties properties
    ) {
        this.repository = repository;
        this.projectAccess = projectAccess;
        this.signer = signer;
        this.purgeService = purgeService;
        this.properties = properties;
    }
```

`trash(...)` 中的 `OffsetDateTime.now().plusDays(7)` 改为 `OffsetDateTime.now().plusDays(properties.trashRetentionDays())`；
`bulkTrash(...)` 中的 `OffsetDateTime purgeAfter = OffsetDateTime.now().plusDays(7);` 同样改为 `properties.trashRetentionDays()`；
`purge(...)` 改为委托：

```java
    @Override
    @Transactional
    public void purge(String userId, String assetId) {
        AssetSummary asset = requireAsset(userId, assetId);
        if (!AssetStatePolicy.canPurge(asset.status().name())) {
            throw new BusinessException(40901, "只有回收站资产可以彻底删除");
        }
        purgeService.purgeManually(assetId);
    }
```

**3d.** `LibraryFolderServiceImpl.java`：

新增 import `com.storyboard.asset.config.StorageProperties`；构造器加 `StorageProperties properties` 字段；`delete(...)` 中调用改为：

```java
        repository.trashAssetsInFolders(
                userId, folder.scope(), folder.ownerProjectId(), folderIds, properties.trashRetentionDays());
```

**3e.** 更新既有测试以适配新签名：

- `AssetCatalogServiceImplTest.setUp()`：
  ```java
        repository = mock(LibraryRepository.class);
        projectAccess = mock(ProjectAccessPort.class);
        service = new AssetCatalogServiceImpl(
                repository, projectAccess, mock(MediaUrlSigner.class),
                new AssetPurgeService(repository),
                new StorageProperties(null, null, null, null, null, 0, 0, 0, 3, false, 6, 24));
  ```
  新增 import `com.storyboard.asset.config.StorageProperties`、`com.storyboard.asset.service.AssetPurgeService`。
  `purgeUnlinksProjectsAndMarksAssetPurged` 里 `when(repository.markPurgePending("u1", "a1"))` 与 `verify(repository).markPurgePending("u1", "a1")` 改为单参 `"a1"`；`purgeKeepsReferencedAssetBlocked` 里 `verify(repository, never()).markPurgePending(anyString(), anyString())` 改为 `verify(repository, never()).markPurgePending(anyString())`。
- `LibraryFolderServiceImplTest.setUp()`（第 39 行）：
  ```java
        service = new LibraryFolderServiceImpl(
                repository, projectAccess, catalogService,
                new StorageProperties(null, null, null, null, null, 0, 0, 0, 3, false, 6, 24));
  ```
  新增 import `com.storyboard.asset.config.StorageProperties`；`deletesNestedFoldersAfterMovingContainedAssetsToTrash` 断言改为：
  ```java
        verify(repository).trashAssetsInFolders(
                "u1", AssetScope.PROJECT, "p1", List.of("folder-child", "folder-1"), 3);
  ```

- [ ] **Step 4: 运行测试确认通过**

Run: `& "E:\Development\apache-maven-3.9.15\bin\mvn.cmd" -pl asset-service -am test "-Dtest=AssetPurgeServiceTest,AssetCatalogServiceImplTest,LibraryFolderServiceImplTest" "-Dsurefire.failIfNoSpecifiedTests=false"`
Expected: BUILD SUCCESS，三个测试类全部通过。

Run: `git grep -n "plusDays(7)\|interval '7 days'"`
Expected: 无输出（三处硬编码回收期都已替换）。

- [ ] **Step 5: 提交**

```bash
git add asset-service/src/main/java/com/storyboard/asset/service/AssetPurgeService.java asset-service/src/main/java/com/storyboard/asset/service/impl/AssetCatalogServiceImpl.java asset-service/src/main/java/com/storyboard/asset/service/impl/LibraryFolderServiceImpl.java asset-service/src/main/java/com/storyboard/asset/repository/LibraryRepository.java asset-service/src/test/java/com/storyboard/asset/service/AssetPurgeServiceTest.java asset-service/src/test/java/com/storyboard/asset/service/impl/AssetCatalogServiceImplTest.java asset-service/src/test/java/com/storyboard/asset/service/impl/LibraryFolderServiceImplTest.java
git commit -m "feat(asset): 抽取共享 purge 逻辑并把回收站保留期改为 3 天"
```

---

### Task 9: TrashPurgeWorker 到期自动清理

**Files:**
- Create: `asset-service/src/main/java/com/storyboard/asset/worker/TrashPurgeWorker.java`
- Modify: `asset-service/src/main/java/com/storyboard/asset/repository/LibraryRepository.java`（新增 `claimDueTrashAssets`）
- Test: `asset-service/src/test/java/com/storyboard/asset/worker/TrashPurgeWorkerTest.java`

**Interfaces:**
- Consumes: `AssetPurgeService.purgeDue(String)` → `PurgeOutcome`（Task 8）
- Produces:
  - `LibraryRepository.claimDueTrashAssets(int limit)` → `List<String>`
  - `TrashPurgeWorker(LibraryRepository repository, AssetPurgeService purgeService)`

**关键约束（照抄到实现里）:**
- 领取条件：`status = 'TRASHED' AND purge_after IS NOT NULL AND purge_after <= now()`，用 `FOR UPDATE SKIP LOCKED` 防止多实例重复处理。
- `run()` 标注 `@Transactional`，使 `SELECT ... FOR UPDATE` 的行锁在整个批次清理期间保持（否则锁会在领取后立刻释放，`SKIP LOCKED` 失去意义）。
- 走 `AssetPurgeService.purgeDue`，与手动 purge 同一条代码路径；`BLOCKED` 只记日志，对象保留。

- [ ] **Step 1: 写失败测试**

创建 `asset-service/src/test/java/com/storyboard/asset/worker/TrashPurgeWorkerTest.java`：

```java
package com.storyboard.asset.worker;

import com.storyboard.asset.repository.LibraryRepository;
import com.storyboard.asset.service.AssetPurgeService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrashPurgeWorkerTest {

    @Test
    void purgesEveryClaimedDueAsset() {
        LibraryRepository repository = mock(LibraryRepository.class);
        AssetPurgeService purgeService = mock(AssetPurgeService.class);
        when(repository.claimDueTrashAssets(anyInt())).thenReturn(List.of("a1", "a2"));
        when(purgeService.purgeDue(anyString())).thenReturn(AssetPurgeService.PurgeOutcome.PURGED);

        new TrashPurgeWorker(repository, purgeService).run();

        verify(purgeService).purgeDue("a1");
        verify(purgeService).purgeDue("a2");
    }

    @Test
    void keepsClaimedAssetWhenPurgeIsBlocked() {
        LibraryRepository repository = mock(LibraryRepository.class);
        AssetPurgeService purgeService = mock(AssetPurgeService.class);
        when(repository.claimDueTrashAssets(anyInt())).thenReturn(List.of("a1"));
        when(purgeService.purgeDue("a1")).thenReturn(AssetPurgeService.PurgeOutcome.BLOCKED);

        new TrashPurgeWorker(repository, purgeService).run();

        verify(purgeService).purgeDue("a1");
    }

    @Test
    void continuesWithRemainingAssetsWhenOneFails() {
        LibraryRepository repository = mock(LibraryRepository.class);
        AssetPurgeService purgeService = mock(AssetPurgeService.class);
        when(repository.claimDueTrashAssets(anyInt())).thenReturn(List.of("a1", "a2"));
        when(purgeService.purgeDue("a1")).thenThrow(new RuntimeException("db error"));
        when(purgeService.purgeDue("a2")).thenReturn(AssetPurgeService.PurgeOutcome.PURGED);

        new TrashPurgeWorker(repository, purgeService).run();

        verify(purgeService).purgeDue("a2");
    }

    @Test
    void doesNothingWhenNoAssetIsDue() {
        LibraryRepository repository = mock(LibraryRepository.class);
        AssetPurgeService purgeService = mock(AssetPurgeService.class);
        when(repository.claimDueTrashAssets(anyInt())).thenReturn(List.of());

        new TrashPurgeWorker(repository, purgeService).run();

        verify(purgeService, never()).purgeDue(anyString());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `& "E:\Development\apache-maven-3.9.15\bin\mvn.cmd" -pl asset-service -am test "-Dtest=TrashPurgeWorkerTest" "-Dsurefire.failIfNoSpecifiedTests=false"`
Expected: 编译失败，`TrashPurgeWorker` 与 `LibraryRepository.claimDueTrashAssets` 不存在。

- [ ] **Step 3: 最小实现**

**3a.** 在 `LibraryRepository.java` 中 `markPurgeBlocked` 之后新增：

```java
    /** 在事务内锁定到期未清理的回收站资产；调用方须在同一事务里完成清理，否则行锁提前释放。 */
    public List<String> claimDueTrashAssets(int limit) {
        return client.sql("""
                        SELECT id FROM asset.library_assets
                        WHERE status = 'TRASHED'
                          AND purge_after IS NOT NULL
                          AND purge_after <= now()
                        ORDER BY purge_after
                        FOR UPDATE SKIP LOCKED
                        LIMIT :limit
                        """)
                .param("limit", limit)
                .query(String.class)
                .list();
    }
```

**3b.** 创建 `asset-service/src/main/java/com/storyboard/asset/worker/TrashPurgeWorker.java`：

```java
package com.storyboard.asset.worker;

import com.storyboard.asset.repository.LibraryRepository;
import com.storyboard.asset.service.AssetPurgeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 回收站到期清理：领取 purge_after <= now() 的 TRASHED 资产，走与手动 purge 相同的 AssetPurgeService。
 * 仍有活跃引用时置 PURGE_BLOCKED，对象保留。
 */
@Component
public class TrashPurgeWorker {

    private static final Logger log = LoggerFactory.getLogger(TrashPurgeWorker.class);
    static final int BATCH_SIZE = 50;

    private final LibraryRepository repository;
    private final AssetPurgeService purgeService;

    public TrashPurgeWorker(LibraryRepository repository, AssetPurgeService purgeService) {
        this.repository = repository;
        this.purgeService = purgeService;
    }

    @Scheduled(fixedDelay = 300_000)
    @Transactional
    public void run() {
        for (String assetId : repository.claimDueTrashAssets(BATCH_SIZE)) {
            try {
                AssetPurgeService.PurgeOutcome outcome = purgeService.purgeDue(assetId);
                if (outcome == AssetPurgeService.PurgeOutcome.BLOCKED) {
                    log.info("回收站资产到期但仍有引用，保留对象: assetId={}", assetId);
                }
            } catch (Exception e) {
                log.warn("回收站到期清理失败: assetId={}, error={}", assetId, e.getMessage());
            }
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `& "E:\Development\apache-maven-3.9.15\bin\mvn.cmd" -pl asset-service -am test "-Dtest=TrashPurgeWorkerTest" "-Dsurefire.failIfNoSpecifiedTests=false"`
Expected: BUILD SUCCESS，4 个测试通过。

- [ ] **Step 5: 提交**

```bash
git add asset-service/src/main/java/com/storyboard/asset/worker/TrashPurgeWorker.java asset-service/src/main/java/com/storyboard/asset/repository/LibraryRepository.java asset-service/src/test/java/com/storyboard/asset/worker/TrashPurgeWorkerTest.java
git commit -m "feat(asset): 增加回收站到期自动清理 worker"
```

---

## 收尾验证（全部 Task 完成后执行）

- [ ] 全量编译与测试：

Run: `& "E:\Development\apache-maven-3.9.15\bin\mvn.cmd" -pl asset-service -am test`
Expected: BUILD SUCCESS。

- [ ] 保护集合回归自查（人工）：

Run: `git grep -n "ObjectReferenceGuard\|StorageOrphanScanner\|TrashPurgeWorker\|AssetPurgeService"`
Expected: 扫描、二次确认、到期清理三条路径都汇聚到 `ObjectReferenceGuard` 与 `AssetPurgeService`，无第二套引用判定。

- [ ] 发布顺序（不改代码，仅操作顺序）：

1. 确认 `V6`/`V7` 迁移已在目标库执行到位。
2. 先发布本批保护逻辑 + `trash-retention-days: 3`，但保持 `orphan-scan-enabled: false`。
3. 观察期后置 `orphan-scan-enabled: true`，先看扫描日志中 `protected` 数量是否与预期一致。
4. 观察期结束后确认 `library_storage_cleanup_tasks` 中无异常入队，再依赖既有 `StorageCleanupWorker` 执行删除。
