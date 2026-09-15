# Canvas Asset MinIO Mapping Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist generated and imported canvas media in MinIO and map every canvas node asset through `canvas_assets`.

**Architecture:** Add a durable canvas asset mapping table and service. MinIO stores bytes, `canvas_assets` stores canvas/node/generation relationships, and IndexedDB becomes only a Blob cache. Generation submissions pass asset IDs; the canvas backend resolves them to short `/api/files/**` URLs, and agent-core uploads those to the gateway as `mm_file://`.

**Tech Stack:** Spring Boot 4.1, MyBatis-Plus, PostgreSQL, MinIO/S3, React 18, TypeScript, Zustand, IndexedDB, Vitest, JUnit 5, Mockito.

**Spec:** `docs/superpowers/specs/2026-09-15-canvas-asset-minio-mapping-design.md`

## Global Constraints

- `canvas_assets.id` is the canonical canvas node `assetId`.
- MinIO is the canonical binary store.
- IndexedDB is cache only.
- New generation requests must not carry `data:` media payloads.
- `agent_assets` remains the generation audit record.
- Mapping writes are idempotent.
- Existing unrelated working-tree changes must not be reverted.

---

### Task 1: Canvas Asset Schema and Entity

**Files:**
- Create: `XiaoYueCanvas/src/main/sql/V6__canvas_assets.sql`
- Create: `XiaoYueCanvas/src/main/java/com/moon/xiaoyuecanvas/entity/CanvasAsset.java`
- Create: `XiaoYueCanvas/src/main/java/com/moon/xiaoyuecanvas/mapper/CanvasAssetMapper.java`
- Create: `XiaoYueCanvas/src/test/java/com/moon/xiaoyuecanvas/entity/CanvasAssetTest.java`

**Interfaces:**
- Produces: `CanvasAsset` fields `id`, `canvasId`, `projectId`, `userId`, `type`, `mimeType`, `objectUrl`, `objectKey`, `generationRecordId`, `producerNodeId`, `status`, `createdAt`, `updatedAt`.
- Produces: `CanvasAssetMapper extends BaseMapper<CanvasAsset>`.

- [ ] **Step 1: Write the failing test**

```java
class CanvasAssetTest {
    @Test
    void defaultsToReadyStatus() {
        CanvasAsset asset = new CanvasAsset();
        asset.setId("asset-1");
        assertEquals("ready", asset.getStatus());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl XiaoYueCanvas -am '-Dtest=CanvasAssetTest' '-Dsurefire.failIfNoSpecifiedTests=false' test -q`

Expected: FAIL because `CanvasAsset` does not exist.

- [ ] **Step 3: Add migration and entity**

Create the `canvas_assets` table exactly as specified in the design document. Add
`@TableName(value = "canvas_assets", schema = "public")` on `CanvasAsset`, with
`status = "ready"` initialized before insert.

- [ ] **Step 4: Add mapper**

```java
@Mapper
public interface CanvasAssetMapper extends BaseMapper<CanvasAsset> {}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -pl XiaoYueCanvas -am '-Dtest=CanvasAssetTest' '-Dsurefire.failIfNoSpecifiedTests=false' test -q`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add XiaoYueCanvas/src/main/sql/V6__canvas_assets.sql XiaoYueCanvas/src/main/java/com/moon/xiaoyuecanvas/entity/CanvasAsset.java XiaoYueCanvas/src/main/java/com/moon/xiaoyuecanvas/mapper/CanvasAssetMapper.java XiaoYueCanvas/src/test/java/com/moon/xiaoyuecanvas/entity/CanvasAssetTest.java
git commit -m "feat(canvas): add asset mapping schema"
```

### Task 2: Canvas Asset Service and APIs

**Files:**
- Create: `XiaoYueCanvas/src/main/java/com/moon/xiaoyuecanvas/service/CanvasAssetService.java`
- Create: `XiaoYueCanvas/src/main/java/com/moon/xiaoyuecanvas/service/impl/CanvasAssetServiceImpl.java`
- Modify: `XiaoYueCanvas/src/main/java/com/moon/xiaoyuecanvas/controller/DesignAgentJobController.java`
- Test: `XiaoYueCanvas/src/test/java/com/moon/xiaoyuecanvas/service/CanvasAssetServiceTest.java`

**Interfaces:**

```java
public interface CanvasAssetService {
    CanvasAsset upload(String userId, String canvasId, String producerNodeId,
                       String type, MultipartFile file);
    List<CanvasAsset> linkGeneration(String userId, String canvasId,
                                     String producerNodeId, String generationRecordId);
    List<CanvasAsset> list(String userId, String canvasId);
    CanvasAsset getOwned(String userId, String assetId);
}
```

- [ ] **Step 1: Write failing upload and idempotent-link tests**

Tests must assert:

```java
assertEquals("asset-1", uploaded.getId());
verify(canvasAssetMapper).insert(any(CanvasAsset.class));
assertEquals(1, linked.size());
verify(canvasAssetMapper, times(1)).insert(any(CanvasAsset.class));
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl XiaoYueCanvas -am '-Dtest=CanvasAssetServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test -q`

