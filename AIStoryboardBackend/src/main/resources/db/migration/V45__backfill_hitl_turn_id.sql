-- 历史 HITL 卡片回填 turnId：turn.checkpoint_id -> checkpoint.plan._hitlMessageId。
UPDATE agent_messages m
SET metadata = jsonb_set(COALESCE(m.metadata, '{}'::jsonb), '{turnId}', to_jsonb(t.id), true)
FROM agent_turns t
JOIN agent_checkpoints c ON c.id = t.checkpoint_id
WHERE c.plan IS NOT NULL
  AND (c.plan::jsonb ->> '_hitlMessageId') = m.id
  AND (m.metadata ->> 'turnId') IS NULL;
