# /chat 输入框「斜杠命令面板」实施计划（压缩上下文 + 状态）

> **For Hermes:** 实现时按任务顺序逐条执行；每个任务完成后跑该任务的验证命令。

**Goal:** 在 /chat（及编辑器内 Moon 悬浮窗）的输入框里输入 `/` 弹出命令面板，列出两项能力：
`压缩上下文`（仅在已有对话时可用）与 `状态`（会话 ID / 背景信息即上下文额度 / 额度占位）。

**Architecture:**
- 压缩复用 **AgentScope harness 自带的压缩组件**（`ConversationCompactor` + `CompactionConfig`），
  不自己写摘要提示词、不自己维护上下文裁剪逻辑。harness 内部在上下文溢出时就是这么做的
  （`HarnessAgent.forceCompactAndRetry`：`CompactionConfig.builder().triggerMessages(1).build()`），
  我们的手动压缩 = 同一段逻辑、由用户主动触发、不走中间件。
- 压缩只改 **AgentScope 运行时会话上下文**（`AgentState.contextMutable()`），不动产品消息表
  `agent_messages`：前端聊天记录不变，与 Codex 的 `/压缩` 语义一致。
- 状态卡的数据**前端已有**（`ChatComposer` 已在算 `contextUsageTokens / contextLimitTokens /
  contextCachedTokens / hasContextData`），无需新增后端接口；额度是占位符。

**Tech Stack:** MoonAgent（Spring Boot 4 / agentscope-harness 2.0.3 / Reactor）、React 19 + TS + Vite（AIStoryboardClient）、vitest。

**已确认的决策（用户确认）:**
1. 列表只做 `压缩上下文` + `状态` 两项（注册表结构，后续加项只往数组里追加）。
2. 「状态」用输入框上方浮层卡，右上「关闭」（截图 2 样式）。
3. 压缩反馈：输入框上方临时提示条（不写入对话消息）。

---

## 现状事实（已核实，实现时不用再查）

| 事实 | 证据 |
|------|------|
| harness 有压缩组件 | `agentscope-harness-2.0.3.jar` → `io.agentscope.harness.agent.memory.compaction.{ConversationCompactor, CompactionConfig, TokenCounterUtil}`、`middleware.CompactionMiddleware` |
| 手动强制压缩的官方写法 | `HarnessAgent.forceCompactAndRetry()`（sources jar，行 1055-1105）：`triggerMessages(1)` + `RuntimeContext.resolveAgentState(...)` + `state.contextMutable().clear()/addAll()` |
| `compactIfNeeded` 签名 | `Mono<Optional<List<Msg>>> compactIfNeeded(RuntimeContext rc, List<Msg> conversationMessages, CompactionConfig config, String agentId, String sessionId)`；`flushBeforeCompact/offloadBeforeCompact=false` 时 `flushManager` 不被解引用（源码确认） |
| 手动压缩返回值语义 | `Optional.empty()` = 未触发；非空 = `[summaryMsg] + 保留尾部`（`SUMMARY_MSG_NAME="__compaction_summary__"`） |
| 自动压缩已开启 | `MoonAgentRuntime.init()`：`builder.compaction(CompactionConfig.builder().triggerMessages(40).triggerTokens(60000).keepMessages(12)...)`，阈值来自 `AgentScopeProperties`（`ai.agentscope.*`） |
| 会话上下文读写范式 | `MoonAgentRuntime.rewindLastTurn()`：`agent.getDelegate().getAgentState(userId, conversationId).contextMutable()`；`ensureSessionFromHistory()`：改完 `state.contextMutable()` 后 `saveAgentState(userId, conversationId)` |
| 上下文窗口/用量 | 前端 `ChatComposer.tsx:105-132` 已算好；`ContextUsageRing` 已存在 |
| 令牌计数 | `io.agentscope.harness.agent.memory.compaction.TokenCounterUtil.calculateToken(List<Msg>)`（public static） |
| 会话级互斥 | `ConversationLock.tryAcquire(conversationId)` / `isLocked` |
| 前端命令面板：**不存在**，需新建 | `grep slash/command-palette` 无结果 |

---

## 后端（MoonAgent）

### Task 1: `MoonAgentRuntime` 新增手动压缩方法

