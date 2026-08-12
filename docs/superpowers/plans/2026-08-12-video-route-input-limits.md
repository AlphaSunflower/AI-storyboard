# video 路由输入约束字段 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** AILLMGateway（8083）video 模型路由编辑配置新增 12 项输入约束字段（17 列），存储 + `/v1/models` 透传。

**架构：** `model_params` 表加 17 个 nullable INT 列（V6 migration）→ 实体/DTO/VO/Service/Controller 全链路透传 → `buildParams` video 组组装下发 → admin-ui 路由弹窗参数区新增滑块与回显。主后端 `GatewayModelServiceImpl` 原样透传 params 字符串，零改动。

**技术栈：** Spring Boot 4 / MyBatis-Plus / PostgreSQL / 静态 JS admin-ui（无构建）

**规格：** `docs/superpowers/specs/2026-08-12-video-route-input-limits-design.md`

---

## 文件结构

| 文件 | 职责 |
|---|---|
| `AILLMGateway/src/main/resources/db/migration/V6__model_params_input_limits.sql` | 新建：ALTER TABLE +17 列 |
| `AILLMGateway/src/main/java/com/llmgateway/entity/ModelParams.java` | 修改：+17 Integer 字段 |
| `AILLMGateway/src/main/java/com/llmgateway/dto/admin/ModelParamsRequest.java` | 修改：+17 record 参数 |
| `AILLMGateway/src/main/java/com/llmgateway/dto/vo/ModelParamsVO.java` | 修改：+17 record 参数 |
| `AILLMGateway/src/main/java/com/llmgateway/controller/admin/ModelParamsController.java` | 修改：toVO +17 |
| `AILLMGateway/src/main/java/com/llmgateway/service/impl/ModelParamsServiceImpl.java` | 修改：applyNonNull +17、范围校验 |
| `AILLMGateway/src/main/java/com/llmgateway/service/impl/GatewayRoutingServiceImpl.java` | 修改：buildParams video 组组装 |
| `AILLMGateway/src/main/resources/static/admin-ui/app.js` | 修改：paramsGroups.video + saveModelParams + loadModelParamsForEdit + bindParamSliders |

字段命名约定（列名 → Java → JS 键）：
范围类（min/max 两列）：`ref_images`→`refImages`、`ref_videos`→`refVideos`、`audio_count`→`audioCount`、`audio_segment_duration`→`audioSegmentDuration`、`video_segment_duration`→`videoSegmentDuration`
单值类：`max_total_duration`→`maxTotalDuration`、`max_total_files`→`maxTotalFiles`、`max_video_size_mb`→`maxVideoSizeMb`、`max_image_size_mb`→`maxImageSizeMb`、`max_audio_size_mb`→`maxAudioSizeMb`、`max_request_body_mb`→`maxRequestBodyMb`、`max_prompt_chars`→`maxPromptChars`

---

### 任务 1：V6 migration + 后端数据链路（实体/DTO/VO/Controller/Service）

**文件：**
- 创建：`AILLMGateway/src/main/resources/db/migration/V6__model_params_input_limits.sql`
- 修改：`AILLMGateway/src/main/java/com/llmgateway/entity/ModelParams.java`
- 修改：`AILLMGateway/src/main/java/com/llmgateway/dto/admin/ModelParamsRequest.java`
- 修改：`AILLMGateway/src/main/java/com/llmgateway/dto/vo/ModelParamsVO.java`
- 修改：`AILLMGateway/src/main/java/com/llmgateway/controller/admin/ModelParamsController.java`
- 修改：`AILLMGateway/src/main/java/com/llmgateway/service/impl/ModelParamsServiceImpl.java`

- [ ] **步骤 1：创建 V6 migration**

