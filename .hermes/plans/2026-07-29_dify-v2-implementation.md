# Dify 工作流 v2 实现计划

> **面向 AI 代理的工作者：** 使用 subagent-driven-development 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法跟踪进度。

**目标：** 基于 AiGenerateRobot.yml，创建精简版 v2（45→~11 节点），不改原文件。

**架构：** 模板节点合并 12 answer、代码节点合并 12 assigner、子工作流封装 3 个"设计-确认"循环。

**技术栈：** Dify v1.16 自托管、YML DSL、Jinja2 模板

---

## 文件结构

| 文件 | 职责 |
|------|------|
| `AIStoryboardDify/AiGenerateRobot-v2.yml` | 精简后主工作流 |
| `AIStoryboardDify/SubDesignConfirm.yml` | 可复用子工作流"设计-确认" |

---

## Task 1：验证 Dify v1.16 三个技术前提

**目的：** 确认模板语法、代码节点变量读写、子工作流支持，否则后续任务无法执行。

### 步骤

- [ ] **1.1 验证模板节点条件语法**

在 Dify UI 中创建一个测试工作流，加入模板节点，测试以下三种写法哪个生效：

```
写法A: {{#if step == 1}} 分镜方案 {{/if}}
写法B: {% if step == 1 %} 分镜方案 {% endif %}
写法C: {{ "分镜方案" if step == 1 else "" }}
```

记录结果 → 后续 Task 3 使用正确语法。

- [ ] **1.2 验证代码节点能否写 conversation_variables**

在测试工作流中创建代码节点：

```python
def main(step: int) -> dict:
    return {"step": step + 1, "test_var": "hello from code"}
```

运行后检查 `conversation_variables.test_var` 是否有值。如果代码节点不能直接写 conversation_variables，则后续 Task 4 需要在代码节点后加变量赋值节点。

- [ ] **1.3 验证子工作流/子应用调用**

在 Dify 中尝试创建一个子工作流，然后用"子应用调用"或"工作流调用"节点在主工作流中引用。确认自托管 v1.16 是否支持此功能。如果不支持，备选方案：三个"设计-确认"循环展开在主画布中（多 6 个节点，仍可接受）。

---

## Task 2：创建子工作流 `SubDesignConfirm.yml`

**目的：** 封装"LLM 生成 → 用户反馈 → 修改或通过"循环。

**文件：** 创建 `AIStoryboardDify/SubDesignConfirm.yml`

### 步骤

- [ ] **2.1 编写子工作流 YML**

节点结构（~3 节点）：

```
start
  │
  ▼
[llm] 生成方案  ← system_prompt 从输入变量读取，user_query 从 conversation 读取
  │
  ▼
[if-else] 用户意图判断
  ├─ "满意/批准" → answer: 输出 llm 结果，action=design_approved
  ├─ "不满意/修改" → 回到 [llm]（带上之前的结果作为上下文）
  └─ "跳过" → answer: 输出跳过提示，action=skip
```

输入变量：
- `system_prompt` (string) — LLM 系统提示词
- `exit_step` (int) — 批准后跳转的 step

输出变量（写入 conversation 或通过节点输出传递）：
- `action` — "design_approved" | "skip"
- `llm_output` — LLM 生成的方案文本

- [ ] **2.2 测试子工作流**

在 Dify 中单独运行子工作流：
- 输入 system_prompt="你是一个分镜设计师..."
- 发送"给我设计一个武侠故事的分镜"
- 验证：能正常生成方案
- 发送"不满意，第三个场景改一下" 
- 验证：LLM 能基于上下文修改
- 发送"可以了"
- 验证：返回 action=design_approved

---

## Task 3：编写主工作流模板节点

**目的：** 1 个模板节点替代原 12 个 answer 节点。

**文件：** 修改 `AIStoryboardDify/AiGenerateRobot-v2.yml` 中的 answer 部分

### 步骤

- [ ] **3.1 添加 conversation_variables**

在 v2 YML 的 `conversation_variables` 中新增：

```yaml
- name: last_answer_text
  value_type: string
  description: LLM 最新输出
- name: last_image_url
  value_type: string
  description: 最新图片 URL
- name: last_video_url
  value_type: string
  description: 最新视频 URL
- name: scene_count
  value_type: number
  description: 分镜数量
```

- [ ] **3.2 编写模板节点内容**

根据 Task 1.1 验证结果选择语法。以 Jinja2 `{% if %}` 为例：

```jinja2
{% if step == -1 %}
你好！请描述你想创作的故事，我会帮你完成分镜设计、图片和视频生成。
{% elif step == 1 %}
{{ last_answer_text }}
{% elif step == 2 %}
分镜已保存！共 {{ scene_count }} 个场景。
{{ last_answer_text }}
{% elif step == 3 %}
{{ last_answer_text }}
{% elif step == 5 %}
图片生成完成！
![生成的图片]({{ last_image_url }})
{% elif step == 6 %}
视频生成完成！
视频链接: {{ last_video_url }}
{% endif %}
```

- [ ] **3.3 验证模板渲染**

在 Dify 中触发各 step，确认模板输出正确：
- step=-1 → 显示欢迎文案
- step=5 且有 last_image_url → 显示图片（Markdown 渲染）
- step=1 且有 last_answer_text → 显示 LLM 文案

---

## Task 4：编写主工作流代码节点

**目的：** 1 个代码节点替代原 12 个 assigner，路由表 + 变量写入。

**文件：** 修改 `AIStoryboardDify/AiGenerateRobot-v2.yml` 中的 assigner 部分

