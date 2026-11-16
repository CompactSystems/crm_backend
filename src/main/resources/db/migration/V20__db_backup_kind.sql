-- Readable data exports next to the full database backups (see
-- service.backup.BackupService and docs/backup.md). Purely additive and safe to
-- re-run (IF NOT EXISTS guards): existing rows are kept unchanged and become
-- kind = 'DUMP', so every existing backup, its download and its retention stay
-- exactly as they were.
--
--  * kind: 'DUMP' = the restorable pg_dump custom-format backup (.dump),
--    'READABLE_EXPORT' = the ZIP of CSV files for viewing the data in Excel.
--    A readable export is NOT a restorable backup. The two kinds have
--    completely separate retention: the newest 14 successful dumps, and the
--    newest 5 successful readable exports that are at most 30 days old.
--  * total_rows: rows written to a readable export (NULL for a dump).
--  * uq_db_backup_one_running still allows only one RUNNING job of ANY kind.

ALTER TABLE db_backup ADD COLUMN IF NOT EXISTS kind VARCHAR(20) NOT NULL DEFAULT 'DUMP';

ALTER TABLE db_backup ADD COLUMN IF NOT EXISTS total_rows BIGINT NULL;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_db_backup_kind') THEN
        ALTER TABLE db_backup ADD CONSTRAINT chk_db_backup_kind CHECK (kind IN ('DUMP', 'READABLE_EXPORT'));
    END IF;
END
$$;

CREATE INDEX IF NOT EXISTS idx_db_backup_kind_started_at ON db_backup (kind, started_at DESC);
