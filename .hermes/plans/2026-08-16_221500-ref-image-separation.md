# 参考图分离（图片生成 vs 视频生成）Implementation Plan

> **For Hermes:** 用此计划逐任务实现；每步跑编译/类型检查/构建验证后再提交。

**Goal:** 图片生成界面上传的参考图，不再出现在视频生成界面；视频生成用自己的参考图。二者彻底分离。

**Architecture:** 分镜参考素材表 `scene_reference_images` 加 `purpose` 列（`image`/`video`）区分用途。图片 tab 上传的参考图 `purpose=image`，视频 tab 上传的参考图/视频/音频 `purpose=video`。前端按 purpose 分组渲染，互不串扰。

**Tech Stack:** Spring Boot 4 + MyBatis-Plus + PostgreSQL；React 19 + TS + Zustand。

**根因:** `PreviewPanel` 里图片 tab 的 `ReferenceUploader(type="image")` 与视频 tab 的 `ReferenceUploader(type="image")` 都读同一个 `refImages = sceneRefs.filter(r => r.type === 'image')`，且都调 `uploadSceneRef(scene.id, 'image', f)`——共用同一池 `type='image'` 素材。

---

## Part 0：回滚错误的 scene_assets purpose 拆分

上一提交 `6953b39`（图片/视频关联资产拆 purpose）改错了对象，需整体回滚。

- [ ] **Step 1** 回滚提交（分支未 push，HEAD 即该提交）：
  ```bash
  git revert --no-edit 6953b39
  ```
  期望：恢复 `scene_assets` 单表关联（无 purpose）、`SceneAssetsUpdateRequest` 回到 `{ assetIds }`、`sceneAssets(sceneId)` 无 purpose 参数、PreviewPanel 回到单一「关联资产」区。

- [ ] **Step 2** 删除已应用的 V9 列（回滚只删了迁移文件，本地 PG 列仍在）：
  ```bash
  PGPASSWORD=123456 "/d/Program Files/PostgreSQL/18/bin/psql" -h localhost -U postgres -d newworkflow \
    -c "ALTER TABLE public.scene_assets DROP COLUMN IF EXISTS purpose;"
  ```
  期望：`\d public.scene_assets` 无 `purpose` 列。

- [ ] **Step 3** 验证回滚后编译/类型检查：
  ```bash
  export JAVA_HOME="C:\\Program Files\\Java\\jdk-21" && \
  "/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AIStoryboardBackend\\pom.xml" compile -q
  cd AIStoryboardClient && node node_modules/typescript/bin/tsc -p tsconfig.app.json --noEmit && npm run build
  ```
  期望：`COMPILE_EXIT=0`、`TSC_EXIT=0`、`built in ...`。

---

## Part 1：后端给 scene_reference_images 加 purpose

- [ ] **Task 1** 新建迁移 `AIStoryboardBackend/src/main/resources/db/migration/V9__scene_reference_purpose.sql`：
  ```sql
  -- 分镜参考素材区分用途：image=图片生成参考图 / video=视频生成参考素材
  ALTER TABLE public.scene_reference_images ADD COLUMN IF NOT EXISTS purpose VARCHAR(16) NOT NULL DEFAULT 'image';
  ```
  应用：
  ```bash
  PGPASSWORD=123456 "/d/Program Files/PostgreSQL/18/bin/psql" -h localhost -U postgres -d newworkflow \
    -c "ALTER TABLE public.scene_reference_images ADD COLUMN IF NOT EXISTS purpose VARCHAR(16) NOT NULL DEFAULT 'image';"
  ```
  期望：`\d public.scene_reference_images` 出现 `purpose` 列（默认 `'image'`）。

- [ ] **Task 2** 实体 `entity/SceneReferenceImage.java` 加字段：
  ```java
  private String type;
  /** 用途：image=图片生成参考 / video=视频生成参考。 */
  private String purpose;
  private String fileName;
  ```

- [ ] **Task 3** VO `dto/response/SceneReferenceResponse.java` 加字段（record 头部）：
  ```java
  public record SceneReferenceResponse(
      String id,
      String type,
      String purpose,
      String url,
      String fileName,
      Long fileSize
  ) {}
  ```

- [ ] **Task 4** `service/SceneService.java` 改签名：
  ```java
  SceneReferenceResponse uploadReference(String sceneId, String type, String purpose, MultipartFile file);
  ```

- [ ] **Task 5** `service/impl/SceneServiceImpl.java`：
  - `listReferences` 组装加 `r.getPurpose()`。
  - `uploadReference` 加 `String purpose` 参数，`ref.setPurpose(purpose)`；返回 `new SceneReferenceResponse(ref.getId(), t, purpose, url, ref.getFileName(), ref.getFileSize())`。

- [ ] **Task 6** `controller/SceneController.java` 上传端点加 purpose：
  ```java
  public ApiResponse<SceneReferenceResponse> uploadReference(@PathVariable String id,
          @RequestParam String type, @RequestParam String purpose, @RequestParam MultipartFile file) {
      return ApiResponse.ok(sceneService.uploadReference(id, type, purpose, file));
  }
  ```

- [ ] **Task 7** 编译：同 Part 0 Step 3 的 mvn 命令，期望 `EXIT=0`。