Expected: FAIL because service does not exist.

- [ ] **Step 3: Implement ownership and upload**

Use `MorrowCanvasService.get(userId, canvasId)` for ownership. Write bytes with
`FileStorageService.saveUploadedReference(type, file)`, parse the returned
`/api/files/{type}/{filename}` path, and insert `CanvasAsset` with a UUID ID.

- [ ] **Step 4: Implement generation linking**

Read the `AgentAsset` by `generationRecordId`. Require `completed`. Read
`result_urls`, falling back to `url`. For each URL, derive type, MIME, filename
and object key. Return an existing row when the unique mapping already exists.

- [ ] **Step 5: Add controller endpoints**

Add:

```java
@PostMapping(value = "/assets/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
@PostMapping("/assets/link-generation")
@GetMapping("/canvases/{canvasId}/assets")
```

Keep `GET /assets/{assetId}` but route it through `CanvasAssetService.getOwned`.

- [ ] **Step 6: Run tests**

Run: `mvn -pl XiaoYueCanvas -am '-Dtest=CanvasAssetServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test -q`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add XiaoYueCanvas/src/main/java/com/moon/xiaoyuecanvas/service/CanvasAssetService.java XiaoYueCanvas/src/main/java/com/moon/xiaoyuecanvas/service/impl/CanvasAssetServiceImpl.java XiaoYueCanvas/src/main/java/com/moon/xiaoyuecanvas/controller/DesignAgentJobController.java XiaoYueCanvas/src/test/java/com/moon/xiaoyuecanvas/service/CanvasAssetServiceTest.java
git commit -m "feat(canvas): add MinIO asset mapping APIs"
```

### Task 3: Resolve Reference Asset IDs in Canvas Jobs

**Files:**
- Modify: `XiaoYueCanvas/src/main/java/com/moon/xiaoyuecanvas/service/DesignJobService.java`
- Modify: `XiaoYueCanvas/src/main/java/com/moon/xiaoyuecanvas/dto/request/JobRequest.java`
- Test: `XiaoYueCanvas/src/test/java/com/moon/xiaoyuecanvas/service/DesignJobServiceAssetReferenceTest.java`

**Interfaces:**
- Consumes: `CanvasAssetService.getOwned`.
- Produces: job params may carry `referenceAssetIds: string[]`.
- Produces: legacy `referenceImages` data URIs are replaced before agent-core invocation.

- [ ] **Step 1: Write failing reference-resolution test**

```java
CanvasAsset asset = new CanvasAsset();
asset.setId("canvas-asset-1");
asset.setObjectUrl("/api/files/images/source.png");
asset.setType("image");
when(assetService.getOwned("user-1", "canvas-asset-1")).thenReturn(asset);

assertDoesNotThrow(() -> service.handleVideo(jobWithReferenceAssetId(), "owner-1"));
verify(videoGenerationService).createVideoTask(
        any(), any(), any(), any(), any(), any(), any(), any(), any(),
        argThat(images -> images.getFirst().url().equals("/api/files/images/source.png")),
        any(), any(), any(), any(), eq(true));
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl XiaoYueCanvas -am '-Dtest=DesignJobServiceAssetReferenceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test -q`