```sql
-- 视频模型输入约束（全部 nullable，兼容存量行；范围类拆 min/max 列）
ALTER TABLE model_params
    ADD COLUMN ref_images_min INT,
    ADD COLUMN ref_images_max INT,
    ADD COLUMN ref_videos_min INT,
    ADD COLUMN ref_videos_max INT,
    ADD COLUMN audio_count_min INT,
    ADD COLUMN audio_count_max INT,
    ADD COLUMN audio_segment_duration_min INT,
    ADD COLUMN audio_segment_duration_max INT,
    ADD COLUMN video_segment_duration_min INT,
    ADD COLUMN video_segment_duration_max INT,
    ADD COLUMN max_total_duration INT,
    ADD COLUMN max_total_files INT,
    ADD COLUMN max_video_size_mb INT,
    ADD COLUMN max_image_size_mb INT,
    ADD COLUMN max_audio_size_mb INT,
    ADD COLUMN max_request_body_mb INT,
    ADD COLUMN max_prompt_chars INT;
```

- [ ] **步骤 2：实体 +17 字段**（`ModelParams.java`，在 `aspectRatioDefault` 后、`createdAt` 前插入）

```java
    // video 输入约束（能力描述，透传不校验）：范围类拆 min/max，单值类一列
    private Integer refImagesMin;
    private Integer refImagesMax;
    private Integer refVideosMin;
    private Integer refVideosMax;
    private Integer audioCountMin;
    private Integer audioCountMax;
    private Integer audioSegmentDurationMin;
    private Integer audioSegmentDurationMax;
    private Integer videoSegmentDurationMin;
    private Integer videoSegmentDurationMax;
    private Integer maxTotalDuration;
    private Integer maxTotalFiles;
    private Integer maxVideoSizeMb;
    private Integer maxImageSizeMb;
    private Integer maxAudioSizeMb;
    private Integer maxRequestBodyMb;
    private Integer maxPromptChars;
```

- [ ] **步骤 3：ModelParamsRequest +17**（record 参数，`aspectRatioDefault` 后追加）

```java
        Integer refImagesMin, Integer refImagesMax,
        Integer refVideosMin, Integer refVideosMax,
        Integer audioCountMin, Integer audioCountMax,
        Integer audioSegmentDurationMin, Integer audioSegmentDurationMax,
        Integer videoSegmentDurationMin, Integer videoSegmentDurationMax,
        Integer maxTotalDuration, Integer maxTotalFiles,
        Integer maxVideoSizeMb, Integer maxImageSizeMb, Integer maxAudioSizeMb,
        Integer maxRequestBodyMb, Integer maxPromptChars) {
}
```

- [ ] **步骤 4：ModelParamsVO +17**（record 参数，`aspectRatioDefault` 后追加）

```java
        Integer refImagesMin, Integer refImagesMax,
        Integer refVideosMin, Integer refVideosMax,
        Integer audioCountMin, Integer audioCountMax,
        Integer audioSegmentDurationMin, Integer audioSegmentDurationMax,
        Integer videoSegmentDurationMin, Integer videoSegmentDurationMax,
        Integer maxTotalDuration, Integer maxTotalFiles,
        Integer maxVideoSizeMb, Integer maxImageSizeMb, Integer maxAudioSizeMb,
        Integer maxRequestBodyMb, Integer maxPromptChars,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
```

- [ ] **步骤 5：Controller toVO +17**（构造参数，`aspectRatioDefault` 后插入）

```java
                e.getAspectRatios(), e.getAspectRatioDefault(),
                e.getRefImagesMin(), e.getRefImagesMax(),
                e.getRefVideosMin(), e.getRefVideosMax(),
                e.getAudioCountMin(), e.getAudioCountMax(),
                e.getAudioSegmentDurationMin(), e.getAudioSegmentDurationMax(),
                e.getVideoSegmentDurationMin(), e.getVideoSegmentDurationMax(),
                e.getMaxTotalDuration(), e.getMaxTotalFiles(),
                e.getMaxVideoSizeMb(), e.getMaxImageSizeMb(), e.getMaxAudioSizeMb(),
                e.getMaxRequestBodyMb(), e.getMaxPromptChars(),
                e.getCreatedAt(), e.getUpdatedAt());
```

- [ ] **步骤 6：ServiceImpl applyNonNull +17 + 范围校验**

`upsert()` 中现有 `nMin/nMax` 校验后追加 5 组范围校验：

