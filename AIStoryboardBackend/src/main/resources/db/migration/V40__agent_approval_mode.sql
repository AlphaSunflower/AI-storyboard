-- Per-conversation AgentScope approval policy.

ALTER TABLE public.conversations
    ADD COLUMN IF NOT EXISTS approval_mode VARCHAR(32) NOT NULL DEFAULT 'PLAN_APPROVAL';

UPDATE public.conversations
SET approval_mode = 'PLAN_APPROVAL'
WHERE approval_mode IS NULL OR approval_mode = '';