Expected: FAIL because `referenceAssetIds` is ignored.

- [ ] **Step 3: Implement asset ID resolution**

Add a helper that:

1. Reads `referenceAssetIds`.
2. Resolves each through `CanvasAssetService.getOwned`.
3. Adds `new ReferenceImage(asset.getObjectUrl(), mappedRole)`.
4. Ignores legacy `referenceImages` only after successful ID resolution.

- [ ] **Step 4: Reject remaining data URIs**

If `referenceImages` still contains `data:`, return a business error:

```java
throw new BusinessException(40001, "参考图片尚未持久化，请重新选择后再生成");
```

- [ ] **Step 5: Run test**

Run: `mvn -pl XiaoYueCanvas -am '-Dtest=DesignJobServiceAssetReferenceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test -q`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add XiaoYueCanvas/src/main/java/com/moon/xiaoyuecanvas/service/DesignJobService.java XiaoYueCanvas/src/main/java/com/moon/xiaoyuecanvas/dto/request/JobRequest.java XiaoYueCanvas/src/test/java/com/moon/xiaoyuecanvas/service/DesignJobServiceAssetReferenceTest.java
git commit -m "feat(canvas): resolve video references through asset mapping"
```

### Task 4: agent-core Normalizes All References to mm_file

**Files:**
- Modify: `agent-core/src/main/java/com/moon/moonagent/ai/impl/VideoGenerationServiceImpl.java`
- Test: `agent-core/src/test/java/com/moon/moonagent/ai/impl/VideoGenerationServiceImplReferenceTest.java`

**Interfaces:**
- Produces: `resolveReferenceUrl(String, String)` returns `mm_file://` for HTTP, `/api/files/**`, local objects, and data URIs.
- Produces: no small-file data URI branch for video generation.

- [ ] **Step 1: Write failing data URI test**

```java
VideoGenerationServiceImpl service = spy(new VideoGenerationServiceImpl(
        config, gatewayModelService, videoPollExecutor, fileStorageService, sceneBridge));
doReturn("mm_file://reference-image").when(service)
        .uploadReferenceToGateway(anyString(), any(byte[].class));

assertEquals("mm_file://reference-image",
        service.resolveReferenceUrl("data:image/png;base64,AQID", "image"));
verify(service).uploadReferenceToGateway(anyString(), aryEq(new byte[]{1, 2, 3}));
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl agent-core -am '-Dtest=VideoGenerationServiceImplReferenceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test -q`

Expected: FAIL because data URI is returned unchanged.

- [ ] **Step 3: Implement decoding and upload**

Add a package-private helper that parses `data:<mime>;base64,<payload>`, maps
MIME to a filename, and calls `uploadReferenceToGateway`.

- [ ] **Step 4: Remove inline data URI conversion**

For `/api/files/**` and local object bytes, always call
`uploadReferenceToGateway`; do not return a data URI.

- [ ] **Step 5: Run test**

Run: `mvn -pl agent-core -am '-Dtest=VideoGenerationServiceImplReferenceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test -q`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add agent-core/src/main/java/com/moon/moonagent/ai/impl/VideoGenerationServiceImpl.java agent-core/src/test/java/com/moon/moonagent/ai/impl/VideoGenerationServiceImplReferenceTest.java
git commit -m "fix(agent): upload video references before generation"
```

### Task 5: Frontend Canvas Asset API and Media Materialization

**Files:**
- Create: `XiaoYueCanvasAgent/src/services/canvasAssets.ts`
- Modify: `XiaoYueCanvasAgent/src/services/jobRunner.ts`
- Modify: `XiaoYueCanvasAgent/src/canvas/generationRuntime.ts`
- Modify: `XiaoYueCanvasAgent/src/types.ts`
- Test: `XiaoYueCanvasAgent/src/services/canvasAssets.test.ts`

**Interfaces:**

```ts
export interface CanvasAssetDescriptor {
  id: string;
  type: 'image' | 'video' | 'audio';
  mimeType: string;
  url: string;
  generationRecordId?: string;
  producerNodeId?: string;
}