```java
        // 输入约束范围校验：5 组 min/max 均非空时 min 必须 <= max
        validateRange(req.refImagesMin(), req.refImagesMax(), "可参考图范围");
        validateRange(req.refVideosMin(), req.refVideosMax(), "可参考视频范围");
        validateRange(req.audioCountMin(), req.audioCountMax(), "音频个数范围");
        validateRange(req.audioSegmentDurationMin(), req.audioSegmentDurationMax(), "音频单段时长范围");
        validateRange(req.videoSegmentDurationMin(), req.videoSegmentDurationMax(), "视频单段时长范围");
```

`applyNonNull` 末尾追加：

```java
        if (req.refImagesMin() != null) entity.setRefImagesMin(req.refImagesMin());
        if (req.refImagesMax() != null) entity.setRefImagesMax(req.refImagesMax());
        if (req.refVideosMin() != null) entity.setRefVideosMin(req.refVideosMin());
        if (req.refVideosMax() != null) entity.setRefVideosMax(req.refVideosMax());
        if (req.audioCountMin() != null) entity.setAudioCountMin(req.audioCountMin());
        if (req.audioCountMax() != null) entity.setAudioCountMax(req.audioCountMax());
        if (req.audioSegmentDurationMin() != null) entity.setAudioSegmentDurationMin(req.audioSegmentDurationMin());
        if (req.audioSegmentDurationMax() != null) entity.setAudioSegmentDurationMax(req.audioSegmentDurationMax());
        if (req.videoSegmentDurationMin() != null) entity.setVideoSegmentDurationMin(req.videoSegmentDurationMin());
        if (req.videoSegmentDurationMax() != null) entity.setVideoSegmentDurationMax(req.videoSegmentDurationMax());
        if (req.maxTotalDuration() != null) entity.setMaxTotalDuration(req.maxTotalDuration());
        if (req.maxTotalFiles() != null) entity.setMaxTotalFiles(req.maxTotalFiles());
        if (req.maxVideoSizeMb() != null) entity.setMaxVideoSizeMb(req.maxVideoSizeMb());
        if (req.maxImageSizeMb() != null) entity.setMaxImageSizeMb(req.maxImageSizeMb());
        if (req.maxAudioSizeMb() != null) entity.setMaxAudioSizeMb(req.maxAudioSizeMb());
        if (req.maxRequestBodyMb() != null) entity.setMaxRequestBodyMb(req.maxRequestBodyMb());
        if (req.maxPromptChars() != null) entity.setMaxPromptChars(req.maxPromptChars());
```

类内新增私有方法：

```java
    /** 范围校验：min/max 均非空且 min > max → 40001 */
    private void validateRange(Integer min, Integer max, String label) {
        if (min != null && max != null && min > max) {
            throw new BusinessException(40001, label + "不合法：min 不能大于 max");
        }
    }
```

- [ ] **步骤 7：编译验证**

```bash
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AILLMGateway\\pom.xml" compile -q
```

预期：BUILD SUCCESS

- [ ] **步骤 8：Commit**

```bash
git add AILLMGateway/src/main/resources/db/migration/V6__model_params_input_limits.sql AILLMGateway/src/main/java/com/llmgateway/entity/ModelParams.java AILLMGateway/src/main/java/com/llmgateway/dto/admin/ModelParamsRequest.java AILLMGateway/src/main/java/com/llmgateway/dto/vo/ModelParamsVO.java AILLMGateway/src/main/java/com/llmgateway/controller/admin/ModelParamsController.java AILLMGateway/src/main/java/com/llmgateway/service/impl/ModelParamsServiceImpl.java
git commit -m "feat(gateway): model_params 表 video 组新增 12 项输入约束（17 列）——可参考图/视频范围、音频个数与单段时长、视频单段时长、总时长/文件数/各类型单文件 MB/请求体 MB/提示词上限，实体-DTO-VO-Controller-Service 全链路 + min<=max 范围校验"
```

---

### 任务 2：/v1/models 下发组装

