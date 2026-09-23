-- LEGACY orchestration is no longer executable. Keep all history and assets,
-- but archive legacy conversations so write APIs reject further turns.
UPDATE conversations
SET status = 'archived'
WHERE orchestration_mode = 'LEGACY'
   OR orchestration_mode IS NULL;

UPDATE conversations
SET debug_comparison_enabled = false
WHERE debug_comparison_enabled IS DISTINCT FROM false;

UPDATE agent_checkpoints
SET status = 'expired'
WHERE owner_engine = 'LEGACY'
  AND status = 'pending';
