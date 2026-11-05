-- Older deadlines were last successful activity + 24 hours. Preserve that activity time
-- while applying the new ten-minute recovery window; terminal results retain seven days.
UPDATE upload_tasks
SET expires_at = expires_at - INTERVAL '23 hours 50 minutes'
WHERE state = 'UPLOADING';