**文件：**
- 修改：`AILLMGateway/src/main/java/com/llmgateway/service/impl/GatewayRoutingServiceImpl.java`

- [ ] **步骤 1：buildParams video 组追加组装**（`aspectRatioDefault` 行后插入）

```java
        // video：输入约束（范围类嵌套 {min,max}，单值类平铺数字）
        putRange(params, "refImages", mp.getRefImagesMin(), mp.getRefImagesMax());
        putRange(params, "refVideos", mp.getRefVideosMin(), mp.getRefVideosMax());
        putRange(params, "audioCount", mp.getAudioCountMin(), mp.getAudioCountMax());
        putRange(params, "audioSegmentDuration", mp.getAudioSegmentDurationMin(), mp.getAudioSegmentDurationMax());
        putRange(params, "videoSegmentDuration", mp.getVideoSegmentDurationMin(), mp.getVideoSegmentDurationMax());
        putInt(params, "maxTotalDuration", mp.getMaxTotalDuration());
        putInt(params, "maxTotalFiles", mp.getMaxTotalFiles());
        putInt(params, "maxVideoSizeMB", mp.getMaxVideoSizeMb());
        putInt(params, "maxImageSizeMB", mp.getMaxImageSizeMb());
        putInt(params, "maxAudioSizeMB", mp.getMaxAudioSizeMb());
        putInt(params, "maxRequestBodyMB", mp.getMaxRequestBodyMb());
        putInt(params, "maxPromptChars", mp.getMaxPromptChars());
```

类内新增两个私有方法（复用现有 `putIntDefault` 风格，`putDefault` 后追加）：

```java
    /** 范围对象 {min,max}：任一端非空才放 */
    private void putRange(Map<String, Object> params, String key, Integer min, Integer max) {
        if (min == null && max == null) return;
        Map<String, Object> r = new LinkedHashMap<>();
        if (min != null) r.put("min", min);
        if (max != null) r.put("max", max);
        params.put(key, r);
    }

    /** Integer 直放（null 跳过） */
    private void putInt(Map<String, Object> params, String key, Integer val) {
        if (val != null) params.put(key, val);
    }
```

- [ ] **步骤 2：编译验证**

```bash
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AILLMGateway\\pom.xml" compile -q
```

预期：BUILD SUCCESS

- [ ] **步骤 3：Commit**

```bash
git add AILLMGateway/src/main/java/com/llmgateway/service/impl/GatewayRoutingServiceImpl.java
git commit -m "feat(gateway): /v1/models 下发 video 输入约束——范围类嵌套 {min,max}、单值类平铺数字（putRange/putInt），主后端原样透传零改动"
```

---

### 任务 3：admin-ui 路由弹窗表单

**文件：**
- 修改：`AILLMGateway/src/main/resources/static/admin-ui/app.js`

- [ ] **步骤 1：paramsGroups.video 追加约束字段表单**（`p-aspectdefault` 行后追加）

```js
       + '<label>画幅默认 <input class="input" id="p-aspectdefault" placeholder="16:9"></label>'
       + sliderField('p-refimagesmin', '参考图min(张)', '0', '10', '1', '0')
       + sliderField('p-refimagesmax', '参考图max(张)', '0', '10', '1', '3')
       + sliderField('p-refvideosmin', '参考视频min(个)', '0', '5', '1', '0')
       + sliderField('p-refvideosmax', '参考视频max(个)', '0', '5', '1', '1')
       + sliderField('p-audiocountmin', '音频个数min', '0', '10', '1', '0')
       + sliderField('p-audiocountmax', '音频个数max', '0', '10', '1', '2')
       + sliderField('p-audiodmin', '音频单段min(秒)', '1', '300', '1', '5')
       + sliderField('p-audiodmax', '音频单段max(秒)', '1', '300', '1', '60')
       + sliderField('p-videodmin', '视频单段min(秒)', '1', '300', '1', '5')
       + sliderField('p-videodmax', '视频单段max(秒)', '1', '300', '1', '60')
       + sliderField('p-maxtotalduration', '总时长上限(秒)', '1', '3600', '1', '300')
       + sliderField('p-maxtotalfiles', '文件总数上限', '1', '50', '1', '10')
       + sliderField('p-maxvideosize', '视频单个(MB)', '1', '500', '1', '100')
       + sliderField('p-maximagesize', '图片单个(MB)', '1', '100', '1', '10')
       + sliderField('p-maxaudiosize', '音频单个(MB)', '1', '100', '1', '15')
       + sliderField('p-maxbody', '请求体上限(MB)', '1', '200', '1', '64')
       + sliderField('p-maxprompt', '提示词上限(字符)', '100', '20000', '100', '2000'),
```

