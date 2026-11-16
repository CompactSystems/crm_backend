# Database backups ("Take Backup") and restore runbook

## What it does

An **ADMIN** opens **Backups** in the sidebar and clicks **Take Backup**.

1. `POST /api/admin/backups` returns **202** at once. The backup runs on one background thread of the backend, so normal CRM requests are not blocked.
2. The backend runs `pg_dump --format=custom --schema=public --no-owner --no-privileges` against the CRM database, using the application's own datasource settings.
   - pg_dump reads inside a single `REPEATABLE READ, READ ONLY` transaction, so the snapshot is consistent and nothing is written.
   - The password is passed only in the `PGPASSWORD` environment variable, never on the command line.
3. The dump is checked before anything else happens. It fails if:
   - the file is empty;
   - `pg_restore --list` cannot read the whole archive (a truncated or corrupt file fails here);
   - the archive has no data for `employees` or `flyway_schema_history`.

   The SHA-256 of the file is recorded.
4. The file is uploaded to the **private** Supabase Storage bucket `crm-backups` (`x-upsert: false`). Only then is the backup marked **SUCCEEDED**. The temporary file on the server is always deleted.
5. The newest **14** successful backups are kept. Older dumps are deleted from the bucket, while their history rows stay.
   - Retention runs only **after** a new backup has succeeded. A failed backup never removes an older one.
   - If an older dump can't be deleted (storage outage), its row stays as it is and the delete is retried after the next backup.
   - If a dump is already gone from the bucket (for example, removed in the Supabase dashboard), that counts as deleted. The row is then shown as "Removed by retention" instead of offering a download that can't work.
6. The page polls `GET /api/admin/backups/{id}` and shows success (size, duration) or the failure reason.
7. **Download** streams the file through `GET /api/admin/backups/{id}/download`, which is ADMIN only and sends `Cache-Control: no-store`.
   - There is no public or pre-signed URL.
   - The browser checks the size and SHA-256 before saving the file.

Each start, success or failure, and download is recorded in the activity log (module `SYSTEM`, actions `BACKUP_*`) and in the `db_backup` table, with who, when and the outcome.

### Not included

- **Uploaded document files** (brochures, company profiles, attachments) are **not** in the backup. They live in Supabase Storage (`DOCUMENT_STORAGE_BACKEND=supabase`) or on disk. The backup contains only their rows (name, type, storage key). Back up the documents bucket separately if you need it.
- Supabase-managed schemas (`auth`, `storage`, `realtime`, ...) are not included. The CRM keeps nothing there; its logins are in `public.employees`.

### Security notes

- **ADMIN is checked twice:** by `@PreAuthorize("hasRole('ADMIN')")` on `BackupController`, and again inside `BackupService`. Callers without a token get 401; every other role gets 403, including when calling the API directly.
- Backups contain personal and business data:
  - The bucket must stay **private**.
  - Supabase encrypts stored objects at rest, and every hop uses TLS (PostgreSQL `sslmode=require`, HTTPS to Supabase and to the browser).
  - v1 adds no application-level encryption.
  - Keep downloaded copies on encrypted disks only, and delete them when no longer needed.
- Error messages shown in the UI and stored in `db_backup.error_message` are scrubbed of host names, IP addresses, the user name, the password and connection URLs. Logs contain only the backup id, status, size and scrubbed reasons.
- Only one backup runs at a time (409 otherwise). This is enforced in the application and by the partial unique index `uq_db_backup_one_running`.
- A backup left `RUNNING` by a restart is marked `FAILED` at the next startup.
- Each step has a timeout: dump 15 min, check 5 min, upload 10 min.

## Readable data export (ZIP of CSV files)

The Backups page has a second, separate tool: **Create Readable Export**. It makes a ZIP that someone can open in Excel to look at the actual records, with no database tools.

> **A readable export is NOT a backup.** It can't restore the CRM: it has no schema, constraints, sequences or migration history, and some columns are deliberately withheld. Only the `.dump` restores the CRM (see the restore runbook below). The two are labelled separately in the UI, the API (`kind`), the file names and the README inside the ZIP.

### What the ZIP contains

`crm-readable-export-YYYYMMDDTHHMMSSZ-<id>.zip` holds one folder with the same name, containing:

| File | Contents |
|---|---|
| `README.txt` | says it is not a backup, how to open it, how records are linked, every withheld column and excluded table with its reason, and a confidentiality note |
| `manifest.json` | `"restorable": false`, export id, UTC time, snapshot, database and CRM schema version, and for every table its file, row count, columns, withheld columns with reasons, the number of formula-guarded cells and the SHA-256 |
| `relationships.csv` | every link between tables: `table, column, refers_to_table, refers_to_column, enforced_by_database, name_column_in_export`. Foreign keys come from the database catalog (`yes`); links without a database constraint come from the export policy (`no (logical link, ...)`) |
| `tables/<table>.csv` | one file per exported table, all columns, ordered by primary key |

CSV format:
- UTF-8 with a byte order mark (so Excel shows accents and other scripts correctly), comma-separated, CRLF line endings, RFC 4180 quoting.
- NULL is an empty field, and an empty text is `""`.
- Dates are `YYYY-MM-DD`. Date/times are `YYYY-MM-DD HH:MM:SS[.ffffff]`, exactly as stored (no time zone; the server's zone is named in the README).
- Numbers are plain, never `1E+6`.
- **Name columns:** next to an id that points to another table there is a readable name, for example `assigned_employee_id` plus `assigned_employee__name`, or `lead_id` plus `lead__company_name`. These come from a `LEFT JOIN` on the referenced id, and the row count is checked, so they can't add or drop rows.
- **Formula-injection guard:** a text value that a spreadsheet would run as a formula gets a leading `'`. That means it starts with `=`, `@`, tab or CR, or with `+` / `-` and is not a plain number or phone number. Phone numbers such as `+91 98765 43210` and numbers such as `-12.5` stay unchanged.

### How it is made

1. `POST /api/admin/backups/readable` (ADMIN only) returns **202**. The job uses the same single background thread, the same "one job at a time" lock (409 while a dump **or** an export runs), the same `db_backup` history (`kind = READABLE_EXPORT`), the same private bucket and the same audit as the dump.
2. `JdbcReadableExporter` reads through the application's own datasource on **one connection, in one `REPEATABLE READ, READ ONLY` transaction**. On PostgreSQL both settings are read back from the server (`current_setting`) before any data is read, and the export stops if either is wrong.
3. The schema is discovered from the database itself, because the core tables are created by Hibernate, not by migrations. It is then checked against the export policy **before anything is written** (see below).
4. Each table is streamed with a forward-only cursor (fetch size 500) into the ZIP on the server's temporary disk, so memory use doesn't grow with the data. The row count from the same snapshot must equal the rows written.
5. **Size limit:** the ZIP is counted while it is written. Once it goes past `crm.backup.readable-max-mb` (default **50 MB**, the Supabase Free plan's per-file limit), the export stops: the row becomes **FAILED**, nothing is uploaded and the temp file is deleted. If you raise the limit, raise the bucket's file size limit too.
6. **Independent check** (`ReadableExportVerifier`), reading the finished ZIP back from disk:
   - exactly the expected files, with safe names;
   - each file's SHA-256 is unchanged;
   - every CSV has the BOM, parses, has the expected header, the snapshot's row count and the same number of fields in every record;
   - the manifest matches and says `restorable: false`, and the README says "NOT A DATABASE BACKUP";
   - `employees` has at least one row (so real data was read, not just a schema);
   - no bcrypt password hash appears anywhere.
7. Only then is the ZIP uploaded (`x-upsert: false`). After the upload the row becomes **SUCCEEDED**, with size, SHA-256, table count and total rows, and readable retention runs.
8. Time limit: `crm.backup.readable-timeout` (default 15 min) for the whole export, also used as the per-statement query timeout.
9. **Download** uses the same endpoint as the dump: `GET /api/admin/backups/{id}/download` with `Content-Type: application/zip` and `no-store`. There is no public or pre-signed URL, and the browser checks size and SHA-256 before saving.

Activity log actions: `READABLE_EXPORT_STARTED`, `_SUCCEEDED`, `_FAILED`, `_DOWNLOADED` (module `SYSTEM`).

### Export policy: what is included, withheld or excluded

`ReadableExportPolicies.crm()` holds an explicit decision for **every table and every column**, and it **fails closed**:
- A table that is not in the policy, or a column of an exported table that is not in it, stops the export before anything is written. The failure message names each missing `table` / `table.column` (only names, never values).
- A **withheld** column stays in the CSV, but every non-empty value reads `[withheld]`, and an empty one stays empty. The query only asks `CASE WHEN col IS NULL ...`, so the value is never read from the database.
- A column whose name looks like a credential (password, token, secret, verifier, API key, nonce, ...) **cannot** be marked as included; building the policy fails.

| Decision | Tables / columns | Why |
|---|---|---|
| **Included in full** | leads, opportunities, customers, follow-ups, products and other lookups, employees (name, e-mail, phone, role, manager), activity log, documents (rows only), e-mail and WhatsApp templates, batches and send logs, suppression and consent **status** | business records for authorised administrators; contact details included in full as approved |
| **Withheld** | `employees.password` | password hash (login credential) |
| | `email_mailbox_account.refresh_token_encrypted` | encrypted OAuth refresh token |
| | `email_mailbox_account.provider_subject`, `.provider_tenant_id` | identifiers that bind a mailbox to its Google/Microsoft sign-in |
| | `whatsapp_consent.evidence`, `whatsapp_consent_audit.evidence` | free-text consent evidence; reviewed and withheld by default as sensitive personal data |
| | `email_suppression.note`, `email_suppression_audit.note` | free-text opt-out notes; same reason |
| **Included (legacy)** | `leads.website`, `.interested_product`, `.lead_source`, `.product_id` (with `product__name`); `opportunities.sales_stage`; `followups.activity_type`, `.custom_activity_type` | columns that **older versions of the entities** created and that only exist in older databases such as production (see below). They hold the website URL, free-text product interest, source / stage / activity enum labels and the old single-product link of records created before the current fields existed. None is a credential or a personal note |
| **Excluded** | `email_oauth_state` | OAuth sign-in state (state hash, PKCE verifier, nonce) |
| | `flyway_schema_history` | migration bookkeeping (the version is in `manifest.json`) |
| | `db_backup` | backup/export history, not CRM data |

WAHA's API key, JWT secret, Supabase key and mail OAuth client secrets are configuration (environment variables), not data, so they are never in the database or in an export.

To export consent evidence or opt-out notes later, change `withhold(...)` to `include(...)` for that column in `ReadableExportPolicies` and update this table. **When the schema changes** (a new entity field or migration), add a decision for the new table or column. `ReadableExportPolicyCoverageTest` (entity schema) and `ReadableExportPostgresTest` (migrated PostgreSQL schema) fail until you do.

#### Legacy columns: the tests can't see everything production has

`spring.jpa.hibernate.ddl-auto=update` adds columns but **never drops them**. So when an entity field is renamed or replaced (for example `Lead.leadSource` enum → `lead_source_id`, `FollowUp.activityType` enum → `activity_type_id`), the old column stays in the deployed database with its old data. No current entity, migration or test schema mentions it.

The first deployed export (16 Nov 2026) stopped for exactly this reason. It found 7 such columns (`ReadableExportPolicies.LEGACY_COLUMNS`), which now have reviewed decisions. `ReadableExportPostgresTest` recreates them on its production-shaped schema, and `ReadableExportLegacyColumnsTest` recreates them on H2.

That failure is the policy working as designed: an unknown column stops the export before anything is written. Don't bypass it. If the export reports more unreviewed columns:
1. List the deployed schema, read-only (for example in the Supabase SQL editor). This shows column names and types only, no data:
   ```sql
   SELECT table_name, column_name, data_type
   FROM information_schema.columns
   WHERE table_schema = 'public'
   ORDER BY table_name, ordinal_position;
   ```
2. For each reported column, find what it holds: `git log -S <field name>` on the entities, or the older code. Decide whether to include, withhold or exclude it.
3. Add the decision to `ReadableExportPolicies`, plus `LEGACY_COLUMNS` and the setup in `ReadableExportPostgresTest` if it's a leftover. Then update the table above and run the tests.

### Retention (separate from the dumps)

| | Full backups (`.dump`) | Readable exports (`.zip`) |
|---|---|---|
| Keep | newest **14** successful (`CRM_BACKUP_RETAIN`) | newest **5** successful (`CRM_BACKUP_READABLE_RETAIN`) **and** none older than **30 days** (`CRM_BACKUP_READABLE_MAX_AGE`, e.g. `30d`) |
| Runs | after each successful dump | after each successful export, and every 6 h on the backup thread (`CRM_BACKUP_READABLE_CLEANUP_INTERVAL`, first run 5 min after start) |
| Download after expiry | - | refused (409 "expired") even if the cleanup hasn't run yet |

Each retention query is filtered by `kind`, so a readable export never counts toward, or causes the deletion of, a `.dump`, and the reverse is also true. History rows stay; only the files are deleted.

## One-time setup

1. **Create the bucket.** In the Supabase dashboard go to Storage → New bucket. Name it `crm-backups` and leave **Public bucket OFF**. Don't add any storage policies: the backend uses the service-role key, and nobody else needs access.
   - The bucket's file size limit must allow your dump size.
   - The Free plan caps uploads at 50 MB per file. Compressed CRM dumps are usually far smaller, but check the size of the first backup.
2. **Backend environment on Render.** The backup feature reuses the variables the document storage already needs:

   | Variable | Needed | Notes |
   |---|---|---|
   | `SUPABASE_URL` | yes | already set if documents use Supabase Storage |
   | `SUPABASE_SERVICE_ROLE_KEY` | yes | secret; never sent to the frontend |
   | `CRM_BACKUP_BUCKET` | no | default `crm-backups` |
   | `CRM_BACKUP_RETAIN` | no | default `14` |
   | `CRM_BACKUP_DUMP_TIMEOUT` | no | default `15m` |
   | `CRM_BACKUP_MIN_FREE_MB` | no | default `256` |
   | `CRM_BACKUP_SSL_MODE` | no | default: the JDBC URL's `sslmode`, otherwise `require` |
   | `CRM_BACKUP_READABLE_RETAIN` | no | readable exports kept, default `5` |
   | `CRM_BACKUP_READABLE_MAX_AGE` | no | maximum age of a readable export, default `30d` |
   | `CRM_BACKUP_READABLE_MAX_MB` | no | largest readable export stored, default `50` (keep ≤ the bucket's file size limit) |
   | `CRM_BACKUP_READABLE_TIMEOUT` | no | time limit for one readable export, default `15m` |

   Without `SUPABASE_URL` and the key, the CRM still starts normally. **Take Backup** then answers 503: "Backups are not configured on this server".
3. **PostgreSQL client version.**
   - pg_dump refuses to dump a server that is newer than itself.
   - The Dockerfile installs `postgresql-client-${PG_CLIENT_MAJOR}` (default **17**) from the official PostgreSQL apt repository.
   - Check the server version once, read-only (`SHOW server_version;` in the Supabase SQL editor). If it's newer than 17, build with `--build-arg PG_CLIENT_MAJOR=<major>`, or change the default.
   - Verified on 12 Nov 2026: the built image contains `pg_dump`/`pg_restore` **17.11**. You reported the production server as **17.6** (same major version, older minor), which this client can dump. The live server version was not read by the build or the tests.
   - A newer client dumps older servers without problems.
4. **Migration.**
   - `V14_2__db_backup.sql` only adds the `db_backup` table. It sorts after 14.1 (mailboxes) and before the WhatsApp migrations 15–17.
   - **Deploy it before V15–V17.** With Flyway's default `outOfOrder=false`, a database that already has 15+ would refuse to start with 14.2 pending.
   - A local dev database that already applied 15–17 has to be recreated.
   - Pushed branches contain migrations only up to `V14_1` (`feature/bulk-email`; `main` stops at `V10`), so a database built from them hasn't applied anything after 14.2. To confirm, read-only, before deploying:
     ```sql
     SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;
     ```
     Expect the newest `version` to be `14.1` or lower, all with `success = true`.
   - `V20__db_backup_kind.sql` (readable exports) is purely additive and can be re-run:
     - it adds `db_backup.kind` (`NOT NULL DEFAULT 'DUMP'`, so every existing row becomes a dump, unchanged) and `total_rows`;
     - it adds the check constraint `chk_db_backup_kind` and the index `idx_db_backup_kind_started_at`;
     - `uq_db_backup_one_running` still allows one running job of either kind.

     `DbBackupKindMigrationPostgresTest` checks all of this on a database with existing backup history.

## Restore runbook

Never restore over the production database without a fresh backup of the current state taken first. Always try the restore on a scratch database first.

1. **Download** the backup from the Backups page, as an ADMIN. The browser has already checked the size and SHA-256. To check again:
   ```
   sha256sum crm-backup-YYYYMMDDTHHMMSSZ-N.dump      # compare with the SHA-256 shown for that backup
   pg_restore --list crm-backup-...dump | head -20    # must list the archive header and entries
   ```
   Use a `pg_restore` whose major version is at least the one in "Dumped by pg_dump version".
2. **Create an empty scratch database**, for example on a local PostgreSQL:
   ```
   createdb -h localhost -U postgres crm_restore_check
   ```
3. **Make room for the archive's `public` schema.** Run `pg_restore --list` on the archive:
   - If the list contains a `SCHEMA - public` entry, drop the existing schema first: `psql -h localhost -U postgres -d crm_restore_check -c "DROP SCHEMA public CASCADE"`.
   - Otherwise leave the database's empty `public` schema as it is.
4. **Restore**, with the password from the environment (`PGPASSWORD`), never on the command line:
   ```
   pg_restore --no-owner --no-privileges --exit-on-error --single-transaction \
     -h localhost -U postgres -d crm_restore_check crm-backup-...dump
   ```
   `--single-transaction` plus `--exit-on-error` gives all or nothing: a failed restore leaves no half-restored data.
5. **Check it:**
   ```sql
   SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;
   SELECT count(*) FROM employees;
   SELECT count(*) FROM leads;
   ```
   You can also point a local backend at the scratch database (`SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/crm_restore_check`). Flyway validates the history at startup, and nothing new is applied.
6. **Restoring into production** is a deliberate, manual operation and has no button.
   - Stop the backend first.
   - Take a fresh backup of the current state.
   - Restore into a **new** empty database, verify it as above, then switch `SPRING_DATASOURCE_URL` to it.
   - Never run `pg_restore --clean` against the live database.

## Automated tests

| Test | Covers |
|---|---|
| `BackupApiTest` | the HTTP API with the real security chain, background thread and table: ADMIN success; EMPLOYEE / MANAGER 403 on every endpoint; 401 without a token; 409 for concurrent requests; dump failure, timeout, empty file, failed verification, failed upload, unexpected errors (raw messages are not exposed); 503 when storage is unconfigured; download headers, audit and 502 / 409 / 404 cases; retention; restart recovery; stale-row recovery |
| `PgDumpRunnerTest` | exact pg_dump arguments (no password, nothing destructive), environment, sslmode, scrubbed failures, timeout and missing-tool messages, TOC parsing, required tables |
| `ProcessCommandExecutorTest` | real child processes: exit code and output capture, removal of inherited `PG*` variables, killing on timeout, temp-file cleanup |
| `DisposableDatabaseGuardTest` | the restore test's safety gate, with no database: every refusal case and the server checks (superuser, non-owner, wrong database) on a mocked connection |
| `SecretScrubberTest`, `PostgresTargetTest` | secrets never in messages; only plain single-host PostgreSQL URLs accepted |
| `SupabaseBackupStorageTest` | private upload (`x-upsert: false`), streamed download, status-only errors, key validation |
| `BackupPostgresRoundTripTest` | **restore verification**: real migrations and seed data, then real `pg_dump`, the server's own check, rejection of a truncated archive, schema drop, `pg_restore`, and a row-for-row comparison of every table, Flyway validation and working sequences |
| frontend `Backups.test.jsx`, `backupService.test.js` | button, disabled while running, polling, success / failure / 409 / 403 messages, disclosure text, size and checksum check before saving; the two separate sections (full backup vs readable export), per-kind history, both buttons disabled while either job runs, export success / failure messages, `.zip` download |
| `ReadableExportApiTest` | readable export over HTTP with the **real exporter and verifier** on H2: ADMIN only (401 / 403 for start, list and download); the uploaded ZIP opened and checked (README / manifest / relationships, real rows, `[withheld]` password and token, formula guard, exact phone numbers, name columns, no hash / token / OAuth nonce anywhere, excluded tables absent); `application/zip` download and audit; per-kind history; one job at a time across kinds; failed upload, failed check and unexpected errors; **retention isolation** in both directions; 30-day expiry and refused download |
| `ReadableExportLimitsTest` | size limit stops the export while writing (FAILED, nothing uploaded, no temp file); unreviewed table / column stops it before any file is written; time limit; low disk space; a truncated ZIP fails the check |
| `ReadableExportPolicyTest`, `ReadableExportPolicyCoverageTest`, `CsvWriterTest` | the reviewed decisions (credentials withheld, consent evidence withheld, contact details included); fail-closed rules; every entity table and column covered; CSV quoting, NULL vs empty, Unicode, formula guard, reader round trip |
| `ReadableExportLegacyColumnsTest` | the 7 legacy production columns added to H2: the real export and check succeed, values exported as stored, `product__name` for the old `product_id`, formula guard on legacy free text, password still withheld; one more unreviewed column still stops the export before any file is written |
| `ReadableExportPostgresTest` | on a disposable PostgreSQL built like production (baseline plus the legacy columns, Flyway V2–V20, Hibernate update): full policy coverage, legacy values and the enforced `leads.product_id` foreign key, an unreviewed column still stopping the export, snapshot checked on the server, every table exported or excluded with exact row counts, real values and formats, real foreign keys plus logical links, no secrets, and the whole `BackupService` job (V20 kind, upload, SUCCEEDED). DESTRUCTIVE, guarded like the restore test |
| `DbBackupKindMigrationPostgresTest` | V20 on a database with existing backup history: rows become `DUMP` unchanged, constraint, one running job of either kind, re-runnable. DESTRUCTIVE, guarded like the restore test |

The three PostgreSQL classes (`BackupPostgresRoundTripTest`, `ReadableExportPostgresTest`, `DbBackupKindMigrationPostgresTest`) each need their **own fresh** `*_restore_test` database, because the guard requires an empty public schema. Run them one class at a time, following the procedure below and substituting the class name.

### Running the restore test (Windows PowerShell)

`BackupPostgresRoundTripTest` is **destructive**: it runs `DROP SCHEMA public CASCADE` twice on the database in `CRM_TEST_PG_URL`. Without `CRM_TEST_PG_URL` it is skipped. Once the variable is set, `DisposableDatabaseGuard` must pass **before anything is changed**; otherwise the test **fails** with every reason listed and the message "Nothing was changed". It never silently skips.

| Guard | When | Prevents |
|---|---|---|
| host is exactly `localhost`, `127.0.0.1` or `::1` | before connecting | Supabase or any other remote server |
| no URL parameters except `sslmode` | before connecting | `?PGHOST=…` / `?PGDBNAME=…`, which the PostgreSQL JDBC driver obeys and which could redirect the connection behind a harmless-looking host |
| database name ends in `_restore_test` | before connecting | wiping a dev database, `crm_test_db`, or `postgres` (Supabase's database name, even through a localhost tunnel) |
| `CRM_TEST_PG_CONFIRM_DROP` equals that name exactly | before connecting | running against a database you didn't name on purpose |
| `CRM_TEST_PG_USER` set, not `postgres` | before connecting | running as the superuser |
| `current_database()` is that name | on the live connection, before **each** DROP | a connection that ended up somewhere else |
| `current_user` **and** `session_user` both equal `CRM_TEST_PG_USER` | before **each** DROP | logging in as one role and acting as another (`SET ROLE`) |
| the role is not a superuser, **directly owns** the database, and owns **no other** database | before **each** DROP | dropping with superuser power, or with a role that also owns a real database. A role that owns only this database cannot drop `public` anywhere else. |
| `public` exists and holds **no objects at all** (tables, views, sequences, indexes, functions, types, operators, text search objects, extended statistics, extensions) | before the **first** DROP only | wiping a database that already holds data, even one named `*_restore_test` and owned by the role. If emptiness can't be established (no schema, no result, NULL count), the test refuses. |

The emptiness check runs only before the first DROP. By the second DROP the test has created the CRM schema itself. As a result, every run needs a **newly created** database. A database left over from an earlier run is refused; drop it and create it again (steps 5 and 1).

The `postgres` superuser is used **only** to check for, create and remove the throwaway role and database (steps 0b, 1, 2 and 5). **The test itself connects as the non-superuser `crm_restore_test`.** Every password is typed at a prompt that does not echo, so none appears on a command line, in the shell history or in this repository.

Run the whole procedure in **one** PowerShell window (Windows PowerShell 5.1 or PowerShell 7), step by step, in this order.

```powershell
# ===== 0. Settings for the whole session ==========================================
$pg       = "C:\Program Files\PostgreSQL\18\bin"
$testDb   = "crm_backup_restore_test"
$testRole = "crm_restore_test"
$repo     = "C:\Projects\crm\crm_backend-main"
$report   = Join-Path $repo "target\surefire-reports\com.compact.crm.service.backup.BackupPostgresRoundTripTest.txt"

# ===== 0b. Make sure neither name is already in use (read-only, as postgres) ======
# expect: 0|0   Anything else: STOP. Inspect the existing role/database and decide
# yourself; never drop something you did not create for this run.
& "$pg\psql.exe" -h localhost -U postgres -d postgres -tA -c "SELECT (SELECT count(*) FROM pg_database WHERE datname = 'crm_backup_restore_test'), (SELECT count(*) FROM pg_roles WHERE rolname = 'crm_restore_test')"

# ===== 1. Create the dedicated role and an empty database (as postgres) ===========
# createuser asks for a NEW throwaway password for crm_restore_test (twice), then for the postgres password.
& "$pg\createuser.exe" -h localhost -U postgres --no-superuser --no-createdb --no-createrole --no-replication --pwprompt $testRole
# createdb asks for the postgres password. template0 = a pristine database; the role owns it (and so its public schema).
& "$pg\createdb.exe" -h localhost -U postgres --owner=$testRole --template=template0 $testDb

# ===== 2. Check before running anything (read-only) ===============================
# expect exactly: crm_restore_test|f|f|f   (not superuser, cannot create databases or roles)
& "$pg\psql.exe" -h localhost -U postgres -d postgres -tA -c "SELECT rolname, rolsuper, rolcreatedb, rolcreaterole FROM pg_roles WHERE rolname = 'crm_restore_test'"
# expect exactly one line: crm_backup_restore_test|crm_restore_test   (the role owns only this database)
& "$pg\psql.exe" -h localhost -U postgres -d postgres -tA -c "SELECT datname, pg_get_userbyid(datdba) FROM pg_database WHERE pg_get_userbyid(datdba) = 'crm_restore_test'"
# expect: Did not find any relations.   (asks for the crm_restore_test password)
& "$pg\psql.exe" -h localhost -U $testRole -d $testDb -c "\dt public.*"

# ===== 3. Run ONLY BackupPostgresRoundTripTest, connected as crm_restore_test =====
Set-Location $repo
$env:JAVA_HOME                = "$HOME\.jdks\temurin-21.0.11"
$env:CRM_TEST_PG_URL          = "jdbc:postgresql://localhost:5432/$testDb"
$env:CRM_TEST_PG_USER         = $testRole
$env:CRM_TEST_PG_CONFIRM_DROP = $testDb
$env:CRM_TEST_PG_BIN          = $pg

$securePassword = Read-Host "Password for $testRole" -AsSecureString
$bstr = [IntPtr]::Zero
try {
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
    $env:CRM_TEST_PG_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
} finally {
    if ($bstr -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
}

# an old report must not be mistaken for this run: delete it (a failed delete stops here)
# and later accept only a report written after $started
if (Test-Path $report) { Remove-Item $report -ErrorAction Stop }
$mvnExit = $null
$started = Get-Date
try {
    & .\mvnw.cmd -o test "-Dtest=BackupPostgresRoundTripTest"
    $mvnExit = $LASTEXITCODE
} finally {
    # clear the test settings and the password from this session, whatever happened
    Remove-Item Env:CRM_TEST_PG_URL, Env:CRM_TEST_PG_USER, Env:CRM_TEST_PG_CONFIRM_DROP, Env:CRM_TEST_PG_PASSWORD, Env:CRM_TEST_PG_BIN -ErrorAction SilentlyContinue
    Remove-Variable securePassword, bstr -ErrorAction SilentlyContinue
}

# ===== 4. Confirm it ACTUALLY ran: exactly 1 test, 0 failures, 0 errors, 0 skipped
$passed = $false
if (-not (Test-Path $report)) {
    "NO REPORT: the build failed before the test ran (see the Maven output above)."
} elseif ((Get-Item $report).LastWriteTime -lt $started) {
    "STALE REPORT: $report was not written by this run."
} else {
    $line = Select-String -Path $report -Pattern 'Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)' | Select-Object -First 1
    if ($null -eq $line) {
        "REPORT HAS NO RESULT LINE: $report"
    } else {
        $line.Line
        $g = $line.Matches[0].Groups
        $passed = ($mvnExit -eq 0) -and ($g[1].Value -eq '1') -and ($g[2].Value -eq '0') -and ($g[3].Value -eq '0') -and ($g[4].Value -eq '0')
    }
}
if ($passed) { "RESTORE TEST RAN AND PASSED" }
else { "RESTORE TEST DID NOT PASS (Skipped: 1 = did not run; Failures: 1 = a guard refused or the restored data differed). Read the report and the Maven output." }

# ===== 5. Clean up: drop ONLY the throwaway database and role (each asks y/n) =====
& "$pg\dropdb.exe"   -h localhost -U postgres --interactive crm_backup_restore_test
& "$pg\dropuser.exe" -h localhost -U postgres --interactive crm_restore_test
```

- `-Dtest=BackupPostgresRoundTripTest` names exactly one class. Never use a wildcard such as `*PostgresTest`: the older Postgres test classes also read `CRM_TEST_PG_URL`, have no such guard, and also drop the schema.
- In step 5 the names are typed out on purpose, with no variables or wildcards, so only these two objects can be dropped. Drop the database first, because the role can't be dropped while it still owns a database.

### Making sure the temporary role can't be reached from other machines

`postgresql.conf` has `listen_addresses = '*'`, so the server accepts TCP connections on every network interface. Two independent layers decide whether another machine could actually log in as `crm_restore_test`. Check both, read-only, **before step 1**:

```powershell
# a) PostgreSQL's own access rules. Every "host" line must be 127.0.0.1/32 or ::1/128 only.
#    "local" lines are this machine only. Anything like 0.0.0.0/0, ::/0, samenet, samehost
#    or a LAN range would let other machines in.
Select-String -Path "C:\Program Files\PostgreSQL\18\data\pg_hba.conf" -Pattern '^\s*[^#\s]' | ForEach-Object { $_.Line }

# b) Windows Firewall: every profile should be Enabled, and there should be NO enabled
#    inbound Allow rule for TCP 5432 or for postgres.exe.
Get-NetFirewallProfile | Select-Object Name, Enabled, DefaultInboundAction
Get-NetFirewallPortFilter -Protocol TCP | Where-Object { $_.LocalPort -contains '5432' } | Get-NetFirewallRule | Where-Object { $_.Enabled -eq 'True' -and $_.Direction -eq 'Inbound' } | Select-Object DisplayName, Action, Profile
Get-NetFirewallApplicationFilter | Where-Object { $_.Program -like '*PostgreSQL*' } | Get-NetFirewallRule | Where-Object { $_.Enabled -eq 'True' -and $_.Direction -eq 'Inbound' } | Select-Object DisplayName, Action, Profile
```

Either layer alone is enough to keep other machines out: `pg_hba.conf` with loopback-only `host` lines, or the firewall blocking inbound 5432. A third-party firewall or antivirus may also apply rules that the commands above don't show.

To make the server itself listen on this machine only (optional, done by you, needs an Administrator PowerShell):
1. Open `C:\Program Files\PostgreSQL\18\data\postgresql.conf` and change `listen_addresses = '*'` to `listen_addresses = 'localhost'`.
2. Restart the service: `Restart-Service postgresql-x64-18`. This briefly disconnects every client of this local server.
3. Check it: `Get-NetTCPConnection -State Listen -LocalPort 5432 | Select-Object LocalAddress`. Expect only `127.0.0.1` and `::1`, no `0.0.0.0` or `::`.

Only do this if nothing on another machine needs this local server. Alternatively, keep `listen_addresses` as it is, rely on loopback-only `pg_hba.conf`, and remove the role right after the test (step 5).