---

## Part 2：前端分离参考图

- [ ] **Task 8** `api/projects.ts` 的 `SceneReferenceAsset` 接口加 `purpose: 'image' | 'video';`（找 `export interface SceneReferenceAsset`）。

- [ ] **Task 9** `api/scenes.ts` 的 `uploadReference` 加 purpose：
  ```ts
  uploadReference: (sceneId: string, type: string, purpose: string, file: File) => {
    const fd = new FormData();
    fd.append('type', type);
    fd.append('purpose', purpose);
    fd.append('file', file);
    return client.post<ApiResponse<SceneReferenceAsset>>(`/scenes/${sceneId}/references`, fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
  },
  ```

- [ ] **Task 10** `stores/projectStore.ts`：
  - 类型：`uploadSceneRef: (sceneId, type, purpose: 'image' | 'video', file) => Promise<void>`。
  - 实现：`await sceneApi.uploadReference(sceneId, type, purpose, file)`，把返回的 `created`（含 purpose）追加进 `sceneRefs[sceneId]`。

- [ ] **Task 11** `components/editor/PreviewPanel.tsx`（约 280 行）拆分组：
  ```tsx
  const refImages  = sceneRefs.filter((r) => r.type === 'image' && r.purpose !== 'video'); // 图片生成参考图
  const refVImages = sceneRefs.filter((r) => r.type === 'image' && r.purpose === 'video'); // 视频生成参考图
  const refVideos  = sceneRefs.filter((r) => r.type === 'video');
  const refAudios  = sceneRefs.filter((r) => r.type === 'audio');
  ```

- [ ] **Task 12** `handleGenerateVideo`（约 339-345 行）：视频多模态参考改用 `refVImages`：
  ```tsx
  } else if (refVImages.length || refVideos.length || refAudios.length) {
    await generateVideo(
      scene.id, prompt, effVideoModel,
      refVImages.map((r) => r.url), undefined,
      refVideos.map((r) => r.url), refAudios.map((r) => r.url)
    );
  }
  ```

- [ ] **Task 13** 图片 tab 参考图上传（约 632-639 行）：
  ```tsx
  onUpload={(f) => uploadSceneRef(scene.id, 'image', 'image', f)}
  ```

- [ ] **Task 14** 视频 tab 三个上传器（约 776-802 行）改 purpose：
  - 参考图：`onUpload={(f) => uploadSceneRef(scene.id, 'image', 'video', f)}`，`items={refVImages}`
  - 参考视频：`onUpload={(f) => uploadSceneRef(scene.id, 'video', 'video', f)}`，`items={refVideos}`
  - 参考音频：`onUpload={(f) => uploadSceneRef(scene.id, 'audio', 'video', f)}`，`items={refAudios}`

- [ ] **Task 15** 类型检查 + 构建：
  ```bash
  cd AIStoryboardClient && node node_modules/typescript/bin/tsc -p tsconfig.app.json --noEmit && npm run build
  ```
  期望：`TSC_EXIT=0`、`built in ...`。

---

## Part 3：验证

- [ ] **Task 16** 运行时冒烟（8085 + 自签 JWT，参照既有验证脚本）：
  1. `POST /api/scenes/{sceneId}/references`（`type=image&purpose=image`）→ 上传一张。
  2. `POST ...`（`type=image&purpose=video`）→ 上传一张。
  3. `GET /api/scenes/{sceneId}/references` → 返回两条，purpose 分别为 `image`/`video`，url 正确。
  4. 前端逻辑断言（代码审查级）：图片 tab 只读 `refImages`（purpose!=video），视频 tab 只读 `refVImages`（purpose==video）。
  5. 清理测试参考素材 + 杀 8085。

- [ ] **Task 17** 提交（回滚提交 + 本次修复提交分开）：
  ```bash
  git add AIStoryboardBackend/src AIStoryboardClient/src
  git commit -m "feat: 图片/视频参考图分离（scene_reference_images 加 purpose）"
  ```

---

## Files likely to change

后端：`db/migration/V9__scene_reference_purpose.sql`(新)、`entity/SceneReferenceImage.java`、`dto/response/SceneReferenceResponse.java`、`service/SceneService.java`、`service/impl/SceneServiceImpl.java`、`controller/SceneController.java`。

前端：`api/projects.ts`、`api/scenes.ts`、`stores/projectStore.ts`、`components/editor/PreviewPanel.tsx`。

## Risks / Open questions

- `purpose` 默认 `'image'`：存量 `type='image'` 参考图默认归图片生成，视频界面将不再显示它们（符合预期——用户正是要清空视频界面的旧图）。若用户希望存量图保留在视频侧，改默认值即可，但会违背"清空视频"的诉求。
- 回滚 `6953b39` 会把资产库关联恢复为图片/视频共用（单一「关联资产」区）。这是用户明确要求（"回滚"）。若后续还想在资产库层面区分图片/视频，另行设计（届时会与参考图分离叠加，需澄清语义）。
- `purpose` 与 `type` 双字段：`type` 仍是媒体类型（image/video/audio），`purpose` 是生成用途（image/video）。图片生成只有 `type=image&purpose=image`，视频生成有 `type∈{image,video,audio}&purpose=video`。这是最小改动，未引入额外表。
