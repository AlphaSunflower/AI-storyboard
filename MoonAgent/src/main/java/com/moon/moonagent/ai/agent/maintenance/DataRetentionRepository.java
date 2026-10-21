package com.moon.moonagent.ai.agent.maintenance;

import com.moon.moonagent.ai.agent.agentscope.AgentScopeProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.regex.Pattern;

/**
 * 数据保留期的取数与删除 SQL。
 *
 * <p>全部用 {@code now() - make_interval(days => ?)} 在数据库侧算截止时间，避免 Java 与 PG 的
 * 时区/类型口径不一致（`agentscope_sessions.updated_at` 是 {@code timestamp}，其余是 {@code timestamptz}）。
 *
 * <p>批量删除统一走 {@code ctid IN (SELECT ctid ... LIMIT ?)}：不需要知道各表主键类型，
 * 且能按截止时间索引取最老的一批，避免一次大事务。
 *
 * <p>AgentScope 状态表**不写死**：库表位置由运行时的 {@code ai.agent.agentscope.state-schema /
 * state-table} 决定（部署可能把它放在 {@code agentscope} schema，而不是 {@code public}）。
 */
@Repository
public class DataRetentionRepository {

    /** 只允许普通标识符，拼进 SQL 前必须校验（表名不能走 PreparedStatement 参数）。 */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    private final JdbcTemplate jdbc;
    private final AgentScopeProperties agentScopeProperties;

    public DataRetentionRepository(JdbcTemplate jdbc, AgentScopeProperties agentScopeProperties) {
        this.jdbc = jdbc;
        this.agentScopeProperties = agentScopeProperties;
    }

    // ---------------------------------------------------------------- 直播事件

    public long countTurnEventsOlderThan(int days) {
        return count("""
                SELECT count(*) FROM public.agent_turn_events
                WHERE created_at < now() - make_interval(days => ?)
                """, days);
    }

    public int deleteTurnEventsOlderThan(int days, int limit) {
        return jdbc.update("""
                DELETE FROM public.agent_turn_events
                WHERE ctid IN (
                    SELECT ctid FROM public.agent_turn_events
                    WHERE created_at < now() - make_interval(days => ?)
                    ORDER BY created_at
                    LIMIT ?
                )
                """, days, limit);
    }

    // ---------------------------------------------------------------- 子智能体审计

    public long countSubagentRunsOlderThan(int days) {
        return count("""
                SELECT count(*) FROM public.agent_subagent_runs
                WHERE created_at < now() - make_interval(days => ?)
                """, days);
    }

    public int deleteSubagentRunsOlderThan(int days, int limit) {
        return jdbc.update("""
                DELETE FROM public.agent_subagent_runs
                WHERE ctid IN (
                    SELECT ctid FROM public.agent_subagent_runs
                    WHERE created_at < now() - make_interval(days => ?)
                    ORDER BY created_at
                    LIMIT ?
                )
                """, days, limit);
    }

    // ---------------------------------------------------------------- 已投递 outbox

    public long countTerminalOutboxOlderThan(int days) {
        return count("""
                SELECT count(*) FROM public.asset_outbox
                WHERE status IN ('SUCCEEDED', 'DEAD')
                  AND updated_at < now() - make_interval(days => ?)
                """, days);
    }

    public int deleteTerminalOutboxOlderThan(int days, int limit) {
        return jdbc.update("""
                DELETE FROM public.asset_outbox
                WHERE ctid IN (
                    SELECT ctid FROM public.asset_outbox
                    WHERE status IN ('SUCCEEDED', 'DEAD')
                      AND updated_at < now() - make_interval(days => ?)
                    ORDER BY updated_at
                    LIMIT ?
                )
                """, days, limit);
    }

    // ---------------------------------------------------------------- 孤儿运行时状态

    /**
     * 只清「会话槽位」的孤儿行，判定条件刻意保守：
     *
     * <ol>
     *   <li>槽位形如 {@code <userId>:<conversationId>}（见 AgentScope {@code PostgresAgentStateStore.slotId}），
     *       正则要求冒号前无 {@code /}，因此沙箱等其它槽位（例如 {@code sandbox/session/...}）不会被误判；
     *   <li>后缀按 UUID 形态取会话 ID，用主键等值去 {@code conversations} 里找；
     *       找不到才认定孤儿，会话还在（含已归档）一律保留。
     * </ol>
     *
     * <p>这样不会误删活跃会话的运行时状态（误删会让下一轮带着空上下文继续）。
     */
    public long countOrphanSessionStatesOlderThan(int days) {
        return count("""
                SELECT count(*) FROM %s s
                WHERE s.updated_at < now() - make_interval(days => ?)
                  AND s.session_id ~ '^[^/:]+:[0-9a-fA-F-]{32,36}$'
                  AND NOT EXISTS (
                      SELECT 1 FROM public.conversations c
                      WHERE c.id = substring(s.session_id from '([0-9a-fA-F-]{32,36})$')
                  )
                """.formatted(sessionStateTable()), days);
    }

    public int deleteOrphanSessionStatesOlderThan(int days, int limit) {
        String table = sessionStateTable();
        return jdbc.update("""
                DELETE FROM %s
                WHERE ctid IN (
                    SELECT s.ctid FROM %s s
                    WHERE s.updated_at < now() - make_interval(days => ?)
                      AND s.session_id ~ '^[^/:]+:[0-9a-fA-F-]{32,36}$'
                      AND NOT EXISTS (
                          SELECT 1 FROM public.conversations c
                          WHERE c.id = substring(s.session_id from '([0-9a-fA-F-]{32,36})$')
                      )
                    ORDER BY s.updated_at
                    LIMIT ?
                )
                """.formatted(table, table), days, limit);
    }

    /**
     * 运行时实际使用的 AgentScope 状态表。
     *
     * <p>必须与 {@code MoonAgentRuntime} 的构建口径一致，否则清理对象会落到错表上：
     * <ul>
     *   <li>开了 DistributedStore（默认）：state store 由
     *       {@code PostgresDistributedStore.agentStateStore()} 提供，它固定使用库默认的
     *       {@code agentscope.agentscope_sessions}，{@code ai.agent.agentscope.state-schema/state-table} 不生效；</li>
     *   <li>关掉 DistributedStore：才使用显式配置的 schema/table。</li>
     * </ul>
     */
    private String sessionStateTable() {
        if (agentScopeProperties.isDistributedStoreEnabled()) {
            return "\"agentscope\".\"agentscope_sessions\"";
        }
        String schema = agentScopeProperties.getStateSchema();
        String table = agentScopeProperties.getStateTable();
        if (schema == null || !SAFE_IDENTIFIER.matcher(schema).matches()
                || table == null || !SAFE_IDENTIFIER.matcher(table).matches()) {
            throw new IllegalStateException(
                    "AgentScope state 表名非法（ai.agent.agentscope.state-schema/state-table）: "
                            + schema + "." + table);
        }
        return "\"" + schema + "\"." + table;
    }

    private long count(String sql, int days) {
        Long value = jdbc.queryForObject(sql, Long.class, days);
        return value == null ? 0L : value;
    }
}