export function uploadCanvasAsset(
  canvasId: string,
  producerNodeId: string,
  kind: 'image' | 'video' | 'audio',
  blob: Blob,
  filename: string,
): Promise<CanvasAssetDescriptor>;

export function linkGeneratedCanvasAssets(
  canvasId: string,
  producerNodeId: string,
  generationRecordId: string,
): Promise<CanvasAssetDescriptor[]>;

export function listCanvasAssets(canvasId: string): Promise<CanvasAssetDescriptor[]>;
export function downloadCanvasAssetBlob(asset: CanvasAssetDescriptor): Promise<Blob>;
```

- [ ] **Step 1: Write failing API test**

```ts
it('uploads a Blob and returns the database asset id', async () => {
  installResponse({ id: 'asset-1', type: 'image', mimeType: 'image/png', url: '/api/files/images/a.png' });
  const asset = await uploadCanvasAsset('canvas-1', 'node-1', 'image', new Blob(['x']), 'a.png');
  expect(asset.id).toBe('asset-1');
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- --run src/services/canvasAssets.test.ts`

Expected: FAIL because `canvasAssets.ts` does not exist.

- [ ] **Step 3: Implement API using apiFetch/apiJson**

Use `FormData` for upload. No `Content-Type` header on multipart requests.

- [ ] **Step 4: Change source reference collection**

In `collectReferenceDataUris`:

1. Read each Blob from `useMediaStore` or IndexedDB.
2. Call `uploadCanvasAsset` with the current canvas and producer node.
3. Return database asset IDs in `images` or `videos`.
4. Do not call `blobToDataURI`.

Keep the existing function name temporarily to limit blast radius.

- [ ] **Step 5: Change generated result materialization**

Before `createImageAsset` or `createVideoAsset`, call
`linkGeneratedCanvasAssets`. Use the returned database ID as the persisted asset
ID and save the downloaded Blob under that same ID.

- [ ] **Step 6: Run tests**

Run: `npm test -- --run src/services/canvasAssets.test.ts src/canvas/generationRuntime.test.ts src/services/jobRunner.batch.test.ts`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add XiaoYueCanvasAgent/src/services/canvasAssets.ts XiaoYueCanvasAgent/src/services/jobRunner.ts XiaoYueCanvasAgent/src/canvas/generationRuntime.ts XiaoYueCanvasAgent/src/types.ts XiaoYueCanvasAgent/src/services/canvasAssets.test.ts
git commit -m "feat(canvas): persist and map browser media assets"
```

### Task 6: Full Verification and Live Regression

**Files:**
- Modify if tests expose issues: files from Tasks 1-5.

**Interfaces:**
- Consumes all previous tasks.

- [ ] **Step 1: Run backend tests**

Run: `mvn -pl XiaoYueCanvas -am test -q`

Expected: exit 0.

- [ ] **Step 2: Run agent-core tests**

Run: `mvn -pl agent-core -am test -q`

Expected: exit 0.

- [ ] **Step 3: Run frontend verification**

Run: `npm run verify`

Expected: typecheck, node typecheck, all tests, and production build pass.

- [ ] **Step 4: Apply migration and restart services**

Run the V6 migration against `ai_storyboard`, rebuild `XiaoYueCanvas`, stop the
running instance, and start the new JAR with the current local profile.

- [ ] **Step 5: Terminate the unsubmitted job**

Set `3aaa11e887cf5389c5b1207a8a733142` to `failed` with:

`参考素材未持久化，任务尚未提交到 MiniMax；请重新生成`

Append the matching `job.failed` event in the same transaction.

- [ ] **Step 6: Live image-to-video verification**

Submit one image-to-video request and verify:

1. `canvas_assets` contains the source image and generated video.
2. `canvas_jobs.provider_task_id` is populated.
3. `canvas_job_events` contains progress events.
4. Gateway `call_log` contains `created` and `polled`.
5. The generated video is readable from MinIO.

- [ ] **Step 7: Commit any verification fixes**

```bash
git add XiaoYueCanvas agent-core XiaoYueCanvasAgent docs/superpowers
git commit -m "fix(canvas): persist media assets and resume video polling"
```