**Files:**
- Modify: `MoonAgent/src/main/java/com/moon/moonagent/ai/agent/agentscope/MoonAgentRuntime.java`（紧跟 `clearSession()` 之后，约 1030 行）

**Step 1: 加 import**

```java
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
import io.agentscope.harness.agent.memory.compaction.TokenCounterUtil;
```
（`CompactionConfig` / `MemoryFlushManager` 已有 import，不用重复。）

**Step 2: 新增结果 record（同包顶层类或 Impl 内 record，按项目「接口签名引用则提顶层」的规范定）**

新建 `MoonAgent/src/main/java/com/moon/moonagent/ai/agent/agentscope/CompactOutcome.java`：

```java
package com.moon.moonagent.ai.agent.agentscope;

/** 手动压缩上下文的结果：status=COMPACTED / SKIPPED / UNAVAILABLE。 */
public record CompactOutcome(
        String status,
        int messagesBefore,
        int messagesAfter,
        int tokensBefore,
        int tokensAfter) {

    public static final CompactOutcome UNAVAILABLE = new CompactOutcome("UNAVAILABLE", 0, 0, 0, 0);
    public static final CompactOutcome SKIPPED = new CompactOutcome("SKIPPED", 0, 0, 0, 0);

    public boolean compacted() {
        return "COMPACTED".equals(status);
    }
}
```

**Step 3: 方法实现（照抄 harness 的 `forceCompactAndRetry`，去掉重试部分）**

```java
/**
 * 手动压缩会话上下文：强制触发 AgentScope 压缩（triggerMessages=1），
 * 只改运行时会话上下文，不动产品消息表 agent_messages。
 * 上下文条目不足 keepMessages 时返回 SKIPPED（无可压缩内容），不报错。
 *
 * @return COMPACTED 已压缩；SKIPPED 无需压缩；UNAVAILABLE 运行时未启用或会话状态缺失
 */
public CompactOutcome compactSession(String userId, String conversationId) {
    if (!enabled() || conversationId == null || conversationId.isBlank()) {
        return CompactOutcome.UNAVAILABLE;
    }
    try {
        AgentState state = agent.getDelegate().getAgentState(userId, conversationId);
        if (state == null) return CompactOutcome.UNAVAILABLE;
        List<Msg> context = state.contextMutable();
        if (context == null || context.isEmpty()) return CompactOutcome.UNAVAILABLE;

        // 会话上下文理论上不含 SYSTEM（系统提示词在推理输入里单独拼），防御性剥离并回填
        Msg system = context.get(0).getRole() == MsgRole.SYSTEM ? context.remove(0) : null;
        try {
            int keepMessages = properties.getCompactionKeepMessages();
            if (context.size() <= keepMessages) return CompactOutcome.SKIPPED;

            int messagesBefore = context.size();
            int tokensBefore = TokenCounterUtil.calculateToken(context);
            RuntimeContext rc = RuntimeContext.builder()
                    .userId(userId).sessionId(conversationId).build();
            CompactionConfig config = CompactionConfig.builder()
                    .triggerMessages(1)          // 手动压缩 = 强制触发
                    .keepMessages(keepMessages)  // 与自动压缩同一保留口径
                    .keepTokens(0)               // 0 = 按条数保留（>0 才会走 token 口径）
                    .flushBeforeCompact(false)   // 本运行时已 disableMemoryHooks，不做长期记忆刷新
                    .offloadBeforeCompact(false)
                    .summaryPrompt(COMPACTION_PROMPT)
                    .build();
            ConversationCompactor compactor = new ConversationCompactor(
                    agent.getModel(),
                    new MemoryFlushManager(agent.getWorkspaceManager(), agent.getModel(),
                            MemoryFlushManager.DEFAULT_FLUSH_PROMPT));
            Optional<List<Msg>> compacted = compactor
                    .compactIfNeeded(rc, context, config, agent.getName(), conversationId)
                    .block(Duration.ofSeconds(90));
            if (compacted == null || compacted.isEmpty()) return CompactOutcome.SKIPPED;

            context.clear();
            context.addAll(compacted.get());
            agent.getDelegate().saveAgentState(userId, conversationId);
            int tokensAfter = TokenCounterUtil.calculateToken(context);
            log.info("[AgentScope] 手动压缩完成 conversationId={} {} msgs/{} tokens → {} msgs/{} tokens",
                    conversationId, messagesBefore, tokensBefore, context.size(), tokensAfter);
            return new CompactOutcome("COMPACTED", messagesBefore, context.size(), tokensBefore, tokensAfter);
        } finally {
            if (system != null) context.add(0, system);
        }
    } catch (Exception e) {
        log.warn("[AgentScope] 手动压缩失败 conversationId={}: {}", conversationId, e.getMessage());
        return CompactOutcome.UNAVAILABLE;
    }
}
```

