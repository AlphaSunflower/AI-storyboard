# Agent Skill 管理系统实现方案

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** 让智能体可以读取 skill 知识库文档，LLM 自主选择 skill 并调用工具

**Architecture:** 复用现有 Admin 面板模式（DB 存储 + REST API），新增 @Tool 方法让 LLM 自主读取 skill，在 OtherIntentHandler（闲聊分支）启用 ChatClient.tools() 自主调用

**Tech Stack:** Spring Boot 4 + MyBatis-Plus + Spring AI 2.0 @Tool + PostgreSQL

---

## 原理说明

### 当前架构（编排模式）

```
用户消息 → AgentOrchestrator → IntentHandler.handle()
                                ↓
                        代码编排流程（handler决定调什么）
                                ↓
                        AgentTools（直接调用，非LLM自主）
```

- LLM 只做意图识别，不自主选择工具
- 流程由 handler 代码硬编码
- AgentTools 的 @Tool 注解当前未被 LLM 直接调用

### 目标架构（编排 + Agent 自主）

```
用户消息 → AgentOrchestrator → IntentHandler.handle()
                                ↓
          ┌─────────────────────┴─────────────────────┐
          │                                           │
   aisplit/pic/video（保持编排）              other/闲聊（Agent 自主）
          │                                           │
   handler 硬编码流程                      ChatClient.tools(agentTools)
          │                                           │
   AgentTools（直接调用）                  LLM 自主决定：
                                           - 读哪个 skill
                                           - 调哪个工具
                                           - 怎么组合
```

- 分镜/图片/视频链路：保持现有编排（有人机确认，流程确定）
- 闲聊链路：启用 LLM 自主（读 skill → 理解 → 调工具）

### 核心概念

**Skill = LLM 可读的知识文档**
- 存储在 DB（agent_skills 表）
- 包含：name（标识）、description（摘要）、content（markdown文档）
- LLM 通过 @Tool 方法读取内容

**Tool = LLM 可调用的后端能力**
- 已有：writeScenes / replaceScenes / refineImage
- 新增：listSkills（列出可用 skill）、readSkill（读取 skill 内容）
- 注册到 ChatClient，LLM 自主决定调用

**Agent 自主 = LLM 自己决策**
- 不是代码编排流程
- LLM 根据用户意图，自主选择：读 skill 获取知识 → 调用工具执行
- 适合：闲聊、问答、自由形式任务

---

## 实现计划

### Task 1: 创建 agent_skills 表（Migration）

**Objective:** DB 存储 skill 文档

**Files:**
- Create: `src/main/resources/db/migration/V10__agent_skills.sql`

**SQL:**
```sql
CREATE TABLE IF NOT EXISTS public.agent_skills (
    id          VARCHAR(36) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    name        VARCHAR(100) NOT NULL UNIQUE,
    description VARCHAR(500),
    content     TEXT NOT NULL,
    category    VARCHAR(50) DEFAULT 'general',
    enabled     BOOLEAN DEFAULT true,
    sort_order  INT DEFAULT 0,
    created_at  TIMESTAMPTZ DEFAULT NOW(),
    updated_at  TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_agent_skills_enabled ON public.agent_skills(enabled);
CREATE INDEX IF NOT EXISTS idx_agent_skills_category ON public.agent_skills(category);
```

**验证:** psql 执行建表成功

---

### Task 2: 创建 AgentSkill 实体 + Mapper

**Objective:** MyBatis-Plus 映射

**Files:**
- Create: `src/main/java/com/storyboard/entity/AgentSkill.java`
- Create: `src/main/java/com/storyboard/mapper/AgentSkillMapper.java`

**代码:**
```java
// AgentSkill.java
@Data
@TableName(value = "agent_skills", schema = "public")
public class AgentSkill {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String name;
    private String description;
    private String content;
    private String category;
    private Boolean enabled;
    private Integer sortOrder;
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}

// AgentSkillMapper.java
@Mapper
public interface AgentSkillMapper extends BaseMapper<AgentSkill> {
}
```

**验证:** Maven compile 通过

---

### Task 3: 创建 AgentSkillService（CRUD）

**Objective:** Skill 业务逻辑层

**Files:**
- Create: `src/main/java/com/storyboard/service/agent/AgentSkillService.java`
- Create: `src/main/java/com/storyboard/service/agent/impl/AgentSkillServiceImpl.java`

**接口方法:**
```java
public interface AgentSkillService {
    List<AgentSkill> listEnabled();           // 列出启用的 skill
    AgentSkill getByName(String name);        // 按名称获取
    AgentSkill create(AgentSkill skill);      // 创建
    AgentSkill update(String id, AgentSkill skill); // 更新
    void delete(String id);                   // 删除
}
```

**验证:** Maven compile 通过

---

### Task 4: 创建 SkillController（REST API）

**Objective:** 前端管理接口

**Files:**
- Create: `src/main/java/com/storyboard/controller/SkillController.java`

**端点:**
```
GET    /api/skills          → 列出所有 skill
GET    /api/skills/{id}     → 获取单个
POST   /api/skills          → 创建
PUT    /api/skills/{id}     → 更新
DELETE /api/skills/{id}     → 删除
```

**验证:** curl 测试 CRUD 端点

---