### 步骤

- [ ] **4.1 编写代码节点**

```python
def main(step: int, action: str, llm_output: str = "", http_body: str = "") -> dict:
    import json

    # 状态路由
    transitions = {
        "intent_detected": 1,
        "design_not_satisfied": step,
        "design_approved": {1: 2, 3: 5, 4: 6},
        "http_done": {2: 3, 5: 4, 6: -1},
        "jump_to": step,
        "reset": -1,
    }

    next_step = transitions.get(action, step)
    if isinstance(next_step, dict):
        next_step = next_step.get(step, step + 1)

    result = {"step": next_step}

    # 变量写入
    if llm_output:
        result["last_answer_text"] = llm_output
    if http_body:
        try:
            body = json.loads(http_body)
        except:
            body = {}
        if "imageUrl" in body:
            result["last_image_url"] = body["imageUrl"]
        if "videoUrl" in body:
            result["last_video_url"] = body["videoUrl"]
        if "sceneCount" in body:
            result["scene_count"] = body["sceneCount"]

    return result
```

- [ ] **4.2 设置节点输入变量**

代码节点需声明输入：
- `step` — 来自 `{{#conversation.step#}}`
- `action` — 来自上游 if-else 分支设定
- `llm_output` — 可选，来自上游 LLM 节点
- `http_body` — 可选，来自上游 HTTP 节点

- [ ] **4.3 验证路由表**

在 Dify 中手动设 step 和 action，验证跳转正确：
- (step=1, action=design_approved) → step=2 ✓
- (step=3, action=design_approved) → step=5 ✓
- (step=4, action=design_approved) → step=6 ✓
- (step=2, action=http_done) → step=3 ✓
- (step=5, action=http_done) → step=4 ✓
- (step=6, action=http_done) → step=-1 ✓

---

## Task 5：组装主工作流 `AiGenerateRobot-v2.yml`

**目的：** 将所有节点连接为完整工作流。

**文件：** 创建 `AIStoryboardDify/AiGenerateRobot-v2.yml`（基于原文件裁剪）

### 步骤

- [ ] **5.1 复制原 YML 基础结构**

从 `AiGenerateRobot.yml` 复制：
- `app` 元数据（改名 v2）
- `dependencies`（市场插件）
- `conversation_variables`（原有 + Task 3.1 新增）
- `environment_variables`
- 意图识别 LLM 节点
- 3 个 HTTP 节点（generate-script, generate-image, generate-video）
- tool 节点

- [ ] **5.2 添加精简后的节点**

替换/添加：
- 模板节点（Task 3 产物）
- 代码节点（Task 4 产物）
- 子工作流调用节点 ×3（Task 2 产物）
- if-else 意图路由节点（保留 1 个，5 分支）

- [ ] **5.3 连接节点边**

按设计文档中的最终工作流结构连接：
```
start → llm 意图识别 → if-else 路由
  ├─ "设计分镜" → 子流程A → http 写分镜 → code → template
  ├─ "设计画面" → 子流程B → http 生图 → code → template
  ├─ "设计视频" → 子流程C → http 生视频 → code → template
  ├─ "直接生图" → http 生图 → code → template
  └─ "直接生视频" → http 生视频 → code → template
```

每个 HTTP 节点输出连接到代码节点时，action 设为 `"http_done"`。
每个子流程出口连接到 HTTP 节点前，从子流程输出取 `llm_output`。

- [ ] **5.4 声明节点间的变量传递**

确保变量链正确：
- LLM 意图识别 → if-else 路由：意图分类结果
- 子流程 → HTTP：exit_step（决定后续行为）
- HTTP → 代码节点：http_body（响应 JSON）
- LLM → 代码节点：llm_output

---

## Task 6：端到端测试

**目的：** 验证精简后的工作流完整可用。

### 步骤

- [ ] **6.1 导入 v2 YML 到 Dify**

在 Dify 中创建新应用，导入 `AiGenerateRobot-v2.yml`。确认所有节点正确加载。

- [ ] **6.2 完整流程测试**

按以下场景逐一测试：

1. **完整创作流程**
   - 输入："帮我创作一个武侠爱情故事"
   - 验证：意图识别 → 子流程A生成分镜方案
   - 输入："第三个场景改一下" → 验证子流程能修改
   - 输入："可以了" → 验证 http 写分镜成功
   - 验证：step 跳转到图片方案
   - 输入："继续" → 验证子流程B生成图片方案
   - 输入："可以" → 验证 http 生图成功
   - 验证：step 跳转到视频方案
   - 输入："可以" → 验证子流程C生成视频方案
   - 输入："生成" → 验证 http 生视频成功

2. **灵活跳转**
   - 输入："直接给我生图" → 验证跳过子流程，直接调 http 生图

3. **模板展示**
   - 生图完成后，验证回复包含 `![生成的图片](url)` Markdown
   - 生视频完成后，验证回复包含视频链接

- [ ] **6.3 对照原工作流回归**

确保原 `AiGenerateRobot.yml` 不受影响，仍可正常运行。

---

## Task 7：清理和文档

**目的：** 收尾。

### 步骤

- [ ] **7.1 更新 AIStoryboardDify/SKILL.md**

记录 v2 工作流的存在和与原版的区别。

- [ ] **7.2 关闭 brainstorm 服务器**

```bash
bash "C:/Users/38632/AppData/Local/hermes/skills/brainstorming/scripts/stop-server.sh" --project-dir "E:/Desktop/AI-storyboard"
```