**Step 4: 编译验证**

```bash
export JAVA_HOME="E:\\Development\\jdk25"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\MoonAgent\\pom.xml" compile -q
```
Expected: BUILD SUCCESS。若 `MemoryFlushManager` 构造器不是 3 参，按 IDE 报错改用 2 参
`(workspaceManager, model)`（源码里两种重载都出现过；两参时 flushPrompt 走默认）。

**Step 5: 提交**

```bash
git add MoonAgent/src/main/java/com/moon/moonagent/ai/agent/agentscope/MoonAgentRuntime.java \
        MoonAgent/src/main/java/com/moon/moonagent/ai/agent/agentscope/CompactOutcome.java
git commit -m "feat(agent): 支持手动压缩会话上下文"
```

---

### Task 2: Service 接口 + 实现

**Files:**
- Modify: `MoonAgent/src/main/java/com/moon/moonagent/ai/agent/AgentChatService.java`（约 190 行 `getRuntimeStatus` 旁）
- Modify: `MoonAgent/src/main/java/com/moon/moonagent/ai/agent/impl/AgentChatServiceImpl.java`

**Step 1: 接口加方法**

```java
    /**
     * 手动压缩会话上下文（/chat 斜杠命令「压缩上下文」）。
     * 只压缩 AgentScope 运行时上下文，产品消息记录不变。
     *
     * @return {compacted, status, messagesBefore, messagesAfter, tokensBefore, tokensAfter}
     */
    Map<String, Object> compactContext(String userId, String conversationId);
```

**Step 2: 实现（照 `deleteMessages` 的归属校验 + `streamMessage` 的锁范式）**

```java
    @Override
    public Map<String, Object> compactContext(String userId, String conversationId) {
        getOwnedConversation(userId, conversationId); // 归属校验（不存在/无权统一 40401）
        if (agentScopeMediaRuntime == null || !agentScopeMediaRuntime.enabled()) {
            throw new BusinessException("40001", "当前会话暂不支持上下文压缩");
        }
        if (!conversationLock.tryAcquire(conversationId)) {
            throw new BusinessException("40901", "对话正在回复中，请稍后再试");
        }
        try {
            CompactOutcome outcome = agentScopeMediaRuntime.compactSession(userId, conversationId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("compacted", outcome.compacted());
            result.put("status", outcome.status());
            result.put("messagesBefore", outcome.messagesBefore());
            result.put("messagesAfter", outcome.messagesAfter());
            result.put("tokensBefore", outcome.tokensBefore());
            result.put("tokensAfter", outcome.tokensAfter());
            return result;
        } finally {
            conversationLock.release(conversationId); // 锁释放 API 名以现有用法为准
        }
    }
```
> 注意：`conversationLock` 的释放方法名以文件里现有调用为准（`release`/`unlock`），别凭猜。

**Step 3: 编译验证**：同 Task 1 Step 4 命令。

---

### Task 3: Controller 薄层端点

**Files:**
- Modify: `MoonAgent/src/main/java/com/moon/moonagent/controller/AgentConversationController.java`（`/runtime` 端点旁，约 355 行）

```java
    /** 手动压缩会话上下文（/chat 斜杠命令「压缩上下文」）。 */
    @PostMapping("/conversations/{id}/compact")
    public ApiResponse<Map<String, Object>> compactContext(Authentication auth,
                                                           @PathVariable String id) {
        return ApiResponse.ok(chatService.compactContext(auth.getName(), id));
    }
```

**验证（需要服务在跑，8084）：**

```bash
curl -s -X POST "http://localhost:8080/api/agent/conversations/<会话ID>/compact" \
  -H "Authorization: Bearer <token>" | jq
```
Expected: `{"code":0,...,"data":{"compacted":true,"messagesBefore":42,"messagesAfter":13,...}}`；
新会话/无上下文 → `compacted=false, status=SKIPPED`。

