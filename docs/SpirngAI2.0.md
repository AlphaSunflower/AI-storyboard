### 📌 核心概念：为什么 Spring AI 2.0 是架构层面的重构？

Spring AI 2.0 将**工具调用循环**从各模型内部提升到 **Advisor 链**中，成为**一等公民 (first-class citizen)**。这意味着工具调用不再是黑盒，而是可组合、可观测、可拦截的组件。

**关键术语**：

| 术语                   | 解释                                                       |
| :--------------------- | :--------------------------------------------------------- |
| **Advisor**            | 类似 Spring Web 的 Filter，在 AI 请求/响应前后执行横切逻辑 |
| **Advisor Chain**      | 多个 Advisor 按 `order` 排序组成的管线，请求依次穿过       |
| **Recursive Advisor**  | 可**递归**调用下游链的特殊 Advisor，用于实现循环           |
| **ToolCallingAdvisor** | 最重要的 Recursive Advisor，驱动工具调用循环               |
| **StateGraph**         | Spring AI Alibaba 提供的工作流编排引擎，定义节点和边       |

**机制**：请求按顺序穿过 Advisor 链（洋葱模型），抵达 `ChatModel` 完成调用，响应反向穿回。`ToolCallingAdvisor` 会在链上**反复循环**，执行“模型请求工具 → 执行工具 → 返回结果 → 模型再决策”的过程，直到模型输出最终答案。

### 🏗️ 多智能体编排的四大设计模式

| 模式                     | 适用场景                                        | Spring AI 2.0 实现方式                                       |
| :----------------------- | :---------------------------------------------- | :----------------------------------------------------------- |
| **Chain**                | 任务有明确的顺序步骤，每步依赖上一步输出        | 用循环依次调用 `ChatClient`，上一步输出作为下一步输入        |
| **Parallelization**      | 处理大量相似独立任务，或需多视角分析            | 用 `ParallelizationWorkflow` 并行执行多个 LLM 调用           |
| **Routing**              | 复杂任务需根据输入类型分发给不同 Specialist     | `LlmRoutingAgent` 分类用户查询，调用 Specialist 并行处理     |
| **Orchestrator-Workers** | 主控拆解复杂任务，委派给多个 Sub-agent 并行执行 | `Subagent` 模式：主 Orchestrator 通过 Task/TaskOutput 工具委派任务 |

其中 **Routing** 和 **Orchestrator-Workers** 是实现“链路灵活切换”的核心模式。

### 💎 智能体编排的三大最佳实践

1.  **从简单开始**：先用基础工作流，满足需求后再增加复杂度，使用最简单的模式。
2.  **为可靠性而设计**：实现清晰的错误处理，使用类型安全的响应，在每一步构建验证。
3.  **善用现有实现**：参考 `spring-ai-examples` 仓库中的 `agentic-patterns` 目录，以及 Alibaba 扩展中的 Multi-agent Patterns 示例。

### 📚 延伸学习资源

*   **官方文档**：[Building Effective Agents](https://docs.spring.io/spring-ai/reference/2.0-SNAPSHOT/api/effective-agents.html)、[Tool Calling](https://docs.spring.io/spring-ai/reference/2.0-SNAPSHOT/api/tools.html)、[Recursive Advisors](https://docs.spring.io/spring-ai/reference/2.0-SNAPSHOT/api/advisors-recursive.html)
*   **示例代码**：[spring-ai-examples/agentic-patterns](https://github.com/spring-projects/spring-ai-examples/tree/main/agentic-patterns)、[alibaba/spring-ai-alibaba/examples/multiagent-patterns](https://github.com/alibaba/spring-ai-alibaba/tree/main/examples/multiagent-patterns)
*   **社区文章**：[Spring AI 2.0 多 Agent 编程实战](https://blog.csdn.net/srlsong/article/details/163566160)、[Spring AI 2.0工程化实践](https://blog.csdn.net/weixin_34380948/article/details/91771122)