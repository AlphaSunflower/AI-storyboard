package com.moon.moonagent.ai.agent.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class SandboxSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(SandboxSessionRegistry.class);

    private final JdbcTemplate jdbc;

    public SandboxSessionRegistry(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void touch(String conversationId, int ttlHours) {
        try {
            jdbc.update("""
                    INSERT INTO public.agent_sandbox_sessions
                        (conversation_id, status, last_used_at, expires_at, version)
                    VALUES (?, 'active', now(), now() + (? * interval '1 hour'), 1)
                    ON CONFLICT (conversation_id) DO UPDATE SET
                        status = 'active',
                        last_used_at = now(),
                        expires_at = now() + (? * interval '1 hour'),
                        version = public.agent_sandbox_sessions.version + 1
                    """, conversationId, ttlHours, ttlHours);
        } catch (RuntimeException error) {
            log.warn("Unable to persist sandbox session {}: {}", conversationId, error.getMessage());
        }
    }

    public void markCleaned(String conversationId) {
        try {
            jdbc.update("""
                    UPDATE public.agent_sandbox_sessions
                    SET status = 'cleaned', updated_at = now(),
                        version = version + 1
                    WHERE conversation_id = ?
                    """, conversationId);
        } catch (RuntimeException error) {
            log.warn("Unable to mark sandbox session {} cleaned: {}",
                    conversationId, error.getMessage());
        }
    }
}