---

## 前端（AIStoryboardClient）

### Task 4: API 客户端

**Files:**
- Modify: `AIStoryboardClient/src/api/agent.ts`（`getRuntimeStatus` 旁，约 833 行）

```ts
export interface CompactContextResult {
  compacted: boolean;
  status: 'COMPACTED' | 'SKIPPED' | 'UNAVAILABLE';
  messagesBefore: number;
  messagesAfter: number;
  tokensBefore: number;
  tokensAfter: number;
}

  compactContext: (conversationId: string) =>
    client.post<{ data: CompactContextResult }>(`/agent/conversations/${conversationId}/compact`),
```

---

### Task 5: 斜杠命令面板组件

**Files:**
- Create: `AIStoryboardClient/src/components/agent/ChatSlashMenu.tsx`

```tsx
import { useEffect, useMemo, useRef, useState } from 'react';
import { Minimize2, Info } from 'lucide-react';
import { agentChatTokens as T } from './agentChatTokens';

export interface SlashCommandItem {
  id: string;
  icon: React.ReactNode;
  title: string;
  description: string;
  disabled?: boolean;
  disabledHint?: string;
  run: () => void;
}

/**
 * 输入框斜杠命令面板：输入「/」时在输入卡上方列出命令。
 * 注册表式，新增命令 = 往 items 数组里加一项（ChatComposer 组装）。
 */
export function ChatSlashMenu({
  items, query, onClose,
}: {
  items: SlashCommandItem[];
  query: string;
  onClose: () => void;
}) {
  const visible = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return items;
    return items.filter((item) => item.title.toLowerCase().includes(q)
      || item.id.toLowerCase().includes(q));
  }, [items, query]);
  const [active, setActive] = useState(0);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => { setActive(0); }, [query]);

  // 键盘由聊天输入框统一转发（避免与 textarea 抢焦点）
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') { e.preventDefault(); onClose(); return; }
      if (visible.length === 0) return;
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        setActive((i) => (i + 1) % visible.length);
      } else if (e.key === 'ArrowUp') {
        e.preventDefault();
        setActive((i) => (i - 1 + visible.length) % visible.length);
      } else if (e.key === 'Enter' || e.key === 'Tab') {
        const item = visible[active];
        if (!item || item.disabled) return;
        e.preventDefault();
        item.run();
      }
    };
    window.addEventListener('keydown', onKey, true);
    return () => window.removeEventListener('keydown', onKey, true);
  }, [visible, active, onClose]);

  if (visible.length === 0) return null;

  return (
    <div ref={ref} role="listbox" aria-label="命令" style={{
      position: 'absolute', bottom: 'calc(100% + 8px)', left: 0, right: 0,
      background: 'white', border: `1px solid ${T.border}`,
      borderRadius: 14, boxShadow: '0 8px 28px rgba(0,0,0,0.14)',
      padding: 6, zIndex: 1100, display: 'flex', flexDirection: 'column', gap: 2,
    }}>
      {visible.map((item, index) => (
        <button
          key={item.id}
          role="option"
          aria-selected={index === active}
          onMouseEnter={() => setActive(index)}
          onClick={() => { if (!item.disabled) item.run(); }}
          disabled={item.disabled}
          style={{
            display: 'flex', alignItems: 'center', gap: 10, width: '100%',
            padding: '9px 10px', border: 'none', borderRadius: 10, textAlign: 'left',
            background: index === active && !item.disabled ? T.hover : 'transparent',
            color: item.disabled ? T.textCaption : T.ink,
            cursor: item.disabled ? 'not-allowed' : 'pointer',
          }}
        >
          <span style={{ display: 'flex', color: item.disabled ? T.textCaption : T.brand }}>{item.icon}</span>
          <span style={{ flex: 1, minWidth: 0 }}>
            <span style={{ display: 'block', fontSize: 13, fontWeight: 600 }}>{item.title}</span>
            <span style={{ display: 'block', fontSize: 12, color: T.textCaption, marginTop: 1 }}>
              {item.disabled ? (item.disabledHint ?? item.description) : item.description}
            </span>
          </span>
        </button>
      ))}
    </div>
  );
}
```

---

### Task 6: 状态浮层卡组件