### Task 5: AgentTools 新增 listSkills + readSkill

**Objective:** LLM 可调用的 skill 读取工具

**Files:**
- Modify: `src/main/java/com/storyboard/service/agent/AgentTools.java`

**新增方法:**
```java
@Tool(description = "列出所有可用的技能文档，返回技能名称和描述列表")
public List<Map<String, String>> listSkills() {
    return skillService.listEnabled().stream()
        .map(s -> Map.of("name", s.getName(), "description", s.getDescription() != null ? s.getDescription() : ""))
        .collect(Collectors.toList());
}

@Tool(description = "读取指定名称的技能文档完整内容")
public String readSkill(@ToolParam(description = "技能名称") String name) {
    AgentSkill skill = skillService.getByName(name);
    if (skill == null) {
        return "技能不存在: " + name;
    }
    return skill.getContent();
}
```

**验证:** Maven compile 通过

---

### Task 6: OtherIntentHandler 启用 ChatClient.tools()

**Objective:** 闲聊时 LLM 自主调用工具

**Files:**
- Modify: `src/main/java/com/storyboard/service/agent/handler/OtherIntentHandler.java`

**改动:**
```java
// 原来：ChatClient 只做文本回答
ChatClient client = chatClientBuilder.build();

// 改为：注入 AgentTools，让 LLM 可自主调用
ChatClient client = chatClientBuilder
    .defaultTools(agentTools)
    .build();
```

**验证:** 对话测试，LLM 能列出/读取 skill

---

### Task 7: Admin 面板前端（可选）

**Objective:** 可视化管理 skill

**Files:**
- Create: `AIStoryboardClient/src/pages/admin/SkillManagement.tsx`
- Modify: 路由配置

**功能:**
- 表格展示 skill 列表
- 新增/编辑表单（name, description, content markdown 编辑器, category, enabled）
- 删除确认

**验证:** 浏览器访问管理页面

---

## 关键设计决策

### 1. 为什么只在 OtherIntentHandler 启用 Agent 自主？

- aisplit/pic/video 链路：已有确定流程 + HITL 人机确认，改成 LLM 自主会丢失控制
- other 链路：闲聊场景，适合 LLM 自主发挥
- **渐进式**：先在 other 验证，后续可扩展到其他链路

### 2. Skill 格式为什么是纯 markdown？

- LLM 原生理解 markdown
- 简单、灵活、可维护
- 不需要复杂的结构化 schema
- **YAGNI**：结构化 schema（参数、执行逻辑）现在不需要，后续可加

### 3. 为什么不用 RAG（向量检索）？

- Skill 数量有限（几十个），全量读取成本低
- LLM 可以先 listSkills 看摘要，再 readSkill 读完整内容
- **YAGNI**：向量检索复杂度高，现在不需要

---

## 验证方案

### 端到端测试流程

1. **建表**：psql 执行 V10 migration
2. **插入测试数据**：
   ```sql
   INSERT INTO agent_skills (name, description, content, category)
   VALUES ('storyboard-optimization', '分镜优化技巧', '# 分镜优化\n\n1. 场景节奏...\n2. 镜头切换...', 'scene');
   ```
3. **启动后端**：mvn spring-boot:run
4. **测试 API**：
   ```bash
   curl http://localhost:8082/api/skills
   curl http://localhost:8082/api/skills/storyboard-optimization
   ```
5. **测试 Agent**：
   - 打开前端对话
   - 发送："有什么技能可用？"
   - 预期：LLM 调用 listSkills 返回列表
   - 发送："告诉我分镜优化的技巧"
   - 预期：LLM 调用 readSkill 读取内容并回答

---

## 风险与边界

### 已知限制

1. **Agent 自主范围**：仅限 other（闲聊）分支，不影响分镜/图片/视频链路
2. **Skill 数量**：全量加载，不适合几百个 skill（需 RAG 时再扩展）
3. **并发安全**：Skill 读多写少，无锁需求

### 后续扩展点（YAGNI，不实现）

- Skill 版本管理
- Skill 权限控制（按项目/用户隔离）
- Skill 结构化 schema（参数、执行逻辑）
- RAG 向量检索（skill 数量 > 100 时）

---

## 文件清单

### 新增文件
```
src/main/resources/db/migration/V10__agent_skills.sql
src/main/java/com/storyboard/entity/AgentSkill.java
src/main/java/com/storyboard/mapper/AgentSkillMapper.java
src/main/java/com/storyboard/service/agent/AgentSkillService.java
src/main/java/com/storyboard/service/agent/impl/AgentSkillServiceImpl.java
src/main/java/com/storyboard/controller/SkillController.java
AIStoryboardClient/src/pages/admin/SkillManagement.tsx (可选)
```

### 修改文件
```
src/main/java/com/storyboard/service/agent/AgentTools.java
src/main/java/com/storyboard/service/agent/handler/OtherIntentHandler.java
```

---

## 实施顺序

1. **Task 1-2**：建表 + Entity/Mapper（基础层）
2. **Task 3-4**：Service + Controller（API 层）
3. **Task 5**：AgentTools 扩展（工具层）
4. **Task 6**：OtherIntentHandler 改造（编排层）
5. **Task 7**：前端管理面板（UI 层，可选）

每步可独立验证，渐进式推进。
