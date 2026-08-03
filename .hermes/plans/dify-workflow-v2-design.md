# Dify 工作流精简方案 — 设计规格

## 目标

将 AiGenerateRobot 工作流从 45 节点精简到 ~11 节点（主画布），保留 7 个 step 状态机和用户自由跳转能力。

## 架构

**三层精简策略：**

```
主画布 (~11 节点)
├── 意图路由层 (3)    : start + llm 意图识别 + if-else 路由分发
├── 业务执行层 (6)    : 3 个子工作流 + 3 个 HTTP 节点
└── 控制层 (2)        : 1 个代码节点(状态路由) + 1 个模板节点(回复)
```

**节点数变化：**

| 类别 | 原节点 | 精简后 | 手段 |
|------|--------|--------|------|
| answer | 12 | 1 | 模板节点条件渲染 |
| assigner | 12 | 1 | 代码节点路由表 |
| LLM | 7 | 2+子流程内 | 子工作流复用 |
| if-else | 8 | 2+子流程内 | 子工作流封装 |
| http-request | 3 | 3 | 不变 |
| 子工作流 | 0 | 3 | 新增（不占主画布） |
| tool | 2 | 2 | 不变 |
| start | 1 | 1 | 不变 |
| **合计** | **45** | **~11+子流程** | |

---

## 设计 1：模板节点 — 12 answer → 1

### 原理
12 个 answer 节点都在做一样的事：根据 `step` 值输出不同文案。用 Dify 模板节点的 `{{#if}}/{{#elif}}` 条件渲染 + `{{变量}}` 插值合并为 1 个。

### 新增 conversation_variables

```
last_answer_text  : string  // LLM 最新输出（代码节点写入）
last_image_url    : string  // 最新图片 URL（代码节点解析 HTTP 响应写入）
last_video_url    : string  // 最新视频 URL
scene_count       : int     // 分镜数量
```

### 模板内容

```
{{#if step == -1}}
  你好！请描述你想创作的故事...
{{#elif step == 1}}
  {{last_answer_text}}
{{#elif step == 2}}
  分镜已保存！共 {{scene_count}} 个场景。
  {{last_answer_text}}
{{#elif step == 3}}
  {{last_answer_text}}
{{#elif step == 5}}
  图片生成完成！
  ![生成图片]({{last_image_url}})
{{#elif step == 6}}
  视频生成完成！
  视频链接: {{last_video_url}}
{{#endif}}
```

---

## 设计 2：代码节点 — 12 assigner → 1

### 原理
所有 assigner 逻辑相同：根据 `(当前 step, 触发动作)` 计算下一个 step。用一张路由表替代。

### 输入
- `conversation.step` — 当前步骤
- `action` — 触发动作（由上游 if-else 分支设定）
- `llm_output` — LLM 节点输出（可选）
- `http_body` — HTTP 响应体字符串（可选）

### 输出（写入 conversation_variables）
- `step` — 下一步
- `last_answer_text` / `last_image_url` / `last_video_url` / `scene_count`

### 路由表

```python
def main(step, action, llm_output=None, http_body=None):
    transitions = {
        "intent_detected":      1,
        "design_not_satisfied": step,     # 留在当前步
        "design_approved": {
            1: 2,   # 方案设计批准 → 写分镜
            3: 5,   # 图片方案批准 → 生图
            4: 6,   # 视频方案批准 → 生视频
        },
        "http_done": {
            2: 3,   # 写分镜完成 → 图片方案
            5: 4,   # 生图完成 → 视频方案
            6: -1,  # 生视频完成 → 初始态
        },
        "jump_to":  step,        # 直接跳转
        "reset":    -1,
    }
    next_step = transitions[action]
    if isinstance(next_step, dict):
        next_step = next_step.get(step, step + 1)
    
    result = {"step": next_step}
    if llm_output:
        result["last_answer_text"] = llm_output
    if http_body:
        body = json_parse(http_body)
        if "imageUrl" in body:   result["last_image_url"] = body["imageUrl"]
        if "videoUrl" in body:   result["last_video_url"] = body["videoUrl"]
        if "sceneCount" in body: result["scene_count"] = body["sceneCount"]
    return result
```

---

## 设计 3：子工作流 — 封装"设计→修改→确认"循环

### 原理
分镜方案设计、图片风格设计、视频效果设计三个循环结构完全相同（LLM 生成 → 用户反馈 → 修改或通过），封装为一个可复用的子工作流模板。

### 子工作流内部结构

```
输入: system_prompt, user_context, exit_step

[llm 生成方案]
    │
    ▼
[if-else 用户意图]
    ├─ "满意/批准" → 返回主流程 (action=design_approved, llm_output)
    ├─ "不满意/修改" → [llm 修改方案] → 回到 if-else
    └─ "跳过" → 返回主流程 (action=skip)
```

### 三个实例

| 实例 | system_prompt | exit_step |
|------|---------------|-----------|
| 子流程 A：方案设计-确认 | 分镜设计提示词 | 2（写分镜） |
| 子流程 B：图片方案-确认 | 画面风格提示词 | 5（生图） |
| 子流程 C：视频方案-确认 | 视频效果提示词 | 6（生视频） |

---

## 最终工作流结构

```
start
  │
  ▼
[llm 意图识别]
  │
  ▼
[if-else 路由分发]
  ├─ "设计分镜" → [子流程A: 方案设计-确认] → [http 写分镜]
  ├─ "设计画面" → [子流程B: 图片方案-确认] → [http 生图]
  ├─ "设计视频" → [子流程C: 视频方案-确认] → [http 生视频]
  ├─ "直接生图" → [http 生图]
  └─ "直接生视频" → [http 生视频]
                      │
                      ▼
              [code 状态路由]
                      │
                      ▼
              [template 回复用户]
                      │
                      ▼
              (循环回意图识别)
```

## 不变的部分
- 7 个 step 状态机（-1~6）完全不动
- 用户随时说"生图"跳 step=5，说"生视频"跳 step=6
- 所有 HTTP 端点（generate-script, generate-image, generate-video）不动
- 现有 conversation_variables 不动，只新增 4 个展示变量

## 实施要点
- 新工作流保存为 `AiGenerateRobot-v2.yml`，不改原文件
- 先在原工作流旁并行创建，验证通过后再切换
- 子工作流用 Dify v1.16 的"子应用调用"节点（需确认自托管版是否支持）
- 代码节点使用 Dify 内置 Python 沙箱（`json.loads` 可直接使用）
- 模板节点语法需实测确认：Dify 模板引擎可能用 Jinja2 `{% if %}` 而非 `{{#if}}`

## 待验证项
1. Dify 自托管 v1.16 是否支持子应用/子工作流嵌套调用
2. 模板节点条件语法（Jinja2 `{% if %}` vs 自定义 `{{#if}}`）
3. 代码节点能否直接读写 `conversation_variables`（还是只能通过变量赋值节点）