**Files:**
- Create: `AIStoryboardClient/src/components/agent/ConversationStatusCard.tsx`

```tsx
import { agentChatTokens as T } from './agentChatTokens';
import { formatTokenCount } from './contextWindow';

/** 额度占位：后续接入每人图片/视频生成额度时替换为后端字段。 */
const QUOTA_PLACEHOLDER = '不可用';

export interface ConversationStatusCardProps {
  conversationId: string | null;
  usedTokens: number;
  limitTokens: number;
  hasContextData: boolean;
  onClose: () => void;
}

/**
 * 「状态」浮层卡（/chat 斜杠命令）：会话 ID / 背景信息（上下文额度）/ 额度占位。
 * 数据全部来自输入卡已有计算，不新增后端接口。
 */
export function ConversationStatusCard({
  conversationId, usedTokens, limitTokens, hasContextData, onClose,
}: ConversationStatusCardProps) {
  const percent = limitTokens > 0 ? Math.round((usedTokens / limitTokens) * 100) : 0;
  const rows: Array<[string, string]> = [
    ['会话/对话串', conversationId ?? '（未选择对话）'],
    ['背景信息', hasContextData
      ? `剩余 ${Math.max(0, 100 - percent)}%（已使用 ${formatTokenCount(usedTokens)}/共 ${formatTokenCount(limitTokens)}）`
      : '暂无数据（尚未发生模型调用）'],
    ['额度', QUOTA_PLACEHOLDER],
  ];
  return (
    <div style={{
      position: 'absolute', bottom: 'calc(100% + 8px)', left: 0, right: 0,
      background: 'white', border: `1px solid ${T.border}`, borderRadius: 14,
      boxShadow: '0 8px 28px rgba(0,0,0,0.14)', padding: '12px 14px', zIndex: 1100,
      display: 'flex', flexDirection: 'column', gap: 8,
    }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <span style={{ fontSize: 13, color: T.textSecondary, fontWeight: 600 }}>状态</span>
        <button
          onClick={onClose}
          style={{ border: 'none', background: 'transparent', color: T.textSecondary,
            fontSize: 13, cursor: 'pointer' }}
        >关闭</button>
      </div>
      {rows.map(([label, value]) => (
        <div key={label} style={{ display: 'flex', gap: 10, fontSize: 13, lineHeight: 1.5 }}>
          <span style={{ width: 88, flexShrink: 0, color: T.textSecondary }}>{label}:</span>
          <span style={{
            flex: 1, minWidth: 0, color: T.ink, wordBreak: 'break-all',
            fontFamily: label === '会话/对话串' ? 'ui-monospace, SFMono-Regular, monospace' : 'inherit',
          }}>{value}</span>
        </div>
      ))}
    </div>
  );
}
```

---

### Task 7: 接线到 ChatComposer

**Files:**
- Modify: `AIStoryboardClient/src/components/agent/ChatComposer.tsx`

**Step 1: 状态与 import**

```tsx
import { ChatSlashMenu, type SlashCommandItem } from './ChatSlashMenu';
import { ConversationStatusCard } from './ConversationStatusCard';
import { Minimize2, Info } from 'lucide-react';
```
```tsx
  const [statusOpen, setStatusOpen] = useState(false);
  const [compressNotice, setCompressNotice] = useState('');
  const [compressing, setCompressing] = useState(false);
```

**Step 2: 命令模式判定（放在 `handleSend` 之前）**