- [ ] **步骤 2：bindParamSliders 绑定新滑块**（绑定列表追加，`'p-durationdefault'` 后追加）

```js
    ['p-temperature', 'p-maxtokens', 'p-topp', 'p-nmin', 'p-nmax', 'p-ndefault', 'p-dmin', 'p-dmax', 'p-durationdefault',
     'p-refimagesmin', 'p-refimagesmax', 'p-refvideosmin', 'p-refvideosmax',
     'p-audiocountmin', 'p-audiocountmax', 'p-audiodmin', 'p-audiodmax', 'p-videodmin', 'p-videodmax',
     'p-maxtotalduration', 'p-maxtotalfiles', 'p-maxvideosize', 'p-maximagesize', 'p-maxaudiosize', 'p-maxbody', 'p-maxprompt']
      .forEach(bind);
```

`bind` 回调内 min<=max 联动追加（`p-dmax` 联动行后）：

```js
        if (id === 'p-refimagesmin') { const mx = document.getElementById('p-refimagesmax'); if (mx && Number(mx.value) < Number(el.value)) mx.value = el.value; }
        if (id === 'p-refimagesmax') { const mn = document.getElementById('p-refimagesmin'); if (mn && Number(mn.value) > Number(el.value)) mn.value = el.value; }
        if (id === 'p-refvideosmin') { const mx = document.getElementById('p-refvideosmax'); if (mx && Number(mx.value) < Number(el.value)) mx.value = el.value; }
        if (id === 'p-refvideosmax') { const mn = document.getElementById('p-refvideosmin'); if (mn && Number(mn.value) > Number(el.value)) mn.value = el.value; }
        if (id === 'p-audiocountmin') { const mx = document.getElementById('p-audiocountmax'); if (mx && Number(mx.value) < Number(el.value)) mx.value = el.value; }
        if (id === 'p-audiocountmax') { const mn = document.getElementById('p-audiocountmin'); if (mn && Number(mn.value) > Number(el.value)) mn.value = el.value; }
        if (id === 'p-audiodmin') { const mx = document.getElementById('p-audiodmax'); if (mx && Number(mx.value) < Number(el.value)) mx.value = el.value; }
        if (id === 'p-audiodmax') { const mn = document.getElementById('p-audiodmin'); if (mn && Number(mn.value) > Number(el.value)) mn.value = el.value; }
        if (id === 'p-videodmin') { const mx = document.getElementById('p-videodmax'); if (mx && Number(mx.value) < Number(el.value)) mx.value = el.value; }
        if (id === 'p-videodmax') { const mn = document.getElementById('p-videodmin'); if (mn && Number(mn.value) > Number(el.value)) mn.value = el.value; }
```

- [ ] **步骤 3：loadModelParamsForEdit 回显 17 值**（`set('p-aspectdefault', ...)` 行后追加）

```js
    set('p-refimagesmin', mp.refImagesMin);
    set('p-refimagesmax', mp.refImagesMax);
    set('p-refvideosmin', mp.refVideosMin);
    set('p-refvideosmax', mp.refVideosMax);
    set('p-audiocountmin', mp.audioCountMin);
    set('p-audiocountmax', mp.audioCountMax);
    set('p-audiodmin', mp.audioSegmentDurationMin);
    set('p-audiodmax', mp.audioSegmentDurationMax);
    set('p-videodmin', mp.videoSegmentDurationMin);
    set('p-videodmax', mp.videoSegmentDurationMax);
    set('p-maxtotalduration', mp.maxTotalDuration);
    set('p-maxtotalfiles', mp.maxTotalFiles);
    set('p-maxvideosize', mp.maxVideoSizeMb);
    set('p-maximagesize', mp.maxImageSizeMb);
    set('p-maxaudiosize', mp.maxAudioSizeMb);
    set('p-maxbody', mp.maxRequestBodyMb);
    set('p-maxprompt', mp.maxPromptChars);
```

