# 修复"查看分镜"/"生成分镜图片/视频"意图识别

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** 让以下表达正确触发 intent-scene-review：
- 查看类："展示所有分镜"、"看看分镜"、"看一下分镜列表"、"项目有多少分镜"、"分镜列表"
- 生成类："我要生成分镜的图片/视频"、"生成分镜的指定视频"、"分镜的图片需要生成"

**Architecture:** 三处修复——正则加模式、LLM prompt 扩宽定义、clarify 卡片加选项。

**Tech Stack:** Java regex, YAML prompt template

---

## 根因

1. **RULE_TABLE 正则缺口语/生成模式**（`IntentRecognitionServiceImpl.java`）
   - 查看类缺：`看看`、`看一下`、`分镜列表`、`多少分镜`
   - 生成类缺：`分镜.{0,6}生成.{0,4}(图|视频)` —— "分镜的图片需要生成"不命中
   - 现有 `生成.{0,8}分镜.{0,6}(图|视频)` 只覆盖"生成在前、分镜在中、图/视频在后"的语序

2. **LLM prompt 对 scene-review 定义太窄**（`intent-recognition.yaml`）
   - 现有："用户**上传**已有分镜，要求**分析**生成状态"
   - 不覆盖"查看分镜列表"、"给分镜生成图片"

3. **intent_clarify 卡片没有"查看分镜"选项**（`PlanGraph.java`）

---

## Task 1: RULE_TABLE 正则扩展

**Objective:** 覆盖查看类+生成类口语表达

**Files:**
- Modify: `MoonAgent/src/main/java/com/moon/moonagent/ai/agent/impl/IntentRecognitionServiceImpl.java`

**Step 1: 修改 intent-scene-review 正则**

在现有模式的 `列出.{0,6}分镜` 后、`所有分镜` 前插入新模式，完整正则：

```java
Map.entry("intent-scene-review", java.util.regex.Pattern.compile(
    "分析.{0,6}分镜|处理.{0,6}分镜|审查.{0,6}分镜|review.{0,6}scene|"
    + "展示.{0,6}分镜|查看.{0,6}分镜|列出.{0,6}分镜|"
    + "看.{0,2}看.{0,4}分镜|看一下.{0,4}分镜|分镜列表|多少分镜|"  // ← 新增：口语查看
    + "所有分镜|"
    + "生成.{0,8}分镜.{0,6}(图|视频)|"
    + "分镜.{0,4}生成.{0,4}(图|视频)|分镜.{0,12}(图|视频|生成)"  // ← 新增：分镜在前的生成语序
)),
```

**新增模式覆盖的表达：**

| 模式 | 匹配表达 |
|------|---------|
| `看.{0,2}看.{0,4}分镜` | "看看分镜"、"我想看看分镜"、"看看这个分镜" |
| `看一下.{0,4}分镜` | "看一下分镜"、"看一下分镜列表" |
| `分镜列表` | "看一下分镜列表"、"分镜列表" |
| `多少分镜` | "项目有多少分镜" |
| `分镜.{0,4}生成.{0,4}(图\|视频)` | "分镜的图片需要生成" |
| `分镜.{0,12}(图\|视频\|生成)` | "分镜的指定视频需要生成"、"我要生成分镜的图片" |

**防误判设计：**
- `分镜.{0,12}生成` 会误匹配"分镜需要生成新的剧本"？
  - 不会："分镜需要生成新的剧本"里没有"图/视频"，第三个选项 `(图|视频|生成)` 中 `生成` 在位置 12+（"需要生成新的剧本"=11 字符，刚好命中 `生成`，但这是个边界 case）
  - 实际上 "分镜需要生成新的剧本" → `分镜` + "需要生成新的剧本"(11字) + `生成` → 在 {0,12} 内 → 误命中！
  - **修复：把第三个选项拆开** —— `分镜.{0,12}(图|视频)` 单独（不含生成），`生成` 由前两个模式覆盖

修正后的正则：
```java
"分镜.{0,4}生成.{0,4}(图|视频)|分镜.{0,12}(图|视频)"
```

这样"分镜需要生成新的剧本"不命中（无图/视频关键词），"分镜的图片需要生成"命中（有"图"）。

**Step 2: 编译验证**

```bash
cd MoonAgent && JAVA_HOME="C:\\Program Files\\Java\\jdk-21" \
  "/e/Development/apache-maven-3.9.15/bin/mvn.cmd" compile -q
```

**Step 3: Commit**

```bash
git add MoonAgent/src/main/java/com/moon/moonagent/ai/agent/impl/IntentRecognitionServiceImpl.java
git commit -m "fix(intent): scene-review 正则覆盖查看类+生成类口语表达"
```

---

## Task 2: LLM prompt 扩宽 scene-review 定义

**Objective:** 让 LLM 把查看分镜、给分镜生成图片/视频也归类为 intent-scene-review

**Files:**
- Modify: `MoonAgent/src/main/resources/prompts/services/intent-recognition.yaml`

**Step 1: 修改 intent-scene-review 定义**

```yaml
# 旧
- intent-scene-review = 分镜审查：用户上传已有分镜，要求分析生成状态、优化方案或补生成图片/视频

# 新
- intent-scene-review = 分镜查看/审查/生成：用户想查看、浏览、列出已有分镜（「看看分镜」「分镜列表」「有多少分镜」），或要求给已有分镜生成图片/视频（「生成分镜的图片」「分镜的视频」），或上传分镜分析状态、优化方案
```

**Step 2: Commit**

```bash
git add MoonAgent/src/main/resources/prompts/services/intent-recognition.yaml
git commit -m "fix(intent): 扩宽 scene-review prompt 覆盖查看+生成表达"
```

---

## Task 3: intent_clarify 卡片加"查看分镜"选项

**Objective:** 低置信度时用户能直接选择查看分镜

**Files:**
- Modify: `MoonAgent/src/main/java/com/moon/moonagent/ai/agent/PlanGraph.java`

**Step 1: 在 clarify actions 列表加 scene-review 选项**

在 `intent-delete` 前插入：
```java
Map.of("id", "intent-scene-review", "title", "查看分镜"),
```

**Step 2: 编译验证**

```bash
cd MoonAgent && JAVA_HOME="C:\\Program Files\\Java\\jdk-21" \
  "/e/Development/apache-maven-3.9.15/bin/mvn.cmd" compile -q
```

**Step 3: Commit**

```bash
git add MoonAgent/src/main/java/com/moon/moonagent/ai/agent/PlanGraph.java
git commit -m "fix(intent): clarify 卡片加入'查看分镜'选项"
```

---

## 验证矩阵

重启 MoonAgent 后逐条测试：

| 输入 | 期望 | 日志关键字 |
|------|------|-----------|
| 展示所有分镜 | 规则命中 intent-scene-review | `意图识别规则命中: type=intent-scene-review` |
| 我想看看分镜 | 规则命中 | 同上 |
| 看一下分镜列表 | 规则命中 | 同上 |
| 项目有多少分镜 | 规则命中 | 同上 |
| 分镜列表 | 规则命中 | 同上 |
| 我要生成分镜的图片 | 规则命中 | 同上 |
| 生成分镜的指定视频 | 规则命中 | 同上 |
| 分镜的图片需要生成 | 规则命中 | 同上 |
| 生成分镜 | **不命中** scene-review → 走 aisplit | `意图识别规则命中: type=intent-aisplit` |
| 分镜需要生成新的剧本 | **不命中** scene-review → 走 LLM/aisplit | 不应出现 scene-review |