```tsx
  // 斜杠命令模式：文本以「/」开头且尚未输入空格（与 Codex 一致）
  const slashQuery = useMemo(() => {
    const trimmedStart = text.trimStart();
    if (!trimmedStart.startsWith('/')) return null;
    const rest = trimmedStart.slice(1);
    return rest.includes(' ') || rest.includes('\n') ? null : rest;
  }, [text]);
  const slashOpen = slashQuery !== null && !readOnly && !busy;

  const runCompress = async () => {
    const conversationId = useAgentStore.getState().activeConversationId;
    if (!conversationId || compressing) return;
    setText('');
    setCompressing(true);
    try {
      const res = await agentApi.compactContext(conversationId);
      const data = res.data.data;
      setCompressNotice(data.compacted
        ? `上下文已压缩：${data.messagesBefore} 条 → ${data.messagesAfter} 条`
        : data.status === 'UNAVAILABLE' ? '当前会话暂不支持压缩' : '当前上下文不足，无需压缩');
    } catch {
      setCompressNotice('压缩失败，请稍后重试');
    } finally {
      setCompressing(false);
    }
  };

  const slashItems: SlashCommandItem[] = [
    {
      id: 'compress',
      icon: <Minimize2 size={16} strokeWidth={2} />,
      title: '压缩上下文',
      description: '压缩此对话的上下文，保留最近若干轮',
      // 新对话（无消息）不生效：禁用并给出说明
      disabled: messages.length === 0,
      disabledHint: '新对话暂无可压缩的上下文',
      run: () => { void runCompress(); },
    },
    {
      id: 'status',
      icon: <Info size={16} strokeWidth={2} />,
      title: '状态',
      description: '显示会话 ID、背景信息与额度',
      run: () => { setText(''); setStatusOpen(true); },
    },
  ];
```

**Step 3: textarea 键盘处理（改写现有 `onKeyDown`，命令模式优先）**

```tsx
        onKeyDown={(e) => {
          if (slashOpen) {
            // 面板自己监听 window keydown 处理 ↑↓/Enter/Esc，这里只拦 Enter 换行
            if (e.key === 'Enter' && !e.shiftKey) e.preventDefault();
            return;
          }
          if (e.nativeEvent.isComposing) return;
          if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); handleSend(); }
        }}
```

**Step 4: 渲染（输入卡 `div.chat-composer-card` 需 `position: 'relative'`，面板绝对定位其上）**

在 `chat-composer-card` 的 style 里补 `position: 'relative'`，并在卡片内最上方插入：

```tsx
      {slashOpen && !statusOpen && (
        <ChatSlashMenu items={slashItems} query={slashQuery ?? ''} onClose={() => setText('')} />
      )}
      {statusOpen && (
        <ConversationStatusCard
          conversationId={activeConversationId ?? null}
          usedTokens={contextUsageTokens}
          limitTokens={contextLimitTokens}
          hasContextData={hasContextData}
          onClose={() => setStatusOpen(false)}
        />
      )}
      {compressing && (
        <div style={{ fontSize: 12, color: DS.textSecondary }}>正在压缩上下文…</div>
      )}
      {compressNotice && (
        <div style={{
          display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          padding: '8px 12px', borderRadius: 12, background: DS.hover,
          color: DS.textSecondary, fontSize: 13,
        }}>
          <span>{compressNotice}</span>
          <button onClick={() => setCompressNotice('')}
            style={{ border: 'none', background: 'none', color: DS.textSecondary, cursor: 'pointer' }}>✕</button>
        </div>
      )}
```

**Step 5: 面板互斥**：`statusOpen` 打开时不再显示命令面板；切换会话时清空提示

```tsx
  // 与现有 setText('') 同一处（切换会话清空草稿）
  useEffect(() => { setText(''); setStatusOpen(false); setCompressNotice(''); }, [activeConversationId]);
```

**Step 6: 前端验证**

```bash
cd AIStoryboardClient && npx tsc -p tsconfig.app.json --noEmit && npm run test && npm run build
```
Expected: 无 TS 报错、vitest 全绿、build 成功。

---

### Task 8: 前端测试

**Files:**
- Modify: `AIStoryboardClient/src/components/agent/ChatComposer.test.tsx`（新增 describe 块，沿用现有 vitest + testing-library 风格）