- [ ] **步骤 4：saveModelParams video 分支取值**（`req.aspectRatioDefault = ...` 行后追加）

```js
    req.refImagesMin = num('p-refimagesmin');
    req.refImagesMax = num('p-refimagesmax');
    req.refVideosMin = num('p-refvideosmin');
    req.refVideosMax = num('p-refvideosmax');
    req.audioCountMin = num('p-audiocountmin');
    req.audioCountMax = num('p-audiocountmax');
    req.audioSegmentDurationMin = num('p-audiodmin');
    req.audioSegmentDurationMax = num('p-audiodmax');
    req.videoSegmentDurationMin = num('p-videodmin');
    req.videoSegmentDurationMax = num('p-videodmax');
    req.maxTotalDuration = num('p-maxtotalduration');
    req.maxTotalFiles = num('p-maxtotalfiles');
    req.maxVideoSizeMb = num('p-maxvideosize');
    req.maxImageSizeMb = num('p-maximagesize');
    req.maxAudioSizeMb = num('p-maxaudiosize');
    req.maxRequestBodyMb = num('p-maxbody');
    req.maxPromptChars = num('p-maxprompt');
```

- [ ] **步骤 5：JS 语法验证 + 断言**

```bash
node --check AILLMGateway/src/main/resources/static/admin-ui/app.js
```

预期：无输出（exit 0）。再跑 ad-hoc 断言脚本（hermes-verify 前缀，tempfile 生成，跑完删除）：断言 app.js 含全部 17 个新 id、saveModelParams 含 17 个 req 赋值、回显含 17 个 set、`GatewayRoutingServiceImpl` 含 putRange/putInt 与 12 个 key、`ModelParams` 实体含 17 字段、V6 migration 含 17 列。

- [ ] **步骤 6：Commit**

```bash
git add AILLMGateway/src/main/resources/static/admin-ui/app.js
git commit -m "feat(gateway): admin-ui video 路由参数区新增 12 项输入约束表单——滑块+min<=max 联动+回显+保存取值，17 个新控件"
```

---

### 任务 4：数据库应用 + 端到端冒烟（可选，需网关实例）

- [ ] **步骤 1：应用 V6 migration**

```bash
PGPASSWORD=123456 psql -h localhost -U postgres -d newworkflow -f AILLMGateway/src/main/resources/db/migration/V6__model_params_input_limits.sql
```

预期：ALTER TABLE × 17 成功

- [ ] **步骤 2：冒烟**（重启 8083 网关后）

- 登录 /admin-ui → 路由管理 → 新建/编辑 video 类型路由 → 参数配置区可见 12 项新滑块 → 保存 → 重新编辑回显正确
- `curl localhost:8083/v1/models?type=video`（带 API key）→ params 含 refImages/refVideos/audioCount/audioSegmentDuration/videoSegmentDuration/maxTotalDuration 等 12 键

---

## 自检

1. **规格覆盖度：** 12 项字段全在任务 1（存储链路）+ 任务 2（下发）+ 任务 3（表单）覆盖；透传零改动（主后端）已确认。
2. **占位符扫描：** 每步含完整代码；无 TODO/待定。
3. **类型一致性：** 列名 snake_case ↔ Java camelCase ↔ JS 键名三处统一（refImages/refVideos/audioCount/audioSegmentDuration/videoSegmentDuration/maxTotalDuration/maxTotalFiles/maxVideoSizeMb/maxImageSizeMb/maxAudioSizeMb/maxRequestBodyMb/maxPromptChars）；Controller toVO 与 VO record 参数顺序一致。