```tsx
describe('ChatComposer 斜杠命令', () => {
  beforeEach(() => {
    useAgentStore.setState({ activeConversationId: 'conversation-1', messages: [] });
  });

  it('输入 / 弹出命令列表', () => {
    render(<ChatComposer />);
    fireEvent.change(screen.getByRole('textbox'), { target: { value: '/' } });
    expect(screen.getByRole('listbox', { name: '命令' })).toBeTruthy();
    expect(screen.getByText('压缩上下文')).toBeTruthy();
    expect(screen.getByText('状态')).toBeTruthy();
  });

  it('新对话（无消息）压缩项不可用', () => {
    render(<ChatComposer />);
    fireEvent.change(screen.getByRole('textbox'), { target: { value: '/压缩' } });
    expect(screen.getByText('新对话暂无可压缩的上下文')).toBeTruthy();
  });

  it('已有消息时可压缩并调用后端', async () => {
    useAgentStore.setState({
      messages: [{ id: 'm1', conversationId: 'conversation-1', role: 'user', content: 'hi' }] as never,
    });
    const spy = vi.spyOn(agentApi, 'compactContext').mockResolvedValue({
      data: { code: 0, message: '', data: { compacted: true, status: 'COMPACTED',
        messagesBefore: 42, messagesAfter: 13, tokensBefore: 1, tokensAfter: 1 } },
    } as never);
    render(<ChatComposer />);
    fireEvent.change(screen.getByRole('textbox'), { target: { value: '/' } });
    fireEvent.click(screen.getByText('压缩上下文'));
    await waitFor(() => expect(spy).toHaveBeenCalledWith('conversation-1'));
    await screen.findByText('上下文已压缩：42 条 → 13 条');
  });

  it('状态卡展示会话 ID / 背景信息 / 额度', () => {
    render(<ChatComposer />);
    fireEvent.change(screen.getByRole('textbox'), { target: { value: '/状态' } });
    fireEvent.click(screen.getByText('状态'));
    expect(screen.getByText('conversation-1')).toBeTruthy();
    expect(screen.getByText('额度:')).toBeTruthy();
    expect(screen.getByText('不可用')).toBeTruthy();
  });
});
```

**验证**：`npm run test -- ChatComposer` → 全部通过。

---

## 手工验收（必须真机走一遍）

1. 启动四件套（8080 ApiGateway / 8082 Backend / 8083 LLMGateway / 8084 MoonAgent）。
2. 打开 /chat → **新对话**输入 `/`：列出两项，「压缩上下文」灰显 + 说明；点「状态」→ 浮层卡显示会话 ID（可能为空）/「暂无数据」/「不可用」。
3. 发几轮消息（跑一次图片或视频方案）→ 再输入 `/` → 点「压缩上下文」→ 提示条出现「上下文已压缩：N 条 → M 条」，右下角上下文环占用下降。
4. 压缩后继续发一条消息，确认 Agent 仍能沿用摘要继续对话（不丢上下文）。
5. 回复流式中输入 `/`：面板不出现（`busy` 抑制），不打断生成。
6. 键盘：↑↓ 切项、Enter 执行、Esc 关闭并清空 `/`。
7. 切换会话：输入框与提示条清空。

---

## 风险与取舍

| 风险 | 处理 |
|------|------|
| 手动压缩要调一次 LLM 摘要，可能 10-60s | 后端 `block(90s)`；前端按钮期间显示「正在压缩上下文…」并禁用重复触发；axios 超时 120s 足够 |
| `MemoryFlushManager` 构造器参数个数/重载不确定 | 编译期暴露；两参重载即用默认 flushPrompt（源码两种都有） |
| `ConversationLock` 释放方法名不确定 | 以 `AgentChatServiceImpl` 现有调用为准，不猜 |
| 压缩后前端「背景信息」数字不会立刻变小 | 上下文环来自 SSE usage，下一次模型调用才刷新；属预期。若要即时反映，压缩接口已返回 tokensAfter，可临时覆盖（本次不做） |
| 上下文条目 ≤ keepMessages(12) 时压缩无效果 | 后端返回 `SKIPPED`，前端提示「当前上下文不足，无需压缩」——新对话天然走到这里 |
| 自动压缩（40 条 / 60k token）与手动压缩重叠 | 同一 `ConversationCompactor`、同 `keepMessages` 口径，不冲突；手动只是提前触发 |
| 与 `/plan` 无关的既有脏工作区 | 只 `git add` 本计划涉及文件，遗留改动原样保留 |

---

## 未决 / 后续

- **额度**：当前是常量占位 `QUOTA_PLACEHOLDER = '不可用'`；接入每人图片/视频额度时，改成
  `GET /api/agent/quota`（或用户中心接口）返回值，`ConversationStatusCard` 多接一个 prop 即可。
- **压缩结果是否需要落库**：本次用临时提示条（用户已确认）。若以后要「对话里留一条系统消息」，
  另开任务写入 `agent_messages`（需要 conversation lock + 消息元数据约定）。
- **列表扩展**：以后加「清除聊天记录 / 归档」只需往 `slashItems` 数组追加一项，面板无需改动。
